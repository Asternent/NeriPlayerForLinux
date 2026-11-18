import QtQuick
import "../"

// 播放器浮层面板的统一骨架：全屏 scrim + 右侧滑入卡片 + 标题栏。
// 面板正文通过默认属性放进卡片（PanelScaffold { ... }），正文区域位于标题栏下方。
Item {
    id: scaffold

    property string title: ""
    /** 卡片宽度：队列/音效 420，音量/睡眠 380（对应 Compose 覆盖面板尺寸）。 */
    property real panelWidth: 420
    property real panelMargin: 12

    default property alias content: contentHost.data

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

        width: Math.min(scaffold.panelWidth, scaffold.width - scaffold.panelMargin * 2)
        height: scaffold.height - scaffold.panelMargin * 2
        anchors.right: parent.right
        anchors.rightMargin: scaffold.panelMargin
        anchors.verticalCenter: parent.verticalCenter
        radius: Theme.radiusXl + 4
        color: Theme.surfaceContainerLow
        clip: true

        // 右侧滑入：卡片从窗口右侧外滑到最终位置
        transform: Translate {
            id: slide
            x: card.width + scaffold.panelMargin
        }

        // 消费卡片内的点击，避免冒泡到遮罩把面板关掉
        MouseArea {
            anchors.fill: parent
        }

        Column {
            anchors.fill: parent

            // 标题栏
            Item {
                id: titleBar
                width: parent.width
                height: 64

                Text {
                    anchors.left: parent.left
                    anchors.leftMargin: 18
                    anchors.right: closeButton.left
                    anchors.rightMargin: 8
                    anchors.verticalCenter: parent.verticalCenter
                    text: scaffold.title
                    elide: Text.ElideRight
                    color: Theme.onSurfaceColor
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.textStyle("titleMedium").size
                    font.weight: Font.DemiBold
                    font.letterSpacing: Theme.textStyle("titleMedium").letterSpacing
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

            // 正文区域：面板内容锚定/定位于此
            Item {
                id: contentHost
                width: parent.width
                height: parent.height - titleBar.height
            }
        }

        Component.onCompleted: slideIn.start()

        SequentialAnimation {
            id: slideIn

            NumberAnimation {
                target: slide
                property: "x"
                from: card.width + scaffold.panelMargin
                to: 0
                duration: Theme.motionMedium
                easing.type: Easing.OutCubic
            }
        }
    }

    // 键盘关闭。用 Shortcut 而不是 Keys，避免焦点在滑杆等控件上时失效。
    Shortcut {
        enabled: scaffold.visible
        sequence: "Escape"
        onActivated: Nav.closePanel()
    }
}
