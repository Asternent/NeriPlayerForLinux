package moe.ouom.neriplayer.desktop.backend.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import moe.ouom.neriplayer.desktop.backend.CoreServer
import moe.ouom.neriplayer.desktop.backend.WireJson
import moe.ouom.neriplayer.desktop.backend.badRequest
import moe.ouom.neriplayer.desktop.backend.log
import moe.ouom.neriplayer.desktop.backend.objectOrNull
import moe.ouom.neriplayer.desktop.backend.stringOrNull
import moe.ouom.neriplayer.desktop.core.AppContainer
import moe.ouom.neriplayer.desktop.core.AppDirs
import moe.ouom.neriplayer.desktop.core.MediaSource
import moe.ouom.neriplayer.desktop.core.Song
import moe.ouom.neriplayer.desktop.net.HttpService
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * 封面解析与缓存。
 *
 * 封面在协议里永远是 URL 字符串（`Song.artworkUrl` / `OnlineCollection.coverUrl` / `OnlineArtist.avatarUrl`），
 * QML 不能直接读网络图，所以由后端下载到 `AppDirs.coverDir`（`~/.cache/NeriPlayer/covers/`）
 * 并按 URL 的 MD5 命名，返回本地绝对路径给 `CoverArt { path }` 用。
 *
 * 相关命令与错误码见 desktop/docs/protocol-artwork.md。
 */
object ArtworkApi {

    private const val NETEASE_REFERER = "https://music.163.com/"
    private const val BILI_REFERER = "https://www.bilibili.com/"

    /** 同一个 URL 并发请求时只下载一次（QML 里多个 CoverArt 可能同时要同一张图）。 */
    private val cacheLocks = ConcurrentHashMap<String, Any>()

    fun register(server: CoreServer, container: AppContainer) {
        val http = container.online.httpService

        server.on("artwork.resolve") { params ->
            val url = params.stringOrNull("url")?.trim()?.takeIf { it.isNotEmpty() }
                ?: badRequest("缺少 url")
            val referer = params.stringOrNull("referer")?.takeIf { it.isNotBlank() }
                ?: defaultReferer(url)
            withContext(Dispatchers.IO) { resolveToCache(http, url, referer) }
        }

        server.on("artwork.forSong") { params ->
            val song = params.requireSong()
            withContext(Dispatchers.IO) {
                val local = song.artworkPath?.trim()?.takeIf { it.isNotEmpty() }?.let(::File)
                if (local != null && local.isFile && local.length() > 0L) {
                    pathResult(local.absolutePath, cached = true)
                } else {
                    val url = song.artworkUrl?.trim()?.takeIf { it.isNotEmpty() }
                    if (url == null) {
                        errorResult("歌曲没有封面（artworkPath 与 artworkUrl 均为空）")
                    } else {
                        val referer = params.stringOrNull("referer")?.takeIf { it.isNotBlank() }
                            ?: refererForSource(song.source)
                            ?: defaultReferer(url)
                        resolveToCache(http, url, referer)
                    }
                }
            }
        }

        server.on("artwork.clearCache") {
            withContext(Dispatchers.IO) {
                var removed = 0
                AppDirs.coverDir.listFiles()?.forEach { file ->
                    if (file.isFile && runCatching { file.delete() }.getOrDefault(false)) removed += 1
                }
                // 内存里的并发锁也一并清掉，避免长期运行积累
                cacheLocks.clear()
                log("artwork cache cleared: removed=$removed")
                buildJsonObject { put("removed", removed) }
            }
        }

        // 播放页封面右键「保存封面」：把本地 / 远程封面另存到用户选择的路径。
        // 参数同时带 url 与 path 时优先本地文件，前端不用关心封面当前是否已在缓存里。
        server.on("artwork.saveAs") { params ->
            val url = params.stringOrNull("url")?.trim()?.takeIf { it.isNotEmpty() }
            val path = params.stringOrNull("path")?.trim()?.takeIf { it.isNotEmpty() }
            val target = params.stringOrNull("target")?.trim()?.takeIf { it.isNotEmpty() }
                ?: badRequest("缺少 target")
            val referer = params.stringOrNull("referer")?.takeIf { it.isNotBlank() }
            withContext(Dispatchers.IO) {
                val source = when {
                    path != null -> localFileOf(path)?.takeIf { it.isFile && it.length() > 0L }
                        ?: return@withContext saveResult(false, null, "本地封面文件不存在或为空: $path")

                    url != null -> {
                        val resolved = resolveToCache(http, url, referer ?: defaultReferer(url))
                        val resolvedPath = resolved.stringOrNull("path")
                        if (resolvedPath.isNullOrEmpty()) {
                            return@withContext saveResult(
                                false,
                                null,
                                resolved.stringOrNull("error") ?: "封面下载失败"
                            )
                        }
                        File(resolvedPath)
                    }

                    else -> return@withContext saveResult(false, null, "缺少 url 或 path")
                }
                saveFileTo(source, target)
            }
        }
    }

    // ------------------------------------------------------------------ 下载与缓存

    /**
     * 下载封面到本地缓存。命中缓存直接返回路径。
     * @return `{path, cached}` 或 `{path:null, error}`
     */
    private fun resolveToCache(http: HttpService, rawUrl: String, referer: String?): JsonObject {
        val url = rawUrl.trim()

        // 已经是本地文件（或 file:// URL）：直接返回路径，不重复下载
        localFileOf(url)?.let { file ->
            return if (file.isFile && file.length() > 0L) {
                pathResult(file.absolutePath, cached = true)
            } else {
                errorResult("本地封面文件不存在或为空: ${file.absolutePath}")
            }
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return errorResult("不支持的 url（需要 http/https 或本地文件路径）: $url")
        }

        // 旧 Compose UI 用 url.hashCode() 命名，优先复用已有缓存
        val legacy = File(AppDirs.coverDir, url.hashCode().toString() + ".img")
        if (legacy.isFile && legacy.length() > 0L) {
            return pathResult(legacy.absolutePath, cached = true)
        }
        val target = File(AppDirs.coverDir, md5Hex(url) + ".img")
        if (target.isFile && target.length() > 0L) {
            return pathResult(target.absolutePath, cached = true)
        }

        val lock = cacheLocks.computeIfAbsent(target.name) { Any() }
        synchronized(lock) {
            if (target.isFile && target.length() > 0L) {
                return pathResult(target.absolutePath, cached = true)
            }
            val headers = referer?.takeIf { it.isNotBlank() }?.let { mapOf("Referer" to it) } ?: emptyMap()
            val bytes = http.download(url, headers)
            if (bytes == null) {
                log("artwork resolve failed (download): $url")
                return errorResult("封面下载失败（网络不可用或 URL 无效）: $url")
            }
            if (bytes.isEmpty()) {
                return errorResult("封面内容为空: $url")
            }
            if (!isImagePayload(bytes)) {
                log("artwork resolve failed (not image, ${bytes.size} bytes): $url")
                return errorResult("返回内容不是图片（可能被防盗链拦截）: $url")
            }
            val written = runCatching {
                target.parentFile?.mkdirs()
                val tmp = File(target.parentFile, "${target.name}.tmp")
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(target)) {
                    target.writeBytes(bytes)
                    tmp.delete()
                }
            }.isSuccess
            if (!written) {
                return errorResult("封面写入缓存失败: ${target.absolutePath}")
            }
            return pathResult(target.absolutePath, cached = false)
        }
    }

    private fun pathResult(path: String, cached: Boolean): JsonObject = buildJsonObject {
        put("path", path)
        put("cached", cached)
    }

    /** `artwork.saveAs` 的结果；失败不抛协议错误，与其它 artwork 命令保持一致。 */
    private fun saveResult(ok: Boolean, path: String?, error: String? = null): JsonObject = buildJsonObject {
        put("ok", ok)
        if (path != null) put("path", path) else put("path", JsonNull)
        if (error != null) put("error", error)
    }

    /**
     * 把封面文件复制到用户指定的路径。
     * 目标没有可识别的图片后缀时按内容补一个，避免出现 `cover.img` 这种打不开的文件名。
     */
    private fun saveFileTo(source: File, rawTarget: String): JsonObject {
        val bytes = runCatching { source.readBytes() }.getOrElse {
            return saveResult(false, null, "读取封面文件失败: ${source.absolutePath}")
        }
        if (bytes.isEmpty()) {
            return saveResult(false, null, "封面文件为空: ${source.absolutePath}")
        }

        var target = File(rawTarget)
        if (!target.isAbsolute) {
            return saveResult(false, null, "target 必须是绝对路径: $rawTarget")
        }
        if (target.isDirectory) {
            return saveResult(false, null, "target 是目录，需要指定文件名: $rawTarget")
        }
        val extension = target.extension.lowercase()
        if (extension !in IMAGE_EXTENSIONS) {
            val detected = detectImageExtension(bytes)
            if (!detected.isNullOrEmpty()) {
                target = File(target.parentFile, target.name + "." + detected)
            }
        }

        val written = runCatching {
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
        }.isSuccess
        if (!written) {
            return saveResult(false, null, "封面保存失败（目标路径不可写）: ${target.absolutePath}")
        }
        log("artwork saved: ${target.absolutePath} (${bytes.size} bytes)")
        return saveResult(true, target.absolutePath)
    }

    private fun detectImageExtension(bytes: ByteArray): String? {
        fun at(index: Int): Int = bytes[index].toInt() and 0xFF
        if (bytes.size >= 4) {
            if (at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF) return "jpg"
            if (at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47) return "png"
            if (at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46) return "gif"
            if (at(0) == 0x42 && at(1) == 0x4D) return "bmp"
        }
        if (bytes.size >= 12) {
            val riff = at(0) == 0x52 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x46
            if (riff && at(8) == 0x57 && at(9) == 0x45 && at(10) == 0x42 && at(11) == 0x50) return "webp"
            if (at(4) == 0x66 && at(5) == 0x74 && at(6) == 0x79 && at(7) == 0x70) return "avif"
        }
        return null
    }

    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif")

    private fun errorResult(message: String): JsonObject = buildJsonObject {
        put("path", JsonNull)
        put("error", message)
    }

    /** 本地文件绝对路径 / file:// URL → File；网络 URL 返回 null。 */
    private fun localFileOf(url: String): File? {
        if (url.startsWith("file://")) {
            return runCatching { File(java.net.URI(url)) }.getOrNull()
        }
        if (url.startsWith("http://") || url.startsWith("https://")) return null
        if (url.startsWith("//")) return null
        return runCatching { File(url) }.getOrNull()?.takeIf { it.isAbsolute }
    }

    /** 各平台图片防盗链：网易云/ B 站图片必须带对应 Referer。 */
    private fun refererForSource(source: MediaSource): String? = when (source) {
        MediaSource.NETEASE -> NETEASE_REFERER
        MediaSource.BILIBILI -> BILI_REFERER
        else -> null
    }

    private fun defaultReferer(url: String): String? {
        val host = runCatching { java.net.URI(url).host }.getOrNull()?.lowercase() ?: return null
        return when {
            host.endsWith("163.com") || host.endsWith("126.net") || host.endsWith("127.net") ->
                NETEASE_REFERER

            host.endsWith("bilibili.com") || host.endsWith("hdslb.com") ||
                host.endsWith("bilivideo.com") || host.endsWith("biliimg.com") ->
                BILI_REFERER

            else -> null
        }
    }

    /** 只接受图片内容，避免把 404/风控页面写进缓存。 */
    private fun isImagePayload(bytes: ByteArray): Boolean {
        fun at(index: Int): Int = bytes[index].toInt() and 0xFF
        if (bytes.size >= 4) {
            val jpeg = at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF
            val png = at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47
            val gif = at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46
            val bmp = at(0) == 0x42 && at(1) == 0x4D
            if (jpeg || png || gif || bmp) return true
        }
        if (bytes.size >= 12) {
            val webp = at(0) == 0x52 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x46 &&
                at(8) == 0x57 && at(9) == 0x45 && at(10) == 0x42 && at(11) == 0x50
            val avif = at(4) == 0x66 && at(5) == 0x74 && at(6) == 0x79 && at(7) == 0x70
            if (webp || avif) return true
        }
        // 未知格式但明显是文本（HTML/JSON 错误页）则拒绝
        val firstText = bytes.firstOrNull { !(it.toInt() and 0xFF).toChar().isWhitespace() }
            ?.let { (it.toInt() and 0xFF).toChar() }
        return firstText !in listOf('<', '{', '[', 'N', '4', '5')
    }

    private fun md5Hex(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun JsonObject.requireSong(): Song {
        val element = objectOrNull("song") ?: badRequest("缺少 song")
        return runCatching { WireJson.decodeFromJsonElement(Song.serializer(), element) }
            .getOrElse { badRequest("song 格式非法: ${it.message}") }
    }
}
