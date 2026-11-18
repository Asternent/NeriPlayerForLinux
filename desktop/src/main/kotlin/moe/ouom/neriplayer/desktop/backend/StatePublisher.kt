package moe.ouom.neriplayer.desktop.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.desktop.backend.api.AppApi
import moe.ouom.neriplayer.desktop.core.AppContainer

/**
 * 把核心的 StateFlow 变化转成 IPC 事件：
 *  - "player"          播放状态快照（变化时推送，播放中最多 4 Hz）
 *  - "player.queue"    播放队列
 *  - "player.lyrics"   歌词
 *  - "theme"           主题调色板
 *  - "settings"        设置
 *  - "toast"           轻提示
 *  - "library.changed" / "library.scan" / "collections.changed" / "history.changed" /
 *    "stats.changed" / "accounts.changed" / "downloads.changed" / "sync.state"
 */
class StatePublisher(
    private val server: CoreServer,
    private val container: AppContainer,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var lastPlayerJson: String = ""

    @Volatile
    private var lastThemeJson: String = ""

    fun start() {
        // 播放状态：定时采样 + 去重，播放中约 4Hz，暂停时只在变化时发
        scope.launch {
            while (true) {
                val state = PlayerWire.state(container.player)
                val text = state.toString()
                if (text != lastPlayerJson) {
                    lastPlayerJson = text
                    server.broadcast("player", state)
                }
                delay(250)
            }
        }

        scope.launch {
            container.player.queue.collectLatest { queue ->
                server.broadcast("player.queue", PlayerWire.queue(container.player))
            }
        }

        scope.launch {
            container.player.lyrics.collectLatest { lyrics ->
                server.broadcast("player.lyrics", PlayerWire.lyrics(lyrics))
            }
        }

        scope.launch {
            container.player.lyricsLoading.collectLatest { loading ->
                server.broadcastMap("player.lyricsLoading", mapOf("loading" to loading))
            }
        }

        scope.launch {
            container.player.coverSeedColor.collectLatest { seed ->
                server.broadcastMap("player.coverSeed", mapOf("seed" to (seed ?: "")))
                broadcastTheme()
            }
        }

        scope.launch {
            container.settings.state.collectLatest {
                server.broadcast("settings", encodeJson(container.settings.current))
            }
        }

        scope.launch {
            container.player.messages.collect { message ->
                server.broadcastMap("toast", mapOf("message" to message, "kind" to "info"))
            }
        }

        scope.launch {
            container.library.songs.collectLatest { songs ->
                server.broadcastMap("library.changed", mapOf("songCount" to songs.size))
            }
        }

        scope.launch {
            container.library.scanState.collectLatest { scan ->
                server.broadcastMap("library.scan", mapOf(
                    "running" to scan.running,
                    "total" to scan.total,
                    "processed" to scan.processed,
                    "found" to scan.found,
                    "error" to scan.error,
                ))
            }
        }

        scope.launch {
            container.playlists.playlists.collectLatest { playlists ->
                server.broadcastMap("collections.changed", mapOf("count" to playlists.size))
            }
        }

        scope.launch {
            container.history.entries.collectLatest { entries ->
                server.broadcastMap("history.changed", mapOf("count" to entries.size))
            }
        }

        scope.launch {
            container.stats.stats.collectLatest { stats ->
                server.broadcastMap("stats.changed", mapOf("count" to stats.size))
            }
        }

        scope.launch {
            container.accounts.state.collectLatest { accounts ->
                server.broadcast("accounts.changed", encodeAny(accounts.accounts))
            }
        }

        scope.launch {
            container.downloads.tasks.collectLatest { tasks ->
                server.broadcastMap("downloads.changed", mapOf(
                    "activeCount" to container.downloads.activeCount.value,
                    "taskCount" to tasks.size,
                ))
            }
        }

        scope.launch {
            container.sync.state.collectLatest { state ->
                server.broadcastMap("sync.state", mapOf(
                    "configured" to state.configured,
                    "running" to state.running,
                    "lastSyncAt" to state.lastSyncAt,
                    "message" to state.message,
                    "success" to state.success,
                ))
            }
        }
    }

    /** 主题只在结果变化时推送，避免设置里改音量之类的操作引起无谓的颜色重算。 */
    fun broadcastTheme() {
        val theme = AppApi.themePayload(container)
        val text = theme.toString()
        if (text == lastThemeJson) return
        lastThemeJson = text
        server.broadcast("theme", theme)
    }

    /** 让外部（AppApi 处理后）也能刷新主题。 */
    fun forceTheme() {
        lastThemeJson = ""
        broadcastTheme()
    }
}
