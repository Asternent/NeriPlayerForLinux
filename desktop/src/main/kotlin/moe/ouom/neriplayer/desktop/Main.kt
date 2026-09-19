package moe.ouom.neriplayer.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import moe.ouom.neriplayer.desktop.core.AppContainer
import moe.ouom.neriplayer.desktop.ui.NeriApp
import moe.ouom.neriplayer.desktop.ui.FloatingLyricsWindow
import moe.ouom.neriplayer.desktop.ui.AppTray
import moe.ouom.neriplayer.desktop.ui.SongChangeNotifier
import moe.ouom.neriplayer.desktop.ui.DesktopTray
import moe.ouom.neriplayer.desktop.ui.isTrayAvailable
import moe.ouom.neriplayer.desktop.ui.AppIntents
import moe.ouom.neriplayer.desktop.ui.TrayControlPanel
import moe.ouom.neriplayer.desktop.ui.setupDesktopLookAndFeel
import moe.ouom.neriplayer.desktop.core.PlaybackState
import moe.ouom.neriplayer.desktop.core.UiScale
import moe.ouom.neriplayer.desktop.ui.ApplyUiScale
import moe.ouom.neriplayer.desktop.ui.mainWindowSize
import moe.ouom.neriplayer.desktop.ui.platformUiScale
import kotlinx.coroutines.delay

fun main() {
    setupDesktopLookAndFeel()
    val container = AppContainer()
    container.bootstrap()
    application {
        val settings by container.settings.state.collectAsState()
        var mainWindowFocused by remember { mutableStateOf(true) }
        var windowVisible by remember { mutableStateOf(true) }
        val traySupported = remember { isTrayAvailable() }
        var trayPanelVisible by remember { mutableStateOf(false) }
        val currentSong by container.player.currentSong.collectAsState()
        val playbackState by container.player.state.collectAsState()
        // 界面缩放：默认跟随桌面（Linux 上 Compose 不读 Xft.dpi，高分屏会只有一半大小）
        val uiScale = remember(settings.uiScale) { UiScale.resolve(settings.uiScale) }
        // 允许通过环境变量覆盖初始窗口尺寸（便于截图与多屏使用）
        val sizeOverride = System.getenv("NERIPLAYER_WINDOW_SIZE").orEmpty()
        val windowSize = sizeOverride.split('x').mapNotNull { it.trim().toIntOrNull() }
            .takeIf { it.size == 2 }
            ?.let { mainWindowSize(it[0].toFloat(), it[1].toFloat(), uiScale) }
            ?: mainWindowSize(1180f, 820f, uiScale)
        val windowState = rememberWindowState(
            size = windowSize,
            position = WindowPosition(Alignment.Center),
        )
        Window(
            onCloseRequest = {
                if (settings.closeToTray && traySupported) {
                    // 隐藏窗口但继续在后台播放，与手机端「退回后台仍播放」一致
                    windowVisible = false
                    if (!settings.trayHintShown) {
                        container.settings.update { it.copy(trayHintShown = true) }
                        DesktopTray.notify(
                            "NeriPlayer 仍在后台运行",
                            "音乐不会中断，点击托盘图标可以打开控制面板",
                        )
                    }
                } else {
                    container.player.persistQueueState()
                    exitApplication()
                }
            },
            visible = windowVisible,
            state = windowState,
            title = currentSong?.let { song ->
                val prefix = if (playbackState == PlaybackState.PLAYING) "▶ " else "⏸ "
                "$prefix${song.displayName()} - ${song.artistText()} · NeriPlayer"
            } ?: "音理音理!! · NeriPlayer",
            onKeyEvent = { event -> handleShortcut(event, container) },
        ) {
            LaunchedEffect(Unit) {
                while (true) {
                    mainWindowFocused = window.isFocused
                    delay(350)
                }
            }
            val windowDensity = LocalDensity.current.density
            LaunchedEffect(uiScale, windowDensity) {
                println(
                    "[ui] 界面缩放 ${"%.0f".format(uiScale * 100)}%（窗口基准密度=" +
                        "${"%.2f".format(windowDensity)}，平台=" +
                        "${"%.2f".format(platformUiScale())}）：" +
                        if (settings.uiScale <= 0f) UiScale.source else "设置中手动指定"
                )
            }
            ApplyUiScale(uiScale) {
                NeriApp(container)
            }
        }

        // 最小化时隐藏到托盘（仍继续播放）
        LaunchedEffect(windowState.isMinimized, settings.minimizeToTray, traySupported) {
            if (windowState.isMinimized && settings.minimizeToTray && traySupported) {
                windowState.isMinimized = false
                windowVisible = false
            }
        }

        // 系统托盘：后台播放控制
        if (traySupported) {
            AppTray(
                container = container,
                onActivate = { trayPanelVisible = !trayPanelVisible },
            )
            SongChangeNotifier(
                container = container,
                windowVisible = { windowVisible },
            )
        } else {
            LaunchedEffect(Unit) {
                println("[tray] 当前桌面环境没有系统托盘，托盘常驻不可用（MPRIS 仍可控制播放）")
            }
        }

        // 应用主题风格的后台控制面板（左键点托盘图标弹出）
        TrayControlPanel(
            container = container,
            visible = trayPanelVisible,
            onDismiss = { trayPanelVisible = false },
            onShowWindow = {
                windowVisible = true
                windowState.isMinimized = false
            },
        )

        // 系统媒体控制（MPRIS）：桌面媒体组件 / 媒体键 / playerctl 可以直接控制播放
        DisposableEffect(Unit) {
            AppIntents.showMainWindow = {
                windowVisible = true
                windowState.isMinimized = false
            }
            AppIntents.quit = {
                container.player.persistQueueState()
                exitApplication()
            }
            AppIntents.toggleTrayPanel = {
                trayPanelVisible = !trayPanelVisible
            }
            container.mpris.onRaise = {
                windowVisible = true
                windowState.isMinimized = false
            }
            container.mpris.onQuit = {
                container.player.persistQueueState()
                exitApplication()
            }
            onDispose {
                AppIntents.showMainWindow = null
                AppIntents.quit = null
                AppIntents.toggleTrayPanel = null
                container.mpris.onRaise = null
                container.mpris.onQuit = null
            }
        }

        // 悬浮歌词：主窗口聚焦且开启「应用内隐藏」时临时隐藏
        if (settings.floatingLyricsEnabled && !(settings.floatingLyricsHideInApp && mainWindowFocused)) {
            FloatingLyricsWindow(
                container = container,
                settings = settings,
                onClose = { container.settings.update { it.copy(floatingLyricsEnabled = false) } },
            )
        }
    }
}

private fun handleShortcut(event: KeyEvent, container: AppContainer): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val player = container.player
    return when {
        event.key == Key.Spacebar -> {
            player.togglePlayPause()
            true
        }

        event.isCtrlPressed && event.key == Key.DirectionRight -> {
            player.next()
            true
        }

        event.isCtrlPressed && event.key == Key.DirectionLeft -> {
            player.previous()
            true
        }

        event.key == Key.DirectionRight -> {
            player.seekTo(player.positionMs.value + 5_000L)
            true
        }

        event.key == Key.DirectionLeft -> {
            player.seekTo(player.positionMs.value - 5_000L)
            true
        }

        event.key == Key.DirectionUp -> {
            player.setVolume(player.volume.value + 0.05f)
            true
        }

        event.key == Key.DirectionDown -> {
            player.setVolume(player.volume.value - 0.05f)
            true
        }

        event.isCtrlPressed && event.key == Key.L -> {
            val enabled = !container.settings.current.floatingLyricsEnabled
            container.settings.update { it.copy(floatingLyricsEnabled = enabled) }
            true
        }

        else -> false
    }
}
