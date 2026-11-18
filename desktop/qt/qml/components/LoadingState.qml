import QtQuick

// 加载中占位。
Item {
    id: root

    property string text: "加载中…"

    implicitHeight: 64

    Column {
        anchors.centerIn: parent
        spacing: 8

        Row {
            anchors.horizontalCenter: parent.horizontalCenter
            spacing: 6
            Repeater {
                model: 3
                delegate: Rectangle {
                    width: 7
                    height: 7
                    radius: 4
                    color: Theme.primary
                    opacity: 0.4
                    SequentialAnimation on opacity {
                        loops: Animation.Infinite
                        PauseAnimation { duration: index * 160 }
                        NumberAnimation { from: 0.25; to: 1; duration: 420 }
                        NumberAnimation { from: 1; to: 0.25; duration: 420 }
                        PauseAnimation { duration: (2 - index) * 160 }
                    }
                }
            }
        }

        Text {
            anchors.horizontalCenter: parent.horizontalCenter
            text: root.text
            color: Theme.onSurfaceVariantColor
            font.family: Theme.fontFamily
            font.pixelSize: 12
        }
    }
}
