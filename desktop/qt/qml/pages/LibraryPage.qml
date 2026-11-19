import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../components"
import "../components/library"
import "../components/library/Format.js" as Format

// 媒体库主页：本地 / 收藏 / 在线音源 三个主 Tab。
// 本地 Tab 内含 歌曲 / 歌手 / 专辑 / 歌单 四个分类；收藏 Tab 展示「我喜欢的音乐」。
// 数据来自 library.* 与 collections.* 命令，事件（library.changed / library.scan /
// collections.changed）到达后自动刷新。
Item {
    id: page

    // ---- 数据 ----
    property var songs: []
    property var artists: []          // [{name, songs}]
    property var albums: []           // [{name, artist, songs}]
    property var playlists: []        // 用户歌单（collections.list 的 playlists）
    property var favorites: []        // 「我喜欢的音乐」的歌曲
    property bool loading: true
    property string errorText: ""

    // ---- 交互状态 ----
    property string query: ""
    property string primaryTab: "local"      // local / favorites / online
    property string localTab: "songs"        // songs / artists / albums / playlists

    // ---- 扫描状态（library.scan 事件）----
    property bool scanning: false
    property int scanProcessed: 0
    property int scanTotal: 0
    property int scanFound: 0

    readonly property string currentKey: Player.hasSong && Player.song ? Player.song.key : ""

    // ---- 搜索过滤（本地过滤：歌曲 / 歌手 / 专辑 / 歌单）----
    readonly property string normalizedQuery: page.query.trim().toLowerCase()

    readonly property var filteredSongs: {
        if (page.normalizedQuery === "")
            return page.songs
        const q = page.normalizedQuery
        return page.songs.filter(function(song) {
            return String(song.title || "").toLowerCase().indexOf(q) >= 0
                    || String(song.artist || "").toLowerCase().indexOf(q) >= 0
                    || String(song.album || "").toLowerCase().indexOf(q) >= 0
        })
    }

    readonly property var filteredArtists: {
        if (page.normalizedQuery === "")
            return page.artists
        const q = page.normalizedQuery
        return page.artists.filter(function(group) {
            if (String(group.name || "").toLowerCase().indexOf(q) >= 0)
                return true
            return (group.songs || []).some(function(song) {
                return String(song.title || "").toLowerCase().indexOf(q) >= 0
            })
        })
    }

    readonly property var filteredAlbums: {
        if (page.normalizedQuery === "")
            return page.albums
        const q = page.normalizedQuery
        return page.albums.filter(function(group) {
            return String(group.name || "").toLowerCase().indexOf(q) >= 0
                    || String(group.artist || "").toLowerCase().indexOf(q) >= 0
        })
    }

    readonly property var filteredPlaylists: {
        if (page.normalizedQuery === "")
            return page.playlists
        const q = page.normalizedQuery
        return page.playlists.filter(function(playlist) {
            return String(playlist.name || "").toLowerCase().indexOf(q) >= 0
        })
    }

    // ---- 播放 ----
    function playSongs(list, index) {
        if (!list || list.length === 0)
            return
        Player.setQueue(list, Math.max(0, Math.min(index, list.length - 1)), true)
    }

    function shuffleSongs(list) {
        if (!list || list.length === 0)
            return
        const shuffled = list.slice()
        for (let i = shuffled.length - 1; i > 0; i--) {
            const j = Math.floor(Math.random() * (i + 1))
            const temp = shuffled[i]
            shuffled[i] = shuffled[j]
            shuffled[j] = temp
        }
        Player.setQueue(shuffled, 0, true)
    }

    function openPlaylist(playlistId) {
        Nav.push("localPlaylist", playlistId)
    }

    // ---- 数据加载 ----
    function reloadLibrary() {
        page.loading = true
        Api.get("library.songs", {}, function(result, error) {
            if (error) {
                page.errorText = error.message || "加载本地歌曲失败"
                page.loading = false
                return
            }
            page.songs = result.songs || []
            page.errorText = ""
            page.loading = false
        })
        Api.get("library.artists", {}, function(result) {
            page.artists = result && result.artists ? result.artists : []
        })
        Api.get("library.albums", {}, function(result) {
            page.albums = result && result.albums ? result.albums : []
        })
    }

    function reloadCollections() {
        Api.get("collections.list", {}, function(result, error) {
            if (error)
                return
            page.playlists = result.playlists || []
            page.favorites = result.favorites && result.favorites.songs ? result.favorites.songs : []
        })
    }

    function reloadScanState() {
        Api.get("library.scanState", {}, function(result) {
            if (!result || !result.scan)
                return
            page.scanning = !!result.scan.running
            page.scanProcessed = result.scan.processed || 0
            page.scanTotal = result.scan.total || 0
            page.scanFound = result.scan.found || 0
        })
    }

    function rescan() {
        Toast.show("正在扫描本地音乐…")
        Api.call("library.scan").then(function() {
            page.reloadLibrary()
        }).catch(function(error) {
            Toast.show(error && error.message ? error.message : "扫描失败", "error")
        })
    }

    // ---- 后端事件 ----
    function onLibraryChanged() {
        page.reloadLibrary()
    }

    function onLibraryScan(data) {
        if (!data)
            return
        page.scanning = !!data.running
        page.scanProcessed = data.processed || 0
        page.scanTotal = data.total || 0
        page.scanFound = data.found || 0
        if (!data.running && data.error)
            Toast.show("扫描失败：" + data.error, "error")
    }

    function onCollectionsChanged() {
        page.reloadCollections()
    }

    Component.onCompleted: {
        Api.on("library.changed", page.onLibraryChanged)
        Api.on("library.scan", page.onLibraryScan)
        Api.on("collections.changed", page.onCollectionsChanged)
        page.reloadLibrary()
        page.reloadCollections()
        page.reloadScanState()
    }

    Component.onDestruction: {
        Api.off("library.changed", page.onLibraryChanged)
        Api.off("library.scan", page.onLibraryScan)
        Api.off("collections.changed", page.onCollectionsChanged)
    }

    // 后端连接建立后补拉一次数据（页面可能在后端连接前就已创建）
    property Connections backendConnection: Connections {
        target: Backend
        function onConnectedChanged() {
            if (Backend.connected) {
                page.reloadLibrary()
                page.reloadCollections()
            }
        }
    }

    // ---- 顶部栏 ----
    Item {
        id: header

        anchors.top: parent.top
        anchors.left: parent.left
        anchors.right: parent.right
        height: 64

        Text {
            anchors.left: parent.left
            anchors.leftMargin: Theme.spacingXl
            anchors.verticalCenter: parent.verticalCenter
            text: "媒体库"
            color: Theme.onSurfaceColor
            font.family: Theme.fontFamily
            font.pixelSize: Theme.textStyle("titleLarge").size
        }

        RowLayout {
            anchors.right: parent.right
            anchors.rightMargin: Theme.spacingLg
            anchors.verticalCenter: parent.verticalCenter
            spacing: Theme.spacingXs

            IconButton {
                icon: "history"
                iconSize: 22
                size: 42
                tooltip: "最近播放"
                onClicked: Nav.push("recent")
            }

            IconButton {
                icon: "bar_chart"
                iconSize: 22
                size: 42
                tooltip: "播放统计"
                onClicked: Nav.push("stats")
            }

            IconButton {
                visible: page.primaryTab === "local"
                icon: "refresh"
                iconSize: 22
                size: 42
                tooltip: "重新扫描"
                onClicked: page.rescan()
            }

            // 搜索框（本地过滤，不需要请求后端；在线音源 Tab 不适用）
            Rectangle {
                visible: page.primaryTab !== "online"
                Layout.preferredWidth: 240
                Layout.preferredHeight: 40
                Layout.leftMargin: Theme.spacingSm
                radius: 20
                color: Theme.surfaceContainerHigh

                NeriIcon {
                    id: searchIcon

                    anchors.left: parent.left
                    anchors.leftMargin: Theme.spacingMd
                    anchors.verticalCenter: parent.verticalCenter
                    name: "search"
                    iconSize: 18
                    color: Theme.onSurfaceVariantColor
                }

                TextField {
                    anchors.left: searchIcon.right
                    anchors.right: parent.right
                    anchors.rightMargin: Theme.spacingMd
                    anchors.verticalCenter: parent.verticalCenter
                    padding: 0
                    text: page.query
                    placeholderText: "搜索本地歌曲 / 歌手 / 专辑"
                    placeholderTextColor: Theme.onSurfaceVariantColor
                    color: Theme.onSurfaceColor
                    selectByMouse: true
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.textStyle("bodyMedium").size
                    background: null
                    onTextChanged: page.query = text
                }
            }
        }
    }

    // ---- 主 Tab（本地 / 收藏 / 在线音源）----
    // 两行分类合并成一行：主分类靠左、子分类靠右。
    // 之前两行叠在一起（内容区紧贴主 TabBar 底部、子分类又从父项顶部开始），
    // 视觉上会重叠；改成同一行左右分布，右侧空间也利用起来了。
    Item {
        id: tabRow

        anchors.top: header.bottom
        anchors.left: parent.left
        anchors.right: parent.right
        height: 40

        TabBar {
            id: primaryTabs

            anchors.left: parent.left
            anchors.leftMargin: Theme.spacingLg
            anchors.verticalCenter: parent.verticalCenter
            implicitHeight: 34
            options: [
                { value: "local", label: "本地" },
                { value: "favorites", label: "收藏" },
                { value: "online", label: "在线音源" }
            ]
            value: page.primaryTab
            onSelected: function(value) { page.primaryTab = value }
        }

        // 子分类（仅本地 Tab）：与主分类同一行、右对齐
        TabBar {
            id: localTabs

            anchors.right: parent.right
            anchors.rightMargin: Theme.spacingLg
            anchors.verticalCenter: parent.verticalCenter
            implicitHeight: 34
            visible: page.primaryTab === "local" && page.errorText === ""
            horizontalPadding: Theme.spacingLg
            options: [
                { value: "songs", label: "歌曲" },
                { value: "artists", label: "歌手" },
                { value: "albums", label: "专辑" },
                { value: "playlists", label: "歌单" }
            ]
            value: page.localTab
            onSelected: function(value) { page.localTab = value }
        }
    }

    // ---- 内容 ----
    Item {
        id: contentArea

        anchors.top: tabRow.bottom
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.bottom: parent.bottom
        clip: true

        // 扫描进度条（仅本地 Tab）
        Item {
            id: scanBanner

            visible: page.scanning && page.primaryTab === "local"
            anchors.top: parent.top
            anchors.left: parent.left
            anchors.right: parent.right
            height: visible ? 30 : 0

            Rectangle {
                anchors.left: parent.left
                anchors.right: parent.right
                anchors.leftMargin: Theme.spacingLg
                anchors.rightMargin: Theme.spacingLg
                anchors.top: parent.top
                height: 3
                radius: 1.5
                color: Theme.surfaceVariant

                Rectangle {
                    width: parent.width * (page.scanTotal > 0 ? Math.min(1, page.scanProcessed / page.scanTotal) : 0)
                    height: parent.height
                    radius: parent.radius
                    color: Theme.primary
                }
            }

            Text {
                anchors.left: parent.left
                anchors.leftMargin: Theme.spacingLg
                anchors.verticalCenter: parent.verticalCenter
                anchors.verticalCenterOffset: 4
                text: "正在扫描本地音乐 " + page.scanProcessed + "/" + page.scanTotal
                      + "，已找到 " + page.scanFound + " 首"
                color: Theme.onSurfaceVariantColor
                font.family: Theme.fontFamily
                font.pixelSize: Theme.textStyle("labelSmall").size
            }
        }

        // 加载 / 错误
        LoadingState {
            anchors.top: scanBanner.bottom
            anchors.left: parent.left
            anchors.right: parent.right
            visible: page.loading && page.songs.length === 0 && page.primaryTab === "local"
            text: "正在加载本地媒体库…"
        }

        ErrorCard {
            anchors.top: scanBanner.bottom
            anchors.left: parent.left
            anchors.right: parent.right
            visible: page.errorText !== ""
            message: page.errorText
            onRetryClicked: page.reloadLibrary()
        }

        // ---- 本地 Tab ----
        Item {
            id: localContent

            anchors.top: scanBanner.bottom
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.bottom: parent.bottom
            visible: page.primaryTab === "local" && page.errorText === ""

            // 歌曲
            Item {
                id: songsPane

                anchors.top: parent.top
                anchors.left: parent.left
                anchors.right: parent.right
                anchors.bottom: parent.bottom
                visible: page.localTab === "songs"

                Item {
                    id: songsToolbar

                    anchors.top: parent.top
                    anchors.left: parent.left
                    anchors.right: parent.right
                    height: 44

                    Text {
                        anchors.left: parent.left
                        anchors.leftMargin: Theme.spacingLg
                        anchors.verticalCenter: parent.verticalCenter
                        text: Format.songsSummary(page.filteredSongs)
                        color: Theme.onSurfaceVariantColor
                        font.family: Theme.fontFamily
                        font.pixelSize: Theme.textStyle("labelMedium").size
                    }

                    Row {
                        anchors.right: parent.right
                        anchors.rightMargin: Theme.spacingLg
                        anchors.verticalCenter: parent.verticalCenter
                        spacing: Theme.spacingSm

                        LibraryButton {
                            text: "播放全部"
                            icon: "play_arrow"
                            enabled: page.filteredSongs.length > 0
                            onClicked: page.playSongs(page.filteredSongs, 0)
                        }

                        LibraryButton {
                            text: "随机"
                            icon: "shuffle"
                            enabled: page.filteredSongs.length > 0
                            onClicked: page.shuffleSongs(page.filteredSongs)
                        }
                    }
                }

                EmptyState {
                    anchors.fill: parent
                    visible: page.filteredSongs.length === 0
                    icon: page.query.trim() !== "" ? "search_off" : "folder"
                    title: page.query.trim() !== "" ? "没有匹配的本地歌曲" : "还没有本地音乐"
                    hint: page.query.trim() !== ""
                          ? "换个关键词试试"
                          : "在设置 - 媒体库中添加音乐文件夹，然后点击右上角刷新按钮扫描"
                }

                ListView {
                    id: songsList

                    anchors.top: songsToolbar.bottom
                    anchors.left: parent.left
                    anchors.right: parent.right
                    anchors.bottom: parent.bottom
                    visible: page.filteredSongs.length > 0
                    clip: true
                    model: page.filteredSongs
                    boundsBehavior: Flickable.StopAtBounds
                    bottomMargin: Theme.spacingXl

                    delegate: Item {
                        id: songDelegate

                        // ListView 注入的上下文属性（index / modelData）
                        property int itemIndex: index
                        property var item: modelData

                        width: songsList.width
                        height: 58

                        SongRow {
                            anchors.fill: parent
                            song: songDelegate.item
                            index: songDelegate.itemIndex
                            playing: page.currentKey !== "" && songDelegate.item
                                     && songDelegate.item.key === page.currentKey
                            onActivated: function(index) { page.playSongs(page.filteredSongs, index) }
                            onMoreClicked: function(song) { songMenu.openFor(song, {}) }
                            onRightClicked: function(song) { songMenu.openFor(song) }
                        }
                    }
                }
            }

            // 歌手
            Flickable {
                id: artistsPane

                anchors.top: parent.top
                anchors.left: parent.left
                anchors.right: parent.right
                anchors.bottom: parent.bottom
                visible: page.localTab === "artists"
                clip: true
                contentWidth: width
                contentHeight: artistsFlow.height + Theme.spacingXl
                boundsBehavior: Flickable.StopAtBounds

                Flow {
                    id: artistsFlow

                    x: Theme.spacingMd
                    y: Theme.spacingSm
                    width: artistsPane.width - Theme.spacingMd * 2
                    spacing: Theme.spacingXs

                    Repeater {
                        model: page.filteredArtists

                        delegate: ArtistCard {
                            id: artistDelegate

                            property var group: modelData

                            name: group ? group.name : ""
                            subtitle: (group && group.songs ? group.songs.length : 0) + " 首"
                            coverPath: group && group.songs && group.songs.length > 0 && group.songs[0].artworkPath
                                       ? group.songs[0].artworkPath : ""
                            onClicked: Nav.push("localArtist", group.name)
                        }
                    }
                }

                EmptyState {
                    anchors.fill: parent
                    visible: page.filteredArtists.length === 0
                    icon: "person"
                    title: "暂无本地歌手"
                    hint: "导入本地音频后，会按歌曲艺术家自动分类"
                }
            }

            // 专辑
            Flickable {
                id: albumsPane

                anchors.top: parent.top
                anchors.left: parent.left
                anchors.right: parent.right
                anchors.bottom: parent.bottom
                visible: page.localTab === "albums"
                clip: true
                contentWidth: width
                contentHeight: albumsFlow.height + Theme.spacingXl
                boundsBehavior: Flickable.StopAtBounds

                Flow {
                    id: albumsFlow

                    x: Theme.spacingMd
                    y: Theme.spacingSm
                    width: albumsPane.width - Theme.spacingMd * 2
                    spacing: Theme.spacingXs

                    Repeater {
                        model: page.filteredAlbums

                        delegate: CollectionCard {
                            id: albumDelegate

                            property var group: modelData

                            name: group ? group.name : ""
                            subtitle: (group && group.artist ? group.artist : "未知艺术家") + " · "
                                      + (group && group.songs ? group.songs.length : 0) + " 首"
                            coverPath: group && group.songs && group.songs.length > 0 && group.songs[0].artworkPath
                                       ? group.songs[0].artworkPath : ""
                            onClicked: page.playSongs(group.songs, 0)
                        }
                    }
                }

                EmptyState {
                    anchors.fill: parent
                    visible: page.filteredAlbums.length === 0
                    icon: "album"
                    title: "暂无本地专辑"
                    hint: "带有专辑标签的本地音乐会显示在这里"
                }
            }

            // 歌单
            Flickable {
                id: playlistsPane

                anchors.top: parent.top
                anchors.left: parent.left
                anchors.right: parent.right
                anchors.bottom: parent.bottom
                visible: page.localTab === "playlists"
                clip: true
                contentWidth: width
                contentHeight: playlistColumn.height + Theme.spacingXl
                boundsBehavior: Flickable.StopAtBounds

                Column {
                    id: playlistColumn

                    width: playlistsPane.width

                    Item {
                        width: parent.width
                        height: 44

                        Text {
                            anchors.left: parent.left
                            anchors.leftMargin: Theme.spacingLg
                            anchors.verticalCenter: parent.verticalCenter
                            text: "歌单"
                            color: Theme.onSurfaceColor
                            font.family: Theme.fontFamily
                            font.pixelSize: Theme.textStyle("titleMedium").size
                            font.weight: Font.DemiBold
                        }

                        LibraryButton {
                            anchors.right: parent.right
                            anchors.rightMargin: Theme.spacingLg
                            anchors.verticalCenter: parent.verticalCenter
                            text: "新建歌单"
                            icon: "add"
                            onClicked: createPlaylistDialog.openFor("")
                        }
                    }

                    Flow {
                        x: Theme.spacingMd
                        width: playlistsPane.width - Theme.spacingMd * 2
                        spacing: Theme.spacingXs

                        Repeater {
                            model: 1 + page.filteredPlaylists.length

                            delegate: CollectionCard {
                                id: playlistDelegate

                                // Repeater 注入的 index；0 号固定是系统收藏歌单
                                property int cardIndex: index

                                readonly property var playlist: cardIndex === 0
                                                                 ? ({ id: "system:favorites", name: "我喜欢的音乐", songs: page.favorites, system: true })
                                                                 : page.filteredPlaylists[cardIndex - 1]

                                name: playlist.name
                                subtitle: (playlist.songs ? playlist.songs.length : 0) + " 首"
                                coverPath: playlist.songs && playlist.songs.length > 0 && playlist.songs[0].artworkPath
                                           ? playlist.songs[0].artworkPath : ""
                                placeholderIcon: "favorite"
                                onClicked: page.openPlaylist(playlist.id || "system:favorites")
                            }
                        }
                    }

                    EmptyState {
                        width: parent.width
                        visible: page.filteredPlaylists.length === 0
                        icon: "queue_music"
                        title: page.query.trim() !== "" ? "没有匹配的歌单" : "还没有自建歌单"
                        hint: "点击右上角「新建歌单」，再从歌曲的更多菜单里添加歌曲"
                    }
                }
            }
        }

        // ---- 收藏 Tab ----
        ListView {
            id: favoritesPane

            anchors.top: scanBanner.bottom
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.bottom: parent.bottom
            visible: page.primaryTab === "favorites"
            clip: true
            model: page.favorites
            boundsBehavior: Flickable.StopAtBounds
            bottomMargin: Theme.spacingXl

            header: Column {
                width: favoritesPane.width

                Item {
                    width: parent.width
                    height: 124

                    Rectangle {
                        anchors.left: parent.left
                        anchors.right: parent.right
                        anchors.leftMargin: Theme.spacingLg
                        anchors.rightMargin: Theme.spacingLg
                        anchors.top: parent.top
                        anchors.topMargin: Theme.spacingSm
                        anchors.bottom: parent.bottom
                        anchors.bottomMargin: Theme.spacingSm
                        radius: Theme.radiusXl
                        color: Theme.secondaryContainer

                        Column {
                            anchors.left: parent.left
                            anchors.right: parent.right
                            anchors.verticalCenter: parent.verticalCenter
                            anchors.leftMargin: Theme.spacingLg
                            anchors.rightMargin: Theme.spacingLg
                            spacing: Theme.spacingSm

                            RowLayout {
                                width: parent.width
                                spacing: 10

                                NeriIcon {
                                    name: "favorite"
                                    filled: true
                                    iconSize: 24
                                    color: Theme.onSecondaryContainerColor
                                    Layout.alignment: Qt.AlignVCenter
                                }

                                Text {
                                    Layout.fillWidth: true
                                    text: "我喜欢的音乐"
                                    elide: Text.ElideRight
                                    color: Theme.onSecondaryContainerColor
                                    font.family: Theme.fontFamily
                                    font.pixelSize: Theme.textStyle("titleMedium").size
                                    font.weight: Font.DemiBold
                                }

                                Text {
                                    text: page.favorites.length + " 首"
                                    Layout.alignment: Qt.AlignVCenter
                                    color: Theme.onSecondaryContainerColor
                                    font.family: Theme.fontFamily
                                    font.pixelSize: Theme.textStyle("labelMedium").size
                                }
                            }

                            Row {
                                spacing: Theme.spacingSm

                                LibraryButton {
                                    text: "播放全部"
                                    icon: "play_arrow"
                                    filled: true
                                    enabled: page.favorites.length > 0
                                    onClicked: page.playSongs(page.favorites, 0)
                                }

                                LibraryButton {
                                    text: "查看歌单"
                                    enabled: page.favorites.length > 0
                                    onClicked: page.openPlaylist("system:favorites")
                                }
                            }
                        }
                    }
                }

                SectionHeader {
                    width: parent.width
                    title: "喜欢的歌曲"
                    icon: "favorite"
                    trailingText: page.favorites.length + " 首"
                }
            }

            delegate: Item {
                id: favoriteDelegate

                property int itemIndex: index
                property var item: modelData

                width: favoritesPane.width
                height: 58

                SongRow {
                    anchors.fill: parent
                    song: favoriteDelegate.item
                    index: favoriteDelegate.itemIndex
                    playing: page.currentKey !== "" && favoriteDelegate.item
                             && favoriteDelegate.item.key === page.currentKey
                    onActivated: function(index) { page.playSongs(page.favorites, index) }
                    onMoreClicked: function(song) { songMenu.openFor(song, { playlistId: "system:favorites" }) }
                    onRightClicked: function(song) { songMenu.openFor(song, { playlistId: "system:favorites" }) }
                }
            }

            footer: EmptyState {
                width: favoritesPane.width
                visible: page.favorites.length === 0
                icon: "favorite_border"
                title: "还没有收藏的歌曲"
                hint: "在歌曲的「更多」菜单里点击「收藏」，歌曲会出现在这里"
            }
        }

        // ---- 在线音源 Tab ----
        OnlineSourcesTab {
            anchors.top: scanBanner.bottom
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.bottom: parent.bottom
            visible: page.primaryTab === "online"
        }
    }

    // ---- 歌曲操作菜单 / 新建歌单 ----
    SongActionsMenu {
        id: songMenu

        onRemoveRequested: function(song) {
            const playlistId = songMenu.playlistId !== "" ? songMenu.playlistId : "system:favorites"
            Api.call("collections.removeSong", { id: playlistId, songKey: song.key }).then(function() {
                Toast.show("已从列表移除")
                page.reloadCollections()
            }).catch(function(error) {
                Toast.show(error && error.message ? error.message : "移除失败", "error")
            })
        }
    }

    CreatePlaylistDialog {
        id: createPlaylistDialog

        onCreated: function() {
            page.reloadCollections()
        }
    }
}
