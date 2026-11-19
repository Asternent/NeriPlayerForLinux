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

    /** 显示面板。force=true 时即使已显示也保持显示（外部命令用，避免误切换成隐藏）。 */
    function showPanel(force) {
        if (visible) {
            if (!force)
                hidePanel()
            else
                raise()
            return
        }
        // 每次弹出都重新贴到屏幕右下角（Wayland 无法拿到托盘图标位置）
        if (screen) {
            x = Math.round(screen.virtualX + screen.width - width - 16)
            y = Math.round(screen.virtualY + screen.height - height - 16)
        }
        // 主窗口隐藏到托盘时，本窗口若是它的 transient child 会被连带隐藏；
        // 这里用 keepVisible 同时断开 transient 关系并确保窗口真正显示出来。
        visible = true
        // 刚弹出的几百毫秒内忽略失焦，等窗口管理器把激活状态送达
        panel._justShown = true
        panel._justShownTimer.restart()
        WindowDrag.keepVisible(panel)
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

    // 失焦自动收起。注意这里只看「本面板自身」是否还激活：
    // 主窗口隐藏到托盘时整个应用会失活（Qt.application.state 变 Inactive），
    // 若用应用级状态判断，会刚弹出就被自己收起来——这正是面板弹不出来的原因。
    // 另外刚弹出的一瞬间 active 还是 false，需要忽略这段时间的失焦事件。
    property bool _justShown: false
    property Timer _justShownTimer: Timer {
        interval: 350
        repeat: false
        onTriggered: panel._justShown = false
    }

    onActiveChanged: {
        if (!active && visible && !panel._justShown)
            hidePanel()
    }

    // 点击主窗口（同一应用内）时也收起面板
    property Connections mainWindowConnection: Connections {
        target: panel.mainWindow
        function onActiveChanged() {
            // 面板刚弹出的瞬间主窗口可能正好被激活，这里要和失焦逻辑一样加豁免，
            // 否则从托盘菜单打开面板会被立刻收起来。
            if (panel._justShown || panel._justShownTimer.running)
                return
            if (panel.mainWindow && panel.mainWindow.active && panel.visible && !panel.active)
                panel.hidePanel()
        }
    }

    Shortcut {
        sequences: [StandardKey.Cancel, "Esc", "Escape"]
        enabled: panel.visible
        onActivated: panel.hidePanel()
    }

    // 无边框 Qt.Tool 窗口在 Wayland 下 Shortcut 不一定能拿到按键，内层 Item 的 Keys 兜底。
    // 注意：该 Item 必须不拦截鼠标（否则下面的按钮全点不到），只负责接键盘焦点。
    Item {
        anchors.fill: parent
        focus: true
        Keys.onPressed: function(event) {
            if (event.key === Qt.Key_Escape) {
                panel.hidePanel()
                event.accepted = true
            }
        }
        Component.onCompleted: {
            forceActiveFocus()
            // 创建时就先脱离父窗口：主窗口若一开始就隐藏到托盘，也能独立弹出。
            WindowDrag.detachFromParent(panel)
        }
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
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.top: parent.top
            anchors.topMargin: 14
            anchors.leftMargin: 14
            anchors.rightMargin: 14
            // 底部让出「退出」按钮 + 分隔线 + 间隙的高度
            anchors.bottomMargin: 72
            spacing: 12

            // ---- 歌曲信息 ----
            Item {
                width: parent.width
                height: 46

                Row {
                    anchors.left: parent.left
                    anchors.right: closeButton.left
                    anchors.rightMargin: 4
                    anchors.verticalCenter: parent.verticalCenter
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

                        Row {
                            width: parent.width
                            spacing: 5

                            // 播放状态圆点：播放中主色，暂停灰（旧版位置在歌手名左侧）
                            Rectangle {
                                anchors.verticalCenter: parent.verticalCenter
                                width: 6
                                height: 6
                                radius: 3
                                color: Player.playing ? Theme.primary : Theme.outline
                            }

                            Text {
                                width: Math.max(0, parent.width - 11)
                                text: Player.hasSong ? Player.artist : "NeriPlayer"
                                elide: Text.ElideRight
                                color: Theme.onSurfaceVariantColor
                                font.family: Theme.fontFamily
                                font.pixelSize: 12
                            }
                        }
                    }
                }

                IconButton {
                    id: closeButton
                    anchors.right: parent.right
                    anchors.verticalCenter: parent.verticalCenter
                    icon: "close"
                    iconSize: 16
                    size: 28
                    tooltip: "关闭控制面板"
                    onClicked: panel.hidePanel()
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

            }

            // ---- 进度 ----
            // 轨道在上、时间文字在下，两者各自占独立的一行，避免互相压住。
            Item {
                width: parent.width
                height: track.height + 4 + timeRow.height

                Rectangle {
                    id: track
                    anchors.left: parent.left
                    anchors.right: parent.right
                    anchors.top: parent.top
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

                Item {
                    id: timeRow
                    anchors.left: parent.left
                    anchors.right: parent.right
                    anchors.top: track.bottom
                    anchors.topMargin: 4
                    height: 14

                    Text {
                        anchors.left: parent.left
                        anchors.verticalCenter: parent.verticalCenter
                        text: formatTime(Player.positionMs)
                        color: Theme.onSurfaceVariantColor
                        font.family: Theme.fontFamily
                        font.pixelSize: 10
                    }

                    Text {
                        anchors.right: parent.right
                        anchors.verticalCenter: parent.verticalCenter
                        text: formatTime(Player.durationMs)
                        color: Theme.onSurfaceVariantColor
                        font.family: Theme.fontFamily
                        font.pixelSize: 10
                    }
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

            // ---- 动作（与旧版一致：常规动作在上，退出单独贴底部并用分隔线隔开） ----
            Column {
                width: parent.width
                spacing: 2

                Repeater {
                    model: [
                        { "icon": "home", "label": "显示主窗口", "action": "showWindow" },
                        { "icon": "album", "label": "打开播放页", "action": "nowplaying" },
                        { "icon": "settings", "label": "设置", "action": "settings" },
                        { "icon": "download", "label": "下载管理", "action": "downloads" },
                        { "icon": "lyrics", "label": Settings.get("floatingLyricsEnabled", false) ? "关闭悬浮歌词" : "开启悬浮歌词", "action": "floating" }
                    ]
                    delegate: Rectangle {
                        required property var modelData
                        width: parent.width
                        height: 32
                        radius: 10
                        color: itemHover.hovered ? Theme.withAlpha(Theme.secondaryContainer, 0.6) : "transparent"

                        Row {
                            anchors.left: parent.left
                            anchors.leftMargin: 8
                            anchors.verticalCenter: parent.verticalCenter
                            spacing: 10

                            NeriIcon {
                                anchors.verticalCenter: parent.verticalCenter
                                name: modelData.icon
                                iconSize: 18
                                color: Theme.onSurfaceVariantColor
                            }

                            Text {
                                anchors.verticalCenter: parent.verticalCenter
                                text: modelData.label
                                color: Theme.onSurfaceColor
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
                                case "showWindow":
                                    if (panel.mainWindow) panel.mainWindow.showMainWindow()
                                    break
                                // 主窗口可能已收进托盘：先把它显示出来再导航，
                                // 否则页面切了但用户看不到（要等下一次点主页才露出来）。
                                case "nowplaying":
                                    if (panel.mainWindow) panel.mainWindow.showMainWindow()
                                    Nav.push("nowplaying")
                                    break
                                case "settings":
                                    if (panel.mainWindow) panel.mainWindow.showMainWindow()
                                    Nav.openTab("settings")
                                    break
                                case "downloads":
                                    if (panel.mainWindow) panel.mainWindow.showMainWindow()
                                    Nav.openDownloadsPanel()
                                    break
                                case "floating":
                                    Settings.set("floatingLyricsEnabled", !(Settings.get("floatingLyricsEnabled", false) === true))
                                    break
                                }
                            }
                        }
                    }
                }
            }

        }

        // ---- 底部退出（锚定卡片底边，与上方内容自然分离） ----
        Rectangle {
            id: quitRow
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.bottom: parent.bottom
            anchors.leftMargin: 14
            anchors.rightMargin: 14
            anchors.bottomMargin: 12
            height: 34
            radius: 10
            color: quitHover.hovered ? Theme.withAlpha(Theme.errorContainer, 0.6) : "transparent"

            Rectangle {
                anchors.left: parent.left
                anchors.right: parent.right
                anchors.bottom: parent.top
                anchors.bottomMargin: 6
                height: 1
                color: Theme.withAlpha(Theme.outlineVariant, 0.6)
            }

            Row {
                anchors.left: parent.left
                anchors.leftMargin: 8
                anchors.verticalCenter: parent.verticalCenter
                spacing: 10

                NeriIcon {
                    anchors.verticalCenter: parent.verticalCenter
                    name: "logout"
                    iconSize: 18
                    color: Theme.error
                }

                Text {
                    anchors.verticalCenter: parent.verticalCenter
                    text: "退出 NeriPlayer"
                    color: Theme.error
                    font.family: Theme.fontFamily
                    font.pixelSize: 13
                }
            }

            HoverHandler {
                id: quitHover
                cursorShape: Qt.PointingHandCursor
            }

            MouseArea {
                anchors.fill: parent
                onClicked: {
                    panel.hidePanel()
                    if (panel.mainWindow && panel.mainWindow.quitApp)
                        panel.mainWindow.quitApp()
                }
            }
        }
    }
}
