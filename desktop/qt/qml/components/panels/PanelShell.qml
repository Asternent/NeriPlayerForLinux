import QtQuick
import ".."

// 主窗口浮层面板的公共骨架：全屏 scrim + 卡片 + 标题栏 + 关闭按钮。
// 卡片可居中（登录）或贴右侧滑入（下载）；高度可铺满或按内容收缩。
//
// 用法：
//   PanelShell {
//       title: "标题"
//       cardWidth: 420
//       content: Item { ... }      // 正文：铺满标题栏以下区域
//       PanelButton { ... }        // 默认属性：标题栏右侧的额外操作
//   }
Item {
    id: shell

    property string title: ""
    /** 卡片宽度（窗口过窄时会自动收缩）。 */
    property real cardWidth: 420
    property real cardMargin: 12
    property real cardRadius: 24
    /** 卡片高度；< 0 表示铺满窗口高度（减去上下边距）。 */
    property real cardHeight: -1
    /** true = 贴右侧；false = 水平居中。 */
    property bool alignRight: true

    /** 标题栏右侧的额外操作（默认属性）。 */
    default property alias headerActions: headerHost.data
    /** 面板正文。 */
    property alias content: bodyHost.data

    readonly property real titleBarHeight: 64
    readonly property real bodyHeight: Math.max(0, card.height - titleBarHeight)

    // 全屏遮罩：点击关闭面板
    Rectangle {
        id: scrim
        anchors.fill: parent
        color: Theme.withAlpha(Theme.scrim, 0.45)
        opacity: 0

        Behavior on opacity {
            NumberAnimation {
                duration: Theme.motionMedium
                easing.type: Easing.OutCubic
            }
        }

        Component.onCompleted: opacity = 1

        MouseArea {
            anchors.fill: parent
            onClicked: Nav.closePanel()
        }
    }

    Rectangle {
        id: card

        width: Math.min(shell.cardWidth, Math.max(0, shell.width - shell.cardMargin * 2))
        height: shell.cardHeight >= 0
                ? Math.max(180, Math.min(shell.cardHeight, shell.height - shell.cardMargin * 2))
                : shell.height - shell.cardMargin * 2
        x: shell.alignRight ? shell.width - width - shell.cardMargin
                            : (shell.width - width) / 2
        y: (shell.height - height) / 2
        radius: shell.cardRadius
        color: Theme.surfaceContainerLow
        clip: true

        // 右侧滑入 + 淡入
        transform: Translate {
            id: slide
        }
        opacity: 0

        Behavior on opacity {
            NumberAnimation {
                duration: Theme.motionShort
            }
        }

        Component.onCompleted: {
            opacity = 1
            slideIn.start()
        }

        SequentialAnimation {
            id: slideIn
            NumberAnimation {
                target: slide
                property: "x"
                from: card.width + shell.cardMargin
                to: 0
                duration: Theme.motionLong
                easing.type: Easing.OutCubic
            }
        }

        // 消费卡片内的点击，避免冒泡到遮罩把面板关掉
        MouseArea {
            anchors.fill: parent
        }

        // 标题栏
        Item {
            id: titleBar
            width: card.width
            height: shell.titleBarHeight

            Text {
                anchors.left: parent.left
                anchors.leftMargin: 20
                anchors.right: headerHost.left
                anchors.rightMargin: 8
                anchors.verticalCenter: parent.verticalCenter
                text: shell.title
                elide: Text.ElideRight
                color: Theme.onSurfaceColor
                font.family: Theme.fontFamily
                font.pixelSize: Theme.textStyle("titleMedium").size
                font.weight: Font.DemiBold
                font.letterSpacing: Theme.textStyle("titleMedium").letterSpacing
            }

            Row {
                id: headerHost
                anchors.right: closeButton.left
                anchors.rightMargin: 8
                anchors.verticalCenter: parent.verticalCenter
                spacing: 4
            }

            IconButton {
                id: closeButton
                anchors.right: parent.right
                anchors.rightMargin: 8
                anchors.verticalCenter: parent.verticalCenter
                icon: "close"
                iconSize: 24
                size: 48
                tooltip: "关闭"
                onClicked: Nav.closePanel()
            }
        }

        // 正文区域
        Item {
            id: bodyHost
            y: shell.titleBarHeight
            width: card.width
            height: shell.bodyHeight
        }
    }

    // 键盘关闭。用 Shortcut 而不是 Keys，避免焦点在滑杆等控件上时失效。
    Shortcut {
        enabled: shell.visible
        sequence: "Escape"
        onActivated: Nav.closePanel()
    }
}
