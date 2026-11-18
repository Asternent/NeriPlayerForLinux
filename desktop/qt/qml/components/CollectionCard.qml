import QtQuick

// 歌单 / 专辑 / 收藏夹卡片（封面 + 标题 + 副标题）。
// 数据可以传 OnlineCollection 或 Playlist 对象；缺失字段用 fallback 属性。
Item {
    id: card

    property var entry: null

    property string title: entry && entry.name !== undefined ? entry.name : ""
    property string subtitle: {
        if (!entry)
            return ""
        if (entry.trackCount !== undefined && entry.trackCount > 0)
            return entry.trackCount + " 首"
        if (entry.songs !== undefined && entry.songs !== null)
            return entry.songs.length + " 首"
        if (entry.creator !== undefined && entry.creator !== "")
            return entry.creator
        return ""
    }
    property string coverUrl: entry && entry.coverUrl !== undefined && entry.coverUrl !== null ? entry.coverUrl : ""
    property string localCoverPath: entry && entry.artworkPath !== undefined && entry.artworkPath !== null ? entry.artworkPath : ""
    property real cardWidth: 150

    signal clicked()

    implicitWidth: cardWidth
    implicitHeight: cover.height + 10 + titleText.implicitHeight + 18

    Rectangle {
        id: cover
        width: card.cardWidth
        height: card.cardWidth
        radius: 16
        color: Theme.surfaceContainerHighest

        CoverArt {
            anchors.fill: parent
            anchors.margins: 6
            radius: 14
            path: card.localCoverPath
            url: card.coverUrl
            placeholderIconSize: 46
        }

        // hover 时叠一层播放提示
        Rectangle {
            anchors.fill: parent
            anchors.margins: 6
            radius: 14
            color: Theme.withAlpha(Theme.scrim, hover.hovered ? 0.22 : 0)
            Behavior on color {
                ColorAnimation { duration: Theme.motionShort }
            }
        }
    }

    Text {
        id: titleText
        anchors.top: cover.bottom
        anchors.topMargin: 8
        width: card.cardWidth
        text: card.title
        elide: Text.ElideRight
        color: Theme.onSurfaceColor
        font.family: Theme.fontFamily
        font.pixelSize: 13
        font.weight: Font.Medium
    }

    Text {
        anchors.top: titleText.bottom
        anchors.topMargin: 2
        width: card.cardWidth
        text: card.subtitle
        elide: Text.ElideRight
        color: Theme.onSurfaceVariantColor
        font.family: Theme.fontFamily
        font.pixelSize: 11
        visible: text !== ""
    }

    HoverHandler {
        id: hover
        cursorShape: Qt.PointingHandCursor
    }

    MouseArea {
        anchors.fill: parent
        onClicked: card.clicked()
    }
}
