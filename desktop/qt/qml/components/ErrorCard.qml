import QtQuick

// 通用错误卡片：一行文案 + 重试按钮。
Rectangle {
    id: card

    property string message: "出错了"

    signal retry()

    implicitHeight: 48
    radius: 16
    color: Theme.errorContainer

    Row {
        anchors.fill: parent
        anchors.leftMargin: 14
        anchors.rightMargin: 14
        spacing: 8

        NeriIcon {
            anchors.verticalCenter: parent.verticalCenter
            name: "error"
            iconSize: 20
            color: Theme.onErrorContainerColor
        }

        Text {
            anchors.verticalCenter: parent.verticalCenter
            width: Math.max(0, parent.width - 120)
            text: card.message
            elide: Text.ElideRight
            color: Theme.onErrorContainerColor
            font.family: Theme.fontFamily
            font.pixelSize: 13
        }

        Text {
            anchors.verticalCenter: parent.verticalCenter
            text: "重试"
            color: Theme.onErrorContainerColor
            font.family: Theme.fontFamily
            font.pixelSize: 13
            font.weight: Font.Medium

            HoverHandler {
                cursorShape: Qt.PointingHandCursor
            }

            MouseArea {
                anchors.fill: parent
                anchors.margins: -10
                onClicked: card.retry()
            }
        }
    }
}
