import QtQuick
import "../"

// 胶囊选项组（M3 FilterChip 风格，自动换行）。
// options 支持 ["a","b"] 或 [{ value: "a", label: "标签" }] 两种形式。
Item {
    id: root

    property var options: []
    property var value: undefined

    signal selected(var value)

    width: parent ? parent.width : 0
    implicitHeight: flow.height

    function optionValue(option) {
        if (option !== null && typeof option === "object")
            return option.value
        return option
    }

    function optionLabel(option) {
        if (option !== null && typeof option === "object")
            return option.label !== undefined ? option.label : String(option.value)
        return String(option)
    }

    Flow {
        id: flow
        width: parent.width
        spacing: Theme.spacingSm

        Repeater {
            model: root.options

            delegate: Rectangle {
                id: chip

                required property var modelData
                readonly property bool selected: String(root.value) === String(root.optionValue(modelData))
                readonly property bool hovered: chipHover.hovered

                width: chipContent.implicitWidth + 24
                height: 32
                radius: Theme.radiusSm
                color: selected
                       ? Theme.secondaryContainer
                       : (hovered ? Theme.withAlpha(Theme.onSurfaceColor, 0.06) : "transparent")
                border.width: selected ? 0 : 1
                border.color: Theme.outlineVariant
                opacity: root.enabled ? 1 : 0.38

                Behavior on color {
                    ColorAnimation {
                        duration: Theme.motionShort
                    }
                }

                Row {
                    id: chipContent
                    anchors.centerIn: parent
                    spacing: 6

                    NeriIcon {
                        visible: chip.selected
                        name: "check"
                        iconSize: 14
                        color: Theme.onSecondaryContainerColor
                        anchors.verticalCenter: parent.verticalCenter
                    }

                    Text {
                        text: root.optionLabel(chip.modelData)
                        color: chip.selected
                               ? Theme.onSecondaryContainerColor
                               : Theme.onSurfaceVariantColor
                        font.family: Theme.fontFamily
                        font.pixelSize: 14
                        font.weight: Font.Medium
                        anchors.verticalCenter: parent.verticalCenter
                    }
                }

                HoverHandler {
                    id: chipHover
                    cursorShape: Qt.PointingHandCursor
                }

                MouseArea {
                    anchors.fill: parent
                    onClicked: root.selected(root.optionValue(chip.modelData))
                }
            }
        }
    }
}
