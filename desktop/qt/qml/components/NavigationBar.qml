import QtQuick
import QtQuick.Layouts

// 左侧导航栏（桌面端样式）。
//
// 原来是 Material 3 的手机式底部导航（图标 + 文字、底部居中、整宽上浮），在桌面窗口里
// 看着像手机 App。桌面应用更常见的是**左侧竖向侧边栏**：图标与文字左对齐、选中项以
// 整行高亮表示、顶部放展开/收起按钮。这里按后者重做。
//
// 收起状态：窗口太窄时自动收起；用户手动点按钮后以手动选择为准（宽度够也不再自动展开）。
Item {
    id: bar

    /** 由 AppShell 按窗口宽度传入的自动收起建议。 */
    property bool autoCollapsed: false
    /** 用户手动指定的收起状态：null 表示跟随 autoCollapsed。 */
    property var manualCollapsed: null

    /** 实际是否收起：手动优先，否则跟随窗口宽度。 */
    readonly property bool collapsed: manualCollapsed === null
                                          ? autoCollapsed
                                          : manualCollapsed

    function requestToggle() {
        bar.manualCollapsed = !bar.collapsed
    }

    readonly property int expandedWidth: 208
    // 收起时只放图标，宽度约为展开态的三分之一
    readonly property int collapsedWidth: 52
    implicitWidth: collapsed ? collapsedWidth : expandedWidth
    width: implicitWidth

    readonly property var tabs: [
        { "name": "home", "label": "首页", "icon": "home" },
        { "name": "explore", "label": "探索", "icon": "explore" },
        { "name": "library", "label": "媒体库", "icon": "library_music" },
        { "name": "settings", "label": "设置", "icon": "settings" }
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

    Column {
        id: column
        anchors.top: header.bottom
        anchors.topMargin: 8
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.leftMargin: bar.collapsed ? 0 : 12
        anchors.rightMargin: bar.collapsed ? 0 : 12
        spacing: 4

        Repeater {
            model: bar.tabs
            delegate: Rectangle {
                id: tabItem
                required property var modelData
                readonly property bool selected: Nav.tab === modelData.name

                width: column.width
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
                    onClicked: Nav.openTab(tabItem.modelData.name)
                }
            }
        }
    }
}
