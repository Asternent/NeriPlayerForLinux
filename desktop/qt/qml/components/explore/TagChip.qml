import QtQuick

// 胶囊标签按钮：搜索历史 / 热门搜索 / 风格标签 / 音源切换共用。
Item {
    id: chip

    property string text: ""
    property bool selected: false
    property bool showBorder: true

    signal clicked()

    implicitHeight: 32
    implicitWidth: label.implicitWidth + 28

    Rectangle {
        id: background
        anchors.fill: parent
        radius: height / 2
        color: {
            if (chip.selected)
                return Theme.secondaryContainer
            return hover.hovered ? Theme.surfaceContainerHighest : Theme.surfaceContainerHigh
        }
        border.width: (chip.selected || !chip.showBorder) ? 0 : 1
        border.color: Theme.outlineVariant

        Behavior on color {
            ColorAnimation { duration: Theme.motionShort }
        }

        Text {
            id: label
            anchors.centerIn: parent
            text: chip.text
            color: chip.selected ? Theme.onSecondaryContainerColor : Theme.onSurfaceColor
            font.family: Theme.fontFamily
            font.pixelSize: 13
            font.weight: chip.selected ? Font.Medium : Font.Normal
        }
    }

    HoverHandler {
        id: hover
        cursorShape: Qt.PointingHandCursor
    }

    MouseArea {
        anchors.fill: parent
        onClicked: chip.clicked()
    }
}
