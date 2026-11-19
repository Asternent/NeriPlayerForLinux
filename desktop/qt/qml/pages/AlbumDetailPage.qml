import QtQuick
import "../components"
import "../components/explore"
import "../components/library"
import "../components/library/Format.js" as Format

// 专辑详情页（统一入口，路由 albumDetail）。
// 路由 arg：{source: "NETEASE"|"YOUTUBE"|"LOCAL"|"BILIBILI", id, name, coverUrl, creator, trackCount}
//   - NETEASE：online.albumDetail → {collection, songs}
//   - YOUTUBE：ytmusic.album → {title, artist, coverUrl, description, songs}（后端缺失时降级展示）
//   - LOCAL：library.songs 按 album 字段过滤
// 页面：大封面头部（渐变底）、歌手（可点→remoteArtist）、曲目数与总时长、
//       播放全部/随机播放、歌曲列表（虚拟化）、简介折叠、空/错误/降级状态。
Item {
    id: page

    readonly property var arg: Nav.currentArg
    readonly property string argSource: arg && arg.source ? String(arg.source).toUpperCase() : "NETEASE"
    readonly property string argId: arg && arg.id !== undefined && arg.id !== null ? String(arg.id) : ""
    readonly property string argName: arg && arg.name ? String(arg.name) : ""
    readonly property string argCoverUrl: arg && arg.coverUrl ? String(arg.coverUrl) : ""
    readonly property string argCreator: arg && arg.creator ? String(arg.creator) : ""
    readonly property int argTrackCount: arg && arg.trackCount !== undefined && arg.trackCount !== null
                                         ? Number(arg.trackCount) : 0

    // ---- 数据 ----
    property var detail: ({})
    property var songs: []
    property bool loading: true
    property string errorMessage: ""
    // 音源/后端能力缺失时的降级提示（页面仍展示参数带来的头部信息）
    property bool degraded: false
    property string degradeText: ""
    property bool descriptionExpanded: false
    property int _seq: 0
    // 只有专辑名、没有数字 id 时（如歌曲菜单按名字降级）用搜索兜底一次。
    property bool _searchTried: false

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
    readonly property string headerTitle: {
        if (page.detail && page.detail.name)
            return String(page.detail.name)
        if (page.argName !== "")
            return page.argName
        return "专辑"
    }
    readonly property string headerCover: {
        if (page.detail && page.detail.coverUrl)
            return String(page.detail.coverUrl)
        return page.argCoverUrl
    }
    readonly property string headerCreator: {
        if (page.detail && page.detail.creator)
            return String(page.detail.creator)
        if (page.argSource === "LOCAL" && page.songs.length > 0 && page.songs[0].artist)
            return String(page.songs[0].artist)
        return page.argCreator
    }
    readonly property string headerDescription: page.detail && page.detail.description
                                               ? String(page.detail.description) : ""
    readonly property string localCoverPath: {
        if (page.argSource !== "LOCAL" || page.songs.length === 0)
            return ""
        return page.songs[0].artworkPath ? String(page.songs[0].artworkPath) : ""
    }
    readonly property int trackCount: page.songs.length > 0 ? page.songs.length : page.argTrackCount
    readonly property real totalDurationMs: {
        let total = 0
        for (let i = 0; i < page.songs.length; i++)
            total += Number(page.songs[i].durationMs || 0)
        return total
    }
    // 头部信息行：曲目数 + 总时长（音源已在歌手行展示，不重复）。
    readonly property string statsText: {
        const parts = []
        if (page.trackCount > 0)
            parts.push(page.trackCount + " 首")
        if (page.totalDurationMs > 0)
            parts.push(Format.formatLongDuration(page.totalDurationMs))
        return parts.join(" · ")
    }
    // 专辑详情不直接返回歌手 id，用歌曲上的 artistId 反查（网易云/B 站歌曲都带）。
    readonly property string headerArtistId: {
        const name = page.headerCreator
        if (name === "")
            return ""
        for (let i = 0; i < page.songs.length; i++) {
            const song = page.songs[i]
            if (song && song.artist === name && song.artistId)
                return String(song.artistId)
        }
        return arg && arg.artistId ? String(arg.artistId) : ""
    }

    PageActions {
        id: actions
    }

    // ---------------------------------------------------------------- 加载

    function applyAlbum(collection, list) {
        page.detail = collection || ({})
        page.songs = list || []
        page.loading = false
    }

    function handleLoadError(error) {
        if (error && error.code === "unknown_method") {
            page.degraded = true
            page.degradeText = "当前后端暂不支持 YouTube 专辑详情（ytmusic.album）"
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
        page.descriptionExpanded = false
        page._searchTried = false

        if (page.argSource === "LOCAL") {
            page.loadLocal()
            return
        }
        if (page.argSource === "BILIBILI") {
            page.detail = ({})
            page.songs = []
            page.loading = false
            page.degraded = true
            page.degradeText = "B 站暂不支持专辑详情"
            return
        }
        if (page.argId === "" && page.argName === "") {
            page.songs = []
            page.loading = false
            page.errorMessage = "缺少专辑参数，请从专辑卡片或歌曲菜单打开"
            return
        }
        if (page.argSource === "YOUTUBE" && page.argId === "") {
            page.songs = []
            page.loading = false
            page.degraded = true
            page.degradeText = "缺少 YouTube 专辑 id，无法加载详情"
            return
        }

        page.loading = true
        const seq = ++page._seq

        if (page.argSource === "YOUTUBE") {
            Api.call("ytmusic.album", { "id": page.argId }).then(function(result) {
                if (seq !== page._seq)
                    return
                const data = result || ({})
                page.applyAlbum({
                    "name": data.title || data.name || "",
                    "creator": data.artist || data.creator || "",
                    "coverUrl": data.coverUrl || data.thumbnail || "",
                    "description": data.description || "",
                    "trackCount": data.trackCount || 0
                }, data.songs || [])
            }).catch(function(error) {
                if (seq !== page._seq)
                    return
                page.handleLoadError(error)
            })
            return
        }

        // 网易云专辑 id 是数字；只有名字时先搜索出 id（歌曲菜单的按名字降级路径）。
        if (page.argSource === "NETEASE" && !/^\d+$/.test(page.argId)) {
            page.resolveAlbumAndLoad(seq)
            return
        }

        page.fetchAlbum(seq)
    }

    function fetchAlbum(seq) {
        Api.call("online.albumDetail", {
                     "source": page.argSource,
                     "id": page.argId
                 }).then(function(result) {
            if (seq !== page._seq)
                return
            page.applyAlbum(result && result.collection ? result.collection : ({}),
                            result && result.songs ? result.songs : [])
        }).catch(function(error) {
            if (seq !== page._seq)
                return
            if (page.argSource === "NETEASE" && page.argName !== "" && !page._searchTried
                    && error && (error.code === "not_found" || error.code === "bad_request")) {
                page._searchTried = true
                page.resolveAlbumAndLoad(seq)
                return
            }
            page.handleLoadError(error)
        })
    }

    // 用专辑名搜索网易云，挑同名专辑后用真实 id 加载详情。
    function resolveAlbumAndLoad(seq) {
        if (page.argName === "") {
            page.errorMessage = "缺少专辑参数，请从专辑卡片或歌曲菜单打开"
            page.loading = false
            return
        }
        Api.call("online.search", {
                     "source": "NETEASE",
                     "keyword": page.argName,
                     "kind": "ALBUM"
                 }).then(function(result) {
            if (seq !== page._seq)
                return
            const list = result && result.collections ? result.collections : []
            let hit = null
            for (let i = 0; i < list.length; i++) {
                if (list[i] && list[i].name === page.argName) {
                    hit = list[i]
                    break
                }
            }
            if (!hit && list.length > 0)
                hit = list[0]
            if (!hit) {
                page.errorMessage = "未找到专辑「" + page.argName + "」"
                page.loading = false
                return
            }
            page._searchTried = true
            Api.call("online.albumDetail", {
                         "source": "NETEASE",
                         "id": String(hit.id)
                     }).then(function(detail) {
                if (seq !== page._seq)
                    return
                page.applyAlbum(detail && detail.collection ? detail.collection : ({}),
                                detail && detail.songs ? detail.songs : [])
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
        page.loading = true
        const wanted = page.argId !== "" ? page.argId : page.argName
        if (wanted === "") {
            page.songs = []
            page.loading = false
            page.errorMessage = "缺少专辑名，无法从本地媒体库查找"
            return
        }
        const seq = ++page._seq
        const wantedArtist = page.argCreator
        Api.call("library.songs").then(function(result) {
            if (seq !== page._seq)
                return
            const all = result && result.songs ? result.songs : []
            const matched = all.filter(function(song) {
                if (String(song.album || "") !== wanted)
                    return false
                return wantedArtist === "" || String(song.artist || "") === wantedArtist
            })
            matched.sort(function(a, b) {
                const ta = Number(a.trackNumber || 0)
                const tb = Number(b.trackNumber || 0)
                if (ta !== tb)
                    return ta - tb
                return String(a.title || "").localeCompare(String(b.title || ""))
            })
            const creator = wantedArtist !== ""
                            ? wantedArtist
                            : (matched.length > 0 ? String(matched[0].artist || "") : "")
            page.applyAlbum({
                "name": wanted,
                "creator": creator,
                "coverUrl": "",
                "description": "",
                "trackCount": matched.length
            }, matched)
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

    function openArtist() {
        const name = page.headerCreator
        if (name === "")
            return
        if (page.argSource === "LOCAL") {
            Nav.push("remoteArtist", { "source": "LOCAL", "id": name, "name": name })
            return
        }
        if (page.headerArtistId !== "") {
            Nav.push("remoteArtist", {
                         "source": page.argSource,
                         "id": page.headerArtistId,
                         "name": name
                     })
            return
        }
        if (page.argSource !== "NETEASE") {
            Toast.show("暂时无法打开该歌手的详情页", "info")
            return
        }
        // 网易云专辑详情没有直接给歌手 id，用歌手搜索兜底一次。
        const seq = page._seq
        Api.call("online.search", {
                     "source": "NETEASE",
                     "keyword": name,
                     "kind": "ARTIST"
                 }).then(function(result) {
            if (seq !== page._seq)
                return
            const list = result && result.artists ? result.artists : []
            let hit = null
            for (let i = 0; i < list.length; i++) {
                if (list[i] && list[i].name === name) {
                    hit = list[i]
                    break
                }
            }
            if (!hit && list.length > 0)
                hit = list[0]
            if (!hit) {
                Toast.show("未找到歌手「" + name + "」", "info")
                return
            }
            Nav.push("remoteArtist", {
                         "source": "NETEASE",
                         "id": String(hit.id),
                         "name": hit.name || name,
                         "avatarUrl": hit.avatarUrl || "",
                         "songCount": hit.songCount || 0
                     })
        }).catch(function() {
            Toast.show("歌手信息加载失败", "error")
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

    // ---------------------------------------------------------------- 内联按钮

    // 头部动作按钮（播放全部 / 随机播放）。filled=true 为主色填充，否则为描边。
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

            // 数据就绪后再创建列表：头部高度在创建时确定，避免加载完成后内容偏移。
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
                text: "正在加载专辑…"
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

    // ---------------------------------------------------------------- 列表

    Component {
        id: tableComponent

        TrackListTable {
            anchors.fill: parent
            songs: page.songs
            showAlbum: false
            headerContent: Component {
                Column {
                    width: parent ? parent.width : 0

                    // ---- 封面头部（渐变底 + 大封面 + 基本信息）----
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
                                id: heroCover

                                width: 172
                                height: 172
                                radius: Theme.radiusXl
                                placeholderIconSize: 48
                                path: page.localCoverPath
                                url: page.headerCover
                            }

                            Column {
                                width: parent.width - heroCover.width - Theme.spacingLg
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

                                // 歌手：点击打开统一歌手页
                                Row {
                                    width: parent.width
                                    spacing: 0

                                    Text {
                                        id: artistLink

                                        text: page.headerCreator !== "" ? page.headerCreator : "未知艺术家"
                                        color: artistLinkMouse.containsMouse && page.headerCreator !== ""
                                               ? Theme.primary : Theme.onSurfaceVariantColor
                                        font.family: Theme.fontFamily
                                        font.pixelSize: Theme.textStyle("bodyLarge").size
                                        font.weight: Font.Medium

                                        MouseArea {
                                            id: artistLinkMouse

                                            anchors.fill: parent
                                            hoverEnabled: true
                                            enabled: page.headerCreator !== ""
                                            cursorShape: enabled ? Qt.PointingHandCursor : Qt.ArrowCursor
                                            onClicked: page.openArtist()
                                        }
                                    }

                                    Text {
                                        text: " · " + page.sourceLabel
                                        color: Theme.onSurfaceVariantColor
                                        font.family: Theme.fontFamily
                                        font.pixelSize: Theme.textStyle("bodySmall").size
                                    }
                                }

                                Text {
                                    width: parent.width
                                    visible: page.statsText !== ""
                                    text: page.statsText
                                    wrapMode: Text.Wrap
                                    color: Theme.withAlpha(Theme.onSurfaceVariantColor, 0.85)
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

                    // ---- 降级提示（后端缺失 YouTube 命令 / B 站不支持时）----
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
                                anchors.verticalCenter: parent.verticalCenter
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

                    // ---- 简介折叠 ----
                    Item {
                        width: parent.width
                        height: page.headerDescription !== "" ? descriptionColumn.height + Theme.spacingMd : 0
                        visible: page.headerDescription !== ""

                        Column {
                            id: descriptionColumn

                            anchors.left: parent.left
                            anchors.right: parent.right
                            anchors.top: parent.top
                            anchors.leftMargin: Theme.spacingLg
                            anchors.rightMargin: Theme.spacingLg
                            anchors.topMargin: Theme.spacingMd
                            spacing: 2

                            Text {
                                id: descriptionText

                                width: parent.width
                                text: page.headerDescription
                                wrapMode: Text.Wrap
                                maximumLineCount: page.descriptionExpanded ? 1000 : 2
                                elide: page.descriptionExpanded ? Text.ElideNone : Text.ElideRight
                                color: Theme.withAlpha(Theme.onSurfaceVariantColor, 0.9)
                                font.family: Theme.fontFamily
                                font.pixelSize: Theme.textStyle("bodySmall").size
                                lineHeight: 1.35
                            }

                            Item {
                                width: 1
                                height: 2
                            }

                            Text {
                                width: parent.width
                                text: page.descriptionExpanded ? "收起简介" : "展开简介"
                                color: Theme.primary
                                font.family: Theme.fontFamily
                                font.pixelSize: Theme.textStyle("labelMedium").size
                                visible: page.descriptionExpanded || page.headerDescription.length > 40

                                MouseArea {
                                    anchors.fill: parent
                                    cursorShape: Qt.PointingHandCursor
                                    onClicked: page.descriptionExpanded = !page.descriptionExpanded
                                }
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
                            title: page.degraded ? "专辑曲目暂不可用" : "这张专辑还没有曲目"
                            hint: page.degraded ? page.degradeText : "稍后重试，或检查该音源是否需要登录"
                            icon: "album"
                        }
                    }
                }
            }
            onSongActivated: function(index) { page.playAt(index) }
            onSongMoreClicked: function(index, song) { songMenu.openFor(song, {}) }
        }
    }

    SongActionsMenu {
        id: songMenu
    }
}
