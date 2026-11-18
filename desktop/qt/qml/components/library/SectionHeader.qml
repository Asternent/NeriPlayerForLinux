import QtQuick
import QtQuick.Layouts
import ".."

// 区块标题（共享组件）。对齐旧 Compose 的 SectionHeader：
// 内边距 h16 v10，可选 20px 主色图标 + 8px 间距，标题 titleMedium 半粗。
// 右侧可放自定义内容：SectionHeader { trailing: Row { ... } }。
Item {
    id: root

    property string title: ""
    property string icon: ""
    property string trailingText: ""
    default property alias trailing: trailingSlot.data

    implicitHeight: Math.max(rowContent.implicitHeight, 20) + 20

    RowLayout {
        id: rowContent

        anchors.left: parent.left
        anchors.right: parent.right
        anchors.verticalCenter: parent.verticalCenter
        anchors.leftMargin: Theme.spacingLg
        anchors.rightMargin: Theme.spacingLg
        spacing: Theme.spacingSm

        NeriIcon {
            visible: root.icon !== ""
            name: root.icon
            iconSize: 20
            color: Theme.primary
            Layout.alignment: Qt.AlignVCenter
        }

        Text {
            Layout.fillWidth: true
            text: root.title
            elide: Text.ElideRight
            maximumLineCount: 1
            color: Theme.onSurfaceColor
            font.family: Theme.fontFamily
            font.pixelSize: Theme.textStyle("titleMedium").size
            font.weight: Font.DemiBold
        }

        Text {
            visible: root.trailingText !== ""
            text: root.trailingText
            color: Theme.onSurfaceVariantColor
            font.family: Theme.fontFamily
            font.pixelSize: Theme.textStyle("labelMedium").size
            Layout.alignment: Qt.AlignVCenter
        }

        Item {
            id: trailingSlot
            Layout.alignment: Qt.AlignVCenter
            implicitWidth: childrenRect.width
            implicitHeight: childrenRect.height
        }
    }
}
