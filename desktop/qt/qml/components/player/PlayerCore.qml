import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../"

// 播放页核心区：封面 + 曲目信息 + 进度条 + 传输控制 + 工具行。
// 窄屏与宽屏共用，仅封面尺寸不同（220 / 300）。
Column {
    id: core

    property real coverSize: 220

    signal lyricsRequested()

    spacing: 0

    // ---- 封面 ----
    Item {
        width: core.width
        height: core.coverSize

        Item {
            id: coverFrame

            width: core.coverSize
            height: core.coverSize
            anchors.horizontalCenter: parent.horizontalCenter

            CoverArt {
                id: cover

                anchors.fill: parent
                radius: 24
                placeholderIconSize: Math.max(28, core.coverSize * 0.22)
                path: Player.hasSong && Player.song && Player.song.artworkPath ? Player.song.artworkPath : ""
                url: Player.hasSong && Player.song && Player.song.artworkUrl ? Player.song.artworkUrl : ""
            }

            // 缓冲/准备中：半透明遮罩 + 旋转指示器
            Rectangle {
                id: bufferingScrim

                anchors.fill: parent
                radius: 24
                visible: Player.buffering || Player.state === "PREPARING"
                color: Theme.withAlpha(Theme.scrim, 0.4)

                NeriIcon {
                    anchors.centerIn: parent
                    name: "progress_activity"
                    iconSize: Math.max(28, core.coverSize * 0.16)
                    color: Theme.onPrimaryColor

                    RotationAnimation on rotation {
                        from: 0
                        to: 360
                        duration: 900
                        loops: Animation.Infinite
                        running: bufferingScrim.visible
                    }
                }
            }
        }
    }

    Item {
        width: 1
        height: 18
    }

    // ---- 曲目信息 ----
    Item {
        width: core.width
        height: titleColumn.implicitHeight

        Column {
            id: titleColumn

            width: parent.width
            spacing: 4

            Text {
                width: parent.width
                text: Player.hasSong ? Player.title : ""
                elide: Text.ElideRight
                wrapMode: Text.NoWrap
                color: Theme.onSurfaceColor
                font.family: Theme.fontFamily
                font.pixelSize: Theme.textStyle("headlineSmall").size
                font.weight: Font.DemiBold
                font.letterSpacing: Theme.textStyle("headlineSmall").letterSpacing
            }

            Text {
                width: parent.width
                text: {
                    if (!Player.hasSong)
                        return ""
                    const artist = Player.artist !== "" ? Player.artist : "未知艺术家"
                    return Player.album !== "" ? artist + " · " + Player.album : artist
                }
                elide: Text.ElideRight
                wrapMode: Text.NoWrap
                color: Theme.onSurfaceVariantColor
                font.family: Theme.fontFamily
                font.pixelSize: Theme.textStyle("bodyMedium").size
                font.letterSpacing: Theme.textStyle("bodyMedium").letterSpacing
            }
        }
    }

    Item {
        width: 1
        height: 10
    }

    // ---- 进度条 ----
    PlayerProgressSlider {
        width: core.width
    }

    Item {
        width: 1
        height: 14
    }

    // ---- 传输控制 ----
    Item {
        width: core.width
        height: 68

        RowLayout {
            anchors.centerIn: parent
            spacing: 18

            IconButton {
                Layout.alignment: Qt.AlignVCenter
                icon: "shuffle"
                iconFilled: Player.shuffle
                iconSize: 24
                size: 48
                iconColor: Player.shuffle ? Theme.primary : Theme.onSurfaceColor
                tooltip: Player.shuffle ? "随机播放：开" : "随机播放：关"
                onClicked: Player.toggleShuffle()
            }

            IconButton {
                Layout.alignment: Qt.AlignVCenter
                icon: "skip_previous"
                iconSize: 34
                size: 52
                iconColor: Theme.onSurfaceColor
                tooltip: "上一首"
                onClicked: Player.previous()
            }

            Rectangle {
                id: playButton

                Layout.alignment: Qt.AlignVCenter
                Layout.preferredWidth: 68
                Layout.preferredHeight: 68
                radius: 34
                color: playHover.hovered ? Theme.primaryContainer : Theme.primary

                Behavior on color {
                    ColorAnimation {
                        duration: Theme.motionShort
                    }
                }

                NeriIcon {
                    anchors.centerIn: parent
                    name: Player.playing ? "pause" : "play_arrow"
                    filled: true
                    iconSize: 36
                    color: Theme.onPrimaryColor
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
                iconSize: 34
                size: 52
                iconColor: Theme.onSurfaceColor
                tooltip: "下一首"
                onClicked: Player.next()
            }

            IconButton {
                Layout.alignment: Qt.AlignVCenter
                icon: Player.repeatMode === "ONE" ? "repeat_one" : "repeat"
                iconFilled: Player.repeatMode !== "OFF"
                iconSize: 24
                size: 48
                iconColor: Player.repeatMode === "OFF" ? Theme.onSurfaceColor : Theme.primary
                tooltip: Player.repeatMode === "ONE" ? "单曲循环" : (Player.repeatMode === "ALL" ? "列表循环" : "不循环")
                onClicked: Player.cycleRepeatMode()
            }
        }
    }

    Item {
        width: 1
        height: 16
    }

    // ---- 工具行 ----
    Item {
        width: core.width
        height: 48

        Row {
            anchors.centerIn: parent
            spacing: 6

            IconButton {
                icon: "volume_up"
                iconSize: 24
                size: 48
                iconColor: Theme.onSurfaceVariantColor
                tooltip: "音量"
                onClicked: Nav.openVolumePanel()
            }

            IconButton {
                icon: "graphic_eq"
                iconSize: 24
                size: 48
                iconColor: Theme.onSurfaceVariantColor
                tooltip: "音效与倍速"
                onClicked: Nav.openEffectsPanel()
            }

            IconButton {
                icon: "queue_music"
                iconSize: 24
                size: 48
                iconColor: Theme.onSurfaceVariantColor
                tooltip: "播放队列"
                onClicked: Nav.openQueuePanel()
            }

            IconButton {
                icon: "bedtime"
                iconSize: 24
                size: 48
                iconColor: Player.sleepTimer && Player.sleepTimer.active ? Theme.primary : Theme.onSurfaceVariantColor
                tooltip: "睡眠定时器"
                onClicked: Nav.openSleepPanel()
            }

            IconButton {
                icon: "download"
                iconSize: 24
                size: 48
                iconColor: Theme.onSurfaceVariantColor
                tooltip: "下载"
                onClicked: Nav.openDownloadsPanel()
            }

            IconButton {
                icon: "lyrics"
                iconSize: 24
                size: 48
                iconColor: Theme.onSurfaceVariantColor
                tooltip: "全屏歌词"
                onClicked: core.lyricsRequested()
            }
        }
    }
}
