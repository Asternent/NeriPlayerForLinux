import QtQuick
import QtQuick.Controls

// 通用图标按钮。
Item {
    id: button

    property string icon: "circle"
    property bool iconFilled: false
    property real iconSize: 24
    property real size: 40
    property color iconColor: Theme.onSurfaceVariantColor
    property color hoverColor: Theme.withAlpha(Theme.onSurfaceColor, 0.08)
    property string tooltip: ""

    signal clicked()

    implicitWidth: size
    implicitHeight: size

    Rectangle {
        anchors.fill: parent
        radius: width / 2
        color: button.containsMouse ? button.hoverColor : "transparent"

        Behavior on color {
            ColorAnimation {
                duration: Theme.motionShort
            }
        }
    }

    NeriIcon {
        anchors.centerIn: parent
        name: button.icon
        filled: button.iconFilled
        iconSize: button.iconSize
        color: button.iconColor
    }

    HoverHandler {
        id: hover
        cursorShape: Qt.PointingHandCursor
    }

    readonly property alias containsMouse: hover.hovered

    MouseArea {
        anchors.fill: parent
        onClicked: button.clicked()
    }

    ToolTip.visible: tooltip !== "" && containsMouse
    ToolTip.text: tooltip
}
