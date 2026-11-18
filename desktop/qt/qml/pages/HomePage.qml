import QtQuick
import "../components"
import "../components/home"

// 首页：LargeTopAppBar 风格大标题 + 栏目列表。
// 数据来源：
//   - online.home 一次返回在线聚合（guessYouLike/hotTracks/newSongs/radar/hotLists/sections/errors）；
//   - history.continuePlaying 单独拿本地最新播放历史（在线聚合里的同名字段只作兜底）。
// 栏目顺序以后端 sections 为准，前端只按 Settings.homeCards 开关过滤；空区块整栏隐藏。
Item {
    id: page

    readonly property bool wide: width >= Theme.wideBreakpoint
    /** 页面内容左右留白：宽屏 20dp / 窄屏 16dp。 */
    readonly property real sideMargin: wide ? 20 : 16
    /** 顶栏随滚动收缩到 64dp。 */
    readonly property bool compactHeader: scroll.contentY > 24

    // ---- 数据状态 ----
    property bool loading: true
    property bool refreshing: false
    property bool loadedOnce: false
    property string loadError: ""
    property var homeData: null
    property var homeErrors: []
    property var continueSongs: []

    readonly property var sections: page.buildSections()
    readonly property bool hasContent: sections.length > 0
    readonly property bool showEmpty: loadedOnce && !loading && !hasContent

    property var _eventHandlers: []

    // ------------------------------------------------------------------ 数据加载

    function reload() {
        if (!Api.connected) {
            // 后端还没连上：等 connected 事件再加载，避免误报「后端未连接」
            return
        }
        refreshing = true
        if (!loadedOnce)
            loading = true
        loadError = ""
        refreshContinuePlaying()

        Api.call("online.home").then(function(result) {
            homeData = result || null
            homeErrors = (result && result.errors) ? result.errors : []
            // 兜底：history.continuePlaying 不可用时用聚合结果里的继续播放
            if ((!continueSongs || continueSongs.length === 0) && result
                    && result.continuePlaying && result.continuePlaying.songs)
                continueSongs = result.continuePlaying.songs
            finishLoad()
        }).catch(function(error) {
            homeData = null
            homeErrors = []
            loadError = (error && error.message) ? error.message : "无法连接在线推荐服务"
            finishLoad()
        })
    }

    function finishLoad() {
        loading = false
        refreshing = false
        loadedOnce = true
    }

    function refreshContinuePlaying() {
        Api.call("history.continuePlaying", { "limit": 12 }).then(function(result) {
            continueSongs = (result && result.songs) ? result.songs : []
        }).catch(function(error) {
            console.warn("[home] history.continuePlaying failed:", error)
        })
    }

    // ------------------------------------------------------------------ 栏目构建

    /** 首页栏目开关（Settings.homeCards；旧设置里「为你推荐」叫 recommended）。 */
    function cardEnabled(id) {
        const cards = Settings.values ? Settings.values.homeCards : undefined
        if (!cards)
            return true
        switch (id) {
        case "continuePlaying":
            return cards.continuePlaying !== false
        case "guessYouLike":
            return cards.recommended !== false
        case "hotTracks":
            return cards.hotTracks !== false
        case "radar":
            return cards.radar !== false
        default:
            return true
        }
    }

    function sectionIcon(id) {
        switch (id) {
        case "continuePlaying":
            return "history"
        case "guessYouLike":
            return "recommend"
        case "hotTracks":
            return "local_fire_department"
        case "newSongs":
            return "auto_awesome"
        case "radar":
            return "radio"
        case "hotLists":
            return "queue_music"
        default:
            return "library_music"
        }
    }

    function sectionTitle(id, fallback) {
        if (fallback && fallback !== "")
            return fallback
        switch (id) {
        case "continuePlaying":
            return "继续播放"
        case "guessYouLike":
            return "为你推荐"
        case "hotTracks":
            return "热歌榜"
        case "newSongs":
            return "推荐新歌"
        case "radar":
            return "私人雷达"
        case "hotLists":
            return "热门榜单"
        default:
            return "推荐"
        }
    }

    function buildSections() {
        const result = []

        // 继续播放：独立命令比聚合里的快照更新
        const history = page.continueSongs || []
        if (history.length > 0 && page.cardEnabled("continuePlaying")) {
            result.push({
                "key": "continuePlaying",
                "title": "继续播放",
                "icon": "history",
                "kind": "continue",
                "items": history,
                "actionText": "最近播放"
            })
        }

        const data = page.homeData
        if (!data)
            return result

        // 后端 sections 已排好序；缺失时回退到各区块字段
        let rawSections = data.sections
        if (!rawSections || rawSections.length === 0) {
            rawSections = []
            const fallback = [
                ["guessYouLike", "为你推荐"],
                ["hotTracks", "热歌榜"],
                ["newSongs", "推荐新歌"],
                ["radar", "私人雷达"],
                ["hotLists", "热门榜单"]
            ]
            for (let i = 0; i < fallback.length; i++) {
                const entry = data[fallback[i][0]]
                if (!entry)
                    continue
                rawSections.push({
                    "id": fallback[i][0],
                    "title": fallback[i][1],
                    "kind": entry.collections !== undefined ? "collections" : "songs",
                    "songs": entry.songs,
                    "collections": entry.collections
                })
            }
        }

        for (let i = 0; i < rawSections.length; i++) {
            const section = rawSections[i]
            if (!section)
                continue
            const id = section.id || ""
            // 继续播放已由上面的独立请求渲染，避免重复
            if (id === "continuePlaying")
                continue
            if (!page.cardEnabled(id))
                continue
            // 热歌榜用竖向列表卡（旧 Compose 的 SongListCard 形态），其余歌曲栏目用横向卡片
            const kind = id === "hotTracks" ? "list"
                       : (section.kind === "collections" ? "collections" : "songs")
            const items = kind === "collections" ? (section.collections || []) : (section.songs || [])
            if (items.length === 0)
                continue
            result.push({
                "key": id !== "" ? id : "section-" + i,
                "title": page.sectionTitle(id, section.title),
                "icon": page.sectionIcon(id),
                "kind": kind,
                "items": items,
                "actionText": kind === "collections" ? "更多" : "播放全部"
            })
        }
        return result
    }

    // ------------------------------------------------------------------ 交互

    /** 用该栏目自己的歌曲列表作为播放队列。 */
    function playSongs(songs, index) {
        if (!songs || songs.length === 0)
            return
        Player.setQueue(songs, Math.max(0, Math.min(index, songs.length - 1)), true)
    }

    function activateSection(section) {
        if (!section)
            return
        if (section.key === "continuePlaying") {
            Nav.openByName("recent")
            return
        }
        if (section.kind === "collections") {
            Nav.openTab("explore")
            return
        }
        playSongs(section.items, 0)
    }

    function openCollection(collection) {
        if (!collection)
            return
        Nav.push("onlineCollection", collection)
    }

    // ------------------------------------------------------------------ 事件订阅

    function subscribeEvents() {
        const handlers = [
            ["library.changed", function() { page.refreshContinuePlaying() }],
            ["collections.changed", function() { page.refreshContinuePlaying() }],
            ["history.changed", function() { page.refreshContinuePlaying() }]
        ]
        for (let i = 0; i < handlers.length; i++) {
            Api.on(handlers[i][0], handlers[i][1])
            _eventHandlers.push(handlers[i])
        }
    }

    function unsubscribeEvents() {
        for (let i = 0; i < _eventHandlers.length; i++)
            Api.off(_eventHandlers[i][0], _eventHandlers[i][1])
        _eventHandlers = []
    }

    Component.onCompleted: {
        subscribeEvents()
        if (Api.connected)
            reload()
        else
            connectTimeout.restart()
    }

    Component.onDestruction: {
        unsubscribeEvents()
        connectTimeout.stop()
    }

    // 后端连上后自动开始加载；长时间连不上时给出错误卡而不是一直转圈
    Connections {
        target: Api

        function onConnectedChanged() {
            if (Api.connected) {
                connectTimeout.stop()
                page.reload()
            } else if (page.loadedOnce || !page.loading) {
                page.homeData = null
                page.homeErrors = []
                page.loadError = "后端未连接"
            }
        }
    }

    Timer {
        id: connectTimeout

        interval: 6000
        onTriggered: {
            if (!Api.connected && page.loading) {
                page.loading = false
                page.loadedOnce = true
                page.loadError = "后端未连接"
            }
        }
    }

    // ------------------------------------------------------------------ 顶部栏

    Item {
        id: header

        anchors.top: parent.top
        anchors.left: parent.left
        anchors.right: parent.right
        height: page.compactHeader ? 64 : 152

        Behavior on height {
            NumberAnimation {
                duration: Theme.motionMedium
                easing.type: Easing.OutCubic
            }
        }

        Column {
            anchors.left: parent.left
            anchors.leftMargin: page.sideMargin
            anchors.right: actions.left
            anchors.rightMargin: Theme.spacingSm
            anchors.bottom: parent.bottom
            anchors.bottomMargin: page.compactHeader ? 12 : Theme.spacingLg
            spacing: 2

            Text {
                width: parent.width
                text: "音理音理!!"
                elide: Text.ElideRight
                color: Theme.onSurfaceColor
                font.family: Theme.fontFamily
                font.pixelSize: page.compactHeader ? 22 : 28
                font.weight: Font.Bold
            }

            Text {
                width: parent.width
                text: "发现今天的好音乐"
                elide: Text.ElideRight
                color: Theme.onSurfaceVariantColor
                font.family: Theme.fontFamily
                font.pixelSize: Theme.textStyle("labelSmall").size
                opacity: page.compactHeader ? 0 : 1
                Behavior on opacity {
                    NumberAnimation { duration: Theme.motionShort }
                }
            }
        }

        Row {
            id: actions

            anchors.right: parent.right
            anchors.rightMargin: page.sideMargin - 8
            anchors.top: parent.top
            anchors.topMargin: 12
            spacing: 2

            IconButton {
                icon: "refresh"
                iconSize: 22
                size: 40
                iconColor: page.refreshing ? Theme.primary : Theme.onSurfaceVariantColor
                tooltip: "刷新首页"
                onClicked: page.reload()
            }

            IconButton {
                icon: "search"
                iconSize: 22
                size: 40
                tooltip: "搜索"
                onClicked: Nav.openTab("explore")
            }

            IconButton {
                icon: "download"
                iconSize: 22
                size: 40
                tooltip: "下载管理"
                onClicked: Nav.openDownloadsPanel()
            }

            IconButton {
                icon: "settings"
                iconSize: 22
                size: 40
                tooltip: "设置"
                onClicked: Nav.openTab("settings")
            }
        }
    }

    // ------------------------------------------------------------------ 首屏加载

    Item {
        id: loadingLayer

        anchors.top: header.bottom
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.bottom: parent.bottom
        visible: page.loading

        LoadingState {
            anchors.centerIn: parent
            width: parent.width
            text: "正在加载首页推荐…"
        }
    }

    // ------------------------------------------------------------------ 内容

    Flickable {
        id: scroll

        anchors.top: header.bottom
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.bottom: parent.bottom
        visible: !page.loading
        contentWidth: width
        contentHeight: contentColumn.height + 24
        boundsBehavior: Flickable.StopAtBounds
        flickableDirection: Flickable.VerticalFlick
        clip: true

        Column {
            id: contentColumn

            x: page.sideMargin
            width: Math.max(0, scroll.width - page.sideMargin * 2)
            spacing: Theme.spacingMd

            Item {
                width: 1
                height: Theme.spacingXs
            }

            // 在线区块加载失败 / 部分失败：页面顶部错误卡 + 重新加载
            ErrorCard {
                id: errorCard

                width: contentColumn.width
                height: 48
                visible: page.loadError !== "" || (page.homeErrors && page.homeErrors.length > 0)
                message: page.loadError !== ""
                         ? "首页加载失败：" + page.loadError
                         : page.homeErrors.join("；")
                onRetry: page.reload()
            }

            Repeater {
                model: page.sections

                delegate: HomeSectionRow {
                    width: contentColumn.width
                    height: implicitHeight
                    title: modelData.title
                    icon: modelData.icon
                    kind: modelData.kind
                    items: modelData.items
                    actionText: modelData.actionText
                    onActionClicked: page.activateSection(modelData)
                    onSongActivated: function(index) {
                        page.playSongs(modelData.items, index)
                    }
                    onCollectionActivated: function(collection) {
                        page.openCollection(collection)
                    }
                }
            }

            EmptyState {
                width: contentColumn.width
                height: 280
                visible: page.showEmpty
                icon: "home"
                title: "首页还没有内容"
                hint: "在设置中开启在线音源，或先播放几首本地音乐"
            }

            Item {
                width: 1
                height: Theme.spacingSm
            }
        }
    }
}
