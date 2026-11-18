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
import moe.ouom.neriplayer.desktop.core.StatusNotifierService
import moe.ouom.neriplayer.desktop.ui.ApplyUiScale
import moe.ouom.neriplayer.desktop.ui.mainWindowSize
import moe.ouom.neriplayer.desktop.ui.platformUiScale
import kotlinx.coroutines.delay

fun main() {
    // 单实例：若已有实例在跑，唤起它的窗口后直接退出，避免多开互相抢托盘/媒体控制
    if (!moe.ouom.neriplayer.desktop.core.SingleInstance.acquire()) {
        kotlin.system.exitProcess(0)
    }
    setupDesktopLookAndFeel()
    val container = AppContainer()
    container.bootstrap()
    application {
        val settings by container.settings.state.collectAsState()
        var mainWindowFocused by remember { mutableStateOf(true) }
        var windowVisible by remember { mutableStateOf(true) }
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

        // 恢复主窗口的唯一入口。只切 visible，不碰 windowState.isMinimized。
        // isMinimized=false 这个 setter 会让底层 AWT frame 走一次 deiconify，
        // 在 XWayland/KWin 下反而给窗口留下清不掉的 _NET_WM_STATE_HIDDEN，
        // 使托盘「显示主窗口」叫不回来。隐藏窗口时 frame 已被 Compose dispose，
        // 重新 visible=true 时是全新 frame，天然是 NORMAL 状态，无需动 isMinimized。
        fun restoreMainWindow() {
            windowVisible = true
        }

        // 托盘：优先注册状态栏指示器（SNI + DBusMenu，菜单由桌面渲染，GNOME 下点击可靠）；
        // 桌面不支持时才回退到 AWT 托盘图标（XEmbed，样式与应用一致但 GNOME 上点击易被弹层抢走）
        val statusNotifier = remember {
            StatusNotifierService(
                title = {
                    container.player.currentSong.value?.let { "NeriPlayer · ${it.displayName()}" }
                        ?: "NeriPlayer"
                },
                playing = { container.player.state.value == PlaybackState.PLAYING },
                floatingLyricsEnabled = { container.settings.current.floatingLyricsEnabled },
                onTogglePlay = { container.player.togglePlayPause() },
                onPrevious = { container.player.previous() },
                onNext = { container.player.next() },
                onShowWindow = { restoreMainWindow() },
                onToggleFloatingLyrics = {
                    container.settings.update { it.copy(floatingLyricsEnabled = !it.floatingLyricsEnabled) }
                },
                onOpenDownloads = {
                    restoreMainWindow()
                    AppIntents.openDownloads?.invoke()
                },
                onOpenSettings = {
                    restoreMainWindow()
                    AppIntents.openSettings?.invoke()
                },
                onOpenPanel = { trayPanelVisible = true },
                onQuit = {
                    container.player.persistQueueState()
                    exitApplication()
                },
            ).also { it.start() }
        }
        // 「后台常驻可用」：原生指示器或 AWT 托盘任一可用即可（关闭/最小化收进托盘依赖它）
        val backgroundAvailable = remember { statusNotifier.running || isTrayAvailable() }
        // 是否需要安装 AWT 托盘图标：仅在原生指示器不可用时才装，避免出现两个图标
        val awtTraySupported = remember { !statusNotifier.running && isTrayAvailable() }

        DisposableEffect(Unit) {
            onDispose { statusNotifier.stop() }
        }

        // 歌曲 / 播放状态 / 悬浮歌词开关变化后刷新托盘菜单文案
        LaunchedEffect(currentSong?.key, playbackState, settings.floatingLyricsEnabled) {
            statusNotifier.refresh()
        }

        Window(
            onCloseRequest = {
                if (settings.closeToTray && backgroundAvailable) {
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

        // 最小化时隐藏到托盘（仍继续播放）。
        // 用户点最小化 → 底层 AWT frame 已被 iconify（KWin 侧留下 HIDDEN）；
        // 这里先隐藏窗口让 Compose dispose 掉那个 frame，再清 isMinimized。
        // 关键：清 isMinimized 必须在 frame 已 dispose 之后——若在有 frame 时清，
        // setter 会触发一次 deiconify/iconify 往返，在 XWayland/KWin 下反而留下
        // 清不掉的 _NET_WM_STATE_HIDDEN，导致托盘「显示主窗口」叫不回来。
        // 隐藏后再清时没有 frame 可操作，setter 不触碰 AWT，只是把状态归零，
        // 下次 visible=true 创建的全新 frame 就是干净的 NORMAL 状态。
        LaunchedEffect(windowState.isMinimized, settings.minimizeToTray, backgroundAvailable) {
            if (windowState.isMinimized && settings.minimizeToTray && backgroundAvailable) {
                windowVisible = false
                kotlinx.coroutines.delay(100)
                windowState.isMinimized = false
            }
        }

        // 系统托盘：后台播放控制
        if (awtTraySupported) {
            AppTray(
                container = container,
                onActivate = { trayPanelVisible = !trayPanelVisible },
            )
            SongChangeNotifier(
                container = container,
                windowVisible = { windowVisible },
            )
        } else if (!backgroundAvailable) {
            LaunchedEffect(Unit) {
                println("[tray] 当前桌面环境没有系统托盘，托盘常驻不可用（MPRIS 仍可控制播放）")
            }
        }

        // 应用主题风格的后台控制面板（左键点托盘图标弹出）
        TrayControlPanel(
            container = container,
            visible = trayPanelVisible,
            waylandAutoDismiss = !isWaylandSession(),
            onDismiss = { trayPanelVisible = false },
            onShowWindow = { restoreMainWindow() },
        )

        // 系统媒体控制（MPRIS）：桌面媒体组件 / 媒体键 / playerctl 可以直接控制播放
        DisposableEffect(Unit) {
            AppIntents.showMainWindow = { restoreMainWindow() }
            AppIntents.hideMainWindow = {
                // 等价于「关闭窗口收进托盘」：窗口隐藏、播放继续
                windowVisible = false
            }
            AppIntents.quit = {
                container.player.persistQueueState()
                exitApplication()
            }
            AppIntents.toggleTrayPanel = {
                trayPanelVisible = !trayPanelVisible
            }
            container.mpris.onRaise = { restoreMainWindow() }
            container.mpris.onQuit = {
                container.player.persistQueueState()
                exitApplication()
            }
            onDispose {
                AppIntents.showMainWindow = null
                AppIntents.hideMainWindow = null
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

/** Wayland 会话判定：托盘面板的「鼠标移开自动收起」依赖 AWT 全局指针坐标，Wayland 下不可靠。 */
private fun isWaylandSession(): Boolean =
    System.getenv("XDG_SESSION_TYPE").equals("wayland", ignoreCase = true) ||
        !System.getenv("WAYLAND_DISPLAY").isNullOrBlank()

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
