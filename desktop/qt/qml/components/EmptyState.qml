import QtQuick

// 通用空状态。
Item {
    id: empty

    property string title: "这里什么都没有"
    property string hint: ""
    property string icon: "inbox"

    implicitHeight: column.implicitHeight + 80

    Column {
        id: column
        anchors.centerIn: parent
        width: Math.min(parent.width - 48, 420)
        spacing: 6

        NeriIcon {
            anchors.horizontalCenter: parent.horizontalCenter
            name: empty.icon
            iconSize: 54
            color: Theme.onSurfaceVariantColor
        }

        Item { width: 1; height: 6 }

        Text {
            width: parent.width
            text: empty.title
            horizontalAlignment: Text.AlignHCenter
            wrapMode: Text.Wrap
            color: Theme.onSurfaceColor
            font.family: Theme.fontFamily
            font.pixelSize: 14
            font.weight: Font.Medium
        }

        Text {
            width: parent.width
            visible: empty.hint !== ""
            text: empty.hint
            horizontalAlignment: Text.AlignHCenter
            wrapMode: Text.Wrap
            color: Theme.onSurfaceVariantColor
            font.family: Theme.fontFamily
            font.pixelSize: 12
        }
    }
}
