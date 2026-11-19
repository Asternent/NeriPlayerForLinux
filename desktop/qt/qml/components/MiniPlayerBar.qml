import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

// 底部迷你播放条。点击空白区域进入播放页，按钮分别打开音量/音效/队列/睡眠面板。
// 布局与旧 Compose 版一致：左(封面+歌曲信息) / 中(上/播/下，严格水平居中) / 右(功能按钮)，
// 两侧各占一份 weight，中间不设权重，因此播放控制永远落在卡片正中。
Item {
    id: bar

    height: 78

    readonly property int cardMarginH: 8
    readonly property int cardMarginV: 4
    readonly property int cardRadius: 18

    // 拖动进度时的临时位置（0..1）。松手才真正 seek，拖动期间进度条显示拖拽位置。
    property bool dragging: false
    property real dragValue: 0
    readonly property real shownProgress: dragging
                                          ? dragValue
                                          : Player.progress

    function formatTime(ms) {
        const total = Math.max(0, Math.round((ms || 0) / 1000))
        const minutes = Math.floor(total / 60)
        const seconds = total % 60
        const ss = seconds < 10 ? "0" + seconds : String(seconds)
        return minutes + ":" + ss
    }

    Rectangle {
        id: card
        anchors.fill: parent
        anchors.leftMargin: bar.cardMarginH
        anchors.rightMargin: bar.cardMarginH
        anchors.topMargin: bar.cardMarginV
        anchors.bottomMargin: bar.cardMarginV
        radius: bar.cardRadius
        color: hover.containsMouse ? Theme.surfaceContainerHighest : Theme.surfaceContainerHigh

        Behavior on color {
            ColorAnimation {
                duration: Theme.motionShort
            }
        }

        HoverHandler {
            id: hover
        }

        // 整条控制栏都可点击进入播放页；进度条与按钮各自消费事件，不会冒泡到这里。
        MouseArea {
            anchors.fill: parent
            cursorShape: Qt.PointingHandCursor
            onClicked: Nav.push("nowplaying")
        }

        // 顶部进度条：常态 2dp，hover / 拖动时 8dp，可拖动 seek。
        Rectangle {
            id: progressTrack

            anchors.left: parent.left
            anchors.right: parent.right
            anchors.top: parent.top
            anchors.topMargin: 2
            anchors.leftMargin: 10
            anchors.rightMargin: 10
            height: (progressArea.containsMouse || bar.dragging) ? 8 : 2
            radius: height / 2
            color: Theme.surfaceVariant

            Behavior on height {
                NumberAnimation { duration: Theme.motionShort }
            }

            Rectangle {
                width: parent.width * bar.shownProgress
                height: parent.height
                radius: parent.radius
                color: Theme.primary
            }

            MouseArea {
                id: progressArea
                anchors.fill: parent
                anchors.topMargin: -6
                anchors.bottomMargin: -6
                hoverEnabled: true
                cursorShape: Qt.PointingHandCursor
                property real startX: 0

                function seekAt(x) {
                    bar.dragValue = Math.max(0, Math.min(1, x / width))
                }

                onPressed: function(mouse) {
                    if (Player.durationMs <= 0)
                        return
                    mouse.accepted = true
                    bar.dragging = true
                    seekAt(mouse.x)
                }
                onPositionChanged: function(mouse) {
                    if (bar.dragging)
                        seekAt(mouse.x)
                }
                onReleased: function(mouse) {
                    if (!bar.dragging)
                        return
                    seekAt(mouse.x)
                    Player.seekTo(bar.dragValue * Player.durationMs)
                    bar.dragging = false
                }
                onCanceled: bar.dragging = false
            }
        }

        // 两侧等宽 → 中间控制组严格水平居中。
        RowLayout {
            anchors.fill: parent
            anchors.leftMargin: 10
            anchors.rightMargin: 10
            spacing: 2

            // 左侧：封面 + 歌曲信息（与右侧等宽）。
            Item {
                Layout.fillWidth: true
                Layout.fillHeight: true

                RowLayout {
                    // 左右都锚定，RowLayout 才有确定宽度；否则内部 Column 拿不到宽度，
                    // 而 Text 又绑定 parent.width，会形成宽度循环并塌缩成 0（歌名不显示）。
                    anchors.left: parent.left
                    anchors.right: parent.right
                    anchors.verticalCenter: parent.verticalCenter
                    spacing: 10

                    CoverArt {
                        Layout.preferredWidth: 42
                        Layout.preferredHeight: 42
                        Layout.alignment: Qt.AlignVCenter
                        radius: 10
                        path: Player.hasSong && Player.song && Player.song.artworkPath ? Player.song.artworkPath : ""
                        url: Player.hasSong && Player.song && Player.song.artworkUrl ? Player.song.artworkUrl : ""
                    }

                    Column {
                        Layout.fillWidth: true
                        spacing: 2

                        Text {
                            width: parent.width
                            text: Player.hasSong ? Player.title : "未在播放"
                            color: Theme.onSurfaceColor
                            elide: Text.ElideRight
                            maximumLineCount: 1
                            font.family: Theme.fontFamily
                            font.pixelSize: 14
                            font.weight: Font.Medium
                        }

                        Text {
                            width: parent.width
                            text: Player.hasSong ? Player.artist : "选择一首歌开始播放"
                            color: Theme.onSurfaceVariantColor
                            elide: Text.ElideRight
                            maximumLineCount: 1
                            font.family: Theme.fontFamily
                            font.pixelSize: 11
                        }
                    }
                }
            }

            // 中间：播放控制（不设权重，天然居中）。
            RowLayout {
                id: controls
                Layout.fillHeight: true
                spacing: 2

                IconButton {
                    icon: "skip_previous"
                    iconSize: 22
                    size: 36
                    onClicked: Player.previous()
                }

                Rectangle {
                    width: 38
                    height: 38
                    radius: 19
                    color: Theme.primary

                    NeriIcon {
                        anchors.centerIn: parent
                        name: Player.playing ? "pause" : "play_arrow"
                        filled: true
                        iconSize: 22
                        color: Theme.onPrimaryColor
                    }

                    HoverHandler {
                        cursorShape: Qt.PointingHandCursor
                    }

                    MouseArea {
                        anchors.fill: parent
                        onClicked: Player.togglePlayPause()
                    }
                }

                IconButton {
                    icon: "skip_next"
                    iconSize: 22
                    size: 36
                    onClicked: Player.next()
                }
            }

            // 右侧：功能按钮（与左侧等宽，靠右排列）。
            Item {
                Layout.fillWidth: true
                Layout.fillHeight: true

                RowLayout {
                    anchors.right: parent.right
                    anchors.verticalCenter: parent.verticalCenter
                    spacing: 2

                    IconButton {
                        icon: "volume_up"
                        iconSize: 19
                        size: 34
                        onClicked: Nav.openVolumePanel()
                    }
                    IconButton {
                        icon: "graphic_eq"
                        iconSize: 19
                        size: 34
                        onClicked: Nav.openEffectsPanel()
                    }
                    IconButton {
                        icon: "queue_music"
                        iconSize: 19
                        size: 34
                        onClicked: Nav.openQueuePanel()
                    }
                    IconButton {
                        icon: "bedtime"
                        iconSize: 19
                        size: 34
                        onClicked: Nav.openSleepPanel()
                    }
                    // 最右侧按钮：悬浮歌词开关（不再进入播放页）。
                    IconButton {
                        icon: "lyrics"
                        iconSize: 19
                        size: 34
                        iconColor: Settings.get("floatingLyricsEnabled", false) === true
                                   ? Theme.primary : Theme.onSurfaceVariantColor
                        tooltip: "悬浮歌词"
                        onClicked: Settings.set("floatingLyricsEnabled",
                                                !(Settings.get("floatingLyricsEnabled", false) === true))
                    }
                }
            }
        }
    }
}
