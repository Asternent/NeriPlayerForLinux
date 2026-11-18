import QtQuick
import QtQuick.Controls
import "../"

// 设置页输入框：M3 OutlinedTextField 风格（透明底 + 1dp 描边，聚焦时主色）。
TextField {
    id: root

    property bool invalid: false

    implicitHeight: 44
    leftPadding: 12
    rightPadding: 12
    topPadding: 10
    bottomPadding: 10

    color: Theme.onSurfaceColor
    placeholderTextColor: Theme.withAlpha(Theme.onSurfaceVariantColor, 0.72)
    selectionColor: Theme.primary
    selectedTextColor: Theme.onPrimaryColor
    font.family: Theme.fontFamily
    font.pixelSize: 14

    background: Rectangle {
        radius: Theme.radiusSm
        color: "transparent"
        border.width: root.activeFocus ? 2 : 1
        border.color: root.invalid
                      ? Theme.error
                      : (root.activeFocus ? Theme.primary : Theme.outlineVariant)

        Behavior on border.color {
            ColorAnimation {
                duration: Theme.motionShort
            }
        }
    }

    HoverHandler {
        cursorShape: Qt.IBeamCursor
    }
}
