import QtQuick
// 根目录 components 与本目录存在同名组件（CollectionCard/EmptyState/ErrorCard/LoadingState），
// 用限定导入避免覆盖同目录组件。
import ".." as Root

// 媒体库「在线音源」Tab：网易云「我的歌单」/ 哔哩哔哩「收藏夹」。
//
// 数据来源（协议见 desktop/docs/protocol-online.md 与 protocol-login.md）：
//   login.accounts                → 判断当前音源是否已登录
//   online.myPlaylists {source}   → 网易云账号歌单（needLogin=true 表示需要重新登录）
//   online.biliFavorites          → 哔哩哔哩账号收藏夹（同样带 needLogin）
// 登录状态变化通过 accounts.changed 事件刷新（事件数据在 Qt 桥里可能拿不到数组内容，拿不到时重新查询）。
Item {
    id: root

    // ---- 音源 ----
    readonly property var sources: {
        const list = []
        if (Settings.get("neteaseEnabled", true) !== false)
            list.push({ "value": "NETEASE", "label": "网易云" })
        if (Settings.get("bilibiliEnabled", true) !== false)
            list.push({ "value": "BILIBILI", "label": "哔哩哔哩" })
        return list
    }
    readonly property bool sourcesEmpty: root.sources.length === 0
    property string source: "NETEASE"

    // ---- 账号 ----
    property var accounts: []
    property bool accountsLoaded: false

    // ---- 歌单 / 收藏夹 ----
    property var collections: []
    property bool loading: false
    property string errorMessage: ""
    property bool needLogin: false
    property int _seq: 0

    readonly property var currentAccount: {
        for (let i = 0; i < root.accounts.length; ++i) {
            const account = root.accounts[i]
            if (account && String(account.source) === root.source)
                return account
        }
        return null
    }
    readonly property bool loggedIn: root.currentAccount !== null
    readonly property string accountName: {
        const account = root.currentAccount
        if (!account)
            return ""
        if (account.nickname && String(account.nickname) !== "")
            return String(account.nickname)
        if (account.userId && String(account.userId) !== "")
            return "UID " + String(account.userId)
        return "已登录"
    }

    // ---------------------------------------------------------------- 工具函数

    /** C++ 桥下发的数组是 QVariantList 包装对象（Array.isArray 为 false），统一转成 JS 数组。 */
    function toArray(value) {
        const list = []
        if (!value)
            return list
        for (let i = 0; i < value.length; ++i)
            list.push(value[i])
        return list
    }

    function ensureValidSource() {
        for (let i = 0; i < root.sources.length; ++i) {
            if (root.sources[i].value === root.source)
                return
        }
        if (root.sources.length > 0)
            root.source = root.sources[0].value
    }

    function normalizeCollection(item, fallbackSource) {
        if (!item)
            return null
        const id = item.id === undefined || item.id === null ? "" : String(item.id)
        if (id === "")
            return null
        return {
            "id": id,
            "name": item.name ? String(item.name) : "未命名歌单",
            "creator": item.creator ? String(item.creator) : "",
            "coverUrl": item.coverUrl ? String(item.coverUrl) : "",
            "trackCount": Number(item.trackCount) || 0,
            "description": item.description ? String(item.description) : "",
            "source": item.source ? String(item.source) : fallbackSource
        }
    }

    function subtitleFor(collection) {
        if (!collection)
            return ""
        const parts = []
        if (collection.creator)
            parts.push(collection.creator)
        if (collection.trackCount > 0)
            parts.push(collection.trackCount + " 首")
        return parts.join(" · ")
    }

    function openCollection(collection) {
        if (!collection)
            return
        // 详情页用 collection.source / id 调 online.collectionDetail。
        Nav.push("onlineCollection", collection)
    }

    // ---------------------------------------------------------------- 数据加载

    function loadAccounts() {
        Api.get("login.accounts", {}, function(result, error) {
            if (error) {
                // 后端尚未连接时保持未加载状态，等 Backend.connected 后重试。
                if (error.code === "disconnected")
                    return
                root.accountsLoaded = true
                return
            }
            root.accounts = root.toArray(result && result.accounts)
            root.accountsLoaded = true
            root.afterAccountsChanged()
        })
    }

    function reloadCollections() {
        if (!root.loggedIn || root.source === "")
            return
        const targetSource = root.source
        const seq = ++root._seq
        root.loading = true
        root.errorMessage = ""
        root.needLogin = false

        function apply(result, error) {
            if (seq !== root._seq)
                return
            root.loading = false
            if (error) {
                root.collections = []
                root.errorMessage = error.message || "加载失败"
                return
            }
            const list = root.toArray(result && result.collections)
            const items = []
            for (let i = 0; i < list.length; ++i) {
                const collection = root.normalizeCollection(list[i], targetSource)
                if (collection)
                    items.push(collection)
            }
            root.collections = items
            root.needLogin = !!(result && result.needLogin)
            root.errorMessage = ""
        }

        if (targetSource === "BILIBILI")
            Api.get("online.biliFavorites", {}, apply)
        else
            Api.get("online.myPlaylists", { source: "NETEASE" }, apply)
    }

    /** 登录状态变化后：已登录则拉歌单，未登录则清空展示登录引导。 */
    function afterAccountsChanged() {
        if (root.loggedIn) {
            root.reloadCollections()
            return
        }
        ++root._seq
        root.collections = []
        root.loading = false
        root.errorMessage = ""
        root.needLogin = false
    }

    function refresh() {
        if (!root.accountsLoaded || !root.loggedIn) {
            root.loadAccounts()
            return
        }
        // 先刷新账号信息（昵称可能变化），loadAccounts 成功后会再拉一次歌单。
        root.loadAccounts()
    }

    function onAccountsChanged(data) {
        const list = root.toArray(data)
        if (list.length > 0) {
            root.accounts = list
            root.accountsLoaded = true
            root.afterAccountsChanged()
            return
        }
        // 数组事件在 Qt 桥里通常只剩空对象，拿不到内容时重新查询账号列表。
        root.loadAccounts()
    }

    function openLogin() {
        Nav.openLoginPanel(root.source)
    }

    // ---------------------------------------------------------------- 生命周期

    onSourcesChanged: root.ensureValidSource()

    onSourceChanged: {
        ++root._seq
        root.collections = []
        root.loading = false
        root.errorMessage = ""
        root.needLogin = false
        if (root.accountsLoaded && root.loggedIn)
            root.reloadCollections()
    }

    Component.onCompleted: {
        Api.on("accounts.changed", root.onAccountsChanged)
        root.ensureValidSource()
        if (Api.connected)
            root.loadAccounts()
    }

    Component.onDestruction: {
        Api.off("accounts.changed", root.onAccountsChanged)
    }

    // 冷启动时页面可能先于后端连接创建，连接建立后再读账号。
    property Connections backendConnection: Connections {
        target: Backend

        function onConnectedChanged() {
            if (Backend.connected && !root.accountsLoaded)
                root.loadAccounts()
        }
    }

    // ---------------------------------------------------------------- 顶部：音源切换 + 账号状态

    Item {
        id: sourceBar

        anchors.top: parent.top
        anchors.left: parent.left
        anchors.right: parent.right
        height: 44

        TabBar {
            id: sourceTabs

            anchors.left: parent.left
            anchors.verticalCenter: parent.verticalCenter
            visible: !root.sourcesEmpty
            options: root.sources
            value: root.source
            onSelected: function(value) { root.source = value }
        }

        Row {
            anchors.right: parent.right
            anchors.rightMargin: Theme.spacingLg
            anchors.verticalCenter: parent.verticalCenter
            spacing: Theme.spacingSm
            visible: root.accountsLoaded && root.loggedIn

            Root.NeriIcon {
                anchors.verticalCenter: parent.verticalCenter
                name: "account_circle"
                iconSize: 18
                color: Theme.onSurfaceVariantColor
            }

            Text {
                anchors.verticalCenter: parent.verticalCenter
                width: Math.min(implicitWidth, 180)
                text: root.accountName
                elide: Text.ElideRight
                maximumLineCount: 1
                color: Theme.onSurfaceVariantColor
                font.family: Theme.fontFamily
                font.pixelSize: Theme.textStyle("labelMedium").size
            }

            Root.IconButton {
                anchors.verticalCenter: parent.verticalCenter
                icon: "refresh"
                iconSize: 20
                size: 36
                tooltip: "刷新"
                onClicked: root.refresh()
            }
        }
    }

    // ---------------------------------------------------------------- 内容

    Item {
        id: contentArea

        anchors.top: sourceBar.bottom
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.bottom: parent.bottom
        clip: true

        // 两个音源都在设置里被禁用
        EmptyState {
            anchors.fill: parent
            visible: root.sourcesEmpty
            icon: "cloud_off"
            title: "在线音源已禁用"
            hint: "在「设置 - 在线音源」中启用网易云 / 哔哩哔哩后，可以在媒体库这里浏览账号歌单"
        }

        LoadingState {
            anchors.top: parent.top
            anchors.left: parent.left
            anchors.right: parent.right
            visible: !root.sourcesEmpty && !root.accountsLoaded
            text: "正在读取账号状态…"
        }

        // 未登录：登录引导卡
        SourceLoginPrompt {
            anchors.fill: parent
            visible: !root.sourcesEmpty && root.accountsLoaded && !root.loggedIn
            source: root.source
            onLoginClicked: root.openLogin()
        }

        // 已登录内容
        Item {
            id: loggedInContent

            anchors.fill: parent
            visible: !root.sourcesEmpty && root.accountsLoaded && root.loggedIn

            LoadingState {
                anchors.top: parent.top
                anchors.left: parent.left
                anchors.right: parent.right
                visible: root.loading && root.collections.length === 0
                        && root.errorMessage === "" && !root.needLogin
                text: "正在加载" + (root.source === "BILIBILI" ? "收藏夹…" : "我的歌单…")
            }

            ErrorCard {
                anchors.top: parent.top
                anchors.left: parent.left
                anchors.right: parent.right
                visible: root.errorMessage !== ""
                message: "加载失败：" + root.errorMessage
                onRetryClicked: root.reloadCollections()
            }

            // 接口返回 needLogin（Cookie 失效 / 账号信息缺失）：提示重新登录
            SourceLoginPrompt {
                anchors.fill: parent
                visible: root.needLogin && !root.loading
                source: root.source
                relogin: true
                onLoginClicked: root.openLogin()
            }

            EmptyState {
                anchors.fill: parent
                visible: !root.loading && !root.needLogin && root.errorMessage === ""
                        && root.collections.length === 0
                icon: root.source === "BILIBILI" ? "bookmark_border" : "queue_music"
                title: root.source === "BILIBILI" ? "还没有收藏夹" : "还没有歌单"
                hint: "当前账号下暂无可浏览的内容，点击右上角刷新重试"
            }

            Flickable {
                id: collectionsPane

                anchors.fill: parent
                visible: !root.needLogin && root.errorMessage === "" && root.collections.length > 0
                clip: true
                contentWidth: width
                contentHeight: collectionsFlow.height + Theme.spacingXl
                boundsBehavior: Flickable.StopAtBounds

                Flow {
                    id: collectionsFlow

                    x: Theme.spacingLg
                    y: Theme.spacingSm
                    width: collectionsPane.width - Theme.spacingLg * 2
                    spacing: Theme.spacingMd

                    // 响应式列数：按可用宽度约 170 一格；卡片宽度随列数伸缩并限制在 190 以内。
                    readonly property int columns: Math.max(1, Math.floor((width + spacing) / 170))
                    readonly property real cardWidth: Math.min(190,
                                                             (width - spacing * (columns - 1)) / columns)

                    Repeater {
                        model: root.collections

                        delegate: CollectionCard {
                            id: collectionCard

                            property var item: modelData

                            cardWidth: collectionsFlow.cardWidth
                            name: item ? item.name : ""
                            subtitle: root.subtitleFor(item)
                            coverUrl: item ? item.coverUrl : ""
                            placeholderIcon: item && item.source === "BILIBILI"
                                             ? "bookmark_border" : "music_note"
                            onClicked: root.openCollection(item)
                        }
                    }
                }
            }
        }
    }
}
