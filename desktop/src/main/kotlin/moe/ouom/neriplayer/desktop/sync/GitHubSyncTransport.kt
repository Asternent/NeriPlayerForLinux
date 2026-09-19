package moe.ouom.neriplayer.desktop.sync

import moe.ouom.neriplayer.desktop.net.HttpService
import moe.ouom.neriplayer.desktop.net.NeriJsonParser
import moe.ouom.neriplayer.desktop.net.asObject
import moe.ouom.neriplayer.desktop.net.int
import moe.ouom.neriplayer.desktop.net.long
import moe.ouom.neriplayer.desktop.net.obj
import moe.ouom.neriplayer.desktop.net.str
import java.util.Base64

sealed interface SyncWriteResult {
    data class Success(val commitSha: String, val headSha: String) : SyncWriteResult

    /** 远端在我们准备提交期间被其他设备更新了。 */
    data object Conflict : SyncWriteResult

    data class Failed(val message: String) : SyncWriteResult
}

data class RemoteSyncFile(
    val content: ByteArray,
    val headSha: String,
    val path: String,
)

/**
 * 与手机端一致：通过 GitHub Git 数据接口读写仓库根目录的同步文件，
 * 提交时带上 expected head（非快进提交会被拒绝）以避免覆盖其他设备的数据。
 */
class GitHubSyncTransport(
    private val http: HttpService,
    private val token: String,
    private val apiBase: String = "https://api.github.com",
) {

    private fun headers(extra: Map<String, String> = emptyMap()): Map<String, String> = buildMap {
        put("Authorization", "Bearer $token")
        put("Accept", "application/vnd.github+json")
        put("X-GitHub-Api-Version", "2022-11-28")
        putAll(extra)
    }

    private fun api(path: String): String = "${apiBase.trimEnd('/')}/$path"

    fun currentUser(): String? {
        val response = http.execute("GET", api("user"), headers = headers())
        if (response == null) {
            println("[sync] 无法访问 ${api("user")}（网络或 TLS 失败）")
            return null
        }
        if (response.status != 200) {
            println("[sync] ${api("user")} 返回 HTTP ${response.status}：${response.text().take(200)}")
            return null
        }
        return NeriJsonParser.parse(response.text()).asObject()?.str("login")
    }

    fun tokenScopes(): List<String> {
        val response = http.execute("GET", api("user"), headers = headers()) ?: return emptyList()
        val raw = response.header("x-oauth-scopes") ?: return emptyList()
        return raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun repoDefaultBranch(owner: String, repo: String): String? {
        val response = http.execute("GET", api("repos/$owner/$repo"), headers = headers()) ?: return null
        if (response.status != 200) return null
        return NeriJsonParser.parse(response.text()).asObject()?.str("default_branch")?.ifBlank { "main" }
    }

    fun branchHead(owner: String, repo: String, branch: String): String? {
        val response = http.execute("GET", api("repos/$owner/$repo/git/ref/heads/$branch"), headers = headers())
            ?: return null
        if (response.status != 200) return null
        return NeriJsonParser.parse(response.text()).asObject()?.obj("object")?.str("sha")
    }

    fun listRepos(): List<String> {
        val response = http.execute(
            "GET",
            api("user/repos?per_page=100&sort=updated&affiliation=owner,collaborator"),
            headers = headers(),
        ) ?: return emptyList()
        if (response.status != 200) return emptyList()
        val array = NeriJsonParser.parse(response.text()) as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return array.mapNotNull { element -> element.asObject()?.str("full_name") }
    }

    fun createRepo(name: String, private: Boolean = true): Result<String> {
        val payload = """{"name":"$name","private":$private,"auto_init":true,"description":"NeriPlayer 同步仓库"}"""
        val response = http.execute(
            "POST",
            api("user/repos"),
            body = payload,
            headers = headers(mapOf("Content-Type" to "application/json")),
        ) ?: return Result.failure(IllegalStateException("网络请求失败"))
        val text = response.text()
        if (response.status == 201) {
            val fullName = NeriJsonParser.parse(text).asObject()?.str("full_name")
            return if (fullName != null) {
                Result.success(fullName)
            } else {
                Result.failure(IllegalStateException("返回数据缺少仓库名"))
            }
        }
        val message = NeriJsonParser.parse(text).asObject()?.str("message") ?: "HTTP ${response.status}"
        println("[sync] 创建仓库失败：$message")
        return Result.failure(IllegalStateException(message))
    }

    fun deleteRepo(owner: String, repo: String): Boolean {
        val response = http.execute("DELETE", api("repos/$owner/$repo"), headers = headers()) ?: return false
        return response.status == 204 || response.status == 404
    }

    /** 读取同步文件：优先当前通道，其次兼容通道（与手机端一致）。 */
    fun readSyncFile(owner: String, repo: String, useDataSaver: Boolean): RemoteSyncFile? {
        val branch = repoDefaultBranch(owner, repo) ?: return null
        val head = branchHead(owner, repo, branch) ?: return null
        val candidates = listOf(SyncDataSerializer.fileNameFor(useDataSaver)) +
            SyncDataSerializer.readFallbackFileNames(useDataSaver)
        candidates.forEach { path ->
            val response = http.execute(
                "GET",
                api("repos/$owner/$repo/contents/$path?ref=$branch"),
                headers = headers(mapOf("Accept" to "application/vnd.github.raw")),
            )
            if (response != null && response.status == 200 && response.bytes.isNotEmpty()) {
                return RemoteSyncFile(response.bytes, head, path)
            }
        }
        return null
    }

    /** 提交同步文件；expectedHead 为空表示这是首次写入。 */
    fun writeSyncFile(
        owner: String,
        repo: String,
        content: ByteArray,
        expectedHead: String?,
        message: String,
        useDataSaver: Boolean,
    ): SyncWriteResult {
        if (content.isEmpty()) return SyncWriteResult.Failed("拒绝上传空的同步数据")
        val branch = repoDefaultBranch(owner, repo) ?: return SyncWriteResult.Failed("无法读取仓库默认分支")
        val head = branchHead(owner, repo, branch) ?: return SyncWriteResult.Failed("无法读取分支 HEAD")
        if (expectedHead != null && expectedHead != head) {
            return SyncWriteResult.Conflict
        }

        val blobSha = createBlob(owner, repo, content) ?: return SyncWriteResult.Failed("创建 blob 失败")
        val baseTree = commitTree(owner, repo, head) ?: return SyncWriteResult.Failed("读取提交树失败")
        val path = SyncDataSerializer.fileNameFor(useDataSaver)
        val treeSha = createTree(owner, repo, baseTree, path, blobSha)
            ?: return SyncWriteResult.Failed("创建 tree 失败")
        val commitSha = createCommit(owner, repo, message, treeSha, head)
            ?: return SyncWriteResult.Failed("创建 commit 失败")
        return when (val updated = updateRef(owner, repo, branch, commitSha)) {
            true -> SyncWriteResult.Success(commitSha, commitSha)
            false -> {
                // 非快进 / 校验失败都按冲突处理，由上层重新拉取合并
                println("[sync] 更新分支引用失败（可能是并发提交）")
                SyncWriteResult.Conflict
            }
        }
    }

    private fun createBlob(owner: String, repo: String, content: ByteArray): String? {
        val payload = """{"content":"${Base64.getEncoder().encodeToString(content)}","encoding":"base64"}"""
        val response = http.execute(
            "POST",
            api("repos/$owner/$repo/git/blobs"),
            body = payload,
            headers = headers(mapOf("Content-Type" to "application/json")),
        ) ?: return null
        if (response.status != 201) return null
        return NeriJsonParser.parse(response.text()).asObject()?.str("sha")
    }

    private fun commitTree(owner: String, repo: String, commitSha: String): String? {
        val response = http.execute("GET", api("repos/$owner/$repo/git/commits/$commitSha"), headers = headers())
            ?: return null
        if (response.status != 200) return null
        return NeriJsonParser.parse(response.text()).asObject()?.obj("tree")?.str("sha")
    }

    private fun createTree(
        owner: String,
        repo: String,
        baseTree: String,
        path: String,
        blobSha: String,
    ): String? {
        val payload = """{"base_tree":"$baseTree","tree":[{"path":"$path","mode":"100644","type":"blob","sha":"$blobSha"}]}"""
        val response = http.execute(
            "POST",
            api("repos/$owner/$repo/git/trees"),
            body = payload,
            headers = headers(mapOf("Content-Type" to "application/json")),
        ) ?: return null
        if (response.status != 201) return null
        return NeriJsonParser.parse(response.text()).asObject()?.str("sha")
    }

    private fun createCommit(
        owner: String,
        repo: String,
        message: String,
        treeSha: String,
        parentSha: String,
    ): String? {
        val payload = """{"message":"${message.replace("\"", "'")}","tree":"$treeSha","parents":["$parentSha"]}"""
        val response = http.execute(
            "POST",
            api("repos/$owner/$repo/git/commits"),
            body = payload,
            headers = headers(mapOf("Content-Type" to "application/json")),
        ) ?: return null
        if (response.status != 201) return null
        return NeriJsonParser.parse(response.text()).asObject()?.str("sha")
    }

    private fun updateRef(owner: String, repo: String, branch: String, commitSha: String): Boolean {
        val payload = """{"sha":"$commitSha","force":false}"""
        val response = http.execute(
            "PATCH",
            api("repos/$owner/$repo/git/refs/heads/$branch"),
            body = payload,
            headers = headers(mapOf("Content-Type" to "application/json")),
        ) ?: return false
        if (response.status == 200) return true
        println("[sync] updateRef HTTP ${response.status}: ${response.text().take(160)}")
        return false
    }
}
