import QtQuick

// 应用内快捷键。用 Keys 处理器而不是 Shortcut，这样文本框获得焦点时输入不会被吞掉。
// 键位与 Compose 版 core/Shortcuts.kt 的默认应用内绑定保持一致。
Item {
    id: handler

    anchors.fill: parent
    focus: true
    opacity: 0

    // 文本框聚焦时把按键让给输入框（Keys 只在没有子项聚焦时才到这里，这里兜底防呆）
    readonly property bool textInputFocused: {
        const item = handler.Window.activeFocusItem
        return item !== null && item !== undefined && item.hasOwnProperty("text") === false
               && (String(item).indexOf("TextInput") >= 0 || String(item).indexOf("TextField") >= 0
                   || String(item).indexOf("TextArea") >= 0 || String(item).indexOf("SpinBox") >= 0)
    }

    function activate() {
        handler.forceActiveFocus()
    }

    Keys.onPressed: function(event) {
        const ctrl = (event.modifiers & Qt.ControlModifier) !== 0
        const shift = (event.modifiers & Qt.ShiftModifier) !== 0
        const alt = (event.modifiers & Qt.AltModifier) !== 0

        if (event.key === Qt.Key_Escape) {
            if (Nav.activePanel !== "") {
                Nav.closePanel()
            } else if (Nav.canGoBack) {
                Nav.back()
            } else {
                return
            }
            event.accepted = true
            return
        }

        // 播放页/浮层打开时，空格等基础键仍然有效
        if (event.key === Qt.Key_Space && !ctrl && !alt && !shift) {
            Player.togglePlayPause()
            event.accepted = true
            return
        }

        if (ctrl && !alt) {
            switch (event.key) {
            case Qt.Key_Right:
                Player.seekBy(5000)
                break
            case Qt.Key_Left:
                Player.seekBy(-5000)
                break
            case Qt.Key_Up:
                Player.setVolume(Math.min(1, Player.volume + 0.05))
                break
            case Qt.Key_Down:
                Player.setVolume(Math.max(0, Player.volume - 0.05))
                break
            case Qt.Key_H:
                Nav.openTab("home")
                break
            case Qt.Key_N:
                Nav.openTab("explore")
                break
            case Qt.Key_J:
                Nav.openTab("library")
                break
            case Qt.Key_S:
                Nav.openTab("settings")
                break
            case Qt.Key_P:
                Nav.push("nowplaying")
                break
            case Qt.Key_D:
                Nav.openDownloadsPanel()
                break
            case Qt.Key_M:
                handler.Window.openPanelByName("tray")
                break
            case Qt.Key_L:
                Settings.set("floatingLyricsEnabled", !(Settings.get("floatingLyricsEnabled", false) === true))
                break
            default:
                return
            }
            event.accepted = true
            return
        }

        if (ctrl && alt && event.key === Qt.Key_L) {
            Settings.set("floatingLyricsLocked", !(Settings.get("floatingLyricsLocked", false) === true))
            event.accepted = true
            return
        }

        if (event.key === Qt.Key_MediaPlay || event.key === Qt.Key_MediaTogglePlayPause) {
            Player.togglePlayPause()
            event.accepted = true
        } else if (event.key === Qt.Key_MediaNext) {
            Player.next()
            event.accepted = true
        } else if (event.key === Qt.Key_MediaPrevious) {
            Player.previous()
            event.accepted = true
        }
    }
}
