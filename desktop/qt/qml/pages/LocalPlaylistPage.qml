import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../components"
import "../components/library"
import "../components/library/Format.js" as Format

// 本地歌单详情页。路由参数（Nav.currentArg）为歌单 id：
//   - 系统歌单 "system:favorites"（也兼容旧的 "favorites"）→ 「我喜欢的音乐」
//   - 其它为 collections.list 返回的用户歌单 id
// 支持播放全部 / 随机播放 / 移除歌曲 / 上移下移排序 / 重命名 / 删除（系统歌单不可重命名删除）。
DetailScaffold {
    id: page

    property var userPlaylists: []
    property var favoritesPlaylist: null
    property bool loading: true
    property string errorText: ""

    readonly property string playlistId: {
        const arg = Nav.currentArg
        return arg === undefined || arg === null ? "" : String(arg)
    }
    readonly property bool favoritesMode: page.playlistId === "system:favorites"
                                          || page.playlistId === "favorites"

    readonly property var playlist: {
        if (page.favoritesMode)
            return page.favoritesPlaylist
        const list = page.userPlaylists || []
        for (let i = 0; i < list.length; i++) {
            if (list[i].id === page.playlistId)
                return list[i]
        }
        return null
    }

    readonly property var songs: page.playlist && page.playlist.songs ? page.playlist.songs : []
    readonly property bool isSystem: page.favoritesMode || (page.playlist ? !!page.playlist.system : false)
    readonly property string currentKey: Player.hasSong && Player.song ? Player.song.key : ""

    title: page.playlist ? page.playlist.name : (page.favoritesMode ? "我喜欢的音乐" : "歌单")
    subtitle: page.playlist
              ? page.songs.length + " 首 · " + Format.formatLongDuration(Format.totalDuration(page.songs))
                + (page.isSystem ? " · 我喜欢的音乐" : "")
              : ""

    actions: [
        IconButton {
            icon: "edit"
            iconSize: 22
            size: 44
            tooltip: "重命名歌单"
            visible: page.playlist !== null && !page.isSystem
            onClicked: renameDialog.openFor(page.playlist.name, page.playlist.id)
        },
        IconButton {
            icon: "delete"
            iconSize: 22
            size: 44
            tooltip: "删除歌单"
            visible: page.playlist !== null && !page.isSystem
            onClicked: deleteDialog.openFor("删除歌单",
                                            "确定要删除歌单「" + page.playlist.name + "」吗？此操作不可恢复。",
                                            "删除")
        }
    ]

    function reload() {
        page.loading = true
        Api.get("collections.list", {}, function(result, error) {
            page.loading = false
            if (error) {
                page.errorText = error.message || "加载歌单失败"
                return
            }
            page.errorText = ""
            page.userPlaylists = result.playlists || []
            page.favoritesPlaylist = result.favorites || null
            if (page.favoritesMode && page.favoritesPlaylist === null)
                page.favoritesPlaylist = { id: "system:favorites", name: "我喜欢的音乐", songs: [], system: true }
            if (!page.favoritesMode && page.playlist === null)
                page.errorText = "歌单不存在，它可能已经被删除"
        })
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

    function playAt(index) {
        if (page.songs.length === 0)
            return
        Player.setQueue(page.songs, Math.max(0, Math.min(index, page.songs.length - 1)), true)
    }

    function removeSong(song) {
        if (!page.playlist || !song)
            return
        Api.call("collections.removeSong", { id: page.playlist.id, songKey: song.key }).then(function() {
            Toast.show("已从歌单移除")
            page.reload()
        }).catch(function(error) {
            Toast.show(error && error.message ? error.message : "移除失败", "error")
        })
    }

    function moveSong(song, delta) {
        if (!page.playlist || !song)
            return
        let from = -1
        for (let i = 0; i < page.songs.length; i++) {
            if (page.songs[i].key === song.key) {
                from = i
                break
            }
        }
        if (from < 0)
            return
        const to = from + delta
        if (to < 0) {
            Toast.show("已经是第一首了")
            return
        }
        if (to >= page.songs.length) {
            Toast.show("已经是最后一首了")
            return
        }
        Api.call("collections.moveSong", { id: page.playlist.id, from: from, to: to }).then(function() {
            page.reload()
        }).catch(function(error) {
            Toast.show(error && error.message ? error.message : "排序失败", "error")
        })
    }

    function deletePlaylist() {
        if (!page.playlist)
            return
        const playlistId = page.playlist.id
        Api.call("collections.delete", { id: playlistId }).then(function() {
            Toast.show("歌单已删除")
            Nav.back()
        }).catch(function(error) {
            Toast.show(error && error.message ? error.message : "删除失败", "error")
        })
    }

    function onCollectionsChanged() {
        page.reload()
    }

    onPlaylistIdChanged: page.reload()

    Component.onCompleted: {
        Api.on("collections.changed", page.onCollectionsChanged)
        page.reload()
    }

    Component.onDestruction: {
        Api.off("collections.changed", page.onCollectionsChanged)
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
        visible: page.loading && page.playlist === null
        text: "正在加载歌单…"
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
        visible: page.playlist !== null && page.errorText === ""
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
                    id: coverArt

                    x: Theme.spacingLg
                    y: Theme.spacingLg
                    width: 132
                    height: 132
                    radius: Theme.radiusXl
                    placeholderIconSize: 46
                    path: page.songs.length > 0 && page.songs[0].artworkPath ? page.songs[0].artworkPath : ""
                    url: page.songs.length > 0 && page.songs[0].artworkUrl ? page.songs[0].artworkUrl : ""
                }

                Column {
                    anchors.left: coverArt.right
                    anchors.leftMargin: Theme.spacingLg
                    anchors.right: parent.right
                    anchors.rightMargin: Theme.spacingLg
                    anchors.verticalCenter: coverArt.verticalCenter
                    spacing: Theme.spacingSm

                    Text {
                        width: parent.width
                        text: page.playlist ? page.playlist.name : ""
                        elide: Text.ElideRight
                        maximumLineCount: 2
                        wrapMode: Text.Wrap
                        color: Theme.onSurfaceColor
                        font.family: Theme.fontFamily
                        font.pixelSize: Theme.textStyle("headlineSmall").size
                        font.weight: Font.DemiBold
                    }

                    Text {
                        width: parent.width
                        text: page.songSummaryText()
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
                onMoreClicked: function(song) {
                    songMenu.openFor(song, { playlistId: page.playlist ? page.playlist.id : "", showMove: true })
                }
                onRightClicked: function(song) {
                    songMenu.openFor(song, { playlistId: page.playlist ? page.playlist.id : "", showMove: true })
                }
            }
        }

        footer: EmptyState {
            width: songList.width
            visible: page.songs.length === 0
            icon: "queue_music"
            title: "歌单还是空的"
            hint: page.isSystem
                  ? "在歌曲的「更多」菜单里点击「收藏」，歌曲会出现在这里"
                  : "在歌曲的「更多」菜单中选择「添加到歌单…」"
        }
    }

    // 头部副标题："N 首 · x 小时 y 分钟"
    function songSummaryText() {
        if (!page.playlist)
            return ""
        return page.songs.length + " 首 · " + Format.formatLongDuration(Format.totalDuration(page.songs))
                + (page.isSystem ? " · 我喜欢的音乐" : "")
    }

    SongActionsMenu {
        id: songMenu

        onRemoveRequested: function(song) { page.removeSong(song) }
        onMoveRequested: function(song, delta) { page.moveSong(song, delta) }
    }

    CreatePlaylistDialog {
        id: renameDialog

        onRenamed: function() { page.reload() }
    }

    ConfirmDialog {
        id: deleteDialog

        onConfirmed: page.deletePlaylist()
    }
}
