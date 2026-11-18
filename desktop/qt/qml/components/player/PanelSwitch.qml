import QtQuick
import "../"

// M3 开关（Switch）。checked 由父级绑定到数据源，点击时只触发 toggled，
// 由父级调用命令让数据源回写，避免打断绑定。
Item {
    id: root

    property bool checked: false
    property bool enabledSwitch: true

    signal toggled(bool value)

    implicitWidth: 52
    implicitHeight: 32

    Rectangle {
        anchors.fill: parent
        radius: height / 2
        color: {
            if (!root.enabledSwitch)
                return Theme.withAlpha(Theme.onSurfaceColor, 0.12)
            if (root.checked)
                return Theme.primary
            return Theme.surfaceContainerHighest
        }

        Behavior on color {
            ColorAnimation {
                duration: Theme.motionShort
            }
        }
    }

    Rectangle {
        width: 24
        height: 24
        radius: 12
        y: 4
        x: root.checked ? parent.width - width - 4 : 4
        color: {
            if (!root.enabledSwitch)
                return Theme.withAlpha(Theme.onSurfaceColor, 0.38)
            return root.checked ? Theme.onPrimaryColor : Theme.outline
        }

        Behavior on x {
            NumberAnimation {
                duration: Theme.motionShort
                easing.type: Easing.OutCubic
            }
        }

        Behavior on color {
            ColorAnimation {
                duration: Theme.motionShort
            }
        }
    }

    MouseArea {
        anchors.fill: parent
        enabled: root.enabledSwitch
        cursorShape: Qt.PointingHandCursor
        onClicked: root.toggled(!root.checked)
    }
}
