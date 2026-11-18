import QtQuick
import "../"

// 通用设置行：左侧标题（+可选说明），右侧 trailing 插槽放按钮/控件。
// 子项放进 trailing 时同样需要自行设置宽高。
Item {
    id: root

    property string title: ""
    property string description: ""
    property color titleColor: Theme.onSurfaceColor
    property color descriptionColor: Theme.onSurfaceVariantColor
    property int titlePixelSize: 16
    property bool titleBold: false

    default property alias trailing: trailingRow.data

    width: parent ? parent.width : 0
    implicitHeight: contentItem.height + 12

    Item {
        id: contentItem
        x: 0
        y: 6
        width: parent.width
        height: Math.max(textColumn.implicitHeight, trailingRow.implicitHeight)

        Column {
            id: textColumn
            width: Math.max(0, parent.width - trailingRow.width
                            - (trailingRow.width > 0 ? Theme.spacingMd : 0))
            spacing: 2

            Text {
                width: parent.width
                text: root.title
                color: root.titleColor
                font.family: Theme.fontFamily
                font.pixelSize: root.titlePixelSize
                font.weight: root.titleBold ? Font.DemiBold : Font.Normal
                wrapMode: Text.Wrap
            }

            Text {
                width: parent.width
                visible: root.description !== ""
                text: root.description
                color: root.descriptionColor
                font.family: Theme.fontFamily
                font.pixelSize: 12
                lineHeightMode: Text.FixedHeight
                lineHeight: 16
                wrapMode: Text.Wrap
            }
        }

        Row {
            id: trailingRow
            anchors.right: parent.right
            anchors.verticalCenter: parent.verticalCenter
            spacing: Theme.spacingSm
        }
    }
}
