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
    // 首帧先不显示：等后端下发真实主题色板后再 show，否则用户会先看到一套
    // 内置的紫色基线配色、随后整窗跳到真实主题色。
    // bootstrap 失败或后端迟迟连不上时由 revealFallback 兜底显示，避免黑屏。
    visible: themeReady
    color: Theme.background

    /** 主题是否已就绪（拿到后端 theme 即可显示）。 */
    property bool themeReady: false

    /** 兜底：后端 3 秒内没连上也要把界面显示出来。 */
    property Timer revealFallback: Timer {
        interval: 3000
        repeat: false
        running: !root.themeReady
        onTriggered: root.revealWindow()
    }

    function revealWindow() {
        if (root.themeReady)
            return
        root.themeReady = true
    }
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
        // 之前收进托盘时释放过页面缓存，这里重建当前页
        if (root.shellItem)
            root.shellItem.rebuildCurrentPage()
    }

    /** 最小化到托盘（与关闭按钮的 closeToTray 行为一致）。 */
    function hideMainWindow() {
        // 先断开子窗口的 transient 关系，否则主窗口隐藏会连带隐藏托盘面板；
        // 悬浮歌词同理——它需要在主窗口收进托盘后继续显示。
        WindowDrag.detachChildWindows(root)
        root.hide()
        // 主窗口收进托盘后可能很久不再打开：把页面缓存（图片纹理/列表模型/delegate）
        // 整体释放，重新显示时按需重建，避免长期占着内存。
        if (root.shellItem)
            root.shellItem.releasePages()
    }

    /** 退出应用（托盘菜单、快捷键）。 */
    function quitApp() {
        root.forceQuit = true
        destroyAuxWindows()
        root.close()
        // 悬浮歌词/托盘面板是用 createObject(null) 独立创建的顶层窗口，
        // 它们不属于 ApplicationWindow 的子对象，root.close() 不会带走它们，
        // 导致「窗口都关了、进程还在」。这里显式销毁并退出事件循环。
        Qt.quit()
    }

    /** 销毁独立创建的辅助窗口（退出前调用）。 */
    function destroyAuxWindows() {
        if (root.floatingLyrics) {
            root.floatingLyrics.close()
            root.floatingLyrics.destroy()
            root.floatingLyrics = null
        }
        if (root.floatingPreview) {
            root.floatingPreview.close()
            root.floatingPreview.destroy()
            root.floatingPreview = null
        }
        if (root.trayPanel) {
            root.trayPanel.close()
            root.trayPanel.destroy()
            root.trayPanel = null
        }
    }

    onClosing: function(close) {
        if (root.forceQuit)
            return
        if (root.trayAvailable && Settings.get("closeToTray", true)) {
            close.accepted = false
            root.hideMainWindow()
            Toast.show("已最小化到托盘，音乐继续播放")
            return
        }
        // 真的要退出：先把独立创建的辅助窗口一并销毁，否则主窗口关了、
        // 进程会因为悬浮歌词/托盘面板还活着而不退出。
        root.destroyAuxWindows()
        Qt.quit()
    }

    /** 供快捷键 / 截图脚本 / 托盘调用的命名路由入口。 */
    function openPageByName(name) {
        Nav.openByName(name)
    }

    /** --page 带 --arg 时使用：arg 是 JSON 字符串，解析后传给路由。 */
    function openPageArgByName(name, argJson) {
        let arg = undefined
        try {
            arg = JSON.parse(argJson)
        } catch (e) {
            arg = argJson
        }
        Nav.openByName(name, arg)
    }

    function openPage(name, arg) {
        Nav.openByName(name, arg)
    }

    function openPanelByName(name) {
        if (name === "tray") {
            root.ensureAuxWindows()
            if (root.trayPanel)
                root.trayPanel.showPanel(true)
        } else {
            Nav.openPanel(name)
        }
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
            // 主题已生效，这时才把主窗口显示出来
            root.themeReady = true
        }).catch(function(error) {
            console.warn("[app] bootstrap failed:", error.message)
            // 后端异常时也要显示界面，否则用户看到的是「程序没起来」
            root.revealWindow()
            Toast.show("无法连接播放后端：" + (error.message || "未知错误"), "error")
        })
    }

    Connections {
        target: Backend
        function onConnectedChanged() {
            if (Backend.connected) {
                root.bootstrap()
                Api.call("app.setSystemTheme", SystemTheme.payload()).catch(function() {})
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

    // 系统主题变化（切换深浅色 / 改 KDE 强调色）时重新上报，让后端重算 M3 色板
    Connections {
        target: SystemTheme
        function onChanged() {
            if (Backend.connected)
                Api.call("app.setSystemTheme", SystemTheme.payload()).catch(function() {})
        }
    }


    /** 后端全局快捷键回调（导航/面板类动作在 Qt 侧执行）。 */
    function handleShortcut(actionId) {
        // 全局快捷键可能在主窗口已收进托盘时触发：导航类动作要先显示窗口，
        // 否则只是切了页面、用户看不到。
        const navActions = ["open_now_playing", "toggle_downloads", "nav_back",
                            "nav_home", "nav_explore", "nav_library", "nav_settings"]
        if (navActions.indexOf(actionId) >= 0)
            root.showMainWindow()
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
            root.ensureAuxWindows()
            if (root.trayPanel)
                root.trayPanel.showPanel(true)
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
            root.ensureAuxWindows()
            if (root.trayPanel)
                root.trayPanel.showPanel(true)
            break
        case "quit":
            root.quitApp()
            break
        case "toggleFloatingLyrics":
            Toast.show(data.enabled === true ? "悬浮歌词已开启" : "悬浮歌词已关闭")
            break
        case "toggleFloatingLyricsLock":
            Toast.show(data.locked === true ? "歌词位置已固定" : "歌词位置已解锁")
            break
        default:
            console.warn("[tray] unknown command:", data.command)
        }
    }

    AppShell {
        id: shell
        anchors.fill: parent
    }

    /** 供 C++/脚本调用的 AppShell 引用（页面缓存释放等）。 */
    readonly property Item shellItem: shell

    AppShortcuts {
        id: shortcuts
        anchors.fill: parent
    }

    ToastOverlay {
        anchors.fill: parent
    }

    // 悬浮歌词 / 托盘面板不在这里以子对象声明：作为 ApplicationWindow 的子 Window 会带上
    // transient parent，主窗口隐藏到托盘时被连带隐藏（面板就弹不出来了）。
    // 改为在 Component.onCompleted 里用 createObject(null) 创建，得到真正独立的顶层窗口。
    property var floatingLyrics: null
    property var trayPanel: null
    property var floatingPreview: null

    // 快捷方式：QML 里其它地方仍按 trayPanel.showPanel() 那样用
    function ensureAuxWindows() {
        if (!root.floatingLyrics) {
            const lyricsComponent = Qt.createComponent("FloatingLyricsWindow.qml")
            if (lyricsComponent.status === Component.Ready)
                root.floatingLyrics = lyricsComponent.createObject(null, { "mainWindow": root })
            else
                console.warn("[app] FloatingLyricsWindow.qml:", lyricsComponent.errorString())
        }
        if (!root.floatingPreview) {
            const previewComponent = Qt.createComponent("FloatingLyricsDragPreview.qml")
            if (previewComponent.status === Component.Ready)
                root.floatingPreview = previewComponent.createObject(null)
            else
                console.warn("[app] FloatingLyricsDragPreview.qml:", previewComponent.errorString())
        }
        // 把预览层注入悬浮歌词窗口
        if (root.floatingLyrics && root.floatingPreview)
            root.floatingLyrics.previewWindow = root.floatingPreview
        if (!root.trayPanel) {
            const panelComponent = Qt.createComponent("TrayControlPanel.qml")
            if (panelComponent.status === Component.Ready)
                root.trayPanel = panelComponent.createObject(null, { "mainWindow": root })
            else
                console.warn("[app] TrayControlPanel.qml:", panelComponent.errorString())
        }
    }

    // 焦点被关闭的弹窗/输入框释放后，把键盘焦点还给快捷键处理器
    onActiveFocusItemChanged: {
        if (activeFocusItem === null)
            shortcuts.activate()
    }

    Component.onCompleted: {
        shortcuts.activate()
        centerWindow()
        ensureAuxWindows()
        if (Qt.application.arguments.indexOf("--selftest") >= 0)
            runSelfTest()
    }

    /** 开发自检：调用各全局包装器，验证它们内部对 Api/Player 的引用可用。 */
    function runSelfTest() {
        console.log("[selftest] typeof Api=", typeof Api, "call=", typeof Api.call, "get=", typeof Api.get,
                    "Promise=", typeof Promise, "Toast=", typeof Toast)
        try { Api.get("app.ping", {}, function(result, error) {
                  console.log("[selftest] Api.get result=", JSON.stringify(result), "error=", JSON.stringify(error))
              }) } catch (e) { console.warn("[selftest] Api.get threw:", e) }
        try { const p2 = new Promise(function(res, rej) { res(1) }); console.log("[selftest] new Promise ok, then=", typeof p2.then) }
        catch (e) { console.warn("[selftest] new Promise failed:", e) }
        try { const p3 = Api.call("app.ping"); console.log("[selftest] Api.call direct ok, then=", typeof p3.then) }
        catch (e) { console.warn("[selftest] Api.call direct failed:", e) }
        try { const p4 = Api.call("app.ping", {}); console.log("[selftest] Api.call {}-params ok") }
        catch (e) { console.warn("[selftest] Api.call {}-params failed:", e) }
        try { const p5 = Api.call("app.ping", { "k": 1 }); console.log("[selftest] Api.call obj-params ok") }
        catch (e) { console.warn("[selftest] Api.call obj-params failed:", e) }
        try { Player._send("app.ping"); console.log("[selftest] Player._send no-params ok") }
        catch (e) { console.warn("[selftest] Player._send no-params failed:", e) }
        try { Player.setVolume(0.7); console.log("[selftest] Player.setVolume ok") }
        catch (e) { console.warn("[selftest] Player.setVolume failed:", e) }
        try { Settings.set("volume", 0.7); console.log("[selftest] Settings.set ok") }
        catch (e) { console.warn("[selftest] Settings.set failed:", e) }
        try { Player.enqueue([]); console.log("[selftest] Player.enqueue ok") }
        catch (e) { console.warn("[selftest] Player.enqueue failed:", e) }
        try { Api.call("app.ping").then(function(r) { console.log("[selftest] Api.ok", r.pong) }) }
        catch (e) { console.warn("[selftest] Api failed:", e) }
    }

    /** 首次启动把窗口居中到当前屏幕（避免 Wayland 下默认出现在左上角）。 */
    function centerWindow() {
        if (!screen)
            return
        x = Math.round(screen.virtualX + (screen.width - width) / 2)
        y = Math.round(screen.virtualY + (screen.height - height) / 2)
    }
}
