import QtQuick
import ".."

// 播放页左侧信息区（宽屏竖排 / 窄屏横排）：封面 + 标题 + 歌手 + 专辑。
// 标题/歌手/专辑文字用可选中组件（支持 Ctrl+C 与右键复制菜单）；
// 歌手/专辑的跳转入口在左侧小图标上，避免和拖选文字冲突。
Item {
    id: core

    property real coverSize: 280
    /** 宽屏时左右留白，让填满左栏的文字不贴边。 */
    property real contentPadding: 0
    /** 窄屏横排：封面在左，文字在右。 */
    property bool compact: false
    /** 歌曲信息水平对齐（Settings.nowPlayingInfoAlignment）：LEFT / CENTER / RIGHT。 */
    property string alignment: "LEFT"

    readonly property int alignmentMode: alignment === "CENTER" ? 1 : (alignment === "RIGHT" ? 2 : 0)
    readonly property int textAlignment: alignmentMode === 1 ? Text.AlignHCenter
                                          : (alignmentMode === 2 ? Text.AlignRight : Text.AlignLeft)

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
        // 填满左栏（不再限制 360），只留两侧留白
        width: Math.max(0, core.width - core.contentPadding * 2)
        height: regularColumn.height

        Column {
            id: regularColumn

            width: parent.width
            spacing: 0

            // 封面跟随信息对齐方式：LEFT 贴左（与文字左边缘对齐），CENTER / RIGHT 居中 / 贴右
            Item {
                width: parent.width
                height: core.coverSize

                NowPlayingCover {
                    x: core.alignmentMode === 1 ? Math.round((parent.width - width) / 2)
                       : (core.alignmentMode === 2 ? parent.width - width : 0)
                    width: core.coverSize
                    height: core.coverSize
                    onActivity: core.activity()
                }
            }

            Item {
                width: 1
                height: 26
            }

            NowPlayingSelectableText {
                width: parent.width
                text: core.songTitle
                color: np.foreground
                fontPixelSize: core.titleSize
                fontWeight: Font.DemiBold
                wrap: true
                maximumLines: 2
                horizontalAlignment: core.textAlignment
                onActivity: core.activity()
            }

            Item {
                width: 1
                height: 4
            }

            NowPlayingSelectableText {
                width: parent.width
                visible: core.songSubtitle !== ""
                text: core.songSubtitle
                color: np.foregroundDim
                fontPixelSize: Math.round(core.titleSize * 0.55)
                wrap: true
                maximumLines: 2
                horizontalAlignment: core.textAlignment
                onActivity: core.activity()
            }

            Item {
                width: 1
                height: 14
            }

            NowPlayingInfoRow {
                width: parent.width
                alignment: core.alignment
                icon: "person"
                text: core.songArtist
                clickable: core.artistId !== ""
                clickTooltip: "查看歌手详情"
                fontPixelSize: 15
                onClicked: core.openArtist()
                onActivity: core.activity()
            }

            NowPlayingInfoRow {
                width: parent.width
                alignment: core.alignment
                icon: "album"
                text: core.songAlbum
                clickable: core.albumId !== ""
                clickTooltip: "查看专辑详情"
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

                NowPlayingSelectableText {
                    width: parent.width
                    text: core.songTitle
                    color: np.foreground
                    fontPixelSize: core.titleSize
                    fontWeight: Font.DemiBold
                    wrap: true
                    maximumLines: 2
                    horizontalAlignment: core.textAlignment
                    onActivity: core.activity()
                }

                NowPlayingSelectableText {
                    width: parent.width
                    visible: core.songSubtitle !== ""
                    text: core.songSubtitle
                    color: np.foregroundDim
                    fontPixelSize: 12
                    wrap: false
                    horizontalAlignment: core.textAlignment
                    onActivity: core.activity()
                }

                Item {
                    width: 1
                    height: 6
                }

                NowPlayingInfoRow {
                    width: parent.width
                    alignment: core.alignment
                    icon: "person"
                    text: core.songArtist
                    clickable: core.artistId !== ""
                    clickTooltip: "查看歌手详情"
                    fontPixelSize: 14
                    iconSize: 16
                    onClicked: core.openArtist()
                    onActivity: core.activity()
                }

                NowPlayingInfoRow {
                    width: parent.width
                    alignment: core.alignment
                    icon: "album"
                    text: core.songAlbum
                    clickable: core.albumId !== ""
                    clickTooltip: "查看专辑详情"
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
