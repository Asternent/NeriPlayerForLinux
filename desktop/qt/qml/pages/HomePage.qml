import QtQuick
import ".."
import "../components"
import "../components/home"
import "../components/library"

// 首页：LargeTopAppBar 风格大标题 + 栏目化推荐列表。
// 数据来源：
//   - online.home 一次返回后端排好序的 sections（songs / collections / groups 三种 kind）；
//   - history.continuePlaying 单独拿本地最新播放历史（在线聚合里的同名字段只作兜底）。
// 栏目只按后端顺序渲染：空栏目后端不会下发；未登录的登录专属栏目也不下发，
// 只在 errors 里留一条「登录后解锁」提示，由页面渲染成温和的登录引导卡。
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
    readonly property bool loggedIn: homeData ? homeData.loggedIn === true : false
    /** 未登录时后端把这些栏目的说明放进 errors，页面单独渲染登录引导卡而不是错误卡。 */
    readonly property var loginErrors: {
        const result = []
        for (let i = 0; i < homeErrors.length; i++) {
            if (String(homeErrors[i]).indexOf("登录") === 0)
                result.push(homeErrors[i])
        }
        return result
    }
    readonly property var loadErrors: {
        const result = []
        for (let i = 0; i < homeErrors.length; i++) {
            if (String(homeErrors[i]).indexOf("登录") !== 0)
                result.push(homeErrors[i])
        }
        return result
    }
    readonly property bool showErrorCard: loadError !== "" || loadErrors.length > 0
    readonly property bool showLoginHint: !loggedIn && loginErrors.length > 0
    readonly property bool youtubeEnabled: Settings.values ? Settings.values.youtubeEnabled === true : false

    property var _eventHandlers: []
    /** 上次请求时的音源开关，用于在设置变化时强制重取。 */
    property string _sourceFlags: ""
    /** 请求序号：只接受最后一次请求的结果，避免并发刷新时旧响应覆盖新数据。 */
    property int _loadSeq: 0

    // ------------------------------------------------------------------ 数据加载

    /** @param force 忽略后端短缓存（下拉刷新 / 登录态变化 / 音源开关变化）。 */
    function reload(force) {
        if (!Api.connected) {
            // 后端还没连上：不发起请求，等 connected 变化后自动重载；已有内容时保留，
            // 首次加载则给出错误卡，避免一直转圈。
            if (!loadedOnce) {
                loading = false
                loadedOnce = true
            }
            if (loadError === "")
                loadError = "后端未连接"
            return
        }
        const seq = ++page._loadSeq
        refreshing = true
        refreshStartedAt = Date.now()
        if (!loadedOnce)
            loading = true
        loadError = ""
        loadTimeout.restart()
        refreshContinuePlaying()

        Api.call("online.home", force ? { "refresh": true } : {}).then(function(result) {
            if (seq !== page._loadSeq)
                return
            loadTimeout.stop()
            homeData = result || null
            homeErrors = (result && result.errors) ? result.errors : []
            // 兜底：history.continuePlaying 不可用时用聚合结果里的继续播放
            if ((!continueSongs || continueSongs.length === 0) && result
                    && result.continuePlaying && result.continuePlaying.songs)
                continueSongs = result.continuePlaying.songs
            finishLoad()
        }).catch(function(error) {
            if (seq !== page._loadSeq)
                return
            loadTimeout.stop()
            homeData = null
            homeErrors = []
            loadError = (error && error.message) ? error.message : "无法连接在线推荐服务"
            finishLoad()
        })
    }

    /** 请求兜底超时：网络卡住时也要结束加载态并给出可点击重试的错误卡。 */
    property Timer loadTimeout: Timer {
        interval: 25000
        repeat: false
        onTriggered: {
            if (!page.loading && !page.refreshing)
                return
            ++page._loadSeq
            page.homeData = null
            page.homeErrors = []
            page.loadError = "加载超时，请检查网络后重试"
            page.finishLoad()
        }
    }

    /** 刷新开始时间，用于保证指示器至少显示一小段时间。 */
    property real refreshStartedAt: 0

    function finishLoad() {
        loading = false
        loadedOnce = true
        const elapsed = Date.now() - refreshStartedAt
        const remain = Math.max(0, 900 - elapsed)
        if (remain > 0) {
            refreshDoneTimer.interval = remain
            refreshDoneTimer.start()
        } else {
            refreshing = false
        }
    }

    property Timer refreshDoneTimer: Timer {
        repeat: false
        onTriggered: page.refreshing = false
    }

    function refreshContinuePlaying() {
        Api.call("history.continuePlaying", { "limit": 12 }).then(function(result) {
            continueSongs = (result && result.songs) ? result.songs : []
        }).catch(function(error) {
            console.warn("[home] history.continuePlaying failed:", error)
        })
    }

    function currentSourceFlags() {
        if (!Settings.values)
            return ""
        return String(Settings.values.neteaseEnabled) + "/" + String(Settings.values.bilibiliEnabled)
    }

    // ------------------------------------------------------------------ 栏目构建

    /**
     * 首页栏目开关（Settings.homeCards）。
     * 新字段为 dailyRecommend / privateFm / biliMusic / ranks / newSongs / playlists；
     * 旧配置里的 hotTracks / recommended 继续生效（ranks 与 hotTracks、playlists 与 recommended 同时成立才显示）。
     */
    function cardEnabled(id) {
        const cards = Settings.values ? Settings.values.homeCards : undefined
        if (!cards)
            return true
        switch (id) {
        case "continuePlaying":
            return cards.continuePlaying !== false
        case "guessYouLike":
            // 同时兼容旧字段 recommended 与新字段 guessYouLike
            return cards.guessYouLike !== false && cards.recommended !== false
        case "ranks":
            return cards.ranks !== false && cards.hotTracks !== false
        case "playlists":
            return cards.playlists !== false && cards.recommended !== false
        case "radar":
            return cards.radar !== false
        case "dailyRecommend":
            return cards.dailyRecommend !== false
        case "privateFm":
            return cards.privateFm !== false
        case "biliMusic":
            return cards.biliMusic !== false
        case "newSongs":
            return cards.newSongs !== false
        // 旧后端兜底字段（没有 sections 时按兼容字段拼栏目）
        case "hotTracks":
            return cards.hotTracks !== false
        case "hotLists":
            return cards.recommended !== false
        default:
            return true
        }
    }

    function sectionIcon(id) {
        switch (id) {
        case "continuePlaying":
            return "history"
        case "guessYouLike":
            return "auto_awesome"
        case "radar":
            return "radar"
        case "dailyRecommend":
            return "today"
        case "privateFm":
            return "headphones"
        case "biliMusic":
            return "smart_display"
        case "ranks":
            return "leaderboard"
        case "newSongs":
            return "new_releases"
        case "playlists":
            return "playlist_play"
        default:
            return "library_music"
        }
    }

    function sectionTitle(id, fallback) {
        if (fallback && fallback !== "")
            return fallback
        switch (id) {
        case "guessYouLike":
            return "猜你喜欢"
        case "radar":
            return "雷达歌单"
        case "dailyRecommend":
            return "每日推荐"
        case "privateFm":
            return "私人 FM"
        case "biliMusic":
            return "B站音乐"
        case "ranks":
            return "排行榜"
        case "newSongs":
            return "推荐新歌"
        case "playlists":
            return "推荐歌单"
        default:
            return "推荐"
        }
    }

    /** 栏目内容数量：groups 取所有 tab 之和。 */
    function sectionItemCount(section) {
        if (!section)
            return 0
        if (section.kind === "groups") {
            let total = 0
            const groups = section.groups || []
            for (let i = 0; i < groups.length; i++) {
                const group = groups[i]
                if (!group)
                    continue
                total += (group.songs ? group.songs.length : 0)
                total += (group.collections ? group.collections.length : 0)
            }
            return total
        }
        if (section.kind === "collections")
            return section.collections ? section.collections.length : 0
        return section.songs ? section.songs.length : 0
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
                "groups": []
            })
        }

        const data = page.homeData
        if (!data)
            return result

        let rawSections = data.sections
        if (!rawSections || rawSections.length === 0) {
            // 兜底：后端没有 sections（旧后端）时按兼容字段拼一遍
            rawSections = []
            const fallback = [
                ["guessYouLike", "猜你喜欢", "songs"],
                ["hotTracks", "热歌榜", "songs"],
                ["newSongs", "推荐新歌", "songs"],
                ["radar", "雷达歌单", "collections"],
                ["hotLists", "热门榜单", "collections"]
            ]
            for (let i = 0; i < fallback.length; i++) {
                const entry = data[fallback[i][0]]
                if (!entry)
                    continue
                rawSections.push({
                    "id": fallback[i][0],
                    "title": fallback[i][1],
                    "kind": fallback[i][2],
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
            if (page.sectionItemCount(section) === 0)
                continue
            const isGroups = section.kind === "groups"
            const collectionKind = section.kind === "collections"
            result.push({
                "key": id !== "" ? id : "section-" + i,
                "title": page.sectionTitle(id, section.title),
                "icon": section.icon !== undefined && section.icon !== ""
                        ? section.icon : page.sectionIcon(id),
                "kind": section.kind,
                "items": isGroups ? [] : (collectionKind ? (section.collections || []) : (section.songs || [])),
                "groups": isGroups ? (section.groups || []) : []
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

    function handleSectionAction(kind, items) {
        if (kind === "collections") {
            Nav.openTab("explore")
            return
        }
        if (kind === "continue") {
            Nav.openByName("recent")
            return
        }
        playSongs(items, 0)
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
            ["history.changed", function() { page.refreshContinuePlaying() }],
            // 登录 / 登出后每日推荐、私人 FM 等栏目的可用性会变化，强制重取
            ["accounts.changed", function() { page.reload(true) }],
            // 音源开关会改变后端返回的栏目，变化时强制重取（用事件负载而不是 Settings.values，
            // 避免与 SettingsGlobal 的订阅顺序产生竞态）
            ["settings", function(data) {
                if (!data)
                    return
                const flags = String(data.neteaseEnabled) + "/" + String(data.bilibiliEnabled)
                if (flags === page._sourceFlags)
                    return
                page._sourceFlags = flags
                if (page.loadedOnce)
                    page.reload(true)
            }]
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
        _sourceFlags = currentSourceFlags()
        subscribeEvents()
        if (Api.connected)
            reload(false)
        else
            connectTimeout.restart()
    }

    Component.onDestruction: {
        unsubscribeEvents()
        connectTimeout.stop()
    }

    // 后端断线重连后自动重新加载；断线时立即结束加载态并给出错误卡（不要无限转圈）
    Connections {
        target: Backend

        function onConnectedChanged() {
            if (Backend.connected) {
                connectTimeout.stop()
                page._sourceFlags = page.currentSourceFlags()
                // 首次加载 / 加载失败（含断线）时强制重取，否则走短缓存快速刷新
                page.reload(page.loadError !== "" || !page.loadedOnce)
            } else {
                ++page._loadSeq
                page.loadTimeout.stop()
                page.loading = false
                page.refreshing = false
                page.loadError = "后端未连接，正在等待重连…"
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

        // 副标题收起时如果只是把 opacity 设 0，它会继续占着高度，标题就被挤到偏上位置。
        // 这里用「标题 + 副标题」整体做垂直居中，副标题高度随收起动画一起归零，标题因此始终居中。
        Column {
            anchors.left: parent.left
            anchors.leftMargin: page.sideMargin
            anchors.right: parent.right
            // 右侧给常驻刷新按钮留出位置，标题过长时省略而不是压到按钮上
            anchors.rightMargin: page.sideMargin + 52
            anchors.verticalCenter: parent.verticalCenter
            spacing: page.compactHeader ? 0 : 2

            Behavior on spacing {
                NumberAnimation { duration: Theme.motionShort }
            }

            Text {
                width: parent.width
                text: "音理音理!!"
                elide: Text.ElideRight
                color: Theme.onSurfaceColor
                font.family: Theme.fontFamily
                font.pixelSize: page.compactHeader ? 22 : 28
                font.weight: Font.Bold
            }

            // 收起时高度也归零，不占位
            Item {
                width: parent.width
                height: page.compactHeader ? 0 : subtitleText.implicitHeight
                clip: true

                Behavior on height {
                    NumberAnimation {
                        duration: Theme.motionShort
                        easing.type: Easing.OutCubic
                    }
                }

                Text {
                    id: subtitleText
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
        }

        // 常驻刷新按钮：下拉刷新在桌面端不容易发现，标题栏固定给一个入口。
        IconButton {
            id: headerRefreshButton

            anchors.right: parent.right
            anchors.rightMargin: page.sideMargin - 8
            anchors.verticalCenter: parent.verticalCenter
            icon: "refresh"
            iconSize: 24
            size: 44
            tooltip: page.refreshing ? "正在刷新首页…" : "刷新首页"
            opacity: page.refreshing ? 0.5 : 1
            enabled: !page.refreshing
            onClicked: page.reload(true)

            // 刷新中旋转图标（IconButton 整体旋转对圆形 hover 底无视觉影响）
            RotationAnimation on rotation {
                running: page.refreshing
                loops: Animation.Infinite
                from: 0
                to: 360
                duration: 900
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
        // 允许下拉过头以触发刷新（松手后自动回弹）
        boundsBehavior: Flickable.DragAndOvershootBounds
        flickableDirection: Flickable.VerticalFlick
        clip: true

        // 下拉刷新指示器：内容拖到顶部以下时显示，松手超过阈值就触发 reload。
        property real pullDistance: Math.max(0, -verticalOvershoot)
        readonly property bool pullReady: pullDistance >= 64

        // 关键：必须在 dragEnded（松手瞬间）判断，不能用 movementEnded——
        // 后者在回弹动画结束后才触发，那时 verticalOvershoot 已归零，永远刷新不了。
        onDragEnded: {
            if (pullReady && !page.refreshing)
                page.reload(true)
        }

        // 下拉指示器：拖动时跟随下拉距离旋转；松手触发刷新后保持显示，
        // 用持续的旋转动画表达「正在刷新」，直到加载完成才淡出。
        Rectangle {
            id: refreshIndicator
            z: 2
            anchors.horizontalCenter: parent.horizontalCenter
            // 拖动时浮在内容上沿（y=-48），回弹后固定在顶部可见位置
            y: page.refreshing ? 12 : -48
            width: 32
            height: 32
            radius: 16
            color: Theme.primary
            opacity: page.refreshing ? 1 : Math.min(1, scroll.pullDistance / 48)
            visible: opacity > 0.01

            Behavior on y {
                NumberAnimation { duration: 180; easing.type: Easing.OutCubic }
            }
            Behavior on opacity {
                NumberAnimation { duration: 180 }
            }

            NeriIcon {
                id: refreshIcon
                anchors.centerIn: parent
                name: "refresh"
                iconSize: 18
                color: Theme.onPrimaryColor
                // 拖动阶段角度随下拉距离；刷新阶段由 refreshSpin 直接接管 rotation
                rotation: page.refreshing ? 0 : scroll.pullDistance * 3

                Behavior on rotation {
                    enabled: !page.refreshing
                    NumberAnimation { duration: 80 }
                }
            }

            // 刷新中的持续旋转（约 1 秒一圈）。
            RotationAnimation {
                id: refreshSpin
                target: refreshIcon
                property: "rotation"
                from: 0
                to: 360
                duration: 1000
                loops: Animation.Infinite
                running: page.refreshing
            }
        }

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
                height: page.showErrorCard ? 48 : 0
                visible: page.showErrorCard
                message: page.loadError !== ""
                         ? "首页加载失败：" + page.loadError
                         : page.loadErrors.join("；")
                onRetry: page.reload(true)
            }

            // 未登录：登录专属栏目被隐藏，用温和的引导卡替代红色错误
            LoginHintCard {
                id: loginHint

                width: contentColumn.width
                height: visible ? implicitHeight : 0
                visible: page.showLoginHint
                message: page.loginErrors.join("；")
                onLoginClicked: Nav.openLoginPanel("NETEASE")
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
                    groups: modelData.groups
                    onActionClicked: function(kind, items) {
                        page.handleSectionAction(kind, items)
                    }
                    onSongActivated: function(songs, index) {
                        page.playSongs(songs, index)
                    }
                    onCollectionActivated: function(collection) {
                        page.openCollection(collection)
                    }
                }
            }

            // YouTube Music 首页 shelves：组件由 YouTube 音源负责人提供，
            // 未启用 / 文件不存在 / 无数据时 Loader 不激活或高度为 0，不影响首页其它栏目。
            Loader {
                id: youtubeLoader

                width: contentColumn.width
                active: page.youtubeEnabled
                // 组件自带高度（implicitHeight 优先，其次 height）才显示；无数据时应自行隐藏为 0。
                visible: status === Loader.Ready && item !== null
                         && ((item.implicitHeight !== undefined && item.implicitHeight > 0)
                             || (item.height !== undefined && item.height > 0))
                height: visible
                        ? (item.implicitHeight !== undefined && item.implicitHeight > 0
                           ? item.implicitHeight : item.height)
                        : 0
                source: Qt.resolvedUrl("../components/youtube/YouTubeHomeSection.qml")
                asynchronous: true

                onStatusChanged: {
                    if (status === Loader.Error)
                        console.warn("[home] YouTube 首页组件不可用：", source)
                }
                onLoaded: {
                    if (item) {
                        item.width = Qt.binding(function() { return contentColumn.width })
                    }
                }
            }

            EmptyState {
                width: contentColumn.width
                height: page.showEmpty ? 280 : 0
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
