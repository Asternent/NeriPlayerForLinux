import QtQuick
import "../"

// 播放页进度条：可拖动 seek，拖动过程中显示拖拽位置，松手才真正 seek。
Item {
    id: root

    property real positionMs: Player.positionMs
    property real durationMs: Player.durationMs
    readonly property bool seekEnabled: durationMs > 0

    property bool dragging: false
    property real dragValue: 0

    readonly property real progress: durationMs > 0
                                    ? Math.max(0, Math.min(1, positionMs / durationMs))
                                    : 0
    readonly property real shownPositionMs: dragging ? dragValue * durationMs : positionMs

    implicitHeight: slider.height + labels.height

    function formatTime(ms) {
        const total = Math.max(0, Math.round((ms || 0) / 1000))
        const hours = Math.floor(total / 3600)
        const minutes = Math.floor((total % 3600) / 60)
        const seconds = total % 60
        const ss = seconds < 10 ? "0" + seconds : "" + seconds
        if (hours > 0) {
            const mm = minutes < 10 ? "0" + minutes : "" + minutes
            return hours + ":" + mm + ":" + ss
        }
        return minutes + ":" + ss
    }

    PlayerSlider {
        id: slider

        width: parent.width
        from: 0
        to: 1
        enabledSlider: root.seekEnabled
        value: root.dragging ? root.dragValue : root.progress

        onMoved: {
            root.dragging = true
            root.dragValue = value
        }

        onReleased: {
            if (root.dragging) {
                Player.seekTo(root.dragValue * root.durationMs)
                root.dragging = false
            }
        }
    }

    Item {
        id: labels

        anchors.top: slider.bottom
        width: parent.width
        height: 18

        Text {
            anchors.left: parent.left
            anchors.leftMargin: 4
            anchors.verticalCenter: parent.verticalCenter
            text: root.formatTime(root.shownPositionMs)
            color: Theme.onSurfaceVariantColor
            font.family: Theme.fontFamily
            font.pixelSize: Theme.textStyle("labelMedium").size
            font.weight: Theme.textStyle("labelMedium").weight
        }

        Text {
            anchors.right: parent.right
            anchors.rightMargin: 4
            anchors.verticalCenter: parent.verticalCenter
            text: root.formatTime(root.durationMs)
            color: Theme.onSurfaceVariantColor
            font.family: Theme.fontFamily
            font.pixelSize: Theme.textStyle("labelMedium").size
            font.weight: Theme.textStyle("labelMedium").weight
        }
    }
}
