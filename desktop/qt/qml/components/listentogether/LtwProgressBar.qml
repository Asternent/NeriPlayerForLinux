import QtQuick

// 一起听进度条：显示房间权威进度；控制者可点击/拖动来 seek（控制者会由后端上报 SEEK）。
Item {
    id: root

    property real positionMs: 0
    property real durationMs: 0
    property bool seekEnabled: false

    signal seekRequested(real ratio)

    implicitHeight: 24

    readonly property real ratio: durationMs > 0
                                  ? Math.max(0, Math.min(1, positionMs / durationMs)) : 0

    Rectangle {
        id: track

        anchors.verticalCenter: parent.verticalCenter
        width: parent.width
        height: 4
        radius: 2
        color: Theme.surfaceContainerHighest
    }

    Rectangle {
        width: Math.max(0, track.width * root.ratio)
        height: track.height
        radius: track.radius
        anchors.verticalCenter: track.verticalCenter
        color: root.seekEnabled ? Theme.primary
                                : Theme.withAlpha(Theme.primary, 0.55)

        Behavior on width {
            NumberAnimation {
                duration: 160
                easing.type: Easing.OutCubic
            }
        }
    }

    Rectangle {
        visible: root.seekEnabled
        width: 12
        height: 12
        radius: 6
        color: Theme.primary
        x: Math.max(0, Math.min(root.width - width, track.width * root.ratio - width / 2))
        anchors.verticalCenter: track.verticalCenter
    }

    MouseArea {
        id: mouse

        anchors.fill: parent
        enabled: root.seekEnabled && root.durationMs > 0
        hoverEnabled: true
        cursorShape: root.seekEnabled ? Qt.PointingHandCursor : Qt.ArrowCursor

        function ratioAt(x) {
            return root.width > 0 ? Math.max(0, Math.min(1, x / root.width)) : 0
        }

        onPressed: function(mouse) {
            if (root.seekEnabled)
                root.seekRequested(ratioAt(mouse.x))
        }

        onPositionChanged: function(mouse) {
            if (mouse.pressed && root.seekEnabled)
                root.seekRequested(ratioAt(mouse.x))
        }
    }
}
