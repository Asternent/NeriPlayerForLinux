import QtQuick
import "../"

// 设置项说明文字（labelSmall 层级，自动换行）。
Item {
    id: root

    property string text: ""
    property color textColor: Theme.onSurfaceVariantColor

    width: parent ? parent.width : 0
    implicitHeight: note.implicitHeight

    Text {
        id: note
        width: parent.width
        text: root.text
        color: root.textColor
        font.family: Theme.fontFamily
        font.pixelSize: 11
        lineHeightMode: Text.FixedHeight
        lineHeight: 16
        wrapMode: Text.Wrap
    }
}
