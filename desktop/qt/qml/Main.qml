import QtQuick
import QtQuick.Controls
import "components"

// 应用主窗口。对应 Compose 版的 Main.kt + NeriApp.kt。
ApplicationWindow {
    id: root

    width: 1180
    height: 820
    minimumWidth: 720
    minimumHeight: 520
    visible: true
    color: Theme.background
    title: Player.hasSong
           ? (Player.playing ? "▶ " : "⏸ ") + Player.title
             + (Player.artist !== "" ? " - " + Player.artist : "") + " · NeriPlayer"
           : "音理音理!! · NeriPlayer"

    // 托盘是否可用（后端推送）
    property bool trayAvailable: false
    // 用户可以真正退出时的标记（托盘菜单退出 / Ctrl+Q）
    property bool forceQuit: false

    /** 供单实例激活 / 托盘菜单调用：恢复并聚焦主窗口。 */
    function showMainWindow() {
        root.show()
        root.raise()
        root.requestActivate()
    }

    /** 退出应用（托盘菜单、快捷键）。 */
    function quitApp() {
        root.forceQuit = true
        root.close()
    }

    onClosing: function(close) {
        if (root.forceQuit)
            return
        if (root.trayAvailable && Settings.get("closeToTray", true)) {
            close.accepted = false
            root.hide()
            Toast.show("已最小化到托盘，音乐继续播放")
        }
    }

    /** 供快捷键 / 截图脚本 / 托盘调用的命名路由入口。 */
    function openPageByName(name) {
        Nav.openByName(name)
    }

    function openPage(name, arg) {
        Nav.openByName(name, arg)
    }

    function openPanelByName(name) {
        if (name === "tray")
            trayPanel.showPanel()
        else
            Nav.openPanel(name)
    }

    function bootstrap() {
        console.log("[app] bootstrap requested")
        Api.call("app.bootstrap").then(function(snapshot) {
            console.log("[app] bootstrap ok, roles=", snapshot && snapshot.theme ? snapshot.theme.roles ? snapshot.theme.roles.primary : "no roles" : "no theme")
            if (!snapshot)
                return
            // 事件通道也会推送，这里再用快照兜底一次，避免事件丢失导致首屏空白
            if (snapshot.settings)
                Settings.values = snapshot.settings
            if (snapshot.theme)
                Theme.applyTheme(snapshot.theme)
            if (snapshot.player)
                Player.applySnapshot(snapshot.player)
            if (snapshot.tray && snapshot.tray.available !== undefined)
                root.trayAvailable = snapshot.tray.available === true
            if (snapshot.queue)
                Player.queue = snapshot.queue
            if (snapshot.lyrics)
                Player.lyrics = snapshot.lyrics
        }).catch(function(error) {
            console.warn("[app] bootstrap failed:", error.message)
            Toast.show("无法连接播放后端：" + (error.message || "未知错误"), "error")
        })
    }

    Connections {
        target: Backend
        function onConnectedChanged() {
            if (Backend.connected) {
                root.bootstrap()
                Api.call("app.setSystemTheme", SystemTheme.payload())
            }
        }
        function onEvent(name, data) {
            if (name === "toast")
                Toast.show(data.message, data.kind)
            else if (name === "navigate")
                Nav.openByName(data.name, data.arg)
            else if (name === "tray")
                root.handleTrayCommand(data)
            else if (name === "shortcut")
                root.handleShortcut(data.action)
        }
    }

    // 后端进程内的状态变化后重新同步主题的触发点
    Connections {
        target: Settings
        function onValuesChanged() {
            if (Backend.connected)
                Api.call("app.refreshTheme").catch(function() {})
        }
    }


    /** 后端全局快捷键回调（导航/面板类动作在 Qt 侧执行）。 */
    function handleShortcut(actionId) {
        switch (actionId) {
        case "open_now_playing":
            Nav.push("nowplaying")
            break
        case "toggle_downloads":
            Nav.openDownloadsPanel()
            break
        case "toggle_floating_lyrics":
            Settings.set("floatingLyricsEnabled", !(Settings.get("floatingLyricsEnabled", false) === true))
            break
        case "toggle_floating_lyrics_lock":
            Settings.set("floatingLyricsLocked", !(Settings.get("floatingLyricsLocked", false) === true))
            break
        case "toggle_tray_panel":
            trayPanel.showPanel()
            break
        case "nav_back":
            Nav.back()
            break
        case "nav_home":
            Nav.openTab("home")
            break
        case "nav_explore":
            Nav.openTab("explore")
            break
        case "nav_library":
            Nav.openTab("library")
            break
        case "nav_settings":
            Nav.openTab("settings")
            break
        default:
            console.warn("[shortcut] unhandled:", actionId)
        }
    }

    /** 托盘菜单回调。 */
    function handleTrayCommand(data) {
        switch (data.command) {
        case "showWindow":
            root.showMainWindow()
            break
        case "settings":
            root.showMainWindow()
            Nav.openTab("settings")
            break
        case "downloads":
            root.showMainWindow()
            Nav.openDownloadsPanel()
            break
        case "openPanel":
            trayPanel.showPanel()
            break
        case "quit":
            root.quitApp()
            break
        case "toggleFloatingLyrics":
            Toast.show(data.enabled === true ? "悬浮歌词已开启" : "悬浮歌词已关闭")
            break
        case "toggleFloatingLyricsLock":
            break
        default:
            console.warn("[tray] unknown command:", data.command)
        }
    }

    AppShell {
        id: shell
        anchors.fill: parent
    }

    AppShortcuts {
        id: shortcuts
        anchors.fill: parent
    }

    ToastOverlay {
        anchors.fill: parent
    }

    // 悬浮歌词独立窗口（无边框置顶，样式跟随设置）
    FloatingLyricsWindow {
        id: floatingLyrics
        mainWindow: root
    }

    // 托盘控制面板（无边框小窗，失焦自动收起）
    TrayControlPanel {
        id: trayPanel
        mainWindow: root
    }

    // 焦点被关闭的弹窗/输入框释放后，把键盘焦点还给快捷键处理器
    onActiveFocusItemChanged: {
        if (activeFocusItem === null)
            shortcuts.activate()
    }

    Component.onCompleted: shortcuts.activate()
}
