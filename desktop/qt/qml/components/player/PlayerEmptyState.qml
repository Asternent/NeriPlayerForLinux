import QtQuick
import "../"

// 空状态：图标 + 标题 + 提示，页面与面板共用（对齐 Compose EmptyState 尺寸）。
Item {
    id: root

    property string icon: "music_off"
    property string title: ""
    property string hint: ""
    property real contentWidth: 360

    implicitWidth: root.contentWidth
    implicitHeight: column.implicitHeight

    Column {
        id: column

        anchors.horizontalCenter: parent.horizontalCenter
        width: root.contentWidth
        spacing: 0

        NeriIcon {
            width: parent.width
            name: root.icon
            iconSize: 54
            color: Theme.withAlpha(Theme.onSurfaceVariantColor, 0.5)
        }

        Item {
            width: 1
            height: 12
        }

        Text {
            width: parent.width
            text: root.title
            horizontalAlignment: Text.AlignHCenter
            elide: Text.ElideRight
            color: Theme.onSurfaceVariantColor
            font.family: Theme.fontFamily
            font.pixelSize: Theme.textStyle("titleSmall").size
            font.weight: Theme.textStyle("titleSmall").weight
        }

        Item {
            width: 1
            height: 6
        }

        Text {
            width: parent.width
            visible: root.hint !== ""
            text: root.hint
            wrapMode: Text.Wrap
            horizontalAlignment: Text.AlignHCenter
            color: Theme.withAlpha(Theme.onSurfaceVariantColor, 0.75)
            font.family: Theme.fontFamily
            font.pixelSize: Theme.textStyle("bodySmall").size
        }
    }
}
