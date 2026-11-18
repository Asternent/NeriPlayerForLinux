import QtQuick
import ".."

// 分段选择器 / Tab 条（共享组件）。M3 FilterChip 风格，对齐旧 Compose 的 FilterChip 行。
//
// 用法：
//   TabBar {
//       options: ["本地", "收藏", "在线音源"]
//       value: "本地"
//       onSelected: function(v) { ... }
//   }
//   // 或带 value/label 的对象数组：
//   options: [{ value: "songs", label: "歌曲" }, { value: "artists", label: "歌手" }]
//
// 属性：options（string[] 或 [{value,label}]）、value（当前选中值）
// 信号：selected(var value)
Item {
    id: root

    property var options: []
    property var value: undefined
    property real horizontalPadding: Theme.spacingLg
    property real itemSpacing: Theme.spacingSm

    signal selected(var value)

    implicitHeight: 34
    implicitWidth: flow.width

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

    Row {
        id: flow

        anchors.left: parent.left
        anchors.leftMargin: root.horizontalPadding
        anchors.verticalCenter: parent.verticalCenter
        spacing: root.itemSpacing

        Repeater {
            model: root.options

            delegate: Rectangle {
                id: chip

                required property var modelData

                readonly property bool selected: String(root.value) === String(root.optionValue(modelData))

                width: chipText.implicitWidth + Theme.spacingXl
                height: 34
                radius: Theme.radiusSm
                color: selected
                       ? Theme.secondaryContainer
                       : (chipHover.hovered ? Theme.withAlpha(Theme.onSurfaceColor, 0.06) : "transparent")
                border.width: selected ? 0 : 1
                border.color: Theme.outlineVariant

                Behavior on color {
                    ColorAnimation {
                        duration: Theme.motionShort
                    }
                }

                Text {
                    id: chipText

                    anchors.centerIn: parent
                    text: root.optionLabel(chip.modelData)
                    color: chip.selected ? Theme.onSecondaryContainerColor : Theme.onSurfaceVariantColor
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.textStyle("labelLarge").size
                    font.weight: chip.selected ? Font.DemiBold : Font.Medium
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
