import QtQuick
import ".."

// 扫码登录面板（占位：后续实现网易云/哔哩哔哩二维码登录）。
Item {
    id: panel

    Rectangle {
        anchors.fill: parent
        color: Theme.withAlpha(Theme.scrim, 0.45)
        MouseArea {
            anchors.fill: parent
            onClicked: Nav.closePanel()
        }
    }

    Rectangle {
        anchors.centerIn: parent
        width: 360
        height: 280
        radius: 24
        color: Theme.surfaceContainerLow

        Column {
            anchors.centerIn: parent
            spacing: 8
            Text {
                anchors.horizontalCenter: parent.horizontalCenter
                text: Nav.loginSource === "BILIBILI" ? "哔哩哔哩扫码登录" : "网易云扫码登录"
                color: Theme.onSurfaceColor
                font.family: Theme.fontFamily
                font.pixelSize: 18
            }
            Text {
                anchors.horizontalCenter: parent.horizontalCenter
                text: "登录面板迁移中"
                color: Theme.onSurfaceVariantColor
                font.family: Theme.fontFamily
                font.pixelSize: 13
            }
        }
    }
}
