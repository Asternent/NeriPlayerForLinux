import QtQuick
import QtQuick.Controls
import "../components"
import "../components/explore"

// 探索页：音源切换 + 搜索 + 搜索历史 / 热门搜索 / 风格标签 / 推荐歌单。
// 对应 Compose 版 ExploreScreen；数据全部来自后端 online.* 命令。
Item {
    id: page

    readonly property var sources: {
        const list = []
        if (Settings.get("neteaseEnabled", true) !== false)
            list.push({ "id": "NETEASE", "name": "网易云" })
        if (Settings.get("bilibiliEnabled", true) !== false)
            list.push({ "id": "BILIBILI", "name": "哔哩哔哩" })
        if (list.length === 0)
            list.push({ "id": "NETEASE", "name": "网易云" })
        return list
    }
    readonly property string sourceName: source === "BILIBILI" ? "哔哩哔哩" : "网易云"

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
    readonly property bool showDefaultLoading: defaultLoading && defaultContentEmpty

    property int _defaultSeq: 0
    property int _pendingDefault: 0
    property int _searchSeq: 0

    // 调试开关：非空时自动搜索「周杰伦」用于截图验证（search/collection/artist），默认关闭，不影响正常使用。
    property string __autoDemo: ""

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

    onSourcesChanged: ensureValidSource()

    function setSource(id) {
        if (source === id)
            return
        source = id
        activeTag = ""
        tagCollections = []
        results = emptyResults()
        errorMessage = ""
        reloadDefault()
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
        if (tagName === "")
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

    // ---------------------------------------------------------------- 跳转 / 播放

    function openCollection(collection) {
        if (!collection)
            return
        Nav.push("onlineCollection", {
                      "id": String(collection.id),
                      "name": collection.name || "",
                      "source": collection.source || page.source,
                      "coverUrl": collection.coverUrl || "",
                      "creator": collection.creator || "",
                      "trackCount": collection.trackCount || 0
                  })
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
        ensureValidSource()
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

    Item {
        id: contentArea
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.top: sourceRow.bottom
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
                    hint: "尝试更换关键词，或切换到其它音源"
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
