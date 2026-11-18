package moe.ouom.neriplayer.desktop.backend.api

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import moe.ouom.neriplayer.desktop.backend.CoreServer
import moe.ouom.neriplayer.desktop.backend.WireJson
import moe.ouom.neriplayer.desktop.backend.arrayOrNull
import moe.ouom.neriplayer.desktop.backend.badRequest
import moe.ouom.neriplayer.desktop.backend.encodeJson
import moe.ouom.neriplayer.desktop.backend.stringOrNull
import moe.ouom.neriplayer.desktop.core.AppContainer
import moe.ouom.neriplayer.desktop.core.DownloadState
import moe.ouom.neriplayer.desktop.core.Song
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 下载管理：任务队列（内存态）、已下载索引（跨启动）、入队/取消/重试/删除/打开目录。
 *
 * 任务进度非常频繁（每 64 KB 一次），前端靠 `downloads.changed` 事件 + 低频
 * `download.tasks` 轮询刷新即可；事件由 StatePublisher 统一推送。
 * 命令清单见 desktop/docs/protocol-download.md。
 */
object DownloadApi {

    fun register(server: CoreServer, container: AppContainer) {
        val downloads = container.downloads
        val catalog = container.downloadCatalog

        fun ok() = buildJsonObject { put("ok", true) }

        fun taskIdOf(params: JsonObject): String =
            params.stringOrNull("id")?.takeIf { it.isNotBlank() } ?: badRequest("缺少 id")

        server.on("download.tasks") {
            buildJsonObject {
                put("tasks", encodeJson(downloads.tasks.value))
                put("activeCount", downloads.activeCount.value)
            }
        }

        server.on("download.items") {
            buildJsonObject {
                put("items", encodeJson(catalog.items.value.values.toList()))
            }
        }

        server.on("download.enqueue") { params ->
            var message: String? = null
            val queued = downloads.enqueue(songsOf(params)) { message = it }
            buildJsonObject {
                put("queued", queued)
                if (message != null) put("message", message)
            }
        }

        server.on("download.cancel") { params ->
            downloads.cancel(taskIdOf(params))
            ok()
        }

        server.on("download.retry") { params ->
            downloads.retry(taskIdOf(params))
            ok()
        }

        // 已完成下载的删除：删除本地文件 + 下载索引记录。
        // id 可以是 DownloadedSong.songKey，也可以是已完成任务的 task.id（内部换算成 song.key）；
        // 任务列表里的条目本身没有单条移除接口，请用 clearFinished / clearAll 清理。
        server.on("download.remove") { params ->
            val id = (params.stringOrNull("id") ?: params.stringOrNull("songKey"))
                ?.takeIf { it.isNotBlank() } ?: badRequest("缺少 id")
            val songKey = when {
                catalog.contains(id) -> id
                else -> downloads.tasks.value
                    .firstOrNull { it.id == id && it.state == DownloadState.DONE }
                    ?.song?.key
            }
            if (songKey == null) {
                return@on buildJsonObject {
                    put("ok", false)
                    put("removed", false)
                    put("fileDeleted", false)
                    put("message", "未找到该下载记录（id 应为 songKey 或已完成任务的 id）")
                }
            }
            val fileDeleted = downloads.deleteDownload(songKey)
            buildJsonObject {
                put("ok", true)
                put("removed", true)
                put("fileDeleted", fileDeleted)
            }
        }

        server.on("download.clearFinished") {
            downloads.removeFinished()
            ok()
        }

        // —— 以下为旧 UI 也用到、但协议必需清单之外的扩展命令 ——
        server.on("download.clearAll") {
            downloads.clearAll()
            ok()
        }

        server.on("download.repairMetadata") {
            buildJsonObject { put("repaired", downloads.repairMetadata()) }
        }

        server.on("download.openDirectory") { params ->
            openDirectory(container, params.stringOrNull("path"))
        }
    }

    private fun songsOf(params: JsonObject): List<Song> {
        val songs = params.arrayOrNull("songs") ?: badRequest("缺少 songs")
        return runCatching {
            WireJson.decodeFromJsonElement(ListSerializer(Song.serializer()), songs)
        }.getOrElse { badRequest("songs 格式非法: ${it.message}") }
    }

    /**
     * 用 xdg-open 打开下载目录。失败不抛异常（前端只提示），返回 ok=false 与原因。
     */
    private fun openDirectory(container: AppContainer, requested: String?): JsonObject {
        val directory = requested?.takeIf { it.isNotBlank() }?.let(::File)
            ?: container.downloads.downloadDirectory()
        val path = directory.absolutePath
        val usable = runCatching { directory.mkdirs() || directory.isDirectory }.getOrDefault(false)
        if (!usable) {
            return buildJsonObject {
                put("ok", false)
                put("path", path)
                put("message", "无法创建下载目录")
            }
        }
        val launched = runCatching {
            val process = ProcessBuilder("xdg-open", path).redirectErrorStream(true).start()
            // xdg-open 正常会很快退出；等待一小段时间以便把启动失败反馈给前端
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                true
            } else {
                process.exitValue() == 0
            }
        }.getOrDefault(false)
        return buildJsonObject {
            put("ok", launched)
            put("path", path)
            if (!launched) put("message", "无法调用 xdg-open 打开目录")
        }
    }
}
