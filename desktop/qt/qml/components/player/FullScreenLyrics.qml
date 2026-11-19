import QtQuick
import QtQuick.Layouts
import ".."

// 全屏歌词覆盖层：在播放页背景之上压一层暗色遮罩，只留大字号歌词。
// 顶栏（关闭/字号调节）默认隐藏，鼠标移到顶部才淡入；Esc 关闭。
Item {
    id: root

    visible: false
    signal closeRequested()

    opacity: visible ? 1 : 0

    Behavior on opacity {
        NumberAnimation { duration: Theme.motionMedium; easing.type: Easing.OutCubic }
    }

    NowPlayingTheme { id: np }

    readonly property real currentScale: {
        const scale = Settings.get("lyricsFontScale", 1.0)
        return (typeof scale === "number" && scale > 0) ? scale : 1.0
    }

    readonly property real baseFontSize: Math.min(34, Math.max(24, width * 0.026))

    function adjustScale(delta) {
        const next = Math.max(0.7, Math.min(1.8, Math.round((currentScale + delta) * 10) / 10))
        Settings.set("lyricsFontScale", next)
    }

    Rectangle {
        anchors.fill: parent
        color: Theme.withAlpha(np.scrim, 0.62)
    }

    // 歌词正文：宽屏限宽居中，窄屏铺满
    LyricsPane {
        id: lyrics

        anchors.fill: parent
        anchors.leftMargin: Math.max(0, (parent.width - Math.min(parent.width, 1000)) / 2)
        anchors.rightMargin: Math.max(0, (parent.width - Math.min(parent.width, 1000)) / 2)
        anchors.topMargin: Theme.spacingHuge
        anchors.bottomMargin: Theme.spacingHuge
        baseFontSize: root.baseFontSize
        horizontalPadding: 16
    }

    // 顶栏 hover 区域：覆盖顶部 96px
    Item {
        id: topZone

        anchors.top: parent.top
        anchors.left: parent.left
        anchors.right: parent.right
        height: 96

        HoverHandler {
            id: topHover
        }

        Rectangle {
            anchors.fill: parent
            opacity: topHover.hovered ? 1 : 0

            Behavior on opacity {
                NumberAnimation { duration: Theme.motionMedium; easing.type: Easing.OutCubic }
            }

            gradient: Gradient {
                GradientStop { position: 0.0; color: Theme.withAlpha(np.scrim, 0.6) }
                GradientStop { position: 1.0; color: Theme.withAlpha(np.scrim, 0.0) }
            }
        }

        Item {
            anchors.fill: parent
            opacity: topHover.hovered ? 1 : 0

            Behavior on opacity {
                NumberAnimation { duration: Theme.motionMedium; easing.type: Easing.OutCubic }
            }

            IconButton {
                anchors.left: parent.left
                anchors.leftMargin: Theme.spacingMd
                anchors.verticalCenter: parent.verticalCenter
                icon: "arrow_back"
                iconSize: 24
                size: 44
                iconColor: np.foreground
                hoverColor: np.hoverFill
                tooltip: "返回播放页"
                onClicked: root.closeRequested()
            }

            RowLayout {
                anchors.right: parent.right
                anchors.rightMargin: Theme.spacingMd
                anchors.verticalCenter: parent.verticalCenter
                spacing: 2

                Text {
                    Layout.alignment: Qt.AlignVCenter
                    text: root.currentScale.toFixed(2) + "x"
                    color: np.foregroundFaint
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.textStyle("labelSmall").size
                }

                IconButton {
                    Layout.alignment: Qt.AlignVCenter
                    icon: "text_decrease"
                    iconSize: 22
                    size: 40
                    iconColor: np.foreground
                    hoverColor: np.hoverFill
                    tooltip: "缩小歌词字号"
                    onClicked: root.adjustScale(-0.1)
                }

                IconButton {
                    Layout.alignment: Qt.AlignVCenter
                    icon: "text_increase"
                    iconSize: 22
                    size: 40
                    iconColor: np.foreground
                    hoverColor: np.hoverFill
                    tooltip: "放大歌词字号"
                    onClicked: root.adjustScale(0.1)
                }
            }
        }
    }

    // 用 Shortcut 而不是焦点 Keys，避免抢走全局快捷键的焦点
    Shortcut {
        enabled: root.visible
        sequence: "Escape"
        onActivated: root.closeRequested()
    }
}
