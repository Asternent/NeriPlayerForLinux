import QtQuick
import QtQuick.Controls
import ".."

// 一起听面板输入框：M3 OutlinedTextField 风格（透明底 + 1dp 描边，聚焦时主色）。
// 与设置页的 SettingsTextField 视觉一致，但面板是独立文件，避免跨目录依赖。
TextField {
    id: root

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
    selectByMouse: true

    background: Rectangle {
        radius: Theme.radiusSm
        color: "transparent"
        border.width: root.activeFocus ? 2 : 1
        border.color: root.activeFocus ? Theme.primary : Theme.outlineVariant

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
