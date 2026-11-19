import QtQuick
import QtQuick.Layouts

// 页面占位骨架：迁移中的页面先复用这个，保证导航/截图流程可用。
Item {
    id: page

    property string title: ""
    property string subtitle: ""
    property string icon: "widgets"

    ColumnLayout {
        anchors.centerIn: parent
        width: Math.min(parent.width - 48, 520)
        spacing: 12

        NeriIcon {
            Layout.alignment: Qt.AlignHCenter
            name: page.icon
            iconSize: 54
            color: Theme.onSurfaceVariantColor
        }

        Text {
            Layout.alignment: Qt.AlignHCenter
            Layout.fillWidth: true
            text: page.title
            horizontalAlignment: Text.AlignHCenter
            color: Theme.onSurfaceColor
            font.family: Theme.fontFamily
            font.pixelSize: 20
            font.weight: Font.Medium
        }

        Text {
            Layout.alignment: Qt.AlignHCenter
            Layout.fillWidth: true
            text: page.subtitle
            wrapMode: Text.Wrap
            horizontalAlignment: Text.AlignHCenter
            color: Theme.onSurfaceVariantColor
            font.family: Theme.fontFamily
            font.pixelSize: 13
        }

        Text {
            Layout.alignment: Qt.AlignHCenter
            Layout.fillWidth: true
            text: Backend.connected
                  ? ("后端已连接 · " + (Player.hasSong ? "正在播放 " + Player.title : "当前无播放"))
                  : "正在等待播放后端启动…"
            horizontalAlignment: Text.AlignHCenter
            color: Backend.connected ? Theme.primary : Theme.error
            font.family: Theme.fontFamily
            font.pixelSize: 12
        }
    }
}
