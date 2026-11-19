import QtQuick
import "../"

// M3 文字按钮 / 填充按钮（设置页用）。hover 有底色反馈。
Item {
    id: root

    property string text: ""
    property string icon: ""
    property bool filled: false

    signal clicked()

    implicitWidth: contentRow.implicitWidth + 24
    implicitHeight: 36
    opacity: enabled ? 1 : 0.38

    Rectangle {
        id: background
        anchors.fill: parent
        radius: height / 2
        color: {
            if (root.filled)
                return mouseArea.pressed ? Theme.withAlpha(Theme.primary, 1.0) : Theme.primary
            if (mouseArea.pressed)
                return Theme.withAlpha(Theme.primary, 0.16)
            return hover.hovered ? Theme.withAlpha(Theme.primary, 0.08) : "transparent"
        }

        Behavior on color {
            ColorAnimation {
                duration: Theme.motionShort
            }
        }
    }

    Row {
        id: contentRow
        anchors.centerIn: parent
        spacing: 6

        NeriIcon {
            visible: root.icon !== ""
            name: root.icon
            iconSize: 16
            color: root.filled ? Theme.onPrimaryColor : Theme.primary
            anchors.verticalCenter: parent.verticalCenter
        }

        Text {
            text: root.text
            color: root.filled ? Theme.onPrimaryColor : Theme.primary
            font.family: Theme.fontFamily
            font.pixelSize: 14
            font.weight: Font.Medium
            anchors.verticalCenter: parent.verticalCenter
        }
    }

    HoverHandler {
        id: hover
        cursorShape: Qt.PointingHandCursor
    }

    MouseArea {
        id: mouseArea
        anchors.fill: parent
        onClicked: root.clicked()
    }
}
