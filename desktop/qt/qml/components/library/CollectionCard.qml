import QtQuick
import ".."

// 歌单 / 专辑 / 封面色块卡（共享组件）。对齐旧 Compose 的 LocalPlaylistCard / AlbumTile：
// 卡片外宽 150、封面 138 圆角 14、标题 bodyMedium、副标题 labelSmall、卡片圆角 16。
//
// 属性：
//   entry           数据对象（OnlineCollection 或 Playlist），有值时自动映射下面各字段
//   name            标题
//   subtitle        副标题（如 "5 首"）
//   coverPath       本地封面路径（Song.artworkPath），优先使用
//   coverUrl        远程封面色 URL
//   placeholderIcon 无封面时的占位图标（默认 music_note）
//   cardWidth       卡片外宽（默认 150）
// 显式传入的 name/subtitle/coverPath/coverUrl 优先于 entry 的映射值。
// 信号：clicked()
Item {
    id: root

    property var entry: null

    property string name: entry && entry.name !== undefined && entry.name !== null ? entry.name : ""
    property string subtitle: {
        if (!entry)
            return ""
        if (entry.trackCount !== undefined && entry.trackCount !== null && entry.trackCount > 0)
            return entry.trackCount + " 首"
        if (entry.songs !== undefined && entry.songs !== null)
            return entry.songs.length + " 首"
        if (entry.creator !== undefined && entry.creator !== null && entry.creator !== "")
            return entry.creator
        return ""
    }
    property string coverPath: entry && entry.artworkPath !== undefined && entry.artworkPath !== null
                               ? entry.artworkPath : ""
    property string coverUrl: entry && entry.coverUrl !== undefined && entry.coverUrl !== null
                              ? entry.coverUrl : ""
    property string placeholderIcon: "music_note"
    property real cardWidth: 150

    signal clicked()

    implicitWidth: cardWidth
    implicitHeight: cardContent.implicitHeight
    width: cardWidth
    height: implicitHeight

    Rectangle {
        anchors.fill: parent
        radius: Theme.radiusLg
        color: cardHover.hovered ? Theme.withAlpha(Theme.onSurfaceColor, 0.06) : "transparent"

        Behavior on color {
            ColorAnimation {
                duration: Theme.motionShort
            }
        }
    }

    Column {
        id: cardContent

        x: 6
        y: 6
        width: parent.width - 12
        spacing: 0

        CoverArt {
            width: parent.width
            height: parent.width
            radius: 14
            placeholderIconSize: 44
            path: root.coverPath
            url: root.coverUrl
        }

        Item {
            width: 1
            height: 8
        }

        Text {
            width: parent.width
            text: root.name
            elide: Text.ElideRight
            maximumLineCount: 1
            color: Theme.onSurfaceColor
            font.family: Theme.fontFamily
            font.pixelSize: Theme.textStyle("bodyMedium").size
            font.weight: Theme.textStyle("bodyMedium").weight
        }

        Text {
            width: parent.width
            text: root.subtitle
            elide: Text.ElideRight
            maximumLineCount: 1
            color: Theme.onSurfaceVariantColor
            font.family: Theme.fontFamily
            font.pixelSize: Theme.textStyle("labelSmall").size
        }
    }

    HoverHandler {
        id: cardHover
        cursorShape: Qt.PointingHandCursor
    }

    MouseArea {
        anchors.fill: parent
        onClicked: root.clicked()
    }
}
