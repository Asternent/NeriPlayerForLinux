import QtQuick
import "../"

// 首页歌曲卡（横向滚动用）：封面 + 歌名 + 歌手。
// 尺寸沿用 design-spec 的列表卡体系：外宽 150、封面 138（圆角 16/14），
// hover 或正在播放时在封面上叠一层播放提示。
Item {
    id: card

    property var song: null
    property real cardWidth: 150
    /** 当前歌曲正在播放：封面高亮并显示均衡器图标。 */
    property bool playing: false

    signal activated()

    readonly property string title: song && song.title ? song.title : "未知歌曲"
    readonly property string artist: song && song.artist ? song.artist : "未知歌手"
    readonly property string artworkPath: song && song.artworkPath ? song.artworkPath : ""
    readonly property string artworkUrl: song && song.artworkUrl ? song.artworkUrl : ""

    implicitWidth: cardWidth
    implicitHeight: cover.height + Theme.spacingSm + titleText.implicitHeight + 2
                    + artistText.implicitHeight

    Rectangle {
        id: cover

        width: card.cardWidth
        height: width
        radius: Theme.radiusLg
        color: Theme.surfaceContainerHighest

        CoverArt {
            anchors.fill: parent
            anchors.margins: 6
            radius: Theme.radiusLg - 2
            path: card.artworkPath
            url: card.artworkUrl
            placeholderIconSize: 46
        }

        // hover / 播放中：遮罩 + 播放提示
        Rectangle {
            anchors.fill: parent
            anchors.margins: 6
            radius: Theme.radiusLg - 2
            color: Theme.withAlpha(Theme.scrim, (hover.hovered || card.playing) ? 0.28 : 0)

            Behavior on color {
                ColorAnimation { duration: Theme.motionShort }
            }

            NeriIcon {
                anchors.centerIn: parent
                name: card.playing ? "graphic_eq" : "play_arrow"
                iconSize: 44
                color: "#FFFFFF"
                opacity: (hover.hovered || card.playing) ? 1 : 0

                Behavior on opacity {
                    NumberAnimation { duration: Theme.motionShort }
                }
            }
        }
    }

    Text {
        id: titleText

        anchors.top: cover.bottom
        anchors.topMargin: Theme.spacingSm
        width: card.cardWidth
        text: card.title
        elide: Text.ElideRight
        color: card.playing ? Theme.primary : Theme.onSurfaceColor
        font.family: Theme.fontFamily
        font.pixelSize: 13
        font.weight: Font.Medium
    }

    Text {
        id: artistText

        anchors.top: titleText.bottom
        anchors.topMargin: 2
        width: card.cardWidth
        text: card.artist
        elide: Text.ElideRight
        color: Theme.onSurfaceVariantColor
        font.family: Theme.fontFamily
        font.pixelSize: 11
    }

    HoverHandler {
        id: hover
        cursorShape: Qt.PointingHandCursor
    }

    MouseArea {
        anchors.fill: parent
        onClicked: card.activated()
    }
}
