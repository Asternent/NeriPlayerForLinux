import QtQuick
import ".."

// 播放页左侧信息区（宽屏竖排 / 窄屏横排）：封面 + 标题 + 歌手 + 专辑。
// 歌手/专辑行在 Song 带有 artistId/albumId 时可点击，跳转到在线歌手/专辑详情。
Item {
    id: core

    property real coverSize: 280
    /** 窄屏横排：封面在左，文字在右。 */
    property bool compact: false

    signal activity()

    readonly property var song: Player.song
    readonly property string songTitle: Player.hasSong ? Player.title : ""
    readonly property string songSubtitle: song && song.subtitle !== undefined && song.subtitle !== null ? String(song.subtitle) : ""
    readonly property string songArtist: Player.hasSong ? Player.artist : ""
    readonly property string songAlbum: Player.hasSong ? Player.album : ""
    readonly property string artistId: song && song.artistId !== undefined && song.artistId !== null ? String(song.artistId) : ""
    readonly property string albumId: song && song.albumId !== undefined && song.albumId !== null ? String(song.albumId) : ""
    readonly property string songSource: song && song.source ? String(song.source) : "LOCAL"
    readonly property string artworkUrl: song && song.artworkUrl ? String(song.artworkUrl) : ""

    readonly property real titleSize: compact ? 20
                                            : Math.round(Math.max(22, Math.min(34, coverSize * 0.13)))

    /** 供页面计算窄屏头部高度。 */
    readonly property real contentHeight: compact ? Math.max(coverSize, compactInfo.height)
                                                  : regularBox.height

    NowPlayingTheme { id: np }

    function openArtist() {
        if (core.artistId === "")
            return
        Nav.push("remoteArtist", {
                     "id": core.artistId,
                     "name": core.songArtist,
                     "avatarUrl": "",
                     "songCount": 0,
                     "source": core.songSource
                 })
    }

    function openAlbum() {
        if (core.albumId === "")
            return
        Nav.push("albumDetail", {
                     "source": core.songSource,
                     "id": core.albumId,
                     "name": core.songAlbum,
                     "coverUrl": core.artworkUrl,
                     "creator": core.songArtist,
                     "trackCount": 0
                 })
    }

    // ---------------------------------------------------------------- 宽屏竖排

    Item {
        id: regularBox

        anchors.horizontalCenter: parent.horizontalCenter
        anchors.verticalCenter: parent.verticalCenter
        visible: !core.compact
        width: Math.min(core.width, Math.max(core.coverSize, 360))
        height: regularColumn.height

        Column {
            id: regularColumn

            width: parent.width
            spacing: 0

            // 封面左对齐，与下方文字左边缘对齐（参考图排版）
            Item {
                width: parent.width
                height: core.coverSize

                NowPlayingCover {
                    anchors.left: parent.left
                    width: core.coverSize
                    height: core.coverSize
                    onActivity: core.activity()
                }
            }

            Item {
                width: 1
                height: 26
            }

            Text {
                id: regularTitle

                width: parent.width
                text: core.songTitle
                color: np.foreground
                font.family: Theme.fontFamily
                font.pixelSize: core.titleSize
                font.weight: Font.DemiBold
                wrapMode: Text.Wrap
                maximumLineCount: 2
                elide: Text.ElideRight
                textFormat: Text.PlainText
            }

            Text {
                width: parent.width
                visible: core.songSubtitle !== ""
                text: core.songSubtitle
                color: np.foregroundDim
                font.family: Theme.fontFamily
                font.pixelSize: Math.round(core.titleSize * 0.55)
                wrapMode: Text.Wrap
                maximumLineCount: 2
                elide: Text.ElideRight
                textFormat: Text.PlainText
                topPadding: 4
            }

            Item {
                width: 1
                height: 14
            }

            NowPlayingInfoRow {
                width: parent.width
                icon: "person"
                text: core.songArtist
                clickable: core.artistId !== ""
                fontPixelSize: 15
                onClicked: core.openArtist()
                onActivity: core.activity()
            }

            NowPlayingInfoRow {
                width: parent.width
                icon: "album"
                text: core.songAlbum
                clickable: core.albumId !== ""
                textColor: Theme.withAlpha(np.foreground, 0.62)
                hoverColor: np.foreground
                fontPixelSize: 14
                onClicked: core.openAlbum()
                onActivity: core.activity()
            }
        }
    }

    // ---------------------------------------------------------------- 窄屏横排

    Row {
        id: compactRow

        anchors.left: parent.left
        anchors.verticalCenter: parent.verticalCenter
        visible: core.compact
        spacing: 20

        Item {
            width: core.coverSize
            height: core.coverSize

            NowPlayingCover {
                anchors.fill: parent
                onActivity: core.activity()
            }
        }

        Item {
            width: Math.max(0, core.width - core.coverSize - 20)
            height: core.coverSize

            Column {
                id: compactInfo

                anchors.verticalCenter: parent.verticalCenter
                width: parent.width
                spacing: 3

                Text {
                    width: parent.width
                    text: core.songTitle
                    color: np.foreground
                    font.family: Theme.fontFamily
                    font.pixelSize: core.titleSize
                    font.weight: Font.DemiBold
                    wrapMode: Text.Wrap
                    maximumLineCount: 2
                    elide: Text.ElideRight
                    textFormat: Text.PlainText
                }

                Text {
                    width: parent.width
                    visible: core.songSubtitle !== ""
                    text: core.songSubtitle
                    color: np.foregroundDim
                    font.family: Theme.fontFamily
                    font.pixelSize: 12
                    elide: Text.ElideRight
                    textFormat: Text.PlainText
                }

                Item {
                    width: 1
                    height: 6
                }

                NowPlayingInfoRow {
                    width: parent.width
                    icon: "person"
                    text: core.songArtist
                    clickable: core.artistId !== ""
                    fontPixelSize: 14
                    iconSize: 16
                    onClicked: core.openArtist()
                    onActivity: core.activity()
                }

                NowPlayingInfoRow {
                    width: parent.width
                    icon: "album"
                    text: core.songAlbum
                    clickable: core.albumId !== ""
                    textColor: Theme.withAlpha(np.foreground, 0.62)
                    hoverColor: np.foreground
                    fontPixelSize: 12
                    iconSize: 15
                    onClicked: core.openAlbum()
                    onActivity: core.activity()
                }
            }
        }
    }
}
