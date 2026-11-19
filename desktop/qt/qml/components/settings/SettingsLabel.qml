import QtQuick
import "../"

// 设置项分组小标题（labelLarge，下边距 6）。
Item {
    id: root

    property string text: ""
    property color textColor: Theme.onSurfaceColor

    width: parent ? parent.width : 0
    implicitHeight: label.implicitHeight + 6

    Text {
        id: label
        width: parent.width
        text: root.text
        color: root.textColor
        font.family: Theme.fontFamily
        font.pixelSize: 14
        font.weight: Font.Medium
        wrapMode: Text.Wrap
    }
}
