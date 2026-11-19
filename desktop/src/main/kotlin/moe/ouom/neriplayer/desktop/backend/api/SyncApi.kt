package moe.ouom.neriplayer.desktop.backend.api

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import moe.ouom.neriplayer.desktop.backend.CoreServer
import moe.ouom.neriplayer.desktop.backend.badRequest
import moe.ouom.neriplayer.desktop.backend.boolOrNull
import moe.ouom.neriplayer.desktop.backend.encodeAny
import moe.ouom.neriplayer.desktop.backend.stringOrNull
import moe.ouom.neriplayer.desktop.core.AppContainer

/**
 * GitHub 跨设备同步：配置读写、手动同步、状态查询。
 *
 * Token 明文只保存在本机 sync.json（600），任何回包都只给掩码。
 * `sync.state` 事件由 StatePublisher 推送，配置变更后会刷新 `configured` 状态。
 * 命令清单见 desktop/docs/protocol-sync.md。
 */
object SyncApi {

    private const val DEFAULT_API_BASE = "https://api.github.com"

    fun register(server: CoreServer, container: AppContainer) {
        server.on("sync.config") {
            configPayload(container)
        }

        server.on("sync.configure") { params ->
            configure(container, params)
            configPayload(container)
        }

        server.on("sync.perform") {
            // performSync 内部会在未配置时返回失败，这里额外区分「未配置」与「同步失败」
            val configuredBefore = container.syncConfig.current.configured
            val state = container.sync.performSync()
            val status = when {
                state.success -> "success"
                !configuredBefore -> "not_configured"
                else -> "failed"
            }
            buildJsonObject {
                put("status", status)
                put("success", state.success)
                put("message", state.message)
                put("lastSyncAt", state.lastSyncAt)
                put("configured", container.syncConfig.current.configured)
            }
        }

        server.on("sync.disable") { params ->
            disable(container, params)
            configPayload(container)
        }

        server.on("sync.status") {
            statusPayload(container)
        }

        // —— 以下为旧 UI 同步设置页也用到、但协议必需清单之外的扩展命令 ——
        server.on("sync.verifyToken") { params ->
            val token = tokenOf(container, params)
            container.sync.verifyToken(token).fold(
                onSuccess = { login ->
                    buildJsonObject {
                        put("ok", true)
                        put("login", login)
                    }
                },
                onFailure = { error ->
                    buildJsonObject {
                        put("ok", false)
                        put("message", error.message ?: "Token 校验失败")
                    }
                },
            )
        }

        server.on("sync.listRepos") { params ->
            val token = tokenOf(container, params)
            buildJsonObject { put("repos", encodeAny(container.sync.listRepos(token))) }
        }

        server.on("sync.createRepo") { params ->
            val token = tokenOf(container, params)
            val name = params.stringOrNull("name")?.trim()?.takeIf { it.isNotBlank() }
                ?: badRequest("缺少 name")
            container.sync.createRepo(token, name).fold(
                onSuccess = { fullName ->
                    buildJsonObject {
                        put("ok", true)
                        put("fullName", fullName)
                    }
                },
                onFailure = { error ->
                    buildJsonObject {
                        put("ok", false)
                        put("message", error.message ?: "创建仓库失败")
                    }
                },
            )
        }
    }

    /** 局部更新：只覆盖 params 里出现的字段；token 传空字符串表示清除。 */
    private fun configure(container: AppContainer, params: JsonObject) {
        container.syncConfig.update { config ->
            var next = config
            if (params.containsKey("token")) {
                next = next.copy(token = params.stringOrNull("token").orEmpty().trim())
            }
            if (params.containsKey("owner")) {
                next = next.copy(owner = params.stringOrNull("owner").orEmpty().trim())
            }
            if (params.containsKey("repo")) {
                next = next.copy(repo = params.stringOrNull("repo").orEmpty().trim())
            }
            if (params.containsKey("useDataSaver")) {
                params.boolOrNull("useDataSaver")?.let { value -> next = next.copy(useDataSaver = value) }
            }
            if (params.containsKey("autoSync")) {
                params.boolOrNull("autoSync")?.let { value -> next = next.copy(autoSync = value) }
            }
            if (params.containsKey("apiBase")) {
                val base = params.stringOrNull("apiBase").orEmpty().trim()
                    .ifBlank { DEFAULT_API_BASE }
                next = next.copy(apiBase = base.trimEnd('/'))
            }
            next
        }
        container.sync.refreshConfiguredState()
    }

    /** 清除登录凭据（token）；owner/repo 默认保留，可在参数里要求一并清除。 */
    private fun disable(container: AppContainer, params: JsonObject) {
        val clearRepo = params.boolOrNull("clearRepo")
            ?: params.boolOrNull("clearOwnerAndRepo")
            ?: false
        container.syncConfig.update { config ->
            config.copy(
                token = "",
                owner = if (clearRepo) "" else config.owner,
                repo = if (clearRepo) "" else config.repo,
            )
        }
        container.sync.refreshConfiguredState()
    }

    private fun tokenOf(container: AppContainer, params: JsonObject): String {
        val token = params.stringOrNull("token")?.takeIf { it.isNotBlank() }
            ?: container.syncConfig.current.token
        if (token.isBlank()) badRequest("缺少 token（也尚未配置）")
        return token
    }

    private fun configPayload(container: AppContainer): JsonObject {
        val config = container.syncConfig.current
        return buildJsonObject {
            put("token", maskToken(config.token))
            put("tokenSet", config.token.isNotBlank())
            put("owner", config.owner)
            put("repo", config.repo)
            put("repoFullName", config.repoFullName)
            put("useDataSaver", config.useDataSaver)
            put("autoSync", config.autoSync)
            put("apiBase", config.apiBase)
            put("lastSyncAt", config.lastSyncAt)
            put("lastStatus", config.lastStatus)
            put("configured", config.configured)
        }
    }

    /** 状态以配置为准（core 的 finish() 在未配置时也会把 configured 置真）。 */
    private fun statusPayload(container: AppContainer): JsonObject {
        val state = container.sync.state.value
        return buildJsonObject {
            put("configured", container.syncConfig.current.configured)
            put("running", state.running)
            put("lastSyncAt", state.lastSyncAt)
            put("message", state.message)
            put("success", state.success)
        }
    }

    /** 只暴露前 4 个字符，其余打码。 */
    private fun maskToken(token: String): String =
        if (token.isBlank()) "" else token.take(4) + "****"
}
