import QtQuick
import QtQuick.Layouts
import "../"

// 睡眠定时器面板：15/30/60/90 分钟、剩余时间、取消、播完当前停止。
PanelScaffold {
    id: panel

    title: "睡眠定时器"
    panelWidth: 380

    readonly property bool timerActive: Player.sleepTimer !== null && Player.sleepTimer !== undefined
                                        && Player.sleepTimer.remainingSeconds > 0
    readonly property bool stopAfterCurrent: Player.sleepTimer !== null && Player.sleepTimer !== undefined
                                             && Player.sleepTimer.stopAfterCurrent === true
    readonly property int defaultMinutes: {
        const value = Settings.get("sleepTimerMinutes", 30)
        return (typeof value === "number" && value > 0) ? value : 30
    }
    readonly property var presetMinutes: [15, 30, 60, 90]

    function formatRemaining(seconds) {
        const total = Math.max(0, Math.round(seconds || 0))
        const minutes = Math.floor(total / 60)
        const rest = total % 60
        return minutes + ":" + (rest < 10 ? "0" + rest : "" + rest)
    }

    Item {
        anchors.fill: parent

        Column {
            id: content

            anchors.top: parent.top
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.topMargin: 8
            anchors.leftMargin: 20
            anchors.rightMargin: 20
            spacing: 16

            // ---- 倒计时进行中 ----
            Column {
                width: content.width
                visible: panel.timerActive
                spacing: 12

                Text {
                    width: parent.width
                    text: "剩余 " + panel.formatRemaining(Player.sleepTimer.remainingSeconds)
                    color: Theme.primary
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.textStyle("headlineSmall").size
                    font.weight: Font.DemiBold
                }

                Text {
                    width: parent.width
                    text: "倒计时结束后暂停播放"
                    color: Theme.onSurfaceVariantColor
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.textStyle("bodyMedium").size
                }

                PanelButton {
                    width: content.width
                    text: "取消定时器"
                    filled: true
                    onClicked: Player.cancelSleepTimer()
                }
            }

            // ---- 播完当前歌曲停止 ----
            Column {
                width: content.width
                visible: !panel.timerActive && panel.stopAfterCurrent
                spacing: 12

                Text {
                    width: parent.width
                    text: "播完当前歌曲后停止"
                    color: Theme.onSurfaceColor
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.textStyle("titleMedium").size
                    font.weight: Font.DemiBold
                }

                Text {
                    width: parent.width
                    text: "当前歌曲播放结束后将暂停播放"
                    color: Theme.onSurfaceVariantColor
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.textStyle("bodySmall").size
                }

                PanelButton {
                    width: content.width
                    text: "取消定时器"
                    filled: true
                    onClicked: Player.cancelSleepTimer()
                }
            }

            // ---- 空闲状态 ----
            Column {
                width: content.width
                visible: !panel.timerActive && !panel.stopAfterCurrent
                spacing: 12

                Text {
                    width: parent.width
                    text: "倒计时结束后暂停播放"
                    color: Theme.onSurfaceColor
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.textStyle("bodyMedium").size
                }

                Row {
                    id: minutesRow

                    width: content.width
                    spacing: 8

                    Repeater {
                        model: panel.presetMinutes

                        delegate: PanelButton {
                            required property var modelData

                            width: (minutesRow.width - 24) / 4
                            text: modelData + " 分"
                            outlined: true
                            onClicked: Player.startSleepTimer(modelData)
                        }
                    }
                }

                Text {
                    width: parent.width
                    text: "默认时长：" + panel.defaultMinutes + " 分钟"
                    color: Theme.withAlpha(Theme.onSurfaceVariantColor, 0.75)
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.textStyle("labelSmall").size
                }

                PanelButton {
                    width: content.width
                    text: "播放完当前歌曲后停止"
                    outlined: true
                    onClicked: Player.stopAfterCurrentSong()
                }
            }
        }
    }
}
