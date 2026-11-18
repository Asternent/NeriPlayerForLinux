import QtQuick
import "../"

// 只读信息行：左侧固定宽度标签（灰）+ 右侧值。
Item {
    id: root

    property string label: ""
    property string value: ""
    property real labelWidth: 96

    width: parent ? parent.width : 0
    implicitHeight: Math.max(labelText.implicitHeight, valueText.implicitHeight) + 8

    Text {
        id: labelText
        x: 0
        y: 4
        width: root.labelWidth
        text: root.label
        color: Theme.onSurfaceVariantColor
        font.family: Theme.fontFamily
        font.pixelSize: 14
        wrapMode: Text.Wrap
    }

    Text {
        id: valueText
        x: root.labelWidth
        y: 4
        width: Math.max(0, parent.width - root.labelWidth)
        text: root.value
        color: Theme.onSurfaceColor
        font.family: Theme.fontFamily
        font.pixelSize: 14
        lineHeightMode: Text.FixedHeight
        lineHeight: 20
        wrapMode: Text.Wrap
    }
}
