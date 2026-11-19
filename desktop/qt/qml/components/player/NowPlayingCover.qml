import QtQuick
import QtQuick.Effects
import ".."

// 播放页大封面：形状可配（CIRCLE / ROUNDED / SQUARE）+ 强调色光晕 + 柔和投影 + 缓冲遮罩。
// 光晕颜色取自当前封面种子（Player.coverSeed），让封面与模糊背景属于同一色相。
Item {
    id: root

    property real coverSize: 280

    signal activity()

    readonly property string shape: {
        const s = Settings.get("nowPlayingCoverShape", "ROUNDED")
        return (s === "CIRCLE" || s === "SQUARE") ? s : "ROUNDED"
    }
    readonly property int shapeRadius: {
        if (shape === "CIRCLE")
            return Math.round(coverSize / 2)
        if (shape === "SQUARE")
            return Theme.radiusXs
        return Math.round(coverSize * 0.082)
    }

    implicitWidth: coverSize
    implicitHeight: coverSize

    NowPlayingTheme { id: np }

    // ---- 强调色光晕 ----
    Item {
        anchors.fill: parent
        anchors.margins: -coverSize * 0.12
        z: -2
        opacity: 0.62

        Rectangle {
            id: glowRect

            anchors.fill: parent
            radius: width / 2
            color: Theme.withAlpha(np.accent, 0.45)
            visible: false
        }

        MultiEffect {
            anchors.fill: glowRect
            source: glowRect
            blurEnabled: true
            blur: 1.0
            blurMax: 64
        }
    }

    // ---- 柔和投影：矩形 + 模糊，比硬阴影更接近参考图里的浮起感 ----
    Item {
        anchors.fill: parent
        z: -1

        Rectangle {
            id: shadowRect

            anchors.fill: parent
            anchors.topMargin: coverSize * 0.06
            radius: root.shapeRadius
            color: Theme.withAlpha(np.scrim, 0.45)
            visible: false
        }

        MultiEffect {
            anchors.fill: shadowRect
            source: shadowRect
            blurEnabled: true
            blur: 1.0
            blurMax: 36
        }
    }

    CoverArt {
        id: cover

        anchors.fill: parent
        radius: root.shapeRadius
        placeholderIconSize: Math.max(28, root.coverSize * 0.22)
        path: Player.song && Player.song.artworkPath ? Player.song.artworkPath : ""
        url: Player.song && Player.song.artworkUrl ? Player.song.artworkUrl : ""

    }

    // ---- 缓冲/准备中的半透明遮罩 ----
    Rectangle {
        id: bufferingScrim

        anchors.fill: parent
        radius: root.shapeRadius
        visible: Player.buffering || Player.state === "PREPARING"
        color: Theme.withAlpha(np.scrim, 0.4)

        NeriIcon {
            anchors.centerIn: parent
            name: "progress_activity"
            iconSize: Math.max(28, root.coverSize * 0.16)
            color: np.foreground

            RotationAnimation on rotation {
                from: 0
                to: 360
                duration: 900
                loops: Animation.Infinite
                running: bufferingScrim.visible
            }
        }
    }

    HoverHandler {
        id: coverHover
        cursorShape: Qt.ArrowCursor
    }

    // 封面盖住了页面底层的鼠标跟踪层，这里自己上报「真实移动」，
    // 否则控制条隐藏后鼠标在封面上移动无法唤回。
    MouseArea {
        anchors.fill: parent
        acceptedButtons: Qt.NoButton
        hoverEnabled: true

        property real lastX: -9999
        property real lastY: -9999

        onPositionChanged: function(mouse) {
            if (Math.abs(mouse.x - lastX) + Math.abs(mouse.y - lastY) < 2)
                return
            lastX = mouse.x
            lastY = mouse.y
            root.activity()
        }
    }

    Connections {
        target: coverHover
        function onHoveredChanged() {
            if (coverHover.hovered)
                root.activity()
        }
    }
}
