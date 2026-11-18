package moe.ouom.neriplayer.desktop

import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
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
import moe.ouom.neriplayer.desktop.core.ShortcutAction
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
    ensureDesktopFile()
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

        // 全局快捷键：启动时按内置默认值静默注册（桌面弹授权，未配过的键直接生效，
        // 已配过的键保留用户设置）。改键走系统设置，不随应用内绑定变化重注册。
        val globalShortcuts = remember { moe.ouom.neriplayer.desktop.core.GlobalShortcutsPortal { actionId ->
            println("[shortcut] 触发：$actionId")
            val action = ShortcutAction.fromId(actionId)
            if (action != null) {
                moe.ouom.neriplayer.desktop.ui.ShortcutDispatcher.dispatch(action, container)
            }
        } }
        LaunchedEffect(Unit) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                globalShortcuts.registerDefaults()
            }
        }
        DisposableEffect(Unit) {
            moe.ouom.neriplayer.desktop.ui.AppIntents.openGlobalShortcutSettings = {
                globalShortcuts.openConfigure()
            }
            onDispose {
                globalShortcuts.stop()
                moe.ouom.neriplayer.desktop.ui.AppIntents.openGlobalShortcutSettings = null
            }
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
                floatingLyricsLocked = { container.settings.current.floatingLyricsLocked },
                onTogglePlay = { container.player.togglePlayPause() },
                onPrevious = { container.player.previous() },
                onNext = { container.player.next() },
                onShowWindow = { restoreMainWindow() },
                onToggleFloatingLyrics = {
                    container.settings.update { it.copy(floatingLyricsEnabled = !it.floatingLyricsEnabled) }
                },
                onToggleFloatingLyricsLock = {
                    container.settings.update { it.copy(floatingLyricsLocked = !it.floatingLyricsLocked) }
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
            // 用预览层而非 post 层：快捷键要在焦点分发之前被处理，
            // 否则焦点在按钮/输入框上时（如空格播放暂停）事件先被控件消费，窗口层收不到
            onPreviewKeyEvent = { event -> handleShortcut(event, container) },
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
            // 快捷键（含全局）通过统一调度器触发控制面板
            moe.ouom.neriplayer.desktop.ui.ShortcutDispatcher.onToggleTrayPanel = {
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
                moe.ouom.neriplayer.desktop.ui.ShortcutDispatcher.onToggleTrayPanel = null
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

/**
 * 确保 ~/.local/share/applications/neriplayer.desktop 存在。
 *
 * xdg-desktop-portal（≥1.16）要求解析出应用 id 才允许 GlobalShortcuts 等调用，
 * 解析依据是已安装 .desktop 文件的 basename。deb 自带的 desktop 文件在
 * /opt/neriplayer/lib/ 下不在标准目录，因此这里在启动时补写一份到用户目录。
 */
private fun ensureDesktopFile() {
    try {
        val dir = java.io.File(System.getProperty("user.home"), ".local/share/applications")
        dir.mkdirs()
        val target = java.io.File(dir, "neriplayer.desktop")
        if (target.exists()) return
        val icon = java.io.File("/opt/neriplayer/lib/NeriPlayer.png")
            .takeIf { it.isFile }?.absolutePath ?: "neriplayer"
        target.writeText(
            """
            [Desktop Entry]
            Name=NeriPlayer
            Comment=NeriPlayer 音理音理 — Linux 原生 Compose Desktop 音乐播放器
            Exec=/opt/neriplayer/bin/NeriPlayer
            Icon=$icon
            Terminal=false
            Type=Application
            Categories=Audio
            StartupWMClass=moe-ouom-neriplayer-desktop-MainKt
            """.trimIndent() + "\n",
        )
        println("[shortcut] 已写入用户 .desktop 文件：$target")
    } catch (e: Exception) {
        println("[shortcut] 写入 .desktop 失败（不影响正常运行）：${e.message}")
    }
}

/**
 * 程序内快捷键：按用户配置（设置页 → 快捷键 → 应用内）匹配，未配置时用默认绑定。
 * 默认绑定与旧版硬编码行为一致：Space 播放暂停、媒体键切歌、Ctrl+L 悬浮歌词、Esc 返回。
 */
private fun handleShortcut(event: KeyEvent, container: AppContainer): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val shortcuts = container.settings.current.shortcuts
    for (action in ShortcutAction.entries) {
        if (shortcuts.localBindingFor(action).matches(event)) {
            return moe.ouom.neriplayer.desktop.ui.ShortcutDispatcher.dispatch(action, container)
        }
    }
    return false
}
