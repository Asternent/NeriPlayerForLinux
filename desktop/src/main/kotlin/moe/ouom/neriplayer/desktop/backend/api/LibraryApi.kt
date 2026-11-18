package moe.ouom.neriplayer.desktop.backend.api

import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import moe.ouom.neriplayer.desktop.backend.CoreServer
import moe.ouom.neriplayer.desktop.backend.arrayOrNull
import moe.ouom.neriplayer.desktop.backend.badRequest
import moe.ouom.neriplayer.desktop.backend.encodeJson
import moe.ouom.neriplayer.desktop.backend.log
import moe.ouom.neriplayer.desktop.backend.stringOrNull
import moe.ouom.neriplayer.desktop.core.AppContainer
import moe.ouom.neriplayer.desktop.core.AppSettings
import java.io.File

/**
 * 本地媒体库命令。
 *
 * 扫描类命令是重操作：这里用 [scanMutex] 把「命令触发的扫描」串行化，避免前后两次扫描
 * 并发读写同一份索引。状态变化仍由 StatePublisher 通过 `library.changed` / `library.scan` 推送。
 */
object LibraryApi {

    private val scanMutex = Mutex()

    fun register(server: CoreServer, container: AppContainer) {
        val library = container.library
        val settings = container.settings

        // ---------------------------------------------------------------- 查询
        server.on("library.songs") {
            buildJsonObject { put("songs", encodeJson(library.songs.value)) }
        }

        server.on("library.artists") {
            buildJsonObject { put("artists", encodeJson(library.artists())) }
        }

        server.on("library.albums") {
            buildJsonObject { put("albums", encodeJson(library.albums())) }
        }

        server.on("library.byKey") { params ->
            val key = params.stringOrNull("key")?.trim().orEmpty()
            if (key.isBlank()) badRequest("缺少 key")
            // 找不到时返回 {"song":null}，前端据此显示空状态
            buildJsonObject { put("song", encodeJson(library.byKey(key))) }
        }

        server.on("library.scanState") {
            buildJsonObject { put("scan", encodeJson(library.scanState.value)) }
        }

        server.on("library.indexLoaded") {
            buildJsonObject { put("loaded", library.indexLoaded.value) }
        }

        // ---------------------------------------------------------------- 扫描
        server.on("library.scan") {
            buildJsonObject { put("count", scanOnce(container)) }
        }

        // ---------------------------------------------------------------- 音乐文件夹
        server.on("library.addFolder") { params ->
            val raw = params.stringOrNull("path")?.trim().orEmpty()
            if (raw.isBlank()) badRequest("缺少 path")
            val path = resolveFolder(raw).absolutePath
            if (!File(path).isDirectory) badRequest("目录不存在或不是文件夹: $raw")
            settings.update { current ->
                if (current.musicFolders.any { samePath(it, path) }) {
                    current
                } else {
                    current.copy(musicFolders = current.musicFolders + path)
                }
            }
            // 设置已生效，扫描放到后台跑，不让前端等整个扫描结束
            container.scope.launch {
                runCatching { scanOnce(container) }
                    .onFailure { log("background library scan failed: ${it.message}") }
            }
            settingsPayload(settings.current)
        }

        server.on("library.removeFolder") { params ->
            val raw = params.stringOrNull("path")?.trim().orEmpty()
            if (raw.isBlank()) badRequest("缺少 path")
            val target = resolveFolder(raw).absolutePath
            settings.update { current ->
                current.copy(musicFolders = current.musicFolders.filterNot { samePath(it, target) })
            }
            settingsPayload(settings.current)
        }

        server.on("library.setFolders") { params ->
            val array = params.arrayOrNull("folders") ?: badRequest("缺少 folders")
            val folders = LinkedHashSet<String>()
            for (element in array) {
                val raw = (element as? JsonPrimitive)
                    ?.takeIf { it.isString }?.content?.trim()
                    ?: badRequest("folders 必须是字符串数组")
                if (raw.isBlank()) badRequest("folders 含空路径")
                folders += resolveFolder(raw).absolutePath
            }
            settings.update { it.copy(musicFolders = folders.toList()) }
            settingsPayload(settings.current)
        }
    }

    private fun settingsPayload(settings: AppSettings) = buildJsonObject {
        put("settings", encodeJson(settings))
    }

    /** 命令触发的扫描串行执行；[moe.ouom.neriplayer.desktop.core.LibraryRepository.scan] 内部已切到 IO 线程。 */
    private suspend fun scanOnce(container: AppContainer): Int =
        scanMutex.withLock { container.library.scan() }

    /** 支持 `~` 与 `~/xxx`（文件选择器给的一般是绝对路径，这里只做兜底）。 */
    private fun resolveFolder(raw: String): File {
        val home = System.getProperty("user.home")
        return when {
            raw == "~" -> File(home)
            raw.startsWith("~/") -> File(home, raw.removePrefix("~/"))
            else -> File(raw)
        }
    }

    /** 按绝对路径比较，避免 `~` 展开、相对路径等写法造成重复收录。 */
    private fun samePath(left: String, absolute: String): Boolean =
        runCatching { resolveFolder(left).absolutePath == absolute }.getOrDefault(left == absolute)
}
