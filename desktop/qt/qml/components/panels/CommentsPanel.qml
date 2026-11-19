import QtQuick
import QtQuick.Controls
import ".."
import "../player"
import "../library"
import "../comments"

// 评论区面板：由播放页「评论」按钮 Nav.openPanel("comments") 打开，读取 Player.song。
// 协议见 desktop/docs/protocol-comments.md；面板挂载由 components/PlayerOverlays.qml 负责。
//
// 数据流：comments.list 拉一级评论（分页 + 游标）→ 点赞用本地覆盖表做即时反馈；
// 展开回复时再按需 comments.replies 拉楼中楼，未登录时由 comments.like/post 返回
// requiresLogin，面板据此提示登录。
PanelShell {
    id: panel

    // ---- 评论数据 ----
    property var comments: []
    property bool loading: false
    property bool loadingMore: false
    property bool hasMore: false
    property int total: -1
    property int page: 0
    property string cursor: ""
    property string loadError: ""
    property string errorCode: ""
    property bool loginRequired: false

    // ---- 平台 / 登录态 ----
    property string platformName: "UNSUPPORTED"
    property string platformLabel: ""
    property bool loggedIn: false
    property int lengthLimit: 140

    // ---- 排序 / 互动 ----
    property string sort: "hot"
    property var likeOverrides: ({})
    property var expandedRoots: ({})
    property var replyState: ({})
    property bool posting: false
    property int composerResetToken: 0

    // 当前正在展示评论的歌曲（直接回传给后端，避免后端再查一次播放器状态）
    property var _target: null
    // 请求序号：切歌 / 换排序时丢弃在途响应，避免旧数据覆盖新列表
    property int _requestToken: 0
    property string _boundSongKey: ""

    // Player.song 每个播放事件都是新对象，这里用稳定 key 判断「真的切歌了」
    readonly property string songKey: Player.song && Player.song.key ? String(Player.song.key) : ""
    readonly property bool hasSong: songKey !== ""
    readonly property bool supported: platformName === "NETEASE" || platformName === "BILIBILI"
    readonly property real loadedCount: panel.comments.length

    title: "评论"
    cardWidth: 480

    onSongKeyChanged: panel.reload()

    // ---------------------------------------------------------------- 生命周期

    Component.onCompleted: {
        panel._boundSongKey = panel.songKey
        Api.on("accounts.changed", panel._onAccountsChanged)
        panel.loadAccounts()
        panel.reload()
    }

    Component.onDestruction: {
        if (panel._onAccountsChanged)
            Api.off("accounts.changed", panel._onAccountsChanged)
    }

    // 面板可能在 Backend 连上之前就被打开（--panel 启动测试），连上后补拉
    property Connections backendConnection: Connections {
        target: Backend

        function onConnectedChanged() {
            if (!Backend.connected)
                return
            // 面板可能先于后端连上创建：登录态与评论数据都要在后端就绪后重试
            panel.loadAccounts()
            if (panel.supported && panel.comments.length === 0 && !panel.loading)
                panel.reload()
        }
    }

    property var _onAccountsChanged: function() {
        panel.loadAccounts()
    }

    // ---------------------------------------------------------------- 工具函数

    function messageOf(error) {
        if (error && error.message)
            return String(error.message)
        if (typeof error === "string" && error !== "")
            return error
        return "未知错误"
    }

    /** C++ 桥下发的数组是 QVariantList 包装对象（Array.isArray 为 false），统一转成 JS 数组。 */
    function toArray(value) {
        if (value === undefined || value === null)
            return []
        const copy = []
        const length = value.length !== undefined ? Number(value.length) : 0
        for (let i = 0; i < length; ++i)
            copy.push(value[i])
        return copy
    }

    function copyMap(source) {
        const copy = {}
        if (source) {
            for (const key in source)
                copy[key] = source[key]
        }
        return copy
    }

    function sourcePlatform(song) {
        if (!song || !song.source)
            return "UNSUPPORTED"
        switch (String(song.source)) {
        case "NETEASE":
            return "NETEASE"
        case "BILIBILI":
            return "BILIBILI"
        default:
            return "UNSUPPORTED"
        }
    }

    function unsupportedHint() {
        const song = Player.song
        if (!song || !song.key)
            return "先播放一首歌再打开评论区"
        switch (String(song.source)) {
        case "LOCAL":
            return "本地歌曲没有评论区"
        case "YOUTUBE":
            return "YouTube 音源暂不支持评论"
        default:
            return "该歌曲来源不支持评论（仅支持网易云音乐 / 哔哩哔哩）"
        }
    }

    // ---------------------------------------------------------------- 数据加载

    function reload() {
        panel._requestToken += 1
        panel.loading = false
        panel.loadingMore = false
        panel.comments = []
        panel.likeOverrides = ({})
        panel.expandedRoots = ({})
        panel.replyState = ({})
        panel.page = 0
        panel.cursor = ""
        panel.hasMore = false
        panel.total = -1
        panel.loadError = ""
        panel.errorCode = ""
        panel.loginRequired = false
        panel.sort = "hot"

        const song = Player.song
        panel._boundSongKey = panel.songKey
        panel._target = song && song.key ? song : null
        panel.platformName = panel._target ? panel.sourcePlatform(song) : "UNSUPPORTED"
        panel.platformLabel = panel.platformName === "BILIBILI"
                               ? "哔哩哔哩"
                               : (panel.platformName === "NETEASE" ? "网易云音乐" : "")
        panel.lengthLimit = panel.platformName === "BILIBILI" ? 1000 : 140
        panel.loadAccounts()
        if (!panel._target || !panel.supported)
            return
        panel.loadPage(1)
    }

    function retry() {
        panel.reload()
    }

    function loadPage(targetPage) {
        const target = panel._target
        if (!target)
            return
        if (targetPage <= 1) {
            panel.loading = true
            panel.loadingMore = false
            panel.loadError = ""
            panel.errorCode = ""
            panel.loginRequired = false
            panel.comments = []
            panel.hasMore = false
            panel.page = 0
            panel.cursor = ""
            panel.total = -1
        } else {
            if (panel.loadingMore || !panel.hasMore)
                return
            panel.loadingMore = true
        }
        const token = panel._requestToken
        const params = { song: target, sort: panel.sort, page: targetPage }
        if (targetPage > 1 && panel.cursor !== "")
            params.cursor = panel.cursor
        Api.call("comments.list", params).then(function(result) {
            if (token !== panel._requestToken)
                return
            panel.loading = false
            panel.loadingMore = false
            const list = panel.toArray(result && result.comments ? result.comments : [])
            panel.comments = targetPage <= 1 ? list : panel.comments.concat(list)
            panel.hasMore = result && result.hasMore === true
            panel.total = result && result.total !== null && result.total !== undefined
                          ? Number(result.total) : -1
            panel.cursor = result && result.cursor ? String(result.cursor) : ""
            panel.page = targetPage
            panel.loginRequired = result && result.loginRequired === true
            if (targetPage <= 1) {
                panel.loadError = result && result.error && list.length === 0 ? String(result.error) : ""
                panel.errorCode = result && result.errorCode && list.length === 0 ? String(result.errorCode) : ""
                listView.positionViewAtBeginning()
            } else if (result && result.error) {
                Toast.show(String(result.error), "info")
            }
        }).catch(function(error) {
            if (token !== panel._requestToken)
                return
            panel.loading = false
            panel.loadingMore = false
            panel.loadError = panel.messageOf(error)
            panel.errorCode = "network"
        })
    }

    function loadMore() {
        if (!panel.hasMore || panel.loadingMore || panel.loading)
            return
        panel.loadPage(panel.page + 1)
    }

    function setSort(value) {
        if (panel.sort === value)
            return
        panel._requestToken += 1
        panel.sort = value
        panel.loadPage(1)
    }

    // ---------------------------------------------------------------- 账号 / 登录

    function loadAccounts() {
        Api.call("login.accounts").then(function(result) {
            const list = panel.toArray(result && result.accounts ? result.accounts : [])
            let found = false
            for (let i = 0; i < list.length; ++i) {
                const account = list[i]
                if (account && String(account.source) === panel.platformName)
                    found = true
            }
            panel.loggedIn = found
        }).catch(function() {
            panel.loggedIn = false
        })
    }

    function openLogin() {
        if (!panel.supported)
            return
        Nav.openLoginPanel(panel.platformName)
    }

    function promptLogin() {
        if (!panel.supported)
            return
        Toast.show("请先登录" + panel.platformLabel + "账号", "info")
    }

    // ---------------------------------------------------------------- 点赞

    function toggleLike(comment) {
        if (!comment || !comment.id)
            return
        if (!panel.supported)
            return
        if (!panel.loggedIn) {
            panel.promptLogin()
            return
        }
        const id = String(comment.id)
        const current = panel.isLiked(comment)
        const next = !current
        const baseCount = panel.likeCountOf(comment)
        Api.call("comments.like", {
                      song: panel._target,
                      commentId: id,
                      like: next
                  }).then(function(result) {
            if (result && result.ok === true) {
                const overrides = panel.copyMap(panel.likeOverrides)
                overrides[id] = {
                    "liked": next,
                    "likedCount": Math.max(0, baseCount + (next ? 1 : -1))
                }
                panel.likeOverrides = overrides
            } else if (result && result.requiresLogin === true) {
                panel.loadAccounts()
                panel.promptLogin()
            } else {
                Toast.show(result && result.error ? String(result.error) : "点赞失败", "error")
            }
        }).catch(function(error) {
            Toast.show(panel.messageOf(error), "error")
        })
    }

    function isLiked(comment) {
        if (!comment || !comment.id)
            return false
        const override = panel.likeOverrides[String(comment.id)]
        return override !== undefined ? override.liked === true : comment.liked === true
    }

    function likeCountOf(comment) {
        if (!comment || !comment.id)
            return 0
        const override = panel.likeOverrides[String(comment.id)]
        if (override !== undefined)
            return Number(override.likedCount || 0)
        return comment.likedCount ? Number(comment.likedCount) : 0
    }

    // ---------------------------------------------------------------- 楼中楼

    function isRootExpanded(commentId) {
        if (!commentId)
            return false
        return panel.expandedRoots[String(commentId)] === true
    }

    function replyStateOf(commentId) {
        if (!commentId)
            return null
        const state = panel.replyState[String(commentId)]
        return state !== undefined ? state : null
    }

    function replyCommentsFor(commentId) {
        const state = panel.replyStateOf(commentId)
        return state && state.comments ? state.comments : []
    }

    function replyLoadingFor(commentId) {
        const state = panel.replyStateOf(commentId)
        return state ? state.loading === true : false
    }

    function replyErrorFor(commentId) {
        const state = panel.replyStateOf(commentId)
        return state && state.error ? String(state.error) : ""
    }

    function replyHasMoreFor(commentId) {
        const state = panel.replyStateOf(commentId)
        return state ? state.hasMore === true : false
    }

    function _setReplyState(commentId, state) {
        const map = panel.copyMap(panel.replyState)
        map[String(commentId)] = state
        panel.replyState = map
    }

    function toggleReplies(comment) {
        if (!comment || !comment.id)
            return
        const id = String(comment.id)
        const expanded = panel.copyMap(panel.expandedRoots)
        const next = expanded[id] !== true
        if (next)
            expanded[id] = true
        else
            delete expanded[id]
        panel.expandedRoots = expanded
        if (next && panel.replyState[id] === undefined)
            panel.loadReplies(id, 1, "")
    }

    function loadReplies(commentId, targetPage, cursor) {
        const id = String(commentId)
        const previous = panel.replyStateOf(id)
        if (previous && previous.loading === true)
            return
        const base = previous || { "comments": [], "hasMore": false, "cursor": "", "page": 0 }
        panel._setReplyState(id, {
                                 "comments": base.comments ? base.comments : [],
                                 "hasMore": base.hasMore === true,
                                 "cursor": base.cursor ? String(base.cursor) : "",
                                 "page": base.page ? Number(base.page) : 0,
                                 "loading": true,
                                 "error": ""
                             })
        const token = panel._requestToken
        const params = { song: panel._target, commentId: id, page: targetPage }
        if (targetPage > 1 && cursor !== "")
            params.cursor = cursor
        Api.call("comments.replies", params).then(function(result) {
            if (token !== panel._requestToken)
                return
            const current = panel.replyStateOf(id) || { "comments": [] }
            const list = panel.toArray(result && result.comments ? result.comments : [])
            const merged = targetPage <= 1 ? list
                                           : (current.comments ? current.comments : []).concat(list)
            panel._setReplyState(id, {
                                     "comments": merged,
                                     "hasMore": result && result.hasMore === true,
                                     "cursor": result && result.cursor ? String(result.cursor) : "",
                                     "page": targetPage,
                                     "loading": false,
                                     "error": result && result.error && list.length === 0
                                              ? String(result.error) : ""
                                 })
        }).catch(function(error) {
            if (token !== panel._requestToken)
                return
            const current = panel.replyStateOf(id) || { "comments": [] }
            panel._setReplyState(id, {
                                     "comments": current.comments ? current.comments : [],
                                     "hasMore": false,
                                     "cursor": "",
                                     "page": current.page ? Number(current.page) : 0,
                                     "loading": false,
                                     "error": panel.messageOf(error)
                                 })
        })
    }

    function loadMoreReplies(comment) {
        if (!comment || !comment.id)
            return
        const id = String(comment.id)
        const state = panel.replyStateOf(id)
        if (!state || state.hasMore !== true || state.loading === true)
            return
        panel.loadReplies(id, Number(state.page || 0) + 1, state.cursor ? String(state.cursor) : "")
    }

    // ---------------------------------------------------------------- 发表评论

    function submitComment(text) {
        const content = String(text === undefined || text === null ? "" : text).trim()
        if (content === "" || panel.posting || !panel._target || !panel.supported)
            return
        if (!panel.loggedIn) {
            panel.promptLogin()
            return
        }
        panel.posting = true
        Api.call("comments.post", { song: panel._target, content: content }).then(function(result) {
            panel.posting = false
            if (result && result.ok === true) {
                Toast.show("评论已发布", "info")
                panel.composerResetToken += 1
                panel.reload()
            } else if (result && result.requiresLogin === true) {
                panel.loadAccounts()
                panel.promptLogin()
                Toast.show(result.error ? String(result.error) : "登录态已失效，请重新登录", "error")
            } else {
                Toast.show(result && result.error ? String(result.error) : "评论发布失败", "error")
            }
        }).catch(function(error) {
            panel.posting = false
            Toast.show(panel.messageOf(error), "error")
        })
    }

    function totalText() {
        if (panel.total >= 0)
            return panel.loadedCount + " / " + panel.total
        return panel.loadedCount > 0 ? String(panel.loadedCount) : ""
    }

    // ---------------------------------------------------------------- 界面

    content: Item {
        id: body

        anchors.fill: parent

        // 歌曲信息条
        Item {
            id: songBar

            anchors.left: parent.left
            anchors.right: parent.right
            anchors.top: parent.top
            height: panel.hasSong ? 58 : 0
            visible: panel.hasSong

            Row {
                anchors.left: parent.left
                anchors.right: parent.right
                anchors.leftMargin: 14
                anchors.rightMargin: 14
                anchors.verticalCenter: parent.verticalCenter
                spacing: 10

                CoverArt {
                    width: 40
                    height: 40
                    radius: Theme.radiusMd
                    path: Player.song && Player.song.artworkPath ? String(Player.song.artworkPath) : ""
                    url: Player.song && Player.song.artworkUrl ? String(Player.song.artworkUrl) : ""
                    placeholderIconSize: 18
                }

                Column {
                    width: parent.width - 40 - 10 - sourceLabel.implicitWidth - 20
                    spacing: 2
                    anchors.verticalCenter: parent.verticalCenter

                    Text {
                        width: parent.width
                        text: Player.song && Player.song.title ? String(Player.song.title) : ""
                        elide: Text.ElideRight
                        maximumLineCount: 1
                        color: Theme.onSurfaceColor
                        font.family: Theme.fontFamily
                        font.pixelSize: Theme.textStyle("titleSmall").size
                        font.weight: Font.DemiBold
                    }

                    Text {
                        width: parent.width
                        text: Player.song && Player.song.artist ? String(Player.song.artist) : ""
                        elide: Text.ElideRight
                        maximumLineCount: 1
                        color: Theme.withAlpha(Theme.onSurfaceVariantColor, 0.8)
                        font.family: Theme.fontFamily
                        font.pixelSize: Theme.textStyle("bodySmall").size
                    }
                }

                Text {
                    id: sourceLabel

                    text: panel.platformLabel
                    color: Theme.primary
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.textStyle("labelSmall").size
                    anchors.verticalCenter: parent.verticalCenter
                }
            }

            Rectangle {
                anchors.left: parent.left
                anchors.right: parent.right
                anchors.bottom: parent.bottom
                height: 1
                color: Theme.withAlpha(Theme.outlineVariant, 0.4)
            }
        }

        // 排序 / 计数
        Item {
            id: sortBar

            anchors.left: parent.left
            anchors.right: parent.right
            anchors.top: songBar.bottom
            height: panel.supported ? 46 : 0
            visible: panel.supported

            Row {
                anchors.left: parent.left
                anchors.leftMargin: 14
                anchors.verticalCenter: parent.verticalCenter
                spacing: 8

                PanelButton {
                    text: "热门"
                    chip: true
                    selected: panel.sort === "hot"
                    onClicked: panel.setSort("hot")
                }

                PanelButton {
                    text: "最新"
                    chip: true
                    selected: panel.sort === "new"
                    onClicked: panel.setSort("new")
                }
            }

            Text {
                anchors.right: parent.right
                anchors.rightMargin: 16
                anchors.verticalCenter: parent.verticalCenter
                text: panel.totalText()
                color: Theme.withAlpha(Theme.onSurfaceVariantColor, 0.8)
                font.family: Theme.fontFamily
                font.pixelSize: Theme.textStyle("labelMedium").size
            }
        }

        // 评论列表区域
        Item {
            id: listArea

            anchors.left: parent.left
            anchors.right: parent.right
            anchors.top: sortBar.bottom
            anchors.bottom: panel.supported ? composer.top : parent.bottom
            clip: true

            ListView {
                id: listView

                anchors.fill: parent
                clip: true
                model: panel.comments
                boundsBehavior: Flickable.StopAtBounds
                cacheBuffer: 800
                visible: panel.comments.length > 0
                ScrollBar.vertical: ScrollBar {
                    policy: ScrollBar.AsNeeded
                }

                onAtYEndChanged: {
                    if (atYEnd && panel.hasMore && !panel.loadingMore
                            && contentHeight > height)
                        panel.loadMore()
                }

                delegate: CommentRow {
                    required property var modelData

                    width: listView.width
                    comment: modelData
                    liked: modelData && modelData.liked === true
                    likedCount: modelData && modelData.likedCount ? Number(modelData.likedCount) : 0
                    likeOverrides: panel.likeOverrides
                    interactive: true
                    showReplies: true
                    expanded: panel.isRootExpanded(modelData ? modelData.id : "")
                    loadedReplies: panel.replyCommentsFor(modelData ? modelData.id : "")
                    repliesLoading: panel.replyLoadingFor(modelData ? modelData.id : "")
                    repliesError: panel.replyErrorFor(modelData ? modelData.id : "")
                    repliesHasMore: panel.replyHasMoreFor(modelData ? modelData.id : "")
                    onLikeClicked: panel.toggleLike(modelData)
                    onRepliesToggleClicked: panel.toggleReplies(modelData)
                    onLoadMoreRepliesClicked: panel.loadMoreReplies(modelData)
                    onReplyLikeClicked: function(reply) {
                        panel.toggleLike(reply)
                    }
                }

                footer: Item {
                    width: listView.width
                    height: footerColumn.implicitHeight + (panel.comments.length > 0 ? 14 : 0)

                    Column {
                        id: footerColumn

                        width: parent.width
                        spacing: 6

                        // 加载更多中
                        Item {
                            visible: panel.loadingMore
                            width: parent.width
                            height: visible ? 34 : 0

                            Row {
                                anchors.centerIn: parent
                                spacing: 6

                                NeriIcon {
                                    name: "progress_activity"
                                    iconSize: 18
                                    color: Theme.onSurfaceVariantColor

                                    RotationAnimation on rotation {
                                        running: panel.loadingMore
                                        loops: Animation.Infinite
                                        from: 0
                                        to: 360
                                        duration: 900
                                    }
                                }

                                Text {
                                    text: "正在加载…"
                                    color: Theme.onSurfaceVariantColor
                                    font.family: Theme.fontFamily
                                    font.pixelSize: Theme.textStyle("labelMedium").size
                                    anchors.verticalCenter: parent.verticalCenter
                                }
                            }
                        }

                        // 手动加载更多
                        Item {
                            visible: panel.hasMore && !panel.loadingMore
                            width: parent.width
                            height: visible ? 38 : 0

                            PanelButton {
                                anchors.centerIn: parent
                                text: "加载更多评论"
                                onClicked: panel.loadMore()
                            }
                        }

                        // 匿名预览受限：总数远大于已加载且没有更多时给出登录提示
                        Text {
                            visible: !panel.hasMore && panel.total > panel.loadedCount
                            width: parent.width
                            horizontalAlignment: Text.AlignHCenter
                            text: panel.loggedIn
                                  ? "登录后才能继续加载更多评论"
                                  : ("仅显示部分评论，登录" + panel.platformLabel + "后可查看更多")
                            wrapMode: Text.Wrap
                            color: Theme.withAlpha(Theme.onSurfaceVariantColor, 0.75)
                            font.family: Theme.fontFamily
                            font.pixelSize: Theme.textStyle("labelSmall").size
                        }

                        // 没有更多
                        Text {
                            visible: !panel.hasMore && panel.total <= panel.loadedCount
                                     && panel.comments.length > 0
                            width: parent.width
                            horizontalAlignment: Text.AlignHCenter
                            text: panel.total >= 0
                                  ? "已显示全部 " + panel.total + " 条评论"
                                  : "没有更多评论了"
                            color: Theme.withAlpha(Theme.onSurfaceVariantColor, 0.6)
                            font.family: Theme.fontFamily
                            font.pixelSize: Theme.textStyle("labelSmall").size
                        }
                    }
                }
            }

            // 首次加载
            LoadingState {
                anchors.centerIn: parent
                visible: panel.loading && panel.comments.length === 0
                text: "正在加载评论…"
            }

            // 不支持评论 / 空数据
            EmptyState {
                anchors.fill: parent
                visible: !panel.loading && panel.comments.length === 0
                         && (panel.loadError === "" || panel.errorCode === "unsupported"
                             || panel.errorCode === "not_found" || panel.errorCode === "closed")
                icon: panel.supported ? "comment" : "comments_disabled"
                title: !panel.hasSong
                       ? "还没有播放歌曲"
                       : (!panel.supported
                          ? "该歌曲暂不支持评论"
                          : (panel.errorCode === "not_found" || panel.errorCode === "closed"
                             ? "评论区不可用"
                             : "暂无评论"))
                hint: !panel.hasSong
                      ? "从媒体库或探索里播放一首歌后再来查看评论"
                      : (!panel.supported
                         ? panel.unsupportedHint()
                         : (panel.loadError !== "" ? panel.loadError : "来发表第一条评论吧"))
            }

            // 加载失败（网络 / 权限等）
            ErrorCard {
                anchors.left: parent.left
                anchors.right: parent.right
                anchors.top: parent.top
                anchors.topMargin: 12
                visible: !panel.loading && panel.comments.length === 0 && panel.loadError !== ""
                         && panel.errorCode !== "unsupported" && panel.errorCode !== "not_found"
                         && panel.errorCode !== "closed"
                message: panel.loadError
                retryable: true
                onRetryClicked: panel.retry()
            }
        }

        // 输入框
        CommentComposer {
            id: composer

            visible: panel.supported
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.bottom: parent.bottom
            loggedIn: panel.loggedIn
            sourceLabel: panel.platformLabel
            maxLength: panel.lengthLimit
            posting: panel.posting
            available: panel.supported
            resetToken: panel.composerResetToken
            onSendRequested: function(text) {
                panel.submitComment(text)
            }
            onLoginRequested: panel.openLogin()
        }
    }

    // 标题栏右侧操作
    IconButton {
        icon: "refresh"
        iconSize: 22
        size: 40
        tooltip: "刷新评论"
        onClicked: panel.retry()
    }
}
