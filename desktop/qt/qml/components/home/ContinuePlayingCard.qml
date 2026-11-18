import QtQuick
import "../"

// 「继续播放」卡：横向 220dp 紧凑卡（对应 Compose HomeScreen.ContinueCard）。
// 左侧 56dp 封面（hover 时显示中心播放徽标）+ 歌名/歌手，右侧小播放按钮。
Rectangle {
    id: card

    property var song: null
    property bool playing: false

    signal activated()

    readonly property string title: song && song.title ? song.title : "未知歌曲"
    readonly property string artist: song && song.artist ? song.artist : "未知歌手"
    readonly property string artworkPath: song && song.artworkPath ? song.artworkPath : ""
    readonly property string artworkUrl: song && song.artworkUrl ? song.artworkUrl : ""

    implicitWidth: 220
    implicitHeight: 72
    radius: Theme.radiusLg + 2
    color: hover.hovered ? Theme.surfaceContainerHigh : Theme.surfaceContainerLow

    Behavior on color {
        ColorAnimation { duration: Theme.motionShort }
    }

    // 整卡点击播放（放在最底层，右侧播放按钮在其上）
    MouseArea {
        anchors.fill: parent
        onClicked: card.activated()
    }

    Row {
        anchors.fill: parent
        anchors.margins: Theme.spacingSm
        spacing: 10

        Item {
            anchors.verticalCenter: parent.verticalCenter
            width: 56
            height: 56

            CoverArt {
                anchors.fill: parent
                radius: 12
                path: card.artworkPath
                url: card.artworkUrl
                placeholderIconSize: 24
            }

            Rectangle {
                anchors.centerIn: parent
                width: 26
                height: 26
                radius: 13
                color: Theme.withAlpha(Theme.primary, 0.85)
                visible: hover.hovered || card.playing

                NeriIcon {
                    anchors.centerIn: parent
                    name: card.playing ? "graphic_eq" : "play_arrow"
                    iconSize: 16
                    color: Theme.onPrimaryColor
                }
            }
        }

        Column {
            anchors.verticalCenter: parent.verticalCenter
            width: Math.max(0, card.width - 56 - 28 - 10 - 10 - Theme.spacingSm * 2)
            spacing: 2

            Text {
                width: parent.width
                text: card.title
                elide: Text.ElideRight
                color: card.playing ? Theme.primary : Theme.onSurfaceColor
                font.family: Theme.fontFamily
                font.pixelSize: Theme.textStyle("bodyMedium").size
            }

            Text {
                width: parent.width
                text: card.artist
                elide: Text.ElideRight
                color: Theme.onSurfaceVariantColor
                font.family: Theme.fontFamily
                font.pixelSize: Theme.textStyle("labelSmall").size
            }
        }

        IconButton {
            anchors.verticalCenter: parent.verticalCenter
            size: 28
            iconSize: 18
            icon: card.playing ? "graphic_eq" : "play_arrow"
            iconColor: Theme.primary
            tooltip: "播放"
            onClicked: card.activated()
        }
    }

    HoverHandler {
        id: hover
        cursorShape: Qt.PointingHandCursor
    }
}
