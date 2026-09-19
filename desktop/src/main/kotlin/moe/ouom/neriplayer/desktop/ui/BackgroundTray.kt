package moe.ouom.neriplayer.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.TrayState
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.rememberTrayState
import moe.ouom.neriplayer.desktop.core.AppContainer
import moe.ouom.neriplayer.desktop.core.PlaybackState
import java.awt.SystemTray

/** 系统托盘是否可用（无面板的极简桌面环境下会返回 false）。 */
/** 桌面环境是否提供系统托盘（无面板的极简环境会返回 false）。 */
fun isTrayAvailable(): Boolean =
    runCatching { SystemTray.isSupported() }.getOrDefault(false)

/** 加载托盘图标（打包在 jar 资源里，避免依赖运行目录）。 */
fun loadTrayIcon(): Painter? = runCatching {
    val bytes = object {}.javaClass.getResourceAsStream("/neriplayer-tray.png")?.readBytes() ?: return null
    BitmapPainter(org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap())
}.getOrNull()

/**
 * 托盘常驻与后台控制：对应手机端的「前台服务 + 通知栏控制」。
 * 托盘图标自带播放控制菜单，窗口关闭后可继续在后台播放。
 */
@Composable
fun ApplicationScope.AppTray(
    container: AppContainer,
    trayState: TrayState,
    onShowWindow: () -> Unit,
    onQuit: () -> Unit,
) {
    val icon = remember { loadTrayIcon() } ?: return
    val song by container.player.currentSong.collectAsState()
    val playbackState by container.player.state.collectAsState()
    val settings by container.settings.state.collectAsState()
    val playing = playbackState == PlaybackState.PLAYING
    val tooltip = song?.let {
        "${if (playing) "正在播放" else "已暂停"}：${it.displayName()} - ${it.artistText()}"
    } ?: "NeriPlayer（未在播放）"

    Tray(
        icon = icon,
        state = trayState,
        tooltip = tooltip,
        onAction = onShowWindow,
        menu = {
            Item("显示主窗口", onClick = onShowWindow)
            Separator()
            Item(if (playing) "暂停" else "播放", onClick = { container.player.togglePlayPause() })
            Item("上一首", onClick = { container.player.previous() })
            Item("下一首", onClick = { container.player.next() })
            Separator()
            Item(
                if (settings.floatingLyricsEnabled) "关闭悬浮歌词" else "开启悬浮歌词",
                onClick = {
                    container.settings.update { it.copy(floatingLyricsEnabled = !it.floatingLyricsEnabled) }
                },
            )
            Separator()
            Item("退出 NeriPlayer", onClick = onQuit)
        },
    )
}

/** 歌曲变化时（窗口隐藏的情况下）发系统通知，等价于手机端的播放通知。 */
@Composable
fun SongChangeNotifier(
    container: AppContainer,
    trayState: TrayState,
    windowVisible: () -> Boolean,
) {
    val song by container.player.currentSong.collectAsState()
    val settings by container.settings.state.collectAsState()
    val key = song?.key
    LaunchedEffect(key) {
        val current = song ?: return@LaunchedEffect
        if (!settings.notifyOnSongChange) return@LaunchedEffect
        if (windowVisible()) return@LaunchedEffect
        runCatching {
            trayState.sendNotification(
                Notification(
                    title = current.displayName(),
                    message = current.artistText(),
                )
            )
        }
    }
}
