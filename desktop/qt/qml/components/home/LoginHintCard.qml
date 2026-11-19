import QtQuick
import ".."

// 首页的登录引导卡：未登录时后端会隐藏每日推荐 / 私人 FM / 每日推荐歌单，
// 并把原因写进 online.home 的 errors。这里用一张温和的提示卡替代红色错误卡。
Item {
    id: root

    property string message: ""
    property string icon: "person"

    signal loginClicked()

    implicitHeight: card.height

    Rectangle {
        id: card

        anchors.left: parent.left
        anchors.right: parent.right
        height: content.implicitHeight + Theme.spacingLg * 2
        radius: Theme.radiusLg
        color: Theme.surfaceContainerLow

        Row {
            id: content

            anchors.left: parent.left
            anchors.right: parent.right
            anchors.verticalCenter: parent.verticalCenter
            anchors.leftMargin: Theme.spacingLg
            anchors.rightMargin: Theme.spacingLg
            spacing: Theme.spacingMd

            Rectangle {
                width: 40
                height: 40
                radius: Theme.radiusFull
                color: Theme.surfaceContainerHighest

                NeriIcon {
                    anchors.centerIn: parent
                    name: root.icon
                    iconSize: 22
                    color: Theme.primary
                }
            }

            Column {
                width: content.width - 40 - Theme.spacingMd - loginButton.width - Theme.spacingMd
                anchors.verticalCenter: parent.verticalCenter
                spacing: 2

                Text {
                    width: parent.width
                    text: "登录网易云音乐，解锁更多推荐"
                    elide: Text.ElideRight
                    color: Theme.onSurfaceColor
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.textStyle("titleSmall").size
                    font.weight: Font.DemiBold
                }

                Text {
                    width: parent.width
                    text: root.message !== "" ? root.message : "每日推荐、私人 FM、每日推荐歌单需要登录后展示"
                    elide: Text.ElideRight
                    color: Theme.onSurfaceVariantColor
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.textStyle("labelSmall").size
                }
            }

            Rectangle {
                id: loginButton

                anchors.verticalCenter: parent.verticalCenter
                width: loginLabel.implicitWidth + Theme.spacingLg * 2
                height: 34
                radius: Theme.radiusFull
                color: loginHover.hovered ? Theme.primaryContainer : Theme.surfaceContainerHighest

                Behavior on color {
                    ColorAnimation { duration: Theme.motionShort }
                }

                Text {
                    id: loginLabel

                    anchors.centerIn: parent
                    text: "去登录"
                    color: Theme.onSurfaceColor
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.textStyle("labelLarge").size
                    font.weight: Font.Medium
                }

                HoverHandler {
                    id: loginHover
                    cursorShape: Qt.PointingHandCursor
                }

                MouseArea {
                    anchors.fill: parent
                    onClicked: root.loginClicked()
                }
            }
        }
    }
}
