import QtQuick
import "../"

// 开关设置行：标题 + 说明 + M3 开关。
Item {
    id: root

    property string title: ""
    property string description: ""
    property bool checked: false

    signal toggled(bool checked)

    width: parent ? parent.width : 0
    implicitHeight: Math.max(textColumn.implicitHeight, 32) + 12

    Item {
        id: contentItem
        x: 0
        y: 6
        width: parent.width
        height: Math.max(textColumn.implicitHeight, 32)

        Column {
            id: textColumn
            width: Math.max(0, parent.width - 52 - Theme.spacingMd)
            anchors.verticalCenter: parent.verticalCenter
            spacing: 2

            Text {
                width: parent.width
                text: root.title
                color: Theme.onSurfaceColor
                font.family: Theme.fontFamily
                font.pixelSize: 16
                wrapMode: Text.Wrap
            }

            Text {
                width: parent.width
                visible: root.description !== ""
                text: root.description
                color: Theme.onSurfaceVariantColor
                font.family: Theme.fontFamily
                font.pixelSize: 12
                lineHeightMode: Text.FixedHeight
                lineHeight: 16
                wrapMode: Text.Wrap
            }
        }

        Item {
            id: switchRoot
            width: 52
            height: 32
            anchors.right: parent.right
            anchors.verticalCenter: parent.verticalCenter
            opacity: root.enabled ? 1 : 0.38

            Rectangle {
                id: track
                anchors.fill: parent
                radius: height / 2
                color: root.checked ? Theme.primary : Theme.surfaceContainerHighest
                border.width: root.checked ? 0 : 2
                border.color: Theme.outline

                Behavior on color {
                    ColorAnimation {
                        duration: Theme.motionShort
                    }
                }
            }

            Rectangle {
                id: thumb
                width: root.checked ? 24 : 16
                height: width
                radius: width / 2
                anchors.verticalCenter: parent.verticalCenter
                x: root.checked ? switchRoot.width - width - 4 : 8
                color: root.checked ? Theme.onPrimaryColor : Theme.outline

                Behavior on x {
                    NumberAnimation {
                        duration: Theme.motionShort
                        easing.type: Easing.OutCubic
                    }
                }

                Behavior on width {
                    NumberAnimation {
                        duration: Theme.motionShort
                        easing.type: Easing.OutCubic
                    }
                }
            }

            HoverHandler {
                cursorShape: root.enabled ? Qt.PointingHandCursor : Qt.ArrowCursor
            }

            MouseArea {
                anchors.fill: parent
                enabled: root.enabled
                onClicked: root.toggled(!root.checked)
            }
        }
    }
}
