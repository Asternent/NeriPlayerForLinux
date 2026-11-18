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

    /** 供快捷键 / 截图脚本 / 托盘调用的命名路由入口。 */
    function openPageByName(name) {
        Nav.openByName(name)
    }

    function openPage(name, arg) {
        Nav.openByName(name, arg)
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


    AppShell {
        anchors.fill: parent
    }

    ToastOverlay {
        anchors.fill: parent
    }
}
