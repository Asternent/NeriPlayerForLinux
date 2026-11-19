import QtQuick
import QtQuick.Controls
import "../components"
import "../components/player"

// 播放页（Refined Now Playing 风格）：
// 封面模糊全屏背景 + 左封面/信息 + 右滚动双语歌词 + 贴底细进度条与控制条。
// 沉浸模式下 AppShell 隐藏侧栏与底部迷你播放条；页内不再显示「正在播放」标题栏。
// 数据与命令全部来自全局 Player，浮层面板由 components/PlayerOverlays.qml 承载。
Item {
    id: page

    readonly property bool wide: width >= Theme.wideBreakpoint
    readonly property bool hasSong: Player.hasSong

    property bool showFullLyrics: false

    // ---- 控制条自动隐藏 ----
    readonly property bool autoHideControls: Settings.get("nowPlayingAutoHideControls", true) !== false
    readonly property int autoHideDelayMs: {
        const value = Settings.get("nowPlayingAutoHideDelayMs", 2600)
        return Math.max(400, typeof value === "number" ? value : 2600)
    }
    property bool controlsVisible: true
    property bool controlsHovered: false

    // ---- 尺寸 ----
    readonly property real wideLeftWidth: Math.max(320, Math.min(width * 0.45, 560))
    readonly property real wideCoverSize: Math.max(160, Math.min(280,
                                                               Math.min(content.height * 0.52,
                                                                        wideLeftWidth * 0.72)))
    readonly property real narrowCoverSize: Math.max(88, Math.min(132,
                                                                 width * 0.17, content.height * 0.18))
    readonly property real wideLyricsSize: Math.max(18, Math.min(26, content.height * 0.028))
    readonly property real narrowLyricsSize: Math.max(16, Math.min(21, width * 0.026))

    NowPlayingTheme { id: np }

    /** 鼠标有活动：显示控制条并重新计时。 */
    function pokeControls() {
        controlsVisible = true
        if (autoHideControls && !controlsHovered) {
            hideTimer.interval = autoHideDelayMs
            hideTimer.restart()
        }
    }

    Timer {
        id: hideTimer

        repeat: false
        onTriggered: {
            if (!page.controlsHovered)
                page.controlsVisible = false
        }
    }

    onControlsHoveredChanged: {
        if (controlsHovered) {
            controlsVisible = true
            hideTimer.stop()
        } else {
            pokeControls()
        }
    }

    onAutoHideControlsChanged: {
        pokeControls()
    }

    // player 事件 4Hz 快照每次都带 song 对象，直接监听 onSongChanged 会每帧唤醒控制条，
    // 导致自动隐藏永远不触发；这里只在歌曲 key 真正变化时唤醒。
    property string _lastSongKey: ""

    Connections {
        target: Player
        function onSongChanged() {
            const key = Player.song && Player.song.key ? String(Player.song.key) : ""
            if (key === page._lastSongKey)
                return
            page._lastSongKey = key
            page.pokeControls()
        }
    }

    Component.onCompleted: pokeControls()

    // Esc 退出沉浸模式（全屏歌词打开时由它自己的 Esc 处理）
    Shortcut {
        sequence: "Escape"
        enabled: Nav.immersive && !page.showFullLyrics
        onActivated: page.toggleImmersive()
    }

    function toggleImmersive() {
        const w = page.Window.window
        const next = !Nav.immersive
        Nav.setImmersive(next)
        if (w) {
            if (next)
                w.showFullScreen()
            else
                w.showNormal()
        }
        page.pokeControls()
    }

    // ---------------------------------------------------------------- 背景

    NowPlayingBackground {
        anchors.fill: parent
    }

    /**
     * 鼠标活动追踪层：放在内容之下。
     * 内容里的普通 Item 不接收 hover，事件会落到这一层；带 hover 的按钮/歌词行
     * 只覆盖一小块区域，它们通过 activity 信号补上报，不会让控制条误隐藏。
     */
    MouseArea {
        id: mouseTracker

        anchors.fill: parent
        z: 0
        hoverEnabled: true
        acceptedButtons: Qt.LeftButton
        onPressed: page.pokeControls()

        property real lastX: -9999
        property real lastY: -9999

        onPositionChanged: function(mouse) {
            // 歌词/布局变化会让 Qt 给静止指针补发 hover 事件，只把真实位移算作活动
            if (Math.abs(mouse.x - lastX) + Math.abs(mouse.y - lastY) < 2)
                return
            lastX = mouse.x
            lastY = mouse.y
            page.pokeControls()
        }
        // 控制条隐藏时指针会「落回」这一层，entered 不能算活动，否则永远藏不住；
        // 真实移动由 onPositionChanged 的位移阈值判断。
        onEntered: {
        }
    }

    // ---------------------------------------------------------------- 内容

    Item {
        id: content

        anchors.fill: parent
        // 窄屏头部从左上角开始，要给顶部浮动按钮（返回/全屏）让出位置
        anchors.topMargin: page.wide ? Theme.spacingXl : 56
        anchors.bottomMargin: controls.height
        clip: true
        // 全屏歌词打开时隐藏页内内容，避免两层歌词叠在一起
        visible: !page.showFullLyrics

        // 无歌曲
        PlayerEmptyState {
            anchors.centerIn: parent
            visible: !page.hasSong
            icon: "music_note"
            title: "暂无播放"
            hint: "在媒体库或探索中选择一首歌开始播放"
            contentWidth: Math.min(420, content.width - 48)
            iconColor: Theme.withAlpha(np.foreground, 0.4)
            titleColor: np.foregroundDim
            hintColor: Theme.withAlpha(np.foreground, 0.5)
        }

        // ---- 宽屏：左信息 + 右歌词 ----
        Item {
            id: wideLayout

            anchors.fill: parent
            visible: page.hasSong && page.wide

            PlayerCore {
                id: wideLeft

                anchors.left: parent.left
                anchors.top: parent.top
                anchors.bottom: parent.bottom
                width: page.wideLeftWidth
                coverSize: page.wideCoverSize
                onActivity: page.pokeControls()
            }

            LyricsPane {
                anchors.left: wideLeft.right
                anchors.right: parent.right
                anchors.rightMargin: Math.max(Theme.spacingXl, parent.width * 0.055)
                anchors.top: parent.top
                anchors.bottom: parent.bottom
                anchors.topMargin: Theme.spacingMd
                anchors.bottomMargin: Theme.spacingMd
                baseFontSize: page.wideLyricsSize
                horizontalPadding: Theme.spacingSm
                onActivity: page.pokeControls()
            }
        }

        // ---- 窄屏：横排头部 + 下方歌词 ----
        Item {
            id: narrowLayout

            anchors.fill: parent
            visible: page.hasSong && !page.wide

            PlayerCore {
                id: narrowHeader

                anchors.left: parent.left
                anchors.right: parent.right
                anchors.leftMargin: Theme.spacingLg
                anchors.rightMargin: Theme.spacingLg
                anchors.top: parent.top
                height: contentHeight
                compact: true
                coverSize: page.narrowCoverSize
                onActivity: page.pokeControls()
            }

            LyricsPane {
                anchors.left: parent.left
                anchors.right: parent.right
                anchors.rightMargin: Theme.spacingSm
                anchors.top: narrowHeader.bottom
                anchors.topMargin: Theme.spacingLg
                anchors.bottom: parent.bottom
                baseFontSize: page.narrowLyricsSize
                horizontalPadding: Theme.spacingLg
                onActivity: page.pokeControls()
            }
        }
    }

    // ---------------------------------------------------------------- 顶部浮动按钮

    Item {
        id: topBar

        anchors.left: parent.left
        anchors.right: parent.right
        anchors.top: parent.top
        height: 64
        z: 3

        opacity: page.controlsVisible && !page.showFullLyrics ? 1 : 0
        visible: opacity > 0.01

        Behavior on opacity {
            NumberAnimation { duration: Theme.motionMedium; easing.type: Easing.OutCubic }
        }

        HoverHandler {
            id: topBarHover
        }

        Connections {
            target: topBarHover
            function onHoveredChanged() {
                if (topBarHover.hovered)
                    page.pokeControls()
            }
        }

        IconButton {
            anchors.left: parent.left
            anchors.leftMargin: Theme.spacingMd
            anchors.verticalCenter: parent.verticalCenter
            icon: "keyboard_arrow_down"
            iconSize: 26
            size: 44
            iconColor: np.foregroundDim
            hoverColor: np.hoverFill
            tooltip: "收起播放页（Esc 退出沉浸）"
            onClicked: Nav.back()
        }

        // 右上角：全屏/沉浸切换（随控制条一起 hover 淡入）
        IconButton {
            anchors.right: parent.right
            anchors.rightMargin: Theme.spacingMd
            anchors.verticalCenter: parent.verticalCenter
            icon: Nav.immersive ? "fullscreen_exit" : "fullscreen"
            iconSize: 22
            size: 44
            iconColor: np.foregroundDim
            hoverColor: np.hoverFill
            tooltip: Nav.immersive ? "退出全屏沉浸 (Esc)" : "全屏沉浸模式"
            onClicked: page.toggleImmersive()
        }
    }

    // ---------------------------------------------------------------- 底部控制条

    NowPlayingControls {
        id: controls

        anchors.left: parent.left
        anchors.right: parent.right
        anchors.bottom: parent.bottom
        compact: !page.wide
        visible: !page.showFullLyrics
        controlsVisible: page.controlsVisible
        secondaryVisible: page.controlsVisible && page.controlsHovered
        onActivity: {
            page.pokeControls()
        }
        onLyricsRequested: page.showFullLyrics = true
        onImmersiveRequested: page.toggleImmersive()

        Connections {
            target: controls
            function onHoveredChanged() {
                page.controlsHovered = controls.hovered
            }
        }
    }

    // ---------------------------------------------------------------- 全屏歌词

    FullScreenLyrics {
        id: fullLyrics

        anchors.fill: parent
        z: 20
        visible: page.showFullLyrics
        onCloseRequested: page.showFullLyrics = false
    }
}
