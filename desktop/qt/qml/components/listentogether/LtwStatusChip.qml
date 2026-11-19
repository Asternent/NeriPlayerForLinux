import QtQuick

// 一起听连接状态胶囊：颜色 + 文案随连接/房态变化。
Item {
    id: root

    /** disconnected / connecting / connected / reconnecting / error */
    property string connection: "disconnected"
    property bool inRoom: false
    property string roomStatus: ""
    /** 非空时覆盖自动文案。 */
    property string overrideText: ""

    readonly property string label: {
        if (root.overrideText !== "")
            return root.overrideText
        if (root.roomStatus === "controller_offline")
            return "控制者离线"
        switch (root.connection) {
        case "connected":
            return root.inRoom ? "已连接" : "服务端可用"
        case "connecting":
            return "连接中"
        case "reconnecting":
            return "重连中"
        case "error":
            return "连接错误"
        default:
            return root.inRoom ? "已断开" : "未连接"
        }
    }

    readonly property color tint: {
        if (root.roomStatus === "controller_offline")
            return Theme.tertiary
        if (root.connection === "connected")
            return Theme.primary
        if (root.connection === "connecting" || root.connection === "reconnecting")
            return Theme.tertiary
        if (root.connection === "error")
            return Theme.error
        return Theme.onSurfaceVariantColor
    }

    implicitWidth: chipRow.implicitWidth + 20
    implicitHeight: 28

    Rectangle {
        anchors.fill: parent
        radius: height / 2
        color: Theme.withAlpha(root.tint, 0.12)
    }

    Row {
        id: chipRow

        anchors.centerIn: parent
        spacing: 6

        Rectangle {
            width: 8
            height: 8
            radius: 4
            color: root.tint
            anchors.verticalCenter: parent.verticalCenter
        }

        Text {
            text: root.label
            color: root.tint
            font.family: Theme.fontFamily
            font.pixelSize: Theme.textStyle("labelMedium").size
            font.weight: Font.Medium
            anchors.verticalCenter: parent.verticalCenter
        }
    }
}
