import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

// 底部迷你播放条。点击空白区域进入播放页，按钮分别打开音量/音效/队列/睡眠面板。
Item {
    id: bar

    height: 78

    readonly property int cardMarginH: 8
    readonly property int cardMarginV: 4
    readonly property int cardRadius: 18

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

        // 左侧：封面 + 标题
        RowLayout {
            id: leftSection
            anchors.left: parent.left
            anchors.leftMargin: 10
            anchors.verticalCenter: parent.verticalCenter
            spacing: 10

            CoverArt {
                Layout.preferredWidth: 42
                Layout.preferredHeight: 42
                radius: 10
                path: Player.hasSong && Player.song && Player.song.artworkPath ? Player.song.artworkPath : ""
                url: Player.hasSong && Player.song && Player.song.artworkUrl ? Player.song.artworkUrl : ""
            }

            Column {
                Layout.maximumWidth: Math.max(80, bar.width * 0.42)
                spacing: 2

                Text {
                    width: parent.width
                    text: Player.hasSong ? Player.title : "未在播放"
                    color: Theme.onSurfaceColor
                    elide: Text.ElideRight
                    font.family: Theme.fontFamily
                    font.pixelSize: 16
                    font.weight: Font.Medium
                }

                Text {
                    width: parent.width
                    text: Player.hasSong ? Player.artist : "选择一首歌开始播放"
                    color: Theme.onSurfaceVariantColor
                    elide: Text.ElideRight
                    font.family: Theme.fontFamily
                    font.pixelSize: 12
                }
            }
        }

        MouseArea {
            anchors.left: parent.left
            anchors.right: controls.left
            anchors.top: parent.top
            anchors.bottom: parent.bottom
            cursorShape: Qt.PointingHandCursor
            onClicked: Nav.push("nowplaying")
        }

        // 右侧：播放控制 + 功能按钮
        RowLayout {
            id: controls
            anchors.right: parent.right
            anchors.rightMargin: 10
            anchors.verticalCenter: parent.verticalCenter
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

            Item { width: 6; height: 1 }

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
            IconButton {
                icon: "lyrics"
                iconSize: 19
                size: 34
                onClicked: Nav.push("nowplaying")
            }
        }

        // 底部进度条：常态 2dp
        Rectangle {
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.bottom: parent.bottom
            height: 2
            radius: 1
            color: Theme.surfaceVariant

            Rectangle {
                width: parent.width * Player.progress
                height: parent.height
                radius: parent.radius
                color: Theme.primary
            }
        }
    }
}
