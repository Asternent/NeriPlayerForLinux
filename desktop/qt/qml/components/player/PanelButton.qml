import QtQuick

// M3 文字按钮 / FilterChip。filled = 主色实心按钮；selected = 选中态 chip；
// outlined = 描边按钮；三者都不是时是低饱和度的普通文字按钮。
Rectangle {
    id: root

    property string text: ""
    property bool filled: false
    property bool outlined: false
    property bool selected: false
    property bool chip: false
    property bool enabledButton: true

    signal clicked()

    implicitWidth: label.implicitWidth + (root.chip ? 24 : 32)
    implicitHeight: root.chip ? 32 : 40
    radius: root.chip ? Theme.radiusSm : height / 2

    color: {
        if (!root.enabledButton)
            return Theme.withAlpha(Theme.onSurfaceColor, 0.08)
        if (root.filled)
            return Theme.primary
        if (root.selected)
            return Theme.secondaryContainer
        if (root.outlined)
            return "transparent"
        return Theme.withAlpha(Theme.onSurfaceColor, mouse.containsMouse ? 0.12 : 0.06)
    }

    border.width: root.outlined && !root.filled ? 1 : 0
    border.color: Theme.outlineVariant

    Behavior on color {
        ColorAnimation {
            duration: Theme.motionShort
        }
    }

    Text {
        id: label

        anchors.centerIn: parent
        text: root.text
        elide: Text.ElideRight
        color: {
            if (!root.enabledButton)
                return Theme.withAlpha(Theme.onSurfaceColor, 0.38)
            if (root.filled)
                return Theme.onPrimaryColor
            if (root.selected)
                return Theme.onSecondaryContainerColor
            return Theme.primary
        }
        font.family: Theme.fontFamily
        font.pixelSize: root.chip ? Theme.textStyle("labelMedium").size : Theme.textStyle("labelLarge").size
        font.weight: Font.Medium
    }

    MouseArea {
        id: mouse

        anchors.fill: parent
        hoverEnabled: true
        enabled: root.enabledButton
        cursorShape: Qt.PointingHandCursor
        onClicked: root.clicked()
    }
}
