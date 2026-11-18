import QtQuick
import QtQuick.Layouts
import "../"

// 音量面板：大号滑杆 + 百分比。
PanelScaffold {
    id: panel

    title: "音量"
    panelWidth: 380

    Item {
        anchors.fill: parent

        Column {
            anchors.centerIn: parent
            width: parent.width - 40
            spacing: 18

            RowLayout {
                width: parent.width
                spacing: 12

                NeriIcon {
                    name: panel.volumeIcon
                    iconSize: 28
                    color: Theme.onSurfaceColor
                }

                Text {
                    Layout.fillWidth: true
                    text: Math.round(Player.volume * 100) + "%"
                    color: Theme.primary
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.textStyle("headlineMedium").size
                    font.weight: Font.DemiBold
                }

                Text {
                    text: "音量"
                    color: Theme.onSurfaceVariantColor
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.textStyle("labelMedium").size
                }
            }

            PlayerSlider {
                width: parent.width
                height: 56
                from: 0
                to: 1
                value: Player.volume
                onMoved: Player.setVolume(value)
            }

            Text {
                width: parent.width
                horizontalAlignment: Text.AlignHCenter
                text: "拖动滑块调节音量"
                color: Theme.withAlpha(Theme.onSurfaceVariantColor, 0.75)
                font.family: Theme.fontFamily
                font.pixelSize: Theme.textStyle("bodySmall").size
            }
        }
    }

    readonly property string volumeIcon: {
        if (Player.volume <= 0.001)
            return "volume_off"
        if (Player.volume < 0.5)
            return "volume_down"
        return "volume_up"
    }
}
