import QtQuick
import QtQuick.Controls
import "components"
import "components/player"

// 托盘控制面板：无边框小窗，从托盘菜单 / Ctrl+M / 托盘图标激活时弹出，失焦自动收起。
Window {
    id: panel

    property Window mainWindow: null

    readonly property real panelWidth: 340
    readonly property real panelHeight: 436

    width: panelWidth
    height: panelHeight
    flags: Qt.Window | Qt.FramelessWindowHint | Qt.WindowStaysOnTopHint | Qt.Tool
    color: "transparent"
    title: "NeriPlayer 控制面板"
    visible: false

    x: screen ? Math.round(screen.virtualX + screen.width - width - 16) : 0
    y: screen ? Math.round(screen.virtualY + screen.height - height - 16) : 0

    function showPanel() {
        if (visible) {
            hidePanel()
            return
        }
        // 每次弹出都重新贴到屏幕右下角（Wayland 无法拿到托盘图标位置）
        if (screen) {
            x = Math.round(screen.virtualX + screen.width - width - 16)
            y = Math.round(screen.virtualY + screen.height - height - 16)
        }
        visible = true
        raise()
        requestActivate()
    }

    function hidePanel() {
        visible = false
    }

    function formatTime(ms) {
        const total = Math.max(0, Math.round((ms || 0) / 1000))
        const hours = Math.floor(total / 3600)
        const minutes = Math.floor((total % 3600) / 60)
        const seconds = total % 60
        const mm = minutes < 10 ? "0" + minutes : String(minutes)
        const ss = seconds < 10 ? "0" + seconds : String(seconds)
        return hours > 0 ? (hours + ":" + mm + ":" + ss) : (mm + ":" + ss)
    }

    onActiveChanged: if (!active && visible) hidePanel()

    // 应用整体失活（切到其它应用）时收起面板
    readonly property bool appActive: Qt.application.state === Qt.ApplicationActive
    onAppActiveChanged: if (!appActive && visible) hidePanel()

    // 点击主窗口（同一应用内）时也收起面板
    property Connections mainWindowConnection: Connections {
        target: panel.mainWindow
        function onActiveChanged() {
            if (panel.mainWindow && panel.mainWindow.active && panel.visible && !panel.active)
                panel.hidePanel()
        }
    }

    Shortcut {
        sequences: [StandardKey.Cancel, "Esc"]
        enabled: panel.visible
        onActivated: panel.hidePanel()
    }

    // Wayland 下 QWindow.active / 失焦事件不总是可靠：面板打开后若长时间无操作则自动收起
    property bool hovered: false
    property Timer autoHideTimer: Timer {
        interval: 15000
        repeat: false
        running: panel.visible && !panel.hovered
        onTriggered: panel.hidePanel()
    }
    onVisibleChanged: if (visible) autoHideTimer.restart()

    // 卡片主体
    Rectangle {
        anchors.fill: parent
        radius: 20
        color: Theme.surfaceContainerLow
        border.width: 1
        border.color: Theme.withAlpha(Theme.outlineVariant, 0.7)

        HoverHandler {
            onHoveredChanged: panel.hovered = hovered
        }

        Column {
            anchors.fill: parent
            anchors.margins: 14
            spacing: 12

            // ---- 歌曲信息 ----
            Row {
                width: parent.width
                spacing: 10

                CoverArt {
                    width: 46
                    height: 46
                    radius: 12
                    path: Player.hasSong && Player.song && Player.song.artworkPath ? Player.song.artworkPath : ""
                    url: Player.hasSong && Player.song && Player.song.artworkUrl ? Player.song.artworkUrl : ""
                }

                Column {
                    width: parent.width - 56
                    anchors.verticalCenter: parent.verticalCenter
                    spacing: 2

                    Text {
                        width: parent.width
                        text: Player.hasSong ? Player.title : "未在播放"
                        elide: Text.ElideRight
                        color: Theme.onSurfaceColor
                        font.family: Theme.fontFamily
                        font.pixelSize: 15
                        font.weight: Font.DemiBold
                    }

                    Text {
                        width: parent.width
                        text: Player.hasSong ? Player.artist : "NeriPlayer"
                        elide: Text.ElideRight
                        color: Theme.onSurfaceVariantColor
                        font.family: Theme.fontFamily
                        font.pixelSize: 12
                    }
                }
            }

            // ---- 传输控制 ----
            Item {
                width: parent.width
                height: 52

                IconButton {
                    anchors.left: parent.left
                    anchors.leftMargin: 12
                    anchors.verticalCenter: parent.verticalCenter
                    icon: "skip_previous"
                    iconSize: 26
                    size: 40
                    onClicked: Player.previous()
                }

                Rectangle {
                    anchors.centerIn: parent
                    width: 52
                    height: 52
                    radius: 26
                    color: Theme.primary

                    NeriIcon {
                        anchors.centerIn: parent
                        name: Player.playing ? "pause" : "play_arrow"
                        filled: true
                        iconSize: 28
                        color: Theme.onPrimaryColor
                    }

                    HoverHandler { cursorShape: Qt.PointingHandCursor }
                    MouseArea {
                        anchors.fill: parent
                        onClicked: Player.togglePlayPause()
                    }
                }

                IconButton {
                    anchors.right: parent.right
                    anchors.rightMargin: 12
                    anchors.verticalCenter: parent.verticalCenter
                    icon: "skip_next"
                    iconSize: 26
                    size: 40
                    onClicked: Player.next()
                }

                // 状态圆点
                Rectangle {
                    anchors.left: parent.left
                    anchors.bottom: parent.bottom
                    width: 7
                    height: 7
                    radius: 4
                    color: Player.playing ? Theme.primary : Theme.outline
                }
            }

            // ---- 进度 ----
            Item {
                width: parent.width
                height: 26

                Rectangle {
                    id: track
                    anchors.left: parent.left
                    anchors.right: parent.right
                    anchors.bottom: parent.bottom
                    anchors.bottomMargin: 8
                    height: 4
                    radius: 2
                    color: Theme.surfaceVariant

                    Rectangle {
                        width: parent.width * Player.progress
                        height: parent.height
                        radius: 2
                        color: Theme.primary
                    }

                    MouseArea {
                        anchors.fill: parent
                        anchors.topMargin: -6
                        anchors.bottomMargin: -6
                        onClicked: function(mouse) {
                            if (Player.durationMs > 0)
                                Player.seekTo(mouse.x / width * Player.durationMs)
                        }
                    }
                }

                Text {
                    anchors.left: parent.left
                    anchors.bottom: parent.bottom
                    text: formatTime(Player.positionMs)
                    color: Theme.onSurfaceVariantColor
                    font.family: Theme.fontFamily
                    font.pixelSize: 10
                }

                Text {
                    anchors.right: parent.right
                    anchors.bottom: parent.bottom
                    text: formatTime(Player.durationMs)
                    color: Theme.onSurfaceVariantColor
                    font.family: Theme.fontFamily
                    font.pixelSize: 10
                }
            }

            // ---- 音量 ----
            Row {
                width: parent.width
                spacing: 6

                NeriIcon {
                    anchors.verticalCenter: parent.verticalCenter
                    name: Player.volume <= 0.001 ? "volume_off" : (Player.volume < 0.5 ? "volume_down" : "volume_up")
                    iconSize: 20
                    color: Theme.onSurfaceVariantColor
                }

                PlayerSlider {
                    width: parent.width - 26
                    from: 0
                    to: 1
                    value: Player.volume
                    onMoved: function(value) { Player.setVolume(value) }
                }
            }

            // ---- 动作 ----
            Column {
                width: parent.width
                spacing: 2

                Repeater {
                    model: [
                        { "icon": "album", "label": "打开播放页", "action": "nowplaying" },
                        { "icon": "settings", "label": "设置", "action": "settings" },
                        { "icon": "download", "label": "下载管理", "action": "downloads" },
                        { "icon": "lyrics", "label": Settings.get("floatingLyricsEnabled", false) ? "关闭悬浮歌词" : "开启悬浮歌词", "action": "floating" },
                        { "icon": "logout", "label": "退出", "action": "quit", "danger": true }
                    ]
                    delegate: Rectangle {
                        required property var modelData
                        width: parent.width
                        height: 32
                        radius: 10
                        color: itemHover.hovered ? Theme.withAlpha(modelData.danger ? Theme.errorContainer : Theme.secondaryContainer, 0.6) : "transparent"

                        Row {
                            anchors.left: parent.left
                            anchors.leftMargin: 8
                            anchors.verticalCenter: parent.verticalCenter
                            spacing: 10

                            NeriIcon {
                                anchors.verticalCenter: parent.verticalCenter
                                name: modelData.icon
                                iconSize: 18
                                color: modelData.danger ? Theme.error : Theme.onSurfaceVariantColor
                            }

                            Text {
                                anchors.verticalCenter: parent.verticalCenter
                                text: modelData.label
                                color: modelData.danger ? Theme.error : Theme.onSurfaceColor
                                font.family: Theme.fontFamily
                                font.pixelSize: 13
                            }
                        }

                        HoverHandler {
                            id: itemHover
                            cursorShape: Qt.PointingHandCursor
                        }

                        MouseArea {
                            anchors.fill: parent
                            onClicked: {
                                panel.hidePanel()
                                switch (modelData.action) {
                                case "nowplaying": Nav.push("nowplaying"); break
                                case "settings": Nav.openTab("settings"); break
                                case "downloads": Nav.openDownloadsPanel(); break
                                case "floating":
                                    Settings.set("floatingLyricsEnabled", !(Settings.get("floatingLyricsEnabled", false) === true))
                                    break
                                case "quit": if (panel.mainWindow && panel.mainWindow.quitApp) panel.mainWindow.quitApp(); break
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
