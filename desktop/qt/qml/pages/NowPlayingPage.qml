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

    /** 封面：窄屏 220 / 宽屏 300；可用高度不足时按比例收缩。 */
    readonly property real coverSize: {
        const padding = page.wide ? 48 : 40
        const available = contentArea.height - page.coreFixedHeight - padding
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
    }

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

            Rectangle {
                id: lyricsCard

                anchors.right: parent.right
                anchors.rightMargin: 16
                anchors.top: parent.top
                anchors.topMargin: 8
                anchors.bottom: parent.bottom
                anchors.bottomMargin: 16
                width: 420
                radius: 24
                color: Theme.surfaceContainerLow

                LyricsPane {
                    anchors.fill: parent
                    horizontalPadding: 24
                    verticalPadding: 16
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
