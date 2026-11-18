package moe.ouom.neriplayer.desktop.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.desktop.core.AppContainer
import moe.ouom.neriplayer.desktop.core.PlaybackState
import moe.ouom.neriplayer.desktop.core.StatusNotifierService

/**
 * 系统托盘（StatusNotifierItem + DBusMenu，复用 Compose 时代的 Kotlin 实现与 Python 桥接脚本）。
 *
 * 播放控制类菜单项直接在后端执行；窗口相关动作通过 "tray" 事件交给 Qt 前端：
 * command = showWindow / settings / downloads / openPanel / quit / toggleFloatingLyrics / toggleFloatingLyricsLock。
 */
class TrayService(
    private val container: AppContainer,
    private val server: CoreServer,
) {
    private var notifier: StatusNotifierService? = null

    val running: Boolean get() = notifier?.running == true

    fun start(scope: CoroutineScope) {
        val service = StatusNotifierService(
            title = {
                container.player.currentSong.value?.let { "NeriPlayer · ${it.title}" } ?: "NeriPlayer"
            },
            playing = { container.player.state.value == PlaybackState.PLAYING },
            floatingLyricsEnabled = { container.settings.current.floatingLyricsEnabled },
            floatingLyricsLocked = { container.settings.current.floatingLyricsLocked },
            onTogglePlay = { container.player.togglePlayPause() },
            onPrevious = { container.player.previous() },
            onNext = { container.player.next() },
            onShowWindow = { emit("showWindow") },
            onToggleFloatingLyrics = {
                val next = !container.settings.current.floatingLyricsEnabled
                container.settings.update { it.copy(floatingLyricsEnabled = next) }
                server.broadcastMap("tray", mapOf("command" to "toggleFloatingLyrics", "enabled" to next))
            },
            onToggleFloatingLyricsLock = {
                val next = !container.settings.current.floatingLyricsLocked
                container.settings.update { it.copy(floatingLyricsLocked = next) }
                server.broadcastMap("tray", mapOf("command" to "toggleFloatingLyricsLock", "locked" to next))
            },
            onOpenDownloads = { emit("downloads") },
            onOpenSettings = { emit("settings") },
            onOpenPanel = { emit("openPanel") },
            onQuit = {
                runCatching { container.player.persistQueueState() }
                emit("quit")
            },
        )
        runCatching { service.start() }
        notifier = service
        log("tray ${if (service.running) "ready" else "unavailable"}")

        // 歌曲/播放状态变化时刷新托盘菜单文案
        scope.launch {
            container.player.currentSong.collect { service.refresh() }
        }
        scope.launch {
            container.player.state.collect { service.refresh() }
        }
    }

    private fun emit(command: String) {
        server.broadcastMap("tray", mapOf("command" to command))
    }

    fun stop() {
        runCatching { notifier?.stop() }
        notifier = null
    }
}
