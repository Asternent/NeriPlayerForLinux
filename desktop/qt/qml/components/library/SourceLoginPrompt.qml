import QtQuick
import ".."

// 在线音源未登录 / 登录态失效时的引导卡。
// 只负责展示，点击主按钮后由父级调用 Nav.openLoginPanel(source)（登录面板读取 Nav.loginSource）。
//
// 属性：source（NETEASE/BILIBILI）、relogin（登录态失效时换成「重新登录」文案）
// 信号：loginClicked()
Item {
    id: root

    property string source: "NETEASE"
    property bool relogin: false

    signal loginClicked()

    readonly property bool bili: root.source === "BILIBILI"
    readonly property string sourceLabel: root.bili ? "哔哩哔哩" : "网易云音乐"
    readonly property string promptText: root.bili ? "登录后可浏览收藏夹" : "登录后可浏览我的歌单"
    readonly property string titleText: root.relogin
                                       ? "登录状态已失效"
                                       : "登录" + root.sourceLabel
    readonly property string hintText: root.relogin
                                      ? "账号信息已过期或无法读取，请重新扫码登录后再刷新"
                                      : "使用手机 " + root.sourceLabel + " 扫码，登录后即可同步账号内容"

    Rectangle {
        id: card

        anchors.horizontalCenter: parent.horizontalCenter
        anchors.top: parent.top
        anchors.topMargin: Theme.spacingHuge
        width: Math.min(root.width - Theme.spacingXl * 2, 420)
        height: column.implicitHeight + Theme.spacingXl * 2
        radius: Theme.radiusXl
        color: Theme.surfaceContainerLow

        Column {
            id: column

            anchors.left: parent.left
            anchors.right: parent.right
            anchors.verticalCenter: parent.verticalCenter
            anchors.leftMargin: Theme.spacingXl
            anchors.rightMargin: Theme.spacingXl
            spacing: Theme.spacingMd

            Rectangle {
                anchors.horizontalCenter: parent.horizontalCenter
                width: 64
                height: 64
                radius: Theme.radiusFull
                color: Theme.surfaceContainerHighest

                NeriIcon {
                    anchors.centerIn: parent
                    name: root.bili ? "bookmark_border" : "person"
                    iconSize: 30
                    color: Theme.primary
                }
            }

            Text {
                width: parent.width
                text: root.titleText
                horizontalAlignment: Text.AlignHCenter
                wrapMode: Text.Wrap
                color: Theme.onSurfaceColor
                font.family: Theme.fontFamily
                font.pixelSize: Theme.textStyle("titleMedium").size
                font.weight: Font.DemiBold
            }

            Text {
                width: parent.width
                text: root.promptText
                horizontalAlignment: Text.AlignHCenter
                wrapMode: Text.Wrap
                color: Theme.onSurfaceColor
                font.family: Theme.fontFamily
                font.pixelSize: Theme.textStyle("bodyMedium").size
            }

            Text {
                width: parent.width
                text: root.hintText
                horizontalAlignment: Text.AlignHCenter
                wrapMode: Text.Wrap
                color: Theme.withAlpha(Theme.onSurfaceVariantColor, 0.85)
                font.family: Theme.fontFamily
                font.pixelSize: Theme.textStyle("bodySmall").size
            }

            Item {
                width: parent.width
                height: loginButton.implicitHeight

                LibraryButton {
                    id: loginButton

                    anchors.horizontalCenter: parent.horizontalCenter
                    text: root.relogin ? "重新扫码登录" : "扫码登录"
                    icon: "login"
                    filled: true
                    onClicked: root.loginClicked()
                }
            }
        }
    }
}
