import QtQuick
import ".."

// 歌手卡（共享组件）。对齐旧 Compose 的 ArtistTile：
// 卡片外宽 136、圆形头像 124、名称 bodyMedium、副标题 labelSmall（如 "5 首"）。
//
// 属性：name、subtitle、coverPath、coverUrl、placeholderIcon（默认 person）、cardWidth（默认 136）
// 信号：clicked()
Item {
    id: root

    property string name: ""
    property string subtitle: ""
    property string coverPath: ""
    property string coverUrl: ""
    property string placeholderIcon: "person"
    property real cardWidth: 136

    signal clicked()

    implicitWidth: cardWidth
    implicitHeight: cardContent.implicitHeight
    width: cardWidth
    height: implicitHeight

    Rectangle {
        anchors.fill: parent
        radius: Theme.radiusLg
        color: artistHover.hovered ? Theme.withAlpha(Theme.onSurfaceColor, 0.06) : "transparent"

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
            radius: width / 2
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
            horizontalAlignment: Text.AlignHCenter
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
            horizontalAlignment: Text.AlignHCenter
            elide: Text.ElideRight
            maximumLineCount: 1
            color: Theme.onSurfaceVariantColor
            font.family: Theme.fontFamily
            font.pixelSize: Theme.textStyle("labelSmall").size
        }
    }

    HoverHandler {
        id: artistHover
        cursorShape: Qt.PointingHandCursor
    }

    MouseArea {
        anchors.fill: parent
        onClicked: root.clicked()
    }
}
