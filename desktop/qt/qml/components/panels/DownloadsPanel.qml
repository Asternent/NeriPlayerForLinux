import QtQuick
import ".."

// 下载管理面板（占位：后续实现完整下载队列/已完成列表）。
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
        anchors.right: parent.right
        anchors.top: parent.top
        anchors.bottom: parent.bottom
        anchors.margins: 12
        width: 420
        radius: 24
        color: Theme.surfaceContainerLow

        Column {
            anchors.centerIn: parent
            spacing: 8
            Text {
                anchors.horizontalCenter: parent.horizontalCenter
                text: "下载管理"
                color: Theme.onSurfaceColor
                font.family: Theme.fontFamily
                font.pixelSize: 18
            }
            Text {
                anchors.horizontalCenter: parent.horizontalCenter
                text: "下载面板迁移中"
                color: Theme.onSurfaceVariantColor
                font.family: Theme.fontFamily
                font.pixelSize: 13
            }
        }
    }
}
