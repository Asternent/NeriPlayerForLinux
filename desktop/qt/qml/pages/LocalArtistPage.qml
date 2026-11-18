import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../components"
import "../components/library"
import "../components/library/Format.js" as Format

// 本地歌手详情页。路由参数（Nav.currentArg）为歌手名：
// 优先用 library.artists 的 ArtistGroup，找不到时回退到 library.songs 按艺术家过滤。
DetailScaffold {
    id: page

    property var artistGroups: []
    property var allSongs: []
    property bool loading: true
    property string errorText: ""

    readonly property string artistName: {
        const arg = Nav.currentArg
        return arg === undefined || arg === null ? "" : String(arg)
    }

    readonly property var songs: {
        const list = page.artistGroups || []
        for (let i = 0; i < list.length; i++) {
            if (list[i].name === page.artistName)
                return list[i].songs || []
        }
        const all = page.allSongs || []
        return all.filter(function(song) {
            return String(song.artist || "") === page.artistName
        })
    }

    readonly property string currentKey: Player.hasSong && Player.song ? Player.song.key : ""

    title: page.artistName !== "" ? page.artistName : "歌手"
    subtitle: page.artistName !== "" ? page.songs.length + " 首歌曲 · 本地" : ""

    function reload() {
        page.loading = true
        Api.get("library.artists", {}, function(result, error) {
            if (error) {
                page.loading = false
                page.errorText = error.message || "加载歌手列表失败"
                return
            }
            page.artistGroups = result.artists || []
            Api.get("library.songs", {}, function(songsResult) {
                page.loading = false
                page.errorText = ""
                page.allSongs = songsResult && songsResult.songs ? songsResult.songs : []
            })
        })
    }

    function playAt(index) {
        if (page.songs.length === 0)
            return
        Player.setQueue(page.songs, Math.max(0, Math.min(index, page.songs.length - 1)), true)
    }

    function playAll() {
        if (page.songs.length > 0)
            Player.setQueue(page.songs, 0, true)
    }

    function shuffleAll() {
        if (page.songs.length === 0)
            return
        const shuffled = page.songs.slice()
        for (let i = shuffled.length - 1; i > 0; i--) {
            const j = Math.floor(Math.random() * (i + 1))
            const temp = shuffled[i]
            shuffled[i] = shuffled[j]
            shuffled[j] = temp
        }
        Player.setQueue(shuffled, 0, true)
    }

    function onLibraryChanged() {
        page.reload()
    }

    onArtistNameChanged: page.reload()

    Component.onCompleted: {
        Api.on("library.changed", page.onLibraryChanged)
        page.reload()
    }

    Component.onDestruction: {
        Api.off("library.changed", page.onLibraryChanged)
    }

    // 后端连接建立后补拉一次数据
    Connections {
        target: Backend
        function onConnectedChanged() {
            if (Backend.connected)
                page.reload()
        }
    }

    LoadingState {
        anchors.fill: parent
        visible: page.loading && page.songs.length === 0
        text: "正在加载歌手作品…"
    }

    ErrorCard {
        anchors.top: parent.top
        anchors.left: parent.left
        anchors.right: parent.right
        visible: page.errorText !== ""
        message: page.errorText
        onRetryClicked: page.reload()
    }

    ListView {
        id: songList

        anchors.fill: parent
        visible: page.errorText === ""
        clip: true
        model: page.songs
        boundsBehavior: Flickable.StopAtBounds
        bottomMargin: Theme.spacingXl

        header: Column {
            width: songList.width

            Item {
                width: parent.width
                height: 164

                CoverArt {
                    id: avatar

                    x: Theme.spacingLg
                    y: Theme.spacingLg
                    width: 132
                    height: 132
                    radius: 66
                    placeholderIconSize: 46
                    path: page.songs.length > 0 && page.songs[0].artworkPath ? page.songs[0].artworkPath : ""
                    url: page.songs.length > 0 && page.songs[0].artworkUrl ? page.songs[0].artworkUrl : ""
                }

                Column {
                    anchors.left: avatar.right
                    anchors.leftMargin: Theme.spacingLg
                    anchors.right: parent.right
                    anchors.rightMargin: Theme.spacingLg
                    anchors.verticalCenter: avatar.verticalCenter
                    spacing: Theme.spacingSm

                    Text {
                        width: parent.width
                        text: page.artistName !== "" ? page.artistName : "未知艺术家"
                        elide: Text.ElideRight
                        maximumLineCount: 1
                        color: Theme.onSurfaceColor
                        font.family: Theme.fontFamily
                        font.pixelSize: Theme.textStyle("headlineSmall").size
                        font.weight: Font.DemiBold
                    }

                    Text {
                        width: parent.width
                        text: page.songs.length + " 首歌曲 · "
                              + Format.formatLongDuration(Format.totalDuration(page.songs)) + " · 本地"
                        elide: Text.ElideRight
                        maximumLineCount: 1
                        color: Theme.onSurfaceVariantColor
                        font.family: Theme.fontFamily
                        font.pixelSize: Theme.textStyle("bodyMedium").size
                    }

                    Row {
                        spacing: Theme.spacingSm

                        LibraryButton {
                            text: "播放全部"
                            icon: "play_arrow"
                            filled: true
                            enabled: page.songs.length > 0
                            onClicked: page.playAll()
                        }

                        LibraryButton {
                            text: "随机播放"
                            icon: "shuffle"
                            enabled: page.songs.length > 0
                            onClicked: page.shuffleAll()
                        }
                    }
                }
            }

            SectionHeader {
                width: songList.width
                title: "歌曲"
                icon: "music_note"
                trailingText: page.songs.length + " 首"
            }
        }

        delegate: Item {
            id: songDelegate

            property int itemIndex: index
            property var item: modelData

            width: songList.width
            height: 58

            SongRow {
                anchors.fill: parent
                song: songDelegate.item
                index: songDelegate.itemIndex
                playing: page.currentKey !== "" && songDelegate.item
                         && songDelegate.item.key === page.currentKey
                onActivated: function(index) { page.playAt(index) }
                onMoreClicked: function(song) { songMenu.openFor(song) }
                onRightClicked: function(song) { songMenu.openFor(song) }
            }
        }

        footer: EmptyState {
            width: songList.width
            visible: page.songs.length === 0 && !page.loading
            icon: "person"
            title: "暂无歌曲"
            hint: "该歌手在当前媒体库中没有歌曲"
        }
    }

    SongActionsMenu {
        id: songMenu
    }
}
