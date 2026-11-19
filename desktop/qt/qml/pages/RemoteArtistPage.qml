import QtQuick
import QtQuick.Controls
import "../components"
import "../components/explore"
import "../components/library"

// 统一歌手页（路由 remoteArtist 不变）。
// 路由 arg：{source: "NETEASE"|"YOUTUBE"|"LOCAL"|"BILIBILI", id, name, avatarUrl, songCount}
//   - NETEASE：online.artistDetail → {artist, songs, albums}
//   - YOUTUBE：ytmusic.artist → {name, avatarUrl, description, songs, albums}（后端缺失时降级）
//   - LOCAL：library.songs 按 artist 字段过滤，并本地归类专辑
//   - BILIBILI：无歌手详情接口，按参数降级展示
// 页面：圆形头像 + 名称/作品数、播放全部/随机、简介、热门歌曲、专辑网格（点→albumDetail）。
Item {
    id: page

    readonly property var arg: Nav.currentArg
    readonly property string argSource: arg && arg.source ? String(arg.source).toUpperCase() : "NETEASE"
    readonly property string argId: page.normalizeId(arg && arg.id)
    readonly property string argName: arg && arg.name ? String(arg.name) : ""
    readonly property string argAvatarUrl: arg && arg.avatarUrl ? String(arg.avatarUrl) : ""
    readonly property int argSongCount: arg && arg.songCount !== undefined && arg.songCount !== null
                                         ? Number(arg.songCount) : 0

    property var detail: ({})
    property var songs: []
    property var albums: []
    property bool loading: true
    property string errorMessage: ""
    property bool degraded: false
    property string degradeText: ""
    property int _seq: 0
    // 只有歌手名、没有数字 id 时（如歌曲菜单按名字降级）用搜索兜底一次。
    property bool _searchTried: false

    /** 空串 / "0" 都不是可用的歌手 id，按名称搜索兜底。 */
    function normalizeId(value) {
        const text = value === undefined || value === null ? "" : String(value).trim()
        if (text === "" || text === "0" || text === "null" || text === "undefined")
            return ""
        return text
    }

    readonly property string sourceLabel: {
        switch (page.argSource) {
        case "NETEASE":
            return "网易云音乐"
        case "YOUTUBE":
            return "YouTube Music"
        case "BILIBILI":
            return "哔哩哔哩"
        default:
            return "本地音乐"
        }
    }
    readonly property var artistData: page.detail && page.detail.artist ? page.detail.artist : ({})
    readonly property string headerTitle: {
        if (page.artistData && page.artistData.name)
            return String(page.artistData.name)
        return page.argName !== "" ? page.argName : "歌手"
    }
    readonly property string headerAvatar: {
        if (page.artistData && page.artistData.avatarUrl)
            return String(page.artistData.avatarUrl)
        if (page.argSource === "LOCAL" && page.songs.length > 0 && page.songs[0].artworkPath)
            return ""
        return page.argAvatarUrl
    }
    // 本地歌手没有头像 URL，用首曲封面缓存路径兜底，列表里比占位图标更容易辨认。
    readonly property string headerAvatarPath: {
        if (page.argSource !== "LOCAL")
            return ""
        for (let i = 0; i < page.songs.length; i++) {
            if (page.songs[i] && page.songs[i].artworkPath)
                return String(page.songs[i].artworkPath)
        }
        return ""
    }
    readonly property int workCount: {
        if (page.artistData && page.artistData.songCount > 0)
            return Number(page.artistData.songCount)
        if (page.songs.length > 0)
            return page.songs.length
        return page.argSongCount
    }
    readonly property string headerDescription: {
        if (page.detail && page.detail.description)
            return String(page.detail.description)
        if (page.detail && page.detail.artist && page.detail.artist.description)
            return String(page.detail.artist.description)
        if (page.detail && page.detail.artist && page.detail.artist.briefDesc)
            return String(page.detail.artist.briefDesc)
        if (page.argSource === "NETEASE" && page.workCount > 0)
            return "网易云音乐歌手，共 " + page.workCount + " 首作品。"
        if (page.argSource === "YOUTUBE")
            return "YouTube Music 艺术家。"
        if (page.argSource === "LOCAL")
            return "本地媒体库中的歌手，共 " + page.songs.length + " 首歌曲、"
                   + page.albums.length + " 张专辑。"
        return page.degradeText
    }
    // 头部信息行：作品数 + 当前热门曲目数（音源在顶栏/降级提示里展示，不重复）。
    readonly property string statsText: {
        const parts = []
        if (page.workCount > 0)
            parts.push("作品 " + page.workCount + " 首")
        if (page.songs.length > 0)
            parts.push("热门 " + page.songs.length + " 首")
        return parts.join(" · ")
    }
    // 包成 {song, idx}，避免 Repeater 上下文里的 index 与 SongRow.index 同名冲突。
    readonly property var songRows: {
        const output = []
        for (let i = 0; i < page.songs.length; i++)
            output.push({ "song": page.songs[i], "idx": i })
        return output
    }

    PageActions {
        id: actions
    }

    // ---------------------------------------------------------------- 加载

    function normalizeAlbum(item, source) {
        if (!item)
            return null
        return {
            "id": item.id !== undefined && item.id !== null && String(item.id) !== ""
                  ? String(item.id) : String(item.browseId || item.name || item.title || ""),
            "name": item.name || item.title || "",
            "creator": item.creator || item.artist || "",
            "coverUrl": item.coverUrl || item.thumbnail || "",
            "trackCount": item.trackCount || 0,
            "source": item.source || source
        }
    }

    function applyArtist(artist, list, albumList) {
        page.detail = ({ "artist": artist || ({}) })
        page.songs = list || []
        page.albums = albumList || []
        page.loading = false
    }

    function handleLoadError(error) {
        if (error && error.code === "unknown_method") {
            page.degraded = true
            page.degradeText = "当前后端暂不支持 YouTube 歌手详情（ytmusic.artist）"
            page.detail = ({})
            page.songs = []
            page.albums = []
            page.loading = false
            return
        }
        page.errorMessage = error && error.message ? error.message : "加载失败"
        page.loading = false
    }

    function load() {
        page.errorMessage = ""
        page.degraded = false
        page.degradeText = ""
        page._searchTried = false

        if (page.argSource === "LOCAL") {
            page.loadLocal()
            return
        }
        if (page.argSource === "BILIBILI") {
            page.detail = ({ "artist": ({ "name": page.argName, "avatarUrl": page.argAvatarUrl }) })
            page.songs = []
            page.albums = []
            page.loading = false
            page.degraded = true
            page.degradeText = "B 站暂不支持歌手详情，可在探索页按名称搜索「" + page.argName + "」。"
            if (page.argName === "")
                page.degradeText = "B 站暂不支持歌手详情，可在探索页搜索该 UP 主。"
            return
        }
        if (page.argId === "" && page.argName === "") {
            page.loading = false
            page.errorMessage = "缺少歌手参数，请从探索页或歌曲菜单打开"
            return
        }
        if (page.argSource === "YOUTUBE" && page.argId === "") {
            page.loading = false
            page.degraded = true
            page.degradeText = "该 YouTube 歌曲没有歌手 id，暂无法打开歌手页，可在探索页搜索「"
                               + page.argName + "」"
            return
        }

        page.loading = true
        const seq = ++page._seq

        if (page.argSource === "YOUTUBE") {
            Api.call("ytmusic.artist", { "id": page.argId }).then(function(result) {
                if (seq !== page._seq)
                    return
                const data = result || ({})
                const artist = {
                    "name": data.name || data.title || page.argName,
                    "avatarUrl": data.avatarUrl || data.thumbnail || page.argAvatarUrl,
                    "songCount": data.songCount || (data.songs ? data.songs.length : 0),
                    "description": data.description || ""
                }
                const albumList = []
                const rawAlbums = data.albums || []
                for (let i = 0; i < rawAlbums.length; i++) {
                    const album = page.normalizeAlbum(rawAlbums[i], "YOUTUBE")
                    if (album && album.name !== "")
                        albumList.push(album)
                }
                page.detail = ({ "artist": artist, "description": data.description || "" })
                page.songs = data.songs || []
                page.albums = albumList
                page.loading = false
            }).catch(function(error) {
                if (seq !== page._seq)
                    return
                page.handleLoadError(error)
            })
            return
        }

        // 网易云歌手 id 是数字；只有名字时先搜索出 id（歌曲菜单的按名字降级路径）。
        if (!/^\d+$/.test(page.argId)) {
            page.resolveArtistAndLoad(seq)
            return
        }

        page.fetchArtist(seq)
    }

    function fetchArtist(seq) {
        Api.call("online.artistDetail", {
                     "source": page.argSource,
                     "id": page.argId
                 }).then(function(result) {
            if (seq !== page._seq)
                return
            const albumList = []
            const rawAlbums = result && result.albums ? result.albums : []
            for (let i = 0; i < rawAlbums.length; i++) {
                const album = page.normalizeAlbum(rawAlbums[i], page.argSource)
                if (album && album.name !== "")
                    albumList.push(album)
            }
            page.applyArtist(result && result.artist ? result.artist : ({}),
                             result && result.songs ? result.songs : [],
                             albumList)
        }).catch(function(error) {
            if (seq !== page._seq)
                return
            if (page.argName !== "" && !page._searchTried
                    && error && (error.code === "not_found" || error.code === "bad_request")) {
                page._searchTried = true
                page.resolveArtistAndLoad(seq)
                return
            }
            page.handleLoadError(error)
        })
    }

    // 用歌手名搜索网易云，挑同名歌手后用真实 id 加载详情。
    function resolveArtistAndLoad(seq) {
        if (page.argName === "") {
            page.errorMessage = "缺少歌手参数，请从探索页或歌曲菜单打开"
            page.loading = false
            return
        }
        // 网易云多歌手字段形如 "A/B"，逐个短名尝试匹配，避免搜不到整体字符串。
        const names = [page.argName]
        const parts = page.argName.split("/")
        for (let i = 0; i < parts.length; i++) {
            const part = parts[i].trim()
            if (part !== "" && names.indexOf(part) < 0)
                names.push(part)
        }
        const keyword = names.length > 1 ? names[1] : names[0]
        Api.call("online.search", {
                     "source": "NETEASE",
                     "keyword": keyword,
                     "kind": "ARTIST"
                 }).then(function(result) {
            if (seq !== page._seq)
                return
            const list = result && result.artists ? result.artists : []
            let hit = null
            for (let i = 0; i < list.length; i++) {
                if (list[i] && names.indexOf(String(list[i].name || "")) >= 0) {
                    hit = list[i]
                    break
                }
            }
            if (!hit && list.length > 0)
                hit = list[0]
            if (!hit) {
                page.errorMessage = "未找到歌手「" + page.argName + "」"
                page.loading = false
                return
            }
            page._searchTried = true
            Api.call("online.artistDetail", {
                         "source": "NETEASE",
                         "id": String(hit.id)
                     }).then(function(detail) {
                if (seq !== page._seq)
                    return
                const albumList = []
                const rawAlbums = detail && detail.albums ? detail.albums : []
                for (let i = 0; i < rawAlbums.length; i++) {
                    const album = page.normalizeAlbum(rawAlbums[i], "NETEASE")
                    if (album && album.name !== "")
                        albumList.push(album)
                }
                page.applyArtist(detail && detail.artist ? detail.artist : ({}),
                                 detail && detail.songs ? detail.songs : [],
                                 albumList)
            }).catch(function(error) {
                if (seq !== page._seq)
                    return
                page.handleLoadError(error)
            })
        }).catch(function(error) {
            if (seq !== page._seq)
                return
            page.handleLoadError(error)
        })
    }

    function loadLocal() {
        const wanted = page.argName !== "" ? page.argName : page.argId
        if (wanted === "") {
            page.loading = false
            page.errorMessage = "缺少歌手名，无法从本地媒体库查找"
            return
        }
        page.loading = true
        const seq = ++page._seq
        Api.call("library.songs").then(function(result) {
            if (seq !== page._seq)
                return
            const all = result && result.songs ? result.songs : []
            const matched = []
            const albumMap = ({})
            const albumOrder = []
            for (let i = 0; i < all.length; i++) {
                const song = all[i]
                if (!song || String(song.artist || "") !== wanted)
                    continue
                matched.push(song)
                const albumName = String(song.album || "")
                if (albumName === "")
                    continue
                const key = albumName + "\u0001" + String(song.artist || "")
                if (albumMap[key] === undefined) {
                    albumMap[key] = {
                        "id": albumName,
                        "name": albumName,
                        "creator": String(song.artist || ""),
                        "coverUrl": "",
                        "artworkPath": song.artworkPath || "",
                        "trackCount": 0,
                        "source": "LOCAL"
                    }
                    albumOrder.push(key)
                }
                albumMap[key].trackCount += 1
            }
            const albumList = []
            for (let i = 0; i < albumOrder.length; i++)
                albumList.push(albumMap[albumOrder[i]])
            page.detail = ({ "artist": {
                "name": wanted,
                "avatarUrl": "",
                "songCount": matched.length,
                "source": "LOCAL"
            } })
            page.songs = matched
            page.albums = albumList
            page.loading = false
        }).catch(function(error) {
            if (seq !== page._seq)
                return
            page.errorMessage = error && error.message ? error.message : "读取本地媒体库失败"
            page.loading = false
        })
    }

    // ---------------------------------------------------------------- 交互

    function shuffle(list) {
        const output = (list || []).slice()
        for (let i = output.length - 1; i > 0; i--) {
            const j = Math.floor(Math.random() * (i + 1))
            const temp = output[i]
            output[i] = output[j]
            output[j] = temp
        }
        return output
    }

    function playAll() {
        if (page.songs.length === 0)
            return
        actions.playSongs(page.songs, 0)
    }

    function shufflePlay() {
        if (page.songs.length === 0)
            return
        actions.playSongs(page.shuffle(page.songs), 0)
    }

    function playAt(index) {
        if (index < 0 || index >= page.songs.length)
            return
        actions.playSongs(page.songs, index)
    }

    function openAlbum(album) {
        if (!album)
            return
        const source = album.source ? String(album.source) : page.argSource
        const albumId = album.id !== undefined && album.id !== null && String(album.id) !== ""
                        ? page.normalizeId(album.id) : String(album.name || "")
        Nav.push("albumDetail", {
                     "source": source,
                     "id": albumId,
                     "name": album.name || "",
                     "coverUrl": album.coverUrl || "",
                     "creator": album.creator || page.headerTitle,
                     "trackCount": album.trackCount || 0
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

    // 内联按钮：主色填充（播放全部）/ 描边（随机播放）。
    component PillButton: Rectangle {
        id: pill

        property string label: ""
        property string iconName: "play_arrow"
        property bool filled: true

        signal clicked()

        implicitHeight: 38
        implicitWidth: pillRow.implicitWidth + 36
        width: implicitWidth
        height: implicitHeight
        radius: height / 2
        color: pill.filled
               ? (pillMouse.containsMouse ? Theme.mix(Theme.primary, Theme.onPrimaryColor, 0.1) : Theme.primary)
               : (pillMouse.containsMouse ? Theme.withAlpha(Theme.primary, 0.1) : "transparent")
        border.width: pill.filled ? 0 : 1
        border.color: Theme.outlineVariant

        Behavior on color {
            ColorAnimation {
                duration: Theme.motionShort
            }
        }

        Row {
            id: pillRow

            anchors.centerIn: parent
            spacing: 6

            NeriIcon {
                width: 18
                height: 18
                name: pill.iconName
                filled: pill.filled
                iconSize: 18
                color: pill.filled ? Theme.onPrimaryColor : Theme.primary
            }

            Text {
                height: 20
                text: pill.label
                verticalAlignment: Text.AlignVCenter
                color: pill.filled ? Theme.onPrimaryColor : Theme.primary
                font.family: Theme.fontFamily
                font.pixelSize: Theme.textStyle("labelLarge").size
                font.weight: Font.Medium
            }
        }

        MouseArea {
            id: pillMouse

            anchors.fill: parent
            hoverEnabled: true
            cursorShape: Qt.PointingHandCursor
            onClicked: pill.clicked()
        }
    }

    // ---------------------------------------------------------------- 页面骨架

    DetailScaffold {
        anchors.fill: parent
        title: page.headerTitle
        subtitle: page.sourceLabel

        Item {
            anchors.fill: parent

            Flickable {
                id: contentFlick

                anchors.fill: parent
                contentWidth: width
                contentHeight: contentColumn.height + Theme.spacingXl
                boundsBehavior: Flickable.StopAtBounds
                clip: true

                ScrollBar.vertical: ScrollBar {
                    policy: ScrollBar.AsNeeded
                }

                Column {
                    id: contentColumn

                    width: contentFlick.width

                    // ---- 头像头部（渐变底 + 圆形头像 + 名称/作品数 + 操作）----
                    Item {
                        width: parent.width
                        height: heroRow.height + Theme.spacingXl * 2

                        Rectangle {
                            anchors.fill: parent
                            radius: Theme.radiusXl
                            gradient: Gradient {
                                orientation: Gradient.Vertical
                                GradientStop {
                                    position: 0.0
                                    color: Theme.withAlpha(Theme.primaryContainer, 0.62)
                                }
                                GradientStop {
                                    position: 1.0
                                    color: Theme.withAlpha(Theme.primaryContainer, 0.0)
                                }
                            }
                        }

                        Row {
                            id: heroRow

                            anchors.left: parent.left
                            anchors.right: parent.right
                            anchors.top: parent.top
                            anchors.leftMargin: Theme.spacingLg
                            anchors.rightMargin: Theme.spacingLg
                            anchors.topMargin: Theme.spacingXl
                            spacing: Theme.spacingLg

                            CoverArt {
                                id: heroAvatar

                                width: 132
                                height: 132
                                radius: width / 2
                                placeholderIconSize: 42
                                path: page.headerAvatarPath
                                url: page.headerAvatar
                            }

                            Column {
                                width: parent.width - heroAvatar.width - Theme.spacingLg
                                spacing: Theme.spacingSm

                                Text {
                                    width: parent.width
                                    text: page.headerTitle
                                    wrapMode: Text.Wrap
                                    maximumLineCount: 2
                                    elide: Text.ElideRight
                                    color: Theme.onSurfaceColor
                                    font.family: Theme.fontFamily
                                    font.pixelSize: 24
                                    font.weight: Font.DemiBold
                                }

                                Text {
                                    width: parent.width
                                    visible: page.statsText !== ""
                                    text: page.statsText
                                    wrapMode: Text.Wrap
                                    color: Theme.onSurfaceVariantColor
                                    font.family: Theme.fontFamily
                                    font.pixelSize: Theme.textStyle("bodySmall").size
                                }

                                Item {
                                    width: 1
                                    height: Theme.spacingXs
                                }

                                Row {
                                    spacing: Theme.spacingSm

                                    PillButton {
                                        label: "播放全部"
                                        iconName: "play_arrow"
                                        filled: true
                                        enabled: page.songs.length > 0
                                        opacity: enabled ? 1 : 0.38
                                        onClicked: page.playAll()
                                    }

                                    PillButton {
                                        label: "随机播放"
                                        iconName: "shuffle"
                                        filled: false
                                        enabled: page.songs.length > 0
                                        opacity: enabled ? 1 : 0.38
                                        onClicked: page.shufflePlay()
                                    }
                                }
                            }
                        }
                    }

                    // ---- 降级提示 ----
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

                    // ---- 简介 ----
                    Item {
                        width: parent.width
                        height: introColumn.height + Theme.spacingMd * 2
                        visible: page.headerDescription !== ""

                        Column {
                            id: introColumn

                            anchors.left: parent.left
                            anchors.right: parent.right
                            anchors.top: parent.top
                            anchors.leftMargin: Theme.spacingLg
                            anchors.rightMargin: Theme.spacingLg
                            anchors.topMargin: Theme.spacingLg
                            spacing: Theme.spacingXs

                            Text {
                                width: parent.width
                                text: "简介"
                                color: Theme.onSurfaceColor
                                font.family: Theme.fontFamily
                                font.pixelSize: Theme.textStyle("titleSmall").size
                                font.weight: Font.DemiBold
                            }

                            Text {
                                width: parent.width
                                text: page.headerDescription
                                wrapMode: Text.Wrap
                                maximumLineCount: 4
                                elide: Text.ElideRight
                                color: Theme.onSurfaceVariantColor
                                font.family: Theme.fontFamily
                                font.pixelSize: Theme.textStyle("bodySmall").size
                                lineHeight: 1.35
                            }
                        }
                    }

                    // ---- 热门歌曲 ----
                    SectionHeader {
                        width: parent.width
                        title: "热门歌曲（" + page.songs.length + "）"
                        icon: "music_note"
                    }

                    EmptyState {
                        width: parent.width
                        visible: page.songs.length === 0
                        height: visible ? 200 : 0
                        title: page.degraded ? "歌手详情暂不可用" : "暂无热门歌曲"
                        hint: page.degraded ? page.degradeText : "稍后重试，或检查该音源是否需要登录"
                        icon: "person"
                    }

                    Repeater {
                        model: page.songRows

                        delegate: SongRow {
                            width: contentColumn.width
                            height: 58
                            song: modelData.song
                            index: modelData.idx
                            showAlbum: false
                            playing: Player.song !== null && Player.song.key === modelData.song.key
                            onActivated: function(index) { page.playAt(index) }
                            onMoreClicked: function(song) { songMenu.openFor(song, {}) }
                            onRightClicked: function(song) { songMenu.openFor(song, {}) }
                        }
                    }

                    // ---- 专辑网格 ----
                    SectionHeader {
                        width: parent.width
                        visible: page.albums.length > 0
                        title: "专辑（" + page.albums.length + "）"
                        icon: "album"
                    }

                    CollectionGrid {
                        visible: page.albums.length > 0
                        items: page.albums
                        onItemClicked: function(item) { page.openAlbum(item) }
                    }

                    EmptyState {
                        width: parent.width
                        visible: page.albums.length === 0 && !page.degraded
                        height: visible ? 160 : 0
                        title: "暂无专辑"
                        hint: "该歌手暂时没有可展示的专辑"
                        icon: "album"
                    }

                    Item {
                        width: 1
                        height: Theme.spacingMd
                    }
                }
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
                anchors.top: parent.top
                anchors.topMargin: 24
                visible: page.errorMessage !== ""
                message: "加载失败：" + page.errorMessage
                onRetry: page.load()
            }
        }
    }

    SongActionsMenu {
        id: songMenu
    }
}
