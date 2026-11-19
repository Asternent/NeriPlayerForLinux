import QtQuick
import QtQuick.Layouts

// 左侧导航栏（桌面端样式）。
//
// 顶部放「在线内容」类入口（首页 / 探索），底部放「本地管理」类入口
// （媒体库 / 下载管理 / 设置），这是桌面应用侧边栏的常见布局。
//
// 收起状态保存在设置项 navCollapsed 里（默认收起），重启后保持。
Item {
    id: bar

    /** 收起状态直接读设置项，默认收起（重启后沿用上次选择）。 */
    readonly property bool collapsed: Settings.get("navCollapsed", true) === true

    function requestToggle() {
        Settings.set("navCollapsed", !bar.collapsed)
    }

    readonly property int expandedWidth: 208
    // 收起时只放图标，宽度约为展开态的三分之一
    readonly property int collapsedWidth: 52
    implicitWidth: collapsed ? collapsedWidth : expandedWidth
    width: implicitWidth

    /** 顶部：在线内容入口。 */
    readonly property var primaryTabs: [
        { "name": "home", "label": "首页", "icon": "home", "kind": "tab" },
        { "name": "explore", "label": "探索", "icon": "explore", "kind": "tab" }
    ]
    /** 底部：本地内容与管理入口（底对齐）。 */
    readonly property var secondaryTabs: [
        { "name": "library", "label": "媒体库", "icon": "library_music", "kind": "tab" },
        { "name": "downloads", "label": "下载管理", "icon": "download", "kind": "panel" },
        { "name": "settings", "label": "设置", "icon": "settings", "kind": "tab" }
    ]

    // 收起/展开过程中内容会短暂超出侧栏宽度，裁剪掉以免画到右侧边界线外面
    clip: true

    Behavior on width {
        NumberAnimation {
            duration: Theme.motionMedium
            easing.type: Theme.easingStandard
        }
    }

    Rectangle {
        anchors.fill: parent
        color: Theme.surfaceContainerLow
    }

    // 竖向分隔线，和右侧内容区分开（放在背景之后才不会被盖住）
    Rectangle {
        anchors.right: parent.right
        anchors.top: parent.top
        anchors.bottom: parent.bottom
        width: 1
        color: Theme.withAlpha(Theme.outlineVariant, 0.6)
    }

    // ---- 顶部：展开/收起按钮 ----
    Item {
        id: header
        anchors.top: parent.top
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.topMargin: 8
        height: 40

        Rectangle {
            id: toggleButton
            width: 32
            height: 32
            radius: 8
            anchors.verticalCenter: parent.verticalCenter
            anchors.left: parent.left
            // 用固定的收起宽度算居中位置，而不是读取正在做动画的 bar.width
            // （否则目标值每帧都在变，按钮会跟着侧栏一起摆动）。
            anchors.leftMargin: bar.collapsed ? (bar.collapsedWidth - width) / 2 : 14
            color: toggleHover.hovered ? Theme.withAlpha(Theme.onSurfaceColor, 0.08) : "transparent"

            Behavior on anchors.leftMargin {
                NumberAnimation {
                    duration: Theme.motionMedium
                    easing.type: Theme.easingStandard
                }
            }

            NeriIcon {
                anchors.centerIn: parent
                // 收起状态显示「展开」，展开状态显示「收起」
                name: bar.collapsed ? "chevron_right" : "chevron_left"
                iconSize: 20
                color: Theme.onSurfaceVariantColor
            }

            HoverHandler {
                id: toggleHover
                cursorShape: Qt.PointingHandCursor
            }

            MouseArea {
                anchors.fill: parent
                onClicked: bar.requestToggle()
            }
        }

        // 展开时在按钮右侧显示应用名
        Text {
            anchors.left: toggleButton.right
            anchors.leftMargin: 10
            anchors.verticalCenter: parent.verticalCenter
            anchors.right: parent.right
            anchors.rightMargin: 12
            text: "NeriPlayer"
            elide: Text.ElideRight
            color: Theme.onSurfaceColor
            font.family: Theme.fontFamily
            font.pixelSize: 14
            font.weight: Font.DemiBold
            // 只改透明度：瞬间隐藏文字会让按钮看起来「先往右再往左」
            opacity: bar.collapsed ? 0 : 1

            Behavior on opacity {
                NumberAnimation {
                    duration: Theme.motionMedium
                    easing.type: Theme.easingStandard
                }
            }
        }
    }

    // 导航项模板：顶部组与底部组共用同一套外观与交互
    Component {
        id: navDelegate

        Rectangle {
            id: tabItem
            required property var modelData

            readonly property bool isPanel: modelData.kind === "panel"
            readonly property bool selected: isPanel
                                             ? (Nav.activePanel === modelData.name)
                                             : (Nav.tab === modelData.name)

            width: ListView.view ? ListView.view.width : parent.width
            height: 44
            radius: 10
            color: tabItem.selected
                   ? Theme.secondaryContainer
                   : (tabHover.hovered ? Theme.withAlpha(Theme.onSurfaceColor, 0.06)
                                       : "transparent")

            Behavior on color {
                ColorAnimation {
                    duration: Theme.motionShort
                    easing.type: Theme.easingStandard
                }
            }

            // 左侧选中指示条
            Rectangle {
                anchors.left: parent.left
                anchors.verticalCenter: parent.verticalCenter
                width: 3
                height: tabItem.selected ? 20 : 0
                radius: 2
                color: Theme.primary

                Behavior on height {
                    NumberAnimation {
                        duration: Theme.motionShort
                        easing.type: Easing.OutCubic
                    }
                }
            }

            // 收起态：居中的图标。与展开态图标做交叉淡入淡出，
            // 这样图标是「原地淡入淡出 + 轻微横移」，不会突然跳位。
            NeriIcon {
                anchors.centerIn: parent
                name: tabItem.modelData.icon
                filled: tabItem.selected
                iconSize: 22
                color: tabItem.selected
                       ? Theme.onSecondaryContainerColor
                       : Theme.onSurfaceVariantColor
                opacity: bar.collapsed ? 1 : 0
                visible: opacity > 0

                Behavior on opacity {
                    NumberAnimation {
                        duration: Theme.motionMedium
                        easing.type: Theme.easingStandard
                    }
                }
            }

            RowLayout {
                anchors.left: parent.left
                anchors.leftMargin: 14
                anchors.right: parent.right
                anchors.rightMargin: 10
                anchors.verticalCenter: parent.verticalCenter
                spacing: 12
                visible: !bar.collapsed

                NeriIcon {
                    id: icon
                    name: tabItem.modelData.icon
                    filled: tabItem.selected
                    iconSize: 22
                    color: tabItem.selected
                           ? Theme.onSecondaryContainerColor
                           : Theme.onSurfaceVariantColor
                    opacity: bar.collapsed ? 0 : 1

                    Behavior on opacity {
                        NumberAnimation {
                            duration: Theme.motionMedium
                            easing.type: Theme.easingStandard
                        }
                    }
                }

                Text {
                    Layout.fillWidth: true
                    // 与图标同时淡出，避免文字瞬间消失造成跳变
                    opacity: bar.collapsed ? 0 : 1
                    visible: opacity > 0
                    text: tabItem.modelData.label
                    elide: Text.ElideRight
                    color: tabItem.selected ? Theme.onSurfaceColor : Theme.onSurfaceVariantColor
                    font.family: Theme.fontFamily
                    font.pixelSize: 14
                    font.weight: tabItem.selected ? Font.DemiBold : Font.Medium

                    Behavior on opacity {
                        NumberAnimation {
                            duration: Theme.motionMedium
                            easing.type: Theme.easingStandard
                        }
                    }
                }
            }

            HoverHandler {
                id: tabHover
                cursorShape: Qt.PointingHandCursor
            }

            MouseArea {
                anchors.fill: parent
                onClicked: {
                    if (tabItem.isPanel)
                        Nav.openPanel(tabItem.modelData.name)
                    else
                        Nav.openTab(tabItem.modelData.name)
                }
            }
        }
    }

    // ---- 顶部导航组 ----
    Column {
        anchors.top: header.bottom
        anchors.topMargin: 8
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.leftMargin: bar.collapsed ? 0 : 12
        anchors.rightMargin: bar.collapsed ? 0 : 12
        spacing: 4

        Repeater {
            model: bar.primaryTabs
            delegate: navDelegate
        }
    }

    // ---- 底部导航组（底对齐）----
    Column {
        anchors.bottom: parent.bottom
        anchors.bottomMargin: 12
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.leftMargin: bar.collapsed ? 0 : 12
        anchors.rightMargin: bar.collapsed ? 0 : 12
        spacing: 4

        Repeater {
            model: bar.secondaryTabs
            delegate: navDelegate
        }
    }
}
