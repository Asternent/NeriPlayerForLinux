package moe.ouom.neriplayer.desktop.ui

import moe.ouom.neriplayer.desktop.core.AppContainer
import moe.ouom.neriplayer.desktop.core.ShortcutAction

/**
 * 快捷键动作的执行入口。
 *
 * 播放与功能类动作直接操作 [AppContainer]；页面导航和窗口相关动作需要 UI 层上下文，
 * 由 NeriApp 启动时注册回调（见 [navigate] / [togglePanel] 等）。
 */
object ShortcutDispatcher {
    /** 页面导航回调，由 NeriApp 注册。参数为动作本身。 */
    @Volatile
    var onNavigate: ((ShortcutAction) -> Unit)? = null

    /** 打开 / 关闭控制面板（托盘菜单同款），由 Main 注册。 */
    @Volatile
    var onToggleTrayPanel: (() -> Unit)? = null

    /** 打开下载管理，由 NeriApp 注册。 */
    @Volatile
    var onOpenDownloads: (() -> Unit)? = null

    /** 执行一个动作；返回 true 表示已被处理。 */
    fun dispatch(action: ShortcutAction, container: AppContainer): Boolean {
        val player = container.player
        return when (action) {
            ShortcutAction.PLAY_PAUSE -> { player.togglePlayPause(); true }
            ShortcutAction.NEXT_SONG -> { player.next(); true }
            ShortcutAction.PREVIOUS_SONG -> { player.previous(); true }
            ShortcutAction.VOLUME_UP -> {
                player.setVolume((player.volume.value + 0.05f).coerceIn(0f, 1f)); true
            }
            ShortcutAction.VOLUME_DOWN -> {
                player.setVolume((player.volume.value - 0.05f).coerceIn(0f, 1f)); true
            }
            ShortcutAction.SEEK_FORWARD -> {
                player.seekTo(player.positionMs.value + 5_000L); true
            }
            ShortcutAction.SEEK_BACKWARD -> {
                player.seekTo((player.positionMs.value - 5_000L).coerceAtLeast(0L)); true
            }
            ShortcutAction.TOGGLE_FLOATING_LYRICS -> {
                container.settings.update {
                    it.copy(floatingLyricsEnabled = !it.floatingLyricsEnabled)
                }
                true
            }
            ShortcutAction.TOGGLE_FLOATING_LYRICS_LOCK -> {
                container.settings.update {
                    it.copy(floatingLyricsLocked = !it.floatingLyricsLocked)
                }
                true
            }
            ShortcutAction.TOGGLE_TRAY_PANEL -> {
                onToggleTrayPanel?.invoke(); onToggleTrayPanel != null
            }
            ShortcutAction.TOGGLE_DOWNLOADS -> {
                onOpenDownloads?.invoke(); onOpenDownloads != null
            }
            ShortcutAction.OPEN_NOW_PLAYING,
            ShortcutAction.NAV_BACK,
            ShortcutAction.NAV_HOME,
            ShortcutAction.NAV_EXPLORE,
            ShortcutAction.NAV_LIBRARY,
            ShortcutAction.NAV_SETTINGS,
            -> {
                onNavigate?.invoke(action); onNavigate != null
            }
        }
    }

    fun clearCallbacks() {
        onNavigate = null
        onToggleTrayPanel = null
        onOpenDownloads = null
    }
}
