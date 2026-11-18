import QtQuick
import QtQuick.Layouts

// 底部导航栏（M3 NavigationBar）：首页 / 探索 / 媒体库 / 设置。
Item {
    id: bar

    implicitHeight: 80
    height: 80

    readonly property var tabs: [
        { "name": "home", "label": "首页", "icon": "home" },
        { "name": "explore", "label": "探索", "icon": "explore" },
        { "name": "library", "label": "媒体库", "icon": "library_music" },
        { "name": "settings", "label": "设置", "icon": "settings" }
    ]

    Rectangle {
        anchors.fill: parent
        color: Theme.surfaceContainer
    }

    // 窄屏（内容收拢到 720）时导航条居中浮动
    Item {
        id: itemsRow
        anchors.centerIn: parent
        width: Math.min(parent.width, 720)
        height: parent.height

        Row {
            anchors.fill: parent
            Repeater {
                model: bar.tabs
                delegate: Item {
                    id: tabItem
                    required property var modelData
                    readonly property bool selected: Nav.tab === modelData.name
                    width: itemsRow.width / bar.tabs.length
                    height: itemsRow.height

                    Rectangle {
                        id: indicator
                        anchors.horizontalCenter: parent.horizontalCenter
                        y: 12
                        width: 64
                        height: 32
                        radius: 16
                        color: tabItem.selected ? Theme.secondaryContainer : "transparent"

                        Behavior on color {
                            ColorAnimation {
                                duration: Theme.motionMedium
                                easing.type: Theme.easingStandard
                            }
                        }
                    }

                    NeriIcon {
                        id: icon
                        anchors.centerIn: indicator
                        name: tabItem.modelData.icon
                        filled: tabItem.selected
                        iconSize: 24
                        color: tabItem.selected ? Theme.onSecondaryContainerColor : Theme.onSurfaceVariantColor
                    }

                    Text {
                        anchors.top: indicator.bottom
                        anchors.topMargin: 4
                        anchors.horizontalCenter: parent.horizontalCenter
                        text: tabItem.modelData.label
                        color: tabItem.selected ? Theme.onSurfaceColor : Theme.onSurfaceVariantColor
                        font.family: Theme.fontFamily
                        font.pixelSize: 12
                        font.weight: Font.Medium
                    }

                    HoverHandler {
                        cursorShape: Qt.PointingHandCursor
                    }

                    MouseArea {
                        anchors.fill: parent
                        onClicked: Nav.openTab(tabItem.modelData.name)
                    }
                }
            }
        }
    }
}
