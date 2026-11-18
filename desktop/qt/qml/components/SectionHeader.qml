import QtQuick

// 区块标题：左侧图标 + 标题，右侧可选操作文字/按钮。
Item {
    id: header

    property string title: ""
    property string icon: ""
    property string actionText: ""
    property bool compact: false

    signal actionClicked()

    implicitHeight: compact ? 36 : 44

    Row {
        anchors.fill: parent
        anchors.leftMargin: 16
        anchors.rightMargin: 16
        spacing: 8

        NeriIcon {
            anchors.verticalCenter: parent.verticalCenter
            visible: header.icon !== ""
            name: header.icon
            iconSize: 20
            color: Theme.primary
        }

        Text {
            anchors.verticalCenter: parent.verticalCenter
            text: header.title
            color: Theme.onSurfaceColor
            font.family: Theme.fontFamily
            font.pixelSize: 16
            font.weight: Font.DemiBold
        }
    }

    Text {
        anchors.verticalCenter: parent.verticalCenter
        anchors.right: parent.right
        anchors.rightMargin: 16
        visible: header.actionText !== ""
        text: header.actionText
        color: Theme.primary
        font.family: Theme.fontFamily
        font.pixelSize: 13
        font.weight: Font.Medium

        HoverHandler {
            cursorShape: Qt.PointingHandCursor
        }

        MouseArea {
            anchors.fill: parent
            anchors.margins: -8
            onClicked: header.actionClicked()
        }
    }
}
