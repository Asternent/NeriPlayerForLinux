import QtQuick
import ".."

// 媒体库页面通用的胶囊按钮（共享组件）。
// 两种样式：filled=true 时 primary 底 + onPrimary 文字；否则透明底 + primary 文字（M3 TextButton 风格）。
//
// 属性：text、icon（Material Symbols 名，可空）、iconSize、filled、enabled
// 信号：clicked()
Item {
    id: root

    property string text: ""
    property string icon: ""
    property real iconSize: 18
    property bool filled: false

    signal clicked()

    implicitWidth: contentRow.implicitWidth + Theme.spacingXl + 8
    implicitHeight: 36
    opacity: enabled ? 1 : 0.4

    Rectangle {
        anchors.fill: parent
        radius: height / 2
        color: {
            if (root.filled) {
                if (buttonMouse.pressed)
                    return Qt.darker(Theme.primary, 1.15)
                return Theme.primary
            }
            if (buttonMouse.pressed)
                return Theme.withAlpha(Theme.primary, 0.16)
            return buttonMouse.containsMouse ? Theme.withAlpha(Theme.primary, 0.08) : "transparent"
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
        spacing: Theme.spacingXs

        NeriIcon {
            visible: root.icon !== ""
            anchors.verticalCenter: parent.verticalCenter
            name: root.icon
            iconSize: root.iconSize
            color: root.filled ? Theme.onPrimaryColor : Theme.primary
        }

        Text {
            anchors.verticalCenter: parent.verticalCenter
            text: root.text
            color: root.filled ? Theme.onPrimaryColor : Theme.primary
            font.family: Theme.fontFamily
            font.pixelSize: Theme.textStyle("labelLarge").size
            font.weight: Font.Medium
        }
    }

    MouseArea {
        id: buttonMouse

        anchors.fill: parent
        enabled: root.enabled
        hoverEnabled: true
        cursorShape: root.enabled ? Qt.PointingHandCursor : Qt.ArrowCursor
        onClicked: root.clicked()
    }
}
