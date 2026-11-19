import QtQuick
import QtQuick.Controls
import "../components"
import "../components/player"

// 播放页：窄屏单列 / 宽屏左信息 + 右歌词侧栏，另含全屏歌词覆盖层。
// 数据与命令全部来自全局 Player，浮层面板由 components/PlayerOverlays.qml 承载。
Item {
    id: page

    readonly property bool wide: width >= Theme.wideBreakpoint
    readonly property bool hasSong: Player.hasSong

    property bool showFullLyrics: false

    // 封面之外的固定高度（标题/进度/传输/工具行），用于矮窗口时收缩封面，避免内容溢出。
    readonly property real coreFixedHeight: 18 + 52 + 10 + 60 + 14 + 68 + 16 + 48

    /** 封面：窄屏 220 / 宽屏 300；窗口较矮时收缩，保证整页不溢出。 */
    readonly property real coverSize: {
        const available = contentArea.height - page.coreFixedHeight - 12
        return Math.max(150, Math.min(page.wide ? 300 : 220, available))
    }

    function sourceName(source) {
        switch (source) {
        case "LOCAL":
            return "本地"
        case "NETEASE":
            return "网易云"
        case "BILIBILI":
            return "哔哩哔哩"
        case "YOUTUBE":
            return "YouTube"
        default:
            return ""
        }
    }

    // ---- 顶部栏 ----
    Item {
        id: topBar

        anchors.top: parent.top
        anchors.left: parent.left
        anchors.right: parent.right
        height: 64

        // 沉浸模式下与侧栏一起淡出，鼠标移动时由 AppShell 短暂唤回
        opacity: Nav.immersive ? 0 : 1
        visible: opacity > 0.01
        z: 2
        Behavior on opacity {
            NumberAnimation { duration: 220; easing.type: Easing.OutCubic }
        }

        IconButton {
            id: backButton

            anchors.left: parent.left
            anchors.leftMargin: 8
            anchors.verticalCenter: parent.verticalCenter
            icon: "arrow_back"
            iconSize: 24
            size: 48
            onClicked: Nav.back()
        }

        Column {
            anchors.left: backButton.right
            anchors.leftMargin: 8
            anchors.right: parent.right
            anchors.rightMargin: 16
            anchors.verticalCenter: parent.verticalCenter
            spacing: 2

            Text {
                text: "正在播放"
                color: Theme.onSurfaceColor
                font.family: Theme.fontFamily
                font.pixelSize: Theme.textStyle("titleMedium").size
                font.weight: Font.DemiBold
                font.letterSpacing: Theme.textStyle("titleMedium").letterSpacing
            }

            Text {
                visible: Player.hasSong
                text: page.sourceName(Player.song && Player.song.source ? Player.song.source : "")
                color: Theme.onSurfaceVariantColor
                font.family: Theme.fontFamily
                font.pixelSize: Theme.textStyle("labelSmall").size
            }
        }

    } // topBar 结束

    /**
     * 沉浸模式：进入真正的全屏、隐藏侧边栏，顶栏（返回按钮与标题）保持淡出。
     * 按钮放在页面根层（不在 topBar 内），这样沉浸时仍可点击退出；
     * Esc 同样可以退出。
     */
    function toggleImmersive() {
        Nav.setImmersive(!Nav.immersive)
        const w = page.Window.window
        if (!w)
            return
        if (Nav.immersive)
            w.showFullScreen()
        else
            w.showNormal()
    }

    IconButton {
        anchors.right: parent.right
        anchors.rightMargin: 12
        anchors.top: parent.top
        anchors.topMargin: 10
        z: 5
        icon: Nav.immersive ? "fullscreen_exit" : "fullscreen"
        iconSize: 22
        size: 44
        // 沉浸时稍微透明，仍然可见可点
        opacity: Nav.immersive ? 0.5 : 1
        tooltip: Nav.immersive ? "退出全屏沉浸 (Esc)" : "全屏沉浸模式"
        onClicked: page.toggleImmersive()

        Behavior on opacity {
            NumberAnimation { duration: 180 }
        }
    }

    // Esc 退出沉浸模式
    Keys.onEscapePressed: function(event) {
        if (Nav.immersive) {
            page.toggleImmersive()
            event.accepted = true
        }
    }
    Component.onCompleted: forceActiveFocus()

    // ---- 内容区 ----
    Item {
        id: contentArea

        anchors.top: topBar.bottom
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.bottom: parent.bottom
        clip: true

        // 无歌曲：空状态
        PlayerEmptyState {
            anchors.centerIn: parent
            visible: !page.hasSong
            icon: "music_note"
            title: "暂无播放"
            hint: "在媒体库或探索中选择一首歌开始播放"
            contentWidth: Math.min(400, contentArea.width - 48)
        }

        // 窄屏：单列居中
        Item {
            anchors.fill: parent
            visible: page.hasSong && !page.wide

            PlayerCore {
                anchors.horizontalCenter: parent.horizontalCenter
                anchors.verticalCenter: parent.verticalCenter
                width: Math.min(parent.width - 40, 560)
                coverSize: page.coverSize
                onLyricsRequested: page.showFullLyrics = true
            }
        }

        // 宽屏：左信息 + 右歌词侧栏（420dp）
        Item {
            id: wideLayout

            anchors.fill: parent
            visible: page.hasSong && page.wide

            Item {
                id: leftRegion

                anchors.left: parent.left
                anchors.top: parent.top
                anchors.bottom: parent.bottom
                anchors.right: lyricsCard.left

                PlayerCore {
                    anchors.horizontalCenter: parent.horizontalCenter
                    anchors.verticalCenter: parent.verticalCenter
                    width: Math.min(parent.width - 48, 560)
                    coverSize: page.coverSize
                    onLyricsRequested: page.showFullLyrics = true
                }
            }

            // 歌词直接显示在右侧，不套卡片背景，保留一条淡分隔线与左侧区域区分
            Item {
                id: lyricsCard

                anchors.right: parent.right
                anchors.rightMargin: 24
                anchors.top: parent.top
                anchors.topMargin: 8
                anchors.bottom: parent.bottom
                anchors.bottomMargin: 16
                // 全屏沉浸时歌词区更宽，字号也更大（见 LyricsPane.immersive）
                width: Nav.immersive ? Math.min(720, parent.width * 0.5) : 420

                Rectangle {
                    anchors.left: parent.left
                    anchors.top: parent.top
                    anchors.bottom: parent.bottom
                    width: 1
                    color: Theme.withAlpha(Theme.outlineVariant, 0.5)
                }

                LyricsPane {
                    anchors.fill: parent
                    anchors.leftMargin: 24
                    horizontalPadding: 8
                    verticalPadding: 16
                    immersive: Nav.immersive
                }
            }
        }
    }

    // ---- 全屏歌词覆盖层 ----
    FullScreenLyrics {
        id: fullLyrics

        anchors.fill: parent
        visible: page.showFullLyrics
        onCloseRequested: page.showFullLyrics = false
    }
}
