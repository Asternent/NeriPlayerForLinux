package moe.ouom.neriplayer.desktop.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.desktop.core.AppContainer
import moe.ouom.neriplayer.desktop.core.GlobalShortcutsPortal
import moe.ouom.neriplayer.desktop.core.ShortcutAction

/**
 * 全局快捷键（xdg-desktop-portal GlobalShortcuts）。
 *
 * 播放控制类动作在后端直接执行；窗口/导航类动作通过 "shortcut" 事件交给 Qt 前端：
 * {action: "<ShortcutAction.id>"}。
 */
class GlobalShortcutService(
    private val container: AppContainer,
    private val server: CoreServer,
) {
    private val portal = GlobalShortcutsPortal { actionId -> handle(actionId) }

    val active: Boolean get() = portal.isActive

    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            runCatching { portal.registerDefaults() }
            log("全局快捷键${if (portal.isActive) "已注册" else "不可用"}")
        }
    }

    fun openConfigure() {
        runCatching { portal.openConfigure() }
    }

    fun stop() {
        runCatching { portal.stop() }
    }

    private fun handle(actionId: String) {
        val action = ShortcutAction.fromId(actionId) ?: return
        when (action) {
            ShortcutAction.PLAY_PAUSE -> container.player.togglePlayPause()
            ShortcutAction.NEXT_SONG -> container.player.next()
            ShortcutAction.PREVIOUS_SONG -> container.player.previous()
            ShortcutAction.VOLUME_UP -> container.player.setVolume((container.player.volume.value + 0.05f).coerceAtMost(1f))
            ShortcutAction.VOLUME_DOWN -> container.player.setVolume((container.player.volume.value - 0.05f).coerceAtLeast(0f))
            ShortcutAction.SEEK_FORWARD -> container.player.seekTo(container.player.positionMs.value + 5000)
            ShortcutAction.SEEK_BACKWARD -> container.player.seekTo(container.player.positionMs.value - 5000)
            else -> server.broadcastMap("shortcut", mapOf("action" to actionId))
        }
    }
}
