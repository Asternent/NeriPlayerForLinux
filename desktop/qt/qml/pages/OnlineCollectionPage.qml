import QtQuick
import "../components"
import "../components/explore"
import "../components/library"

// 在线歌单 / 专辑 / 收藏夹详情页。
// Nav.currentArg 是 OnlineCollection 对象（可额外带 kind: playlist|album|favorites）。
Item {
    id: page

    readonly property var arg: Nav.currentArg
    readonly property string kind: arg && arg.kind ? String(arg.kind) : "playlist"
    readonly property string argSource: arg && arg.source ? String(arg.source) : "NETEASE"
    readonly property string argId: arg && arg.id !== undefined && arg.id !== null ? String(arg.id) : ""
    readonly property string headerTitle: detail && detail.title ? detail.title : (arg && arg.name ? arg.name : "在线歌单")
    readonly property string headerCover: detail && detail.coverUrl ? detail.coverUrl
                                     : (arg && arg.coverUrl ? arg.coverUrl : "")
    readonly property string headerDescription: detail && detail.description ? detail.description : ""
    readonly property string headerSubtitle: {
        const parts = []
        if (detail && detail.creator)
            parts.push(detail.creator)
        parts.push("已加载 " + page.songs.length + " 首")
        return parts.join(" · ")
    }

    property var detail: ({})
    property var songs: []
    property bool loading: true
    property string errorMessage: ""
    property int _seq: 0

    function load() {
        if (argId === "") {
            loading = false
            errorMessage = "缺少歌单参数，请从探索页打开"
            return
        }
        loading = true
        errorMessage = ""
        const seq = ++page._seq
        Api.call("online.collectionDetail", {
                     source: page.argSource,
                     id: page.argId,
                     kind: page.kind
                 }).then(function(result) {
            if (seq !== page._seq)
                return
            page.detail = result || ({})
            page.songs = result && result.songs ? result.songs : []
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

        // 数据就绪后再创建列表，避免头部高度在创建后变化导致 ListView 内容偏移。
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
            text: "正在加载歌单…"
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
            showAlbum: page.kind !== "album"
            headerContent: Component {
                Column {
                    width: parent ? parent.width : 0

                    DetailHeader {
                        width: parent.width
                        coverUrl: page.headerCover
                        title: page.headerTitle
                        subtitle: page.headerSubtitle
                        description: page.headerDescription
                        showPlayAll: page.songs.length > 0
                        showCollect: page.songs.length > 0
                        onPlayAllClicked: page.playAll()
                        onCollectClicked: collectDialog.openFor(page.songs)
                    }

                    SectionHeader {
                        width: parent.width
                        title: "歌曲（" + page.songs.length + "）"
                        icon: "queue_music"
                    }

                    Item {
                        width: parent.width
                        height: page.songs.length === 0 ? 220 : 8

                        EmptyState {
                            anchors.fill: parent
                            visible: page.songs.length === 0
                            title: "暂时没有获取到歌曲"
                            hint: "该歌单可能需要登录后才能完整访问"
                            icon: "queue_music"
                        }
                    }
                }
            }
            onSongActivated: function(index) { page.playAt(index) }
            onSongMoreClicked: function(index, song) { page.enqueueSong(song) }
        }
    }

    AddToPlaylistDialog {
        id: collectDialog
    }
}
