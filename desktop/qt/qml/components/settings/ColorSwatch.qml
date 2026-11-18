import QtQuick
import "../"

// 圆形色块（预设主题色 / 悬浮歌词配色）。
Item {
    id: root

    property color swatchColor: "#000000"
    property bool selected: false
    property real swatchSize: 34

    signal clicked()

    width: swatchSize
    height: swatchSize
    opacity: enabled ? 1 : 0.38

    Rectangle {
        anchors.fill: parent
        radius: width / 2
        color: root.swatchColor
        border.width: root.selected ? 3 : 1
        border.color: root.selected ? Theme.onSurfaceColor : Theme.outlineVariant
    }

    Rectangle {
        visible: swatchHover.hovered && !root.selected
        anchors.fill: parent
        anchors.margins: -3
        radius: width / 2
        color: "transparent"
        border.width: 2
        border.color: Theme.withAlpha(Theme.primary, 0.55)
    }

    HoverHandler {
        id: swatchHover
        cursorShape: root.enabled ? Qt.PointingHandCursor : Qt.ArrowCursor
    }

    MouseArea {
        anchors.fill: parent
        onClicked: root.clicked()
    }
}
