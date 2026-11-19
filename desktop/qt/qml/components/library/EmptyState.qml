import QtQuick
import ".."

// 空状态（共享组件）。对齐旧 Compose 的 EmptyState：
// 上边距 40、图标 54（onSurfaceVariant α0.5）、标题 titleSmall、hint bodySmall（α0.75）。
Item {
    id: root

    property string title: "暂无内容"
    property string hint: ""
    property string icon: "album"

    implicitWidth: parent ? parent.width : 320
    implicitHeight: column.implicitHeight + 80

    Column {
        id: column

        anchors.top: parent.top
        anchors.topMargin: 40
        anchors.horizontalCenter: parent.horizontalCenter
        width: Math.min(root.width - 48, 420)
        spacing: 0

        Item {
            width: parent.width
            height: 54

            NeriIcon {
                anchors.centerIn: parent
                name: root.icon
                iconSize: 54
                color: Theme.withAlpha(Theme.onSurfaceVariantColor, 0.5)
            }
        }

        Item {
            width: 1
            height: 12
        }

        Text {
            width: parent.width
            text: root.title
            horizontalAlignment: Text.AlignHCenter
            wrapMode: Text.Wrap
            color: Theme.onSurfaceVariantColor
            font.family: Theme.fontFamily
            font.pixelSize: Theme.textStyle("titleSmall").size
            font.weight: Theme.textStyle("titleSmall").weight
        }

        Item {
            width: 1
            height: 6
            visible: root.hint !== ""
        }

        Text {
            visible: root.hint !== ""
            width: parent.width
            text: root.hint
            horizontalAlignment: Text.AlignHCenter
            wrapMode: Text.Wrap
            color: Theme.withAlpha(Theme.onSurfaceVariantColor, 0.75)
            font.family: Theme.fontFamily
            font.pixelSize: Theme.textStyle("bodySmall").size
        }
    }
}
