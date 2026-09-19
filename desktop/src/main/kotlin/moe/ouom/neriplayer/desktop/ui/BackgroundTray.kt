package moe.ouom.neriplayer.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import moe.ouom.neriplayer.desktop.core.AppContainer
import moe.ouom.neriplayer.desktop.core.PlaybackState

/** 桌面环境是否提供系统托盘（无托盘面板的极简环境会返回 false）。 */
fun isTrayAvailable(): Boolean = DesktopTray.available

/**
 * 托盘常驻与后台控制：对应手机端的「前台服务 + 通知栏控制」。
 *
 * 点击托盘图标会弹出应用主题风格的控制面板（[TrayControlPanel]），
 * 而不是样式无法跟随主题的原生菜单。
 */
@Composable
fun AppTray(
    container: AppContainer,
    onActivate: () -> Unit,
) {
    val activate by rememberUpdatedState(onActivate)
    val song by container.player.currentSong.collectAsState()
    val playbackState by container.player.state.collectAsState()
    val playing = playbackState == PlaybackState.PLAYING
    val tooltip = song?.let {
        "${if (playing) "正在播放" else "已暂停"}：${it.displayName()} - ${it.artistText()}"
    } ?: "NeriPlayer（未在播放）"

    DisposableEffect(Unit) {
        DesktopTray.install { activate() }
        onDispose { DesktopTray.dispose() }
    }

    LaunchedEffect(tooltip) {
        DesktopTray.updateTooltip(tooltip)
    }
}

/** 歌曲变化时（窗口隐藏的情况下）发系统通知，等价于手机端的播放通知。 */
@Composable
fun SongChangeNotifier(
    container: AppContainer,
    windowVisible: () -> Boolean,
) {
    val song by container.player.currentSong.collectAsState()
    val settings by container.settings.state.collectAsState()
    val key = song?.key
    LaunchedEffect(key) {
        val current = song ?: return@LaunchedEffect
        if (!settings.notifyOnSongChange) return@LaunchedEffect
        if (windowVisible()) return@LaunchedEffect
        DesktopTray.notify(current.displayName(), current.artistText())
    }
}
