import QtQuick
import QtQuick.Controls
import "../components"
import "../components/explore"
import "../components/library"
import "../components/youtube"

// 探索页：音源切换 + 搜索 + 搜索历史 / 热门搜索 / 风格标签 / 推荐歌单。
// 对应 Compose 版 ExploreScreen；数据全部来自后端 online.* / ytmusic.* 命令。
//
// YouTube 音源（ytmusic.*，协议见 desktop/docs/protocol-youtube.md）：
// - 未登录时内嵌「粘贴 Cookie」登录卡（桌面没有 WebView）；
// - 搜索分「全部 / 单曲 / 专辑 / 歌手 / 歌单」，结果卡片复用库里的组件；
// - 匿名也能搜歌 / 浏览公开首页推荐（播放直链与个性化需要登录）。
Item {
    id: page

    readonly property var sources: {
        // 读取一次 Settings.values，让这个绑定依赖设置对象；只调 Settings.get 不会建立依赖。
        const settings = Settings.values
        const list = []
        if (Settings.get("neteaseEnabled", true) !== false)
            list.push({ "id": "NETEASE", "name": "网易云" })
        if (Settings.get("bilibiliEnabled", true) !== false)
            list.push({ "id": "BILIBILI", "name": "哔哩哔哩" })
        if (Settings.get("youtubeEnabled", false) === true)
            list.push({ "id": "YOUTUBE", "name": "YouTube" })
        if (list.length === 0)
            list.push({ "id": "NETEASE", "name": "网易云" })
        return list
    }
    readonly property string sourceName: {
        if (source === "BILIBILI")
            return "哔哩哔哩"
        if (source === "YOUTUBE")
            return "YouTube"
        return "网易云"
    }
    readonly property bool youtube: source === "YOUTUBE"

    readonly property var searchHistory: {
        // Settings.get 返回的是 QVariantList 包装（Array.isArray 为 false），这里转成普通数组。
        const value = Settings.get("exploreSearchHistory", [])
        const list = []
        if (value) {
            for (let i = 0; i < value.length; i++)
                list.push(value[i])
        }
        return list
    }

    property string source: "NETEASE"
    property var hotKeywords: []
    property var tags: []
    property var recommended: []
    property var tagCollections: []
    property var results: ({ "songs": [], "collections": [], "artists": [] })

    // ---- YouTube 专有状态 ----
    property bool ytLoggedIn: false
    property var ytAccount: null
    property var ytSections: []
    property string ytMessage: ""
    property bool _ytEnabled: false
    /** YouTube 搜索过滤："" / SONG / ALBUM / ARTIST / PLAYLIST */
    property string ytKind: ""

    readonly property var ytKinds: [
        { "id": "", "name": "全部" },
        { "id": "SONG", "name": "单曲" },
        { "id": "ALBUM", "name": "专辑" },
        { "id": "ARTIST", "name": "歌手" },
        { "id": "PLAYLIST", "name": "歌单" }
    ]

    readonly property var resultSongs: results && results.songs ? results.songs : []
    readonly property var resultCollections: results && results.collections ? results.collections : []
    readonly property var resultArtists: results && results.artists ? results.artists : []
    readonly property bool hasResults: resultSongs.length + resultCollections.length + resultArtists.length > 0

    property bool loading: false            // 搜索 / 标签歌单加载
    property bool defaultLoading: false     // 首页推荐/热词/标签加载
    property string errorMessage: ""
    property string submittedQuery: ""
    property string activeTag: ""

    readonly property bool defaultContentEmpty: searchHistory.length === 0 && hotKeywords.length === 0
                                                && tags.length === 0 && recommended.length === 0
                                                && ytSections.length === 0
    readonly property bool showDefaultLoading: defaultLoading && defaultContentEmpty

    property int _defaultSeq: 0
    property int _pendingDefault: 0
    property int _searchSeq: 0

    // 调试开关：非空时自动搜索「周杰伦」用于截图验证（search/collection/artist），默认关闭，不影响正常使用。
    property string __autoDemo: ""
    // 调试开关：非空时启动即切到该音源（截图用），默认关闭。
    property string __autoSource: ""

    PageActions {
        id: actions
    }

    function emptyResults() {
        return { "songs": [], "collections": [], "artists": [] }
    }

    function ensureValidSource() {
        for (let i = 0; i < sources.length; i++) {
            if (sources[i].id === source)
                return
        }
        if (sources.length > 0)
            source = sources[0].id
    }

    function applyAutoSource() {
        if (page.__autoSource === "" || page.__autoSource === page.source)
            return
        for (let i = 0; i < page.sources.length; i++) {
            if (page.sources[i].id === page.__autoSource)
                page.setSource(page.__autoSource)
        }
    }

    onSourcesChanged: {
        ensureValidSource()
        applyAutoSource()
    }

    function setSource(id) {
        if (source === id)
            return
        source = id
        activeTag = ""
        tagCollections = []
        ytKind = ""
        results = emptyResults()
        errorMessage = ""
        if (source === "YOUTUBE") {
            hotKeywords = []
            tags = []
            recommended = []
            loadYouTubeDefault()
        } else {
            ytSections = []
            ytMessage = ""
            reloadDefault()
        }
        if (submittedQuery !== "")
            runSearch(submittedQuery, false)
        else
            loading = false
    }

    // ---------------------------------------------------------------- 搜索

    function saveHistory(keyword) {
        // Settings.get 返回 QVariantList 包装，先转普通数组再拼接。
        const current = Settings.get("exploreSearchHistory", [])
        const list = []
        if (current) {
            for (let i = 0; i < current.length; i++) {
                if (current[i] !== keyword)
                    list.push(current[i])
            }
        }
        actions.saveSetting("exploreSearchHistory", [keyword].concat(list).slice(0, 8))
    }

    function clearHistory() {
        actions.saveSetting("exploreSearchHistory", [])
    }

    function runSearch(keyword, recordHistory) {
        const trimmed = String(keyword === undefined || keyword === null ? "" : keyword).trim()
        if (trimmed === "")
            return
        searchBar.text = trimmed
        submittedQuery = trimmed
        activeTag = ""
        tagCollections = []
        errorMessage = ""
        loading = true
        const seq = ++page._searchSeq
        if (recordHistory !== false)
            saveHistory(trimmed)
        if (page.youtube)
            runYouTubeSearch(trimmed, seq)
        else
            runOnlineSearch(trimmed, seq)
    }

    function runOnlineSearch(trimmed, seq) {
        Api.call("online.search", { source: page.source, keyword: trimmed, page: 1 }).then(function(result) {
            if (seq !== page._searchSeq)
                return
            page.results = result || page.emptyResults()
            page.loading = false
            page.maybeAutoDemo()
        }).catch(function(error) {
            if (seq !== page._searchSeq)
                return
            page.results = page.emptyResults()
            page.errorMessage = error && error.message ? error.message : "搜索失败"
            page.loading = false
            Toast.show("搜索失败：" + page.errorMessage, "error")
        })
    }

    function runYouTubeSearch(trimmed, seq) {
        const params = { "query": trimmed }
        if (page.ytKind !== "")
            params["kind"] = page.ytKind
        Api.call("ytmusic.search", params).then(function(result) {
            if (seq !== page._searchSeq)
                return
            page.results = page.mapYouTubeResults(result)
            page.loading = false
        }).catch(function(error) {
            if (seq !== page._searchSeq)
                return
            page.results = page.emptyResults()
            page.errorMessage = error && error.message ? error.message : "YouTube 搜索失败"
            page.loading = false
            Toast.show("搜索失败：" + page.errorMessage, "error")
        })
    }

    /** ytmusic.search 的四类结果 → 现有搜索结果组件需要的 songs / collections / artists。 */
    function mapYouTubeResults(result) {
        const data = result || {}
        const collections = []
        if (page.ytKind === "" || page.ytKind === "ALBUM") {
            const albums = data.albums || []
            for (let i = 0; i < albums.length; i++)
                collections.push(albums[i])
        }
        if (page.ytKind === "" || page.ytKind === "PLAYLIST") {
            const playlists = data.playlists || []
            for (let i = 0; i < playlists.length; i++)
                collections.push(playlists[i])
        }
        return {
            "songs": page.ytKind === "" || page.ytKind === "SONG" ? (data.songs || []) : [],
            "collections": collections,
            "artists": page.ytKind === "" || page.ytKind === "ARTIST" ? (data.artists || []) : []
        }
    }

    function setYouTubeKind(kind) {
        if (page.ytKind === kind)
            return
        page.ytKind = kind
        if (page.submittedQuery !== "")
            page.runSearch(page.submittedQuery, false)
    }

    function resetSearch() {
        _searchSeq += 1
        submittedQuery = ""
        activeTag = ""
        tagCollections = []
        results = emptyResults()
        errorMessage = ""
        loading = false
    }

    function maybeAutoDemo() {
        if (__autoDemo === "collection" && resultCollections.length > 0)
            openCollection(resultCollections[0])
        else if (__autoDemo === "artist" && resultArtists.length > 0)
            openArtist(resultArtists[0])
    }

    // ---------------------------------------------------------------- 标签

    function runTag(tagName) {
        if (tagName === "" || page.youtube)
            return
        activeTag = tagName
        submittedQuery = ""
        results = emptyResults()
        errorMessage = ""
        tagCollections = []
        loading = true
        const seq = ++page._searchSeq
        Api.call("online.tagPlaylists", { tag: tagName, page: 1, source: page.source }).then(function(result) {
            if (seq !== page._searchSeq)
                return
            page.tagCollections = result && result.collections ? result.collections : []
            page.loading = false
        }).catch(function(error) {
            if (seq !== page._searchSeq)
                return
            page.errorMessage = error && error.message ? error.message : "加载标签歌单失败"
            page.loading = false
            Toast.show(page.errorMessage, "error")
        })
    }

    function clearTag() {
        _searchSeq += 1
        activeTag = ""
        tagCollections = []
        loading = false
        errorMessage = ""
    }

    // ---------------------------------------------------------------- 默认内容

    function finishDefault(seq) {
        if (seq !== page._defaultSeq)
            return
        page._pendingDefault = Math.max(0, page._pendingDefault - 1)
        if (page._pendingDefault === 0)
            page.defaultLoading = false
    }

    function reloadDefault() {
        if (page.source === "YOUTUBE") {
            loadYouTubeDefault()
            return
        }
        const seq = ++page._defaultSeq
        _pendingDefault = 0
        defaultLoading = true
        hotKeywords = []
        tags = []
        recommended = []

        _pendingDefault += 1
        Api.call("online.hotSearches", { source: source }).then(function(result) {
            if (seq !== page._defaultSeq)
                return
            page.hotKeywords = result && result.keywords ? result.keywords : []
        }).catch(function() {
            if (seq === page._defaultSeq)
                page.hotKeywords = []
        }).then(function() { page.finishDefault(seq) })

        if (source === "NETEASE") {
            _pendingDefault += 1
            Api.call("online.tags", {}).then(function(result) {
                if (seq !== page._defaultSeq)
                    return
                page.tags = result && result.tags ? result.tags : []
            }).catch(function() {
                if (seq === page._defaultSeq)
                    page.tags = []
            }).then(function() { page.finishDefault(seq) })

            _pendingDefault += 1
            Api.call("online.home").then(function(result) {
                if (seq !== page._defaultSeq)
                    return
                const radar = result && result.radar && result.radar.collections ? result.radar.collections : []
                const hotLists = result && result.hotLists && result.hotLists.collections ? result.hotLists.collections : []
                const all = radar.concat(hotLists)
                const seen = ({})
                const merged = []
                for (let i = 0; i < all.length && merged.length < 12; i++) {
                    const item = all[i]
                    if (!item || !item.id || seen[item.id])
                        continue
                    seen[item.id] = true
                    merged.push(item)
                }
                page.recommended = merged
            }).catch(function() {
                if (seq === page._defaultSeq)
                    page.recommended = []
            }).then(function() { page.finishDefault(seq) })
        }
    }

    /** YouTube 默认内容：登录态 + 公开首页推荐（未登录也能拿到公开 shelf）。 */
    function loadYouTubeDefault() {
        const seq = ++page._defaultSeq
        defaultLoading = true
        ytSections = []
        ytMessage = ""
        Api.call("ytmusic.status").then(function(result) {
            if (seq !== page._defaultSeq)
                return null
            const status = result && result.status ? result.status : null
            page.ytLoggedIn = !!(status && status.loggedIn)
            page.ytAccount = status && status.account ? status.account : null
            return Api.call("ytmusic.home", { "limit": 8 })
        }).then(function(result) {
            if (seq !== page._defaultSeq)
                return
            page.ytSections = result && result.sections ? result.sections : []
            page.ytMessage = result && result.message ? String(result.message) : ""
            page.defaultLoading = false
        }).catch(function(error) {
            if (seq !== page._defaultSeq)
                return
            page.ytSections = []
            page.ytMessage = error && error.message ? String(error.message) : "YouTube 推荐加载失败"
            page.defaultLoading = false
        })
    }

    function onYouTubeAccountChanged() {
        page.loadYouTubeDefault()
        if (page.submittedQuery !== "")
            page.runSearch(page.submittedQuery, false)
    }

    // ---------------------------------------------------------------- 跳转 / 播放

    function isYouTubeAlbum(collection) {
        return collection && String(collection.id).indexOf("MPRE") === 0
    }

    function openCollection(collection) {
        if (!collection)
            return
        const sourceName = collection.source || page.source
        const args = {
            "id": String(collection.id),
            "name": collection.name || "",
            "source": sourceName,
            "coverUrl": collection.coverUrl || "",
            "creator": collection.creator || "",
            "trackCount": collection.trackCount || 0
        }
        if (sourceName === "YOUTUBE" && page.isYouTubeAlbum(collection))
            Nav.push("albumDetail", args)
        else
            Nav.push("onlineCollection", args)
    }

    function openArtist(artist) {
        if (!artist)
            return
        Nav.push("remoteArtist", {
                      "id": String(artist.id),
                      "name": artist.name || "",
                      "source": artist.source || page.source,
                      "avatarUrl": artist.avatarUrl || "",
                      "songCount": artist.songCount || 0
                  })
    }

    function playAt(index) {
        const list = page.resultSongs
        if (index < 0 || index >= list.length)
            return
        actions.playSongs(list, index)
    }

    function playYouTubeShelfItem(song, songs, index) {
        if (!song)
            return
        const list = songs && songs.length > 0 ? songs : [song]
        actions.playSongs(list, index < 0 ? 0 : index)
    }

    function enqueueSong(song) {
        if (!song)
            return
        actions.enqueueNext([song])
    }

    function startDemo() {
        if (__autoDemo !== "" && !demoTimer.running)
            demoTimer.start()
    }

    Component.onCompleted: {
        _ytEnabled = Settings.get("youtubeEnabled", false) === true
        ensureValidSource()
        applyAutoSource()
        if (Api.connected) {
            reloadDefault()
            startDemo()
        }
    }

    // 截图/冷启动时页面可能先于后端连接创建，连接建立后再拉一次数据。
    Connections {
        target: Backend

        function onConnectedChanged() {
            if (Backend.connected) {
                page.ensureValidSource()
                page.reloadDefault()
                page.startDemo()
            }
        }
    }

    // 设置里启停 YouTube 音源后刷新音源列表。
    Connections {
        target: Settings

        function onValuesChanged() {
            const enabled = Settings.get("youtubeEnabled", false) === true
            const toggled = enabled !== page._ytEnabled
            page._ytEnabled = enabled
            page.ensureValidSource()
            page.applyAutoSource()
            // 只有开关状态变化时才重载，避免保存搜索历史等设置写入触发无谓的 YouTube 请求
            if (toggled && page.youtube)
                page.loadYouTubeDefault()
        }
    }

    Timer {
        id: demoTimer
        interval: 2200
        repeat: false
        onTriggered: page.runSearch("周杰伦", false)
    }

    // ---------------------------------------------------------------- 界面

    Text {
        id: pageTitle

        anchors.left: parent.left
        anchors.leftMargin: 20
        anchors.top: parent.top
        anchors.topMargin: 16
        text: "探索"
        color: Theme.onSurfaceColor
        font.family: Theme.fontFamily
        font.pixelSize: 24
        font.weight: Font.Bold
    }

    SearchBar {
        id: searchBar

        anchors.left: parent.left
        anchors.right: parent.right
        anchors.leftMargin: 16
        anchors.rightMargin: 16
        anchors.top: pageTitle.bottom
        anchors.topMargin: 10
        onSubmitted: function(keyword) { page.runSearch(keyword, true) }
        onCleared: page.resetSearch()
    }

    Row {
        id: sourceRow

        anchors.left: parent.left
        anchors.right: parent.right
        anchors.leftMargin: 16
        anchors.rightMargin: 16
        anchors.top: searchBar.bottom
        anchors.topMargin: 10
        spacing: 8

        Repeater {
            model: page.sources

            delegate: TagChip {
                text: modelData.name
                selected: page.source === modelData.id
                onClicked: page.setSource(modelData.id)
            }
        }
    }

    // YouTube 搜索分类（其它音源高度为 0）
    Item {
        id: kindRow

        anchors.left: parent.left
        anchors.right: parent.right
        anchors.leftMargin: 16
        anchors.rightMargin: 16
        anchors.top: sourceRow.bottom
        height: page.youtube ? 38 : 0

        Row {
            anchors.verticalCenter: parent.verticalCenter
            spacing: 8
            visible: page.youtube

            Repeater {
                model: page.ytKinds

                delegate: TagChip {
                    text: modelData.name
                    selected: page.ytKind === modelData.id
                    onClicked: page.setYouTubeKind(modelData.id)
                }
            }
        }
    }

    Item {
        id: contentArea

        anchors.left: parent.left
        anchors.right: parent.right
        anchors.top: kindRow.bottom
        anchors.topMargin: 6
        anchors.bottom: parent.bottom
        clip: true

        Flickable {
            id: scroller

            anchors.fill: parent
            visible: !page.loading && !page.showDefaultLoading && page.errorMessage === ""
            contentWidth: width
            contentHeight: scrollColumn.implicitHeight + 24
            boundsBehavior: Flickable.StopAtBounds
            clip: true

            Column {
                id: scrollColumn

                width: scroller.width
                spacing: 0

                // ---------------- 默认内容：历史 / 热词 / 标签 / 推荐歌单 ----------------
                SearchHistoryRow {
                    width: scrollColumn.width
                    history: page.searchHistory
                    visible: page.submittedQuery === "" && page.activeTag === "" && page.searchHistory.length > 0
                    onKeywordClicked: function(keyword) { page.runSearch(keyword, true) }
                    onClearRequested: page.clearHistory()
                }

                SectionHeader {
                    width: scrollColumn.width
                    visible: page.submittedQuery === "" && page.activeTag === "" && page.hotKeywords.length > 0
                    title: "热门搜索"
                    icon: "local_fire_department"
                }

                ChipFlow {
                    width: scrollColumn.width
                    items: page.hotKeywords.slice(0, 16)
                    visible: page.submittedQuery === "" && page.activeTag === "" && page.hotKeywords.length > 0
                    onItemClicked: function(keyword) { page.runSearch(keyword, true) }
                }

                SectionHeader {
                    width: scrollColumn.width
                    visible: page.submittedQuery === "" && page.activeTag === "" && page.tags.length > 0
                    title: "风格标签"
                    icon: "sell"
                }

                ChipFlow {
                    width: scrollColumn.width
                    items: page.tags.map(function(tag) { return tag.name })
                    visible: page.submittedQuery === "" && page.activeTag === "" && page.tags.length > 0
                    onItemClicked: function(tagName) { page.runTag(tagName) }
                }

                SectionHeader {
                    width: scrollColumn.width
                    visible: page.submittedQuery === "" && page.activeTag === "" && page.recommended.length > 0
                    title: "推荐歌单"
                    icon: "album"
                }

                CollectionGrid {
                    width: scrollColumn.width
                    items: page.recommended
                    visible: page.submittedQuery === "" && page.activeTag === "" && page.recommended.length > 0
                    onItemClicked: function(item) { page.openCollection(item) }
                }

                // ---------------- YouTube：登录卡 + 公开推荐 ----------------
                Item {
                    width: scrollColumn.width
                    height: page.youtube && !page.ytLoggedIn && page.submittedQuery === "" && page.activeTag === ""
                            ? loginCard.height + Theme.spacingLg * 2 : 0
                    visible: height > 0

                    YouTubeLoginCard {
                        id: loginCard

                        x: Theme.spacingLg
                        y: Theme.spacingLg
                        width: Math.min(scrollColumn.width - Theme.spacingLg * 2, 560)
                        onLoginStateChanged: page.onYouTubeAccountChanged()
                    }
                }

                Repeater {
                    model: page.youtube && page.submittedQuery === "" && page.activeTag === "" ? page.ytSections : []

                    delegate: YouTubeShelfRow {
                        width: scrollColumn.width
                        title: modelData && modelData.title ? String(modelData.title) : ""
                        items: modelData && modelData.items ? modelData.items : []
                        onPlaySong: function(song, songs, index) { page.playYouTubeShelfItem(song, songs, index) }
                        onOpenCollection: function(collection) { page.openCollection(collection) }
                        onOpenArtist: function(artist) { page.openArtist(artist) }
                    }
                }

                EmptyState {
                    width: scrollColumn.width
                    visible: page.submittedQuery === "" && page.activeTag === ""
                             && page.source === "BILIBILI" && !page.defaultLoading
                             && page.hotKeywords.length === 0 && page.recommended.length === 0
                    title: "哔哩哔哩暂无推荐内容"
                    hint: "输入关键词搜索视频，切换回网易云可以浏览推荐歌单"
                    icon: "explore"
                }

                EmptyState {
                    width: scrollColumn.width
                    visible: page.submittedQuery === "" && page.activeTag === ""
                             && page.source === "NETEASE" && !page.defaultLoading && page.defaultContentEmpty
                    title: "暂无推荐内容"
                    hint: "请检查网络连接，或在设置中开启网易云音源"
                    icon: "explore"
                }

                EmptyState {
                    width: scrollColumn.width
                    visible: page.submittedQuery === "" && page.activeTag === ""
                             && page.youtube && !page.defaultLoading && page.hasResults === false
                             && page.ytSections.length === 0 && page.ytMessage !== ""
                    title: "YouTube 暂无推荐内容"
                    hint: page.ytMessage
                    icon: "smart_display"
                }

                // ---------------- 搜索结果 ----------------
                SearchResultSections {
                    width: scrollColumn.width
                    visible: page.submittedQuery !== "" && page.activeTag === "" && page.hasResults
                    songs: page.resultSongs
                    collections: page.resultCollections
                    artists: page.resultArtists
                    sourceName: page.sourceName
                    onPlayAt: function(index) { page.playAt(index) }
                    onSongMore: function(song) { page.enqueueSong(song) }
                    onOpenCollection: function(collection) { page.openCollection(collection) }
                    onOpenArtist: function(artist) { page.openArtist(artist) }
                }

                EmptyState {
                    width: scrollColumn.width
                    visible: page.submittedQuery !== "" && page.activeTag === "" && !page.hasResults
                    title: "无搜索结果"
                    hint: page.youtube ? "尝试更换关键词或分类，YouTube 未登录时也能匿名搜索"
                                       : "尝试更换关键词，或切换到其它音源"
                    icon: "search"
                }

                // ---------------- 标签歌单 ----------------
                SectionHeader {
                    width: scrollColumn.width
                    visible: page.activeTag !== ""
                    title: "「" + page.activeTag + "」歌单（" + page.tagCollections.length + "）"
                    icon: "sell"
                    actionText: "清除"
                    onActionClicked: page.clearTag()
                }

                CollectionGrid {
                    width: scrollColumn.width
                    items: page.tagCollections
                    visible: page.activeTag !== "" && page.tagCollections.length > 0
                    onItemClicked: function(item) { page.openCollection(item) }
                }

                EmptyState {
                    width: scrollColumn.width
                    visible: page.activeTag !== "" && page.tagCollections.length === 0
                    title: "暂无标签歌单"
                    hint: "该标签下没有取到歌单，可以换个标签试试"
                    icon: "sell"
                }

                Item { width: 1; height: 24 }
            }
        }

        LoadingState {
            anchors.horizontalCenter: parent.horizontalCenter
            anchors.top: parent.top
            anchors.topMargin: 72
            visible: page.loading || page.showDefaultLoading
            text: page.showDefaultLoading ? "正在加载探索内容…" : "正在加载…"
        }

        ErrorCard {
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.leftMargin: 16
            anchors.rightMargin: 16
            anchors.top: parent.top
            anchors.topMargin: 24
            visible: page.errorMessage !== ""
            message: page.errorMessage
            onRetry: {
                if (page.submittedQuery !== "")
                    page.runSearch(page.submittedQuery, false)
                else if (page.activeTag !== "")
                    page.runTag(page.activeTag)
                else
                    page.reloadDefault()
            }
        }
    }
}
