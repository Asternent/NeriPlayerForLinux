import QtQuick
import QtQuick.Layouts
import ".."

// 播放页底部控制条（Refined Now Playing 风格）：
// 贴底细进度条 + 红心/播放暂停居中/上一首下一首/时间常驻，
// 其余按钮（随机/循环/队列/音效/音量/睡眠/下载/评论/一起听/全屏歌词/沉浸）在 hover 时淡入。
Item {
    id: root

    signal activity()
    signal lyricsRequested()
    signal immersiveRequested()

    /** 页面控制：鼠标活动时显示，静止一段时间后淡出。 */
    property bool controlsVisible: true
    /** hover 控制条时露出二级按钮。 */
    property bool secondaryVisible: false
    property bool compact: false

    readonly property real sideMargin: compact ? Theme.spacingLg : 28
    /** 播放页背景恒为暗色，浅色主题下的 error 是深红，需要提亮才能看清。 */
    readonly property color favoriteColor: Theme.mix(Theme.error, np.foreground, 0.35)
    /** 鼠标是否停在控制条上（页面据此露出二级按钮）。 */
    readonly property bool hovered: barHover.hovered

    implicitHeight: secondaryRow.height + mainRow.height + progress.implicitHeight
    opacity: controlsVisible ? 1 : 0
    enabled: controlsVisible

    Behavior on opacity {
        NumberAnimation { duration: Theme.motionMedium; easing.type: Easing.OutCubic }
    }

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

    // ---- 底部压暗，按钮压在歌词上时仍可读 ----
    Rectangle {
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.bottom: parent.bottom
        height: parent.height + 40
        gradient: Gradient {
            GradientStop { position: 0.0; color: Theme.withAlpha(np.scrim, 0.0) }
            GradientStop { position: 0.55; color: Theme.withAlpha(np.scrim, 0.28) }
            GradientStop { position: 1.0; color: Theme.withAlpha(np.scrim, 0.5) }
        }
    }

    // ---- 控制条 hover：露出二级按钮 ----
    HoverHandler {
        id: barHover
    }

    Connections {
        target: barHover
        function onHoveredChanged() {
            if (barHover.hovered)
                root.activity()
        }
    }

    // ---------------------------------------------------------------- 二级按钮

    Item {
        id: secondaryRow

        anchors.left: parent.left
        anchors.right: parent.right
        anchors.bottom: mainRow.top
        height: compact ? 38 : 42
        opacity: root.controlsVisible && root.secondaryVisible ? 1 : 0
        visible: opacity > 0.01

        Behavior on opacity {
            NumberAnimation { duration: Theme.motionMedium; easing.type: Easing.OutCubic }
        }

        Row {
            anchors.centerIn: parent
            spacing: 2

            IconButton {
                icon: "shuffle"
                iconFilled: Player.shuffle
                iconSize: 21
                size: 38
                iconColor: Player.shuffle ? np.accent : np.foregroundDim
                hoverColor: np.hoverFill
                tooltip: Player.shuffle ? "随机播放：开" : "随机播放：关"
                onClicked: Player.toggleShuffle()
            }

            IconButton {
                icon: Player.repeatMode === "ONE" ? "repeat_one" : "repeat"
                iconFilled: Player.repeatMode !== "OFF"
                iconSize: 21
                size: 38
                iconColor: Player.repeatMode === "OFF" ? np.foregroundDim : np.accent
                hoverColor: np.hoverFill
                tooltip: Player.repeatMode === "ONE" ? "单曲循环"
                       : (Player.repeatMode === "ALL" ? "列表循环" : "不循环")
                onClicked: Player.cycleRepeatMode()
            }

            IconButton {
                icon: "queue_music"
                iconSize: 21
                size: 38
                iconColor: np.foregroundDim
                hoverColor: np.hoverFill
                tooltip: "播放队列"
                onClicked: Nav.openQueuePanel()
            }

            IconButton {
                icon: "graphic_eq"
                iconSize: 21
                size: 38
                iconColor: Player.equalizerEnabled ? np.accent : np.foregroundDim
                hoverColor: np.hoverFill
                tooltip: "音效与倍速"
                onClicked: Nav.openEffectsPanel()
            }

            IconButton {
                icon: "volume_up"
                iconSize: 21
                size: 38
                iconColor: np.foregroundDim
                hoverColor: np.hoverFill
                tooltip: "音量"
                onClicked: Nav.openVolumePanel()
            }

            IconButton {
                icon: "bedtime"
                iconSize: 21
                size: 38
                iconColor: Player.sleepTimer && Player.sleepTimer.active ? np.accent : np.foregroundDim
                hoverColor: np.hoverFill
                tooltip: "睡眠定时器"
                onClicked: Nav.openSleepPanel()
            }

            IconButton {
                icon: "download"
                iconSize: 21
                size: 38
                iconColor: np.foregroundDim
                hoverColor: np.hoverFill
                tooltip: "下载"
                onClicked: Nav.openDownloadsPanel()
            }

            Item {
                width: 1
                height: 38

                Rectangle {
                    anchors.centerIn: parent
                    width: 1
                    height: 18
                    color: Theme.withAlpha(np.foreground, 0.16)
                }
            }

            IconButton {
                icon: "comment"
                iconSize: 21
                size: 38
                iconColor: np.foregroundDim
                hoverColor: np.hoverFill
                tooltip: "评论"
                onClicked: {
                    if (typeof Nav.openCommentsPanel === "function")
                        Nav.openCommentsPanel()
                    else
                        Nav.openPanel("comments")
                }
            }

            IconButton {
                icon: "group"
                iconSize: 21
                size: 38
                iconColor: np.foregroundDim
                hoverColor: np.hoverFill
                tooltip: "一起听"
                onClicked: {
                    if (typeof Nav.openListenTogetherPanel === "function")
                        Nav.openListenTogetherPanel()
                    else
                        Nav.openPanel("listenTogether")
                }
            }

            IconButton {
                icon: "lyrics"
                iconSize: 21
                size: 38
                iconColor: np.foregroundDim
                hoverColor: np.hoverFill
                tooltip: "全屏歌词"
                onClicked: root.lyricsRequested()
            }

            IconButton {
                icon: Nav.immersive ? "fullscreen_exit" : "fullscreen"
                iconSize: 21
                size: 38
                iconColor: np.foregroundDim
                hoverColor: np.hoverFill
                tooltip: Nav.immersive ? "退出沉浸（Esc）" : "沉浸模式"
                onClicked: root.immersiveRequested()
            }
        }
    }

    // ---------------------------------------------------------------- 主控制行

    Item {
        id: mainRow

        anchors.left: parent.left
        anchors.right: parent.right
        anchors.bottom: progress.top
        height: compact ? 58 : 64

        // 左：红心收藏
        IconButton {
            id: favoriteButton

            anchors.left: parent.left
            anchors.leftMargin: root.sideMargin
            anchors.verticalCenter: parent.verticalCenter
            icon: "favorite"
            iconFilled: Player.favorite
            iconSize: 25
            size: 44
            iconColor: Player.favorite ? root.favoriteColor : np.foregroundDim
            hoverColor: np.hoverFill
            tooltip: Player.favorite ? "取消收藏" : "加入我喜欢的音乐"
            onClicked: Player.toggleFavorite()
        }

        // 右：时间
        Text {
            anchors.right: parent.right
            anchors.rightMargin: root.sideMargin
            anchors.verticalCenter: parent.verticalCenter
            text: root.formatTime(Player.positionMs) + " / " + root.formatTime(Player.durationMs)
            color: np.foregroundFaint
            font.family: Theme.fontFamily
            font.pixelSize: Theme.textStyle("labelMedium").size
            font.weight: Theme.textStyle("labelMedium").weight
        }

        // 中：上一首 / 播放暂停 / 下一首
        RowLayout {
            anchors.centerIn: parent
            spacing: compact ? 14 : 22

            IconButton {
                Layout.alignment: Qt.AlignVCenter
                icon: "skip_previous"
                iconFilled: true
                iconSize: 30
                size: 48
                iconColor: np.foreground
                hoverColor: np.hoverFill
                tooltip: "上一首"
                onClicked: Player.previous()
            }

            Item {
                id: playButton

                Layout.alignment: Qt.AlignVCenter
                Layout.preferredWidth: 60
                Layout.preferredHeight: 60
                width: 60
                height: 60

                Rectangle {
                    anchors.fill: parent
                    radius: width / 2
                    color: playHover.hovered ? np.hoverFill : "transparent"

                    Behavior on color {
                        ColorAnimation { duration: Theme.motionShort }
                    }
                }

                NeriIcon {
                    anchors.centerIn: parent
                    name: Player.playing ? "pause" : "play_arrow"
                    filled: true
                    iconSize: 34
                    color: np.foreground
                }

                HoverHandler {
                    id: playHover
                    cursorShape: Qt.PointingHandCursor
                }

                MouseArea {
                    anchors.fill: parent
                    onClicked: Player.togglePlayPause()
                }
            }

            IconButton {
                Layout.alignment: Qt.AlignVCenter
                icon: "skip_next"
                iconFilled: true
                iconSize: 30
                size: 48
                iconColor: np.foreground
                hoverColor: np.hoverFill
                tooltip: "下一首"
                onClicked: Player.next()
            }
        }
    }

    // ---------------------------------------------------------------- 贴底进度条

    PlayerProgressSlider {
        id: progress

        anchors.left: parent.left
        anchors.right: parent.right
        anchors.bottom: parent.bottom
    }
}
