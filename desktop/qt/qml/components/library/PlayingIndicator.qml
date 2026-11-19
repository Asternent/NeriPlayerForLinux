import QtQuick
import ".."

// 正在播放指示器：三根跳动的音量条（对应旧 Compose 的 PlayingIndicator）。
// 只在「当前歌曲且正在播放」时显示；暂停时页面应改用 NeriIcon("pause")。
Item {
    id: root

    property bool playing: true
    property color color: Theme.primary
    property real barWidth: 3
    property real barSpacing: 2
    property real barHeight: 16

    implicitWidth: barWidth * 3 + barSpacing * 2
    implicitHeight: barHeight

    Row {
        anchors.centerIn: parent
        spacing: root.barSpacing

        Repeater {
            model: 3

            delegate: Item {
                required property int index

                width: root.barWidth
                height: root.barHeight

                Rectangle {
                    id: bar

                    anchors.bottom: parent.bottom
                    width: parent.width
                    radius: width / 2
                    color: root.color
                    height: parent.height * 0.55

                    SequentialAnimation on height {
                        running: root.playing
                        loops: Animation.Infinite
                        NumberAnimation {
                            to: parent.height * 0.95
                            duration: 300 + index * 140
                            easing.type: Easing.InOutSine
                        }
                        NumberAnimation {
                            to: parent.height * 0.35
                            duration: 300 + index * 140
                            easing.type: Easing.InOutSine
                        }
                    }
                }
            }
        }
    }
}
