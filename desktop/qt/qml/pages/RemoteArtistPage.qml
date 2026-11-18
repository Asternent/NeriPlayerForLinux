import QtQuick
import "../components"
import "../components/explore"
import "../components/library"

// 在线歌手详情页：圆形头像 + 歌曲数 + 播放全部 + 专辑网格 + 热门歌曲。
// Nav.currentArg 是 OnlineArtist 对象（id/name/avatarUrl/songCount/source）。
Item {
    id: page

    readonly property var arg: Nav.currentArg
    readonly property string argSource: arg && arg.source ? String(arg.source) : "NETEASE"
    readonly property string argId: arg && arg.id !== undefined && arg.id !== null ? String(arg.id) : ""
    readonly property var artistData: detail && detail.artist ? detail.artist : (arg || ({}))
    readonly property string headerTitle: artistData && artistData.name ? artistData.name : "在线歌手"
    readonly property string headerCover: artistData && artistData.avatarUrl ? artistData.avatarUrl
                                     : (arg && arg.avatarUrl ? arg.avatarUrl : "")
    readonly property string headerSubtitle: {
        const parts = ["网易云歌手"]
        if (page.detail && page.detail.artist && page.detail.artist.songCount > 0)
            parts.push("作品 " + page.detail.artist.songCount + " 首")
        parts.push("热门 " + page.songs.length + " 首")
        return parts.join(" · ")
    }

    property var detail: ({})
    property var songs: []
    property var albums: []
    property bool loading: true
    property string errorMessage: ""
    property int _seq: 0

    function load() {
        if (argId === "") {
            loading = false
            errorMessage = "缺少歌手参数，请从探索页打开"
            return
        }
        loading = true
        errorMessage = ""
        const seq = ++page._seq
        Api.call("online.artistDetail", { source: page.argSource, id: page.argId }).then(function(result) {
            if (seq !== page._seq)
                return
            page.detail = result || ({})
            page.songs = result && result.songs ? result.songs : []
            page.albums = result && result.albums ? result.albums : []
            page.loading = false
        }).catch(function(error) {
            if (seq !== page._seq)
                return
            page.errorMessage = error && error.message ? error.message : "加载失败"
            page.loading = false
        })
    }

    PageActions {
        id: actions
    }

    function playAll() {
        actions.playSongs(page.songs, 0)
    }

    function playAt(index) {
        if (index < 0 || index >= page.songs.length)
            return
        actions.playSongs(page.songs, index)
    }

    function enqueueSong(song) {
        if (!song)
            return
        actions.enqueueNext([song])
    }

    function openAlbum(album) {
        if (!album)
            return
        Nav.push("onlineCollection", {
                      "id": String(album.id),
                      "name": album.name || "",
                      "source": album.source || page.argSource,
                      "coverUrl": album.coverUrl || "",
                      "trackCount": album.trackCount || 0,
                      "kind": "album"
                  })
    }

    Component.onCompleted: {
        if (Api.connected)
            load()
    }

    // 冷启动/截图时页面可能先于后端连接创建，连接建立后再加载。
    Connections {
        target: Backend
        function onConnectedChanged() {
            if (Backend.connected)
                page.load()
        }
    }

    // ---------------------------------------------------------------- 顶部栏

    Item {
        id: topBar
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.top: parent.top
        height: 60

        IconButton {
            id: backButton
            anchors.left: parent.left
            anchors.leftMargin: 6
            anchors.verticalCenter: parent.verticalCenter
            icon: "chevron_left"
            iconSize: 24
            tooltip: "返回"
            onClicked: Nav.back()
        }

        Text {
            anchors.left: backButton.right
            anchors.leftMargin: 4
            anchors.right: parent.right
            anchors.rightMargin: 16
            anchors.verticalCenter: parent.verticalCenter
            text: page.headerTitle
            elide: Text.ElideRight
            color: Theme.onSurfaceColor
            font.family: Theme.fontFamily
            font.pixelSize: 20
            font.weight: Font.DemiBold
        }
    }

    // ---------------------------------------------------------------- 内容

    Item {
        id: contentArea
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.top: topBar.bottom
        anchors.bottom: parent.bottom
        clip: true

        // 数据就绪后再创建列表：列表头部（专辑网格）高度在创建时确定，
        // 避免先以 0 高度创建后高度剧变导致 ListView 内容偏移。
        Loader {
            anchors.fill: parent
            active: !page.loading && page.errorMessage === ""
            sourceComponent: tableComponent
        }

        LoadingState {
            anchors.horizontalCenter: parent.horizontalCenter
            anchors.top: parent.top
            anchors.topMargin: 96
            visible: page.loading
            text: "正在加载歌手信息…"
        }

        ErrorCard {
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.leftMargin: 16
            anchors.rightMargin: 16
            anchors.top: parent.top
            anchors.topMargin: 24
            visible: page.errorMessage !== ""
            message: "加载失败：" + page.errorMessage
            onRetry: page.load()
        }
    }

    Component {
        id: tableComponent

        TrackListTable {
            anchors.fill: parent
            songs: page.songs
            headerContent: Component {
                Column {
                    width: parent ? parent.width : 0

                    DetailHeader {
                        width: parent.width
                        circular: true
                        coverUrl: page.headerCover
                        title: page.headerTitle
                        subtitle: page.headerSubtitle
                        showPlayAll: page.songs.length > 0
                        showCollect: false
                        onPlayAllClicked: page.playAll()
                    }

                    SectionHeader {
                        width: parent.width
                        visible: page.albums.length > 0
                        title: "专辑（" + page.albums.length + "）"
                        icon: "album"
                    }

                    CollectionGrid {
                        width: parent.width
                        visible: page.albums.length > 0
                        items: page.albums
                        onItemClicked: function(item) { page.openAlbum(item) }
                    }

                    SectionHeader {
                        width: parent.width
                        title: "热门歌曲（" + page.songs.length + "）"
                        icon: "music_note"
                    }

                    Item {
                        width: parent.width
                        height: page.songs.length === 0 ? 200 : 8

                        EmptyState {
                            anchors.fill: parent
                            visible: page.songs.length === 0
                            title: "暂无歌曲"
                            hint: "该歌手的热门歌曲暂时不可用"
                            icon: "person"
                        }
                    }
                }
            }
            onSongActivated: function(index) { page.playAt(index) }
            onSongMoreClicked: function(index, song) { page.enqueueSong(song) }
        }
    }
}
