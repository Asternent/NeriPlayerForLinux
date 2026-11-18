import QtQuick
import ".."

// 加载中状态（共享组件）。旋转的 Material Symbols 进度图标 + 一行说明文字。
Item {
    id: root

    property string text: "正在加载…"
    property bool running: true

    implicitWidth: parent ? parent.width : 320
    implicitHeight: 160

    Column {
        anchors.centerIn: parent
        width: Math.min(root.width - 48, 300)
        spacing: Theme.spacingMd

        Item {
            width: parent.width
            height: 30

            NeriIcon {
                id: spinner

                anchors.centerIn: parent
                name: "progress_activity"
                iconSize: 30
                color: Theme.primary

                RotationAnimation on rotation {
                    running: root.running
                    loops: Animation.Infinite
                    from: 0
                    to: 360
                    duration: 900
                }
            }
        }

        Text {
            width: parent.width
            text: root.text
            horizontalAlignment: Text.AlignHCenter
            color: Theme.onSurfaceVariantColor
            font.family: Theme.fontFamily
            font.pixelSize: Theme.textStyle("bodyMedium").size
        }
    }
}
