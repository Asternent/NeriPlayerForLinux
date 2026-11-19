import QtQuick

// 播放页贴底细进度条：常态 3px，hover / 拖动时加粗到 6px，拖动时在指针上方显示目标时间。
// 不占满宽度、不套圆角，直接压在窗口最底部（参考 Refined Now Playing 的底边进度线）。
Item {
    id: root

    property bool dragging: false
    property real dragValue: 0
    /** 控制条整体 hover 时也加粗轨道（指针不一定正好落在进度条上）。 */
    property bool forceExpanded: false

    readonly property bool seekEnabled: Player.durationMs > 0
    readonly property bool hovered: trackMouse.containsMouse
    readonly property bool expanded: hovered || dragging || forceExpanded
    // 后端进度只有 4Hz，用全局平滑值驱动，进度条才不会一顿一顿
    readonly property real progress: Player.durationMs > 0
                                     ? Math.max(0, Math.min(1, Player.smoothPositionMs / Player.durationMs))
                                     : 0
    readonly property real shownRatio: dragging ? dragValue : progress

    implicitHeight: 20

    NowPlayingTheme { id: np }

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

    // ---- 轨道 ----
    Rectangle {
        id: track

        anchors.left: parent.left
        anchors.right: parent.right
        anchors.bottom: parent.bottom
        height: root.expanded ? 6 : 3
        color: Theme.withAlpha(np.foreground, root.expanded ? 0.24 : 0.18)

        Behavior on height {
            NumberAnimation { duration: Theme.motionShort; easing.type: Easing.OutCubic }
        }

        Rectangle {
            id: fill

            anchors.left: parent.left
            anchors.top: parent.top
            anchors.bottom: parent.bottom
            width: track.width * root.shownRatio
            color: np.accent
        }
    }

    // ---- hover / 拖动时的小圆点 ----
    // 垂直方向跟轨道中心对齐：轨道展开时从 3px 长到 6px，贴底会让圆点看起来偏下。
    Rectangle {
        visible: root.expanded
        x: Math.max(0, Math.min(track.width - width, track.width * root.shownRatio - width / 2))
        anchors.verticalCenter: track.verticalCenter
        width: 12
        height: 12
        radius: 6
        color: np.accent

        Behavior on x {
            enabled: !root.dragging
            NumberAnimation { duration: Theme.motionShort }
        }
    }

    // ---- 拖动时的时间气泡 ----
    Rectangle {
        id: dragBubble

        visible: root.dragging
        width: bubbleText.implicitWidth + 20
        height: 26
        radius: 13
        color: Theme.withAlpha(np.scrim, 0.8)
        border.width: 1
        border.color: Theme.withAlpha(np.foreground, 0.18)
        x: Math.max(0, Math.min(parent.width - width,
                                parent.width * root.shownRatio - width / 2))
        // 气泡相对轨道顶部留固定间距，不随轨道粗细变化
        anchors.bottom: track.top
        anchors.bottomMargin: 24

        Text {
            id: bubbleText

            anchors.centerIn: parent
            text: root.formatTime(root.dragging ? root.dragValue * Player.durationMs
                                               : Player.smoothPositionMs)
            color: np.foreground
            font.family: Theme.fontFamily
            font.pixelSize: Theme.textStyle("labelMedium").size
            font.weight: Theme.textStyle("labelMedium").weight
        }
    }

    function _updateFromX(mouseX) {
        if (!root.seekEnabled)
            return
        root.dragValue = Math.max(0, Math.min(1, mouseX / Math.max(1, width)))
    }

    MouseArea {
        id: trackMouse

        anchors.fill: parent
        enabled: root.seekEnabled
        hoverEnabled: true
        cursorShape: Qt.PointingHandCursor

        onPressed: function(mouse) {
            root.dragging = true
            root._updateFromX(mouse.x)
        }
        onPositionChanged: function(mouse) {
            if (root.dragging)
                root._updateFromX(mouse.x)
        }
        onReleased: function(mouse) {
            if (!root.dragging)
                return
            root._updateFromX(mouse.x)
            Player.seekTo(root.dragValue * Player.durationMs)
            root.dragging = false
        }
        onCanceled: root.dragging = false
    }
}
