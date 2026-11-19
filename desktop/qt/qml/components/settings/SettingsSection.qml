import QtQuick
import "../"

// 设置页卡片容器：左侧主色图标 + 半粗标题 + 说明，点击标题栏折叠/展开内容。
// 默认折叠，只有「关于」这类信息量小的分组保持展开。
// 外观对齐旧 Compose 的 SettingsSection：圆角 20、卡片外边距 h12 v6、内边距 v8，
// 标题栏 h16 v10、内容区 h16 v4。
Item {
    id: root

    property string title: ""
    property string description: ""
    property string icon: "settings"
    /** 是否展开。默认折叠，避免设置页一进来就是很长的列表；个别分组可显式设为 true。 */
    property bool expanded: false

    // 内容插槽。子项直接声明即可，需要自行设置 width: parent.width。
    default property alias content: contentColumn.data

    width: parent ? parent.width : 0
    implicitHeight: card.height

    Rectangle {
        id: card
        width: parent.width
        height: body.height
        radius: Theme.radiusXl
        color: Theme.surfaceContainerLow
    }

    Column {
        id: body
        width: parent.width

        Item {
            id: header
            width: parent.width
            height: headerContent.height + 20

            Rectangle {
                anchors.fill: parent
                radius: Theme.radiusXl
                color: Theme.withAlpha(Theme.onSurfaceColor, 0.04)
                visible: headerHover.hovered
            }

            HoverHandler {
                id: headerHover
                cursorShape: Qt.PointingHandCursor
            }

            MouseArea {
                anchors.fill: parent
                onClicked: root.expanded = !root.expanded
            }

            Row {
                id: headerContent
                x: Theme.spacingLg
                y: 10
                width: parent.width - Theme.spacingLg * 2
                spacing: Theme.spacingMd

                NeriIcon {
                    name: root.icon
                    iconSize: 22
                    color: Theme.primary
                    anchors.verticalCenter: parent.verticalCenter
                }

                Column {
                    id: titleColumn
                    width: Math.max(0, parent.width - 22 - Theme.spacingMd * 2 - collapseGroup.width)
                    spacing: 2
                    anchors.verticalCenter: parent.verticalCenter

                    Text {
                        width: parent.width
                        text: root.title
                        color: Theme.onSurfaceColor
                        font.family: Theme.fontFamily
                        font.pixelSize: 16
                        font.weight: Font.DemiBold
                        elide: Text.ElideRight
                    }

                    Text {
                        width: parent.width
                        visible: root.description !== ""
                        text: root.description
                        color: Theme.onSurfaceVariantColor
                        font.family: Theme.fontFamily
                        font.pixelSize: 11
                        lineHeightMode: Text.FixedHeight
                        lineHeight: 16
                        wrapMode: Text.Wrap
                    }
                }

                Row {
                    id: collapseGroup
                    spacing: 2
                    anchors.verticalCenter: parent.verticalCenter

                    Text {
                        anchors.verticalCenter: parent.verticalCenter
                        text: root.expanded ? "收起" : "展开"
                        color: Theme.onSurfaceVariantColor
                        font.family: Theme.fontFamily
                        font.pixelSize: 12
                        font.weight: Font.Medium
                    }

                    NeriIcon {
                        anchors.verticalCenter: parent.verticalCenter
                        name: root.expanded ? "expand_less" : "expand_more"
                        iconSize: 18
                        color: Theme.onSurfaceVariantColor
                    }
                }
            }
        }

        Item {
            id: contentWrapper
            width: parent.width
            visible: root.expanded
            height: visible ? contentColumn.height + 8 : 0

            Column {
                id: contentColumn
                x: Theme.spacingLg
                y: 4
                width: parent.width - Theme.spacingLg * 2
                spacing: 0
            }
        }
    }
}
