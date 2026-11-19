import QtQuick
import "../components"
import "../components/explore"
import "../components/library"

// 在线歌单 / 专辑 / 收藏夹详情页。
// Nav.currentArg 是 OnlineCollection 对象（可额外带 kind: playlist|album|favorites）。
Item {
    id: page

    readonly property var arg: Nav.currentArg
    readonly property string kind: arg && arg.kind ? String(arg.kind).toLowerCase() : "playlist"
    readonly property string argSource: arg && arg.source ? String(arg.source).toUpperCase() : "NETEASE"
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
    // 音源后端能力缺失时的降级提示（页面仍展示参数带来的头部信息）。
    property bool degraded: false
    property string degradeText: ""
    property int _seq: 0

    function load() {
        if (argId === "") {
            loading = false
            errorMessage = "缺少歌单参数，请从探索页打开"
            return
        }
        loading = true
        errorMessage = ""
        degraded = false
        degradeText = ""
        const seq = ++page._seq

        // YouTube 歌单走 ytmusic.*（online.collectionDetail 不支持 YOUTUBE）。
        if (page.argSource === "YOUTUBE") {
            Api.call("ytmusic.playlist", { "id": page.argId }).then(function(result) {
                if (seq !== page._seq)
                    return
                const data = result || ({})
                page.detail = {
                    "title": data.title || data.name || "",
                    "coverUrl": data.coverUrl || data.thumbnail || "",
                    "description": data.description || "",
                    "creator": data.creator || data.artist || ""
                }
                page.songs = data.songs || []
                page.loading = false
            }).catch(function(error) {
                if (seq !== page._seq)
                    return
                if (error && error.code === "unknown_method") {
                    page.degraded = true
                    page.degradeText = "当前后端暂不支持 YouTube 歌单详情（ytmusic.playlist）"
                    page.loading = false
                    return
                }
                page.errorMessage = error && error.message ? error.message : "加载失败"
                page.loading = false
            })
            return
        }

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

    function openAlbumPage() {
        if (page.argId === "")
            return
        Nav.push("albumDetail", {
                     "source": page.argSource,
                     "id": page.argId,
                     "name": page.headerTitle,
                     "coverUrl": page.headerCover,
                     "creator": page.detail && page.detail.creator ? String(page.detail.creator) : "",
                     "trackCount": page.songs.length
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

                    // 降级提示（YouTube 后端命令缺失等）。
                    Rectangle {
                        width: parent.width - Theme.spacingLg * 2
                        x: Theme.spacingLg
                        visible: page.degraded
                        height: visible ? degradeRow.implicitHeight + Theme.spacingMd * 2 : 0
                        radius: Theme.radiusMd
                        color: Theme.surfaceContainerHigh

                        Row {
                            id: degradeRow

                            anchors.left: parent.left
                            anchors.right: parent.right
                            anchors.verticalCenter: parent.verticalCenter
                            anchors.leftMargin: Theme.spacingMd
                            anchors.rightMargin: Theme.spacingMd
                            spacing: Theme.spacingSm

                            NeriIcon {
                                width: 18
                                height: 18
                                name: "info"
                                iconSize: 18
                                color: Theme.onSurfaceVariantColor
                            }

                            Text {
                                width: parent.width - 18 - Theme.spacingSm
                                text: page.degradeText
                                wrapMode: Text.Wrap
                                color: Theme.onSurfaceVariantColor
                                font.family: Theme.fontFamily
                                font.pixelSize: Theme.textStyle("bodySmall").size
                            }
                        }
                    }

                    // 在线专辑页 → 专辑详情页入口（专辑页有封面头部/简介/总时长）。
                    Item {
                        width: parent.width
                        height: page.kind === "album" ? 44 : 0
                        visible: page.kind === "album"

                        Rectangle {
                            anchors.left: parent.left
                            anchors.leftMargin: Theme.spacingLg
                            anchors.verticalCenter: parent.verticalCenter
                            height: 34
                            width: albumEntryRow.implicitWidth + 28
                            radius: height / 2
                            color: albumEntryMouse.containsMouse ? Theme.withAlpha(Theme.primary, 0.1) : "transparent"
                            border.width: 1
                            border.color: Theme.outlineVariant

                            Row {
                                id: albumEntryRow

                                anchors.centerIn: parent
                                spacing: 6

                                NeriIcon {
                                    width: 18
                                    height: 18
                                    name: "album"
                                    iconSize: 18
                                    color: Theme.primary
                                }

                                Text {
                                    height: 20
                                    text: "打开专辑页"
                                    verticalAlignment: Text.AlignVCenter
                                    color: Theme.primary
                                    font.family: Theme.fontFamily
                                    font.pixelSize: Theme.textStyle("labelLarge").size
                                    font.weight: Font.Medium
                                }
                            }

                            MouseArea {
                                id: albumEntryMouse

                                anchors.fill: parent
                                hoverEnabled: true
                                cursorShape: Qt.PointingHandCursor
                                onClicked: page.openAlbumPage()
                            }
                        }
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
            onSongMoreClicked: function(index, song) { songMenu.openFor(song, {}) }
        }
    }

    AddToPlaylistDialog {
        id: collectDialog
    }

    SongActionsMenu {
        id: songMenu
    }
}
