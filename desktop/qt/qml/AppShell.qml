import QtQuick
import QtQuick.Layouts
import "components"

// 主界面骨架：内容区 + 底部迷你播放条 + 底部导航栏（与 Compose 版一致，没有侧边栏）。
Item {
    id: shell

    readonly property real contentMaxWidth: 1180
    readonly property real wideBreakpoint: 900
    readonly property bool wide: width >= wideBreakpoint

    // 底部栏：迷你播放条(≈70) + 导航栏(80) + 外边距
    readonly property real bottomInset: playerBar.height + navBar.height + Theme.spacingSm

    // 已访问过的页面缓存：key = 路由完整名（tab / 详情页 arg 不同则各自一份）。
    // Loader 一旦加载就不再销毁，切走时只是 visible=false，回来时保留滚动位置与内部状态，
    // 也不会重新触发 Component.onCompleted 里的网络请求。
    property var pageCache: ({})
    readonly property string currentKey: routeKey(Nav.current)

    function routeKey(route) {
        if (!route)
            return ""
        const arg = route.arg === undefined || route.arg === null
                    ? "" : (typeof route.arg === "object" ? JSON.stringify(route.arg) : String(route.arg))
        return (route.kind || "page") + ":" + route.name + ":" + arg
    }

    /** 取得（必要时创建）某路由对应的 Loader。 */
    function loaderFor(key, route) {
        if (shell.pageCache[key])
            return shell.pageCache[key]
        const component = Qt.createComponent(Qt.resolvedUrl("PageHost.qml"))
        const host = component.createObject(pageHostContainer, {
                                                "cacheKey": key,
                                                "pageSource": shell.sourceForRoute(route)
                                            })
        const cache = shell.pageCache
        cache[key] = host
        shell.pageCache = cache
        return host
    }

    Item {
        id: contentArea
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.top: parent.top
        anchors.bottom: bottomBar.top
        clip: true

        Item {
            id: pageHostContainer
            anchors.fill: parent
        }

        // 当前页面切换时：先让新页显示，再淡入 + 轻微上浮。
        property Item currentHost: null
        function showCurrentPage() {
            const route = Nav.current
            if (!route)
                return
            const host = shell.loaderFor(shell.currentKey, route)
            if (currentHost && currentHost !== host)
                currentHost.visible = false
            host.visible = true
            currentHost = host
            pageEnter.restart()
        }

        Connections {
            target: shell
            function onCurrentKeyChanged() { contentArea.showCurrentPage() }
        }

        Component.onCompleted: showCurrentPage()

        SequentialAnimation {
            id: pageEnter
            ParallelAnimation {
                NumberAnimation {
                    target: pageHostContainer
                    property: "opacity"
                    from: 0.6
                    to: 1
                    duration: Theme.motionMedium
                    easing.type: Easing.OutCubic
                }
                NumberAnimation {
                    target: pageHostContainer
                    property: "y"
                    from: 6
                    to: 0
                    duration: Theme.motionMedium
                    easing.type: Easing.OutCubic
                }
            }
        }
    }

    // 播放页/迷你播放条触发的浮层面板（队列、音量、睡眠定时器、音效、下载管理…）
    PlayerOverlays {
        anchors.fill: parent
    }

    Item {
        id: bottomBar
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.bottom: parent.bottom
        height: playerBar.height + navBar.height

        MiniPlayerBar {
            id: playerBar
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.top: parent.top
        }

        NavigationBar {
            id: navBar
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.bottom: parent.bottom
        }
    }

    /** 路由 → QML 文件。二级页面都从 pages/ 下加载。 */
    function sourceForRoute(route) {
        if (!route)
            return ""
        switch (route.name) {
        case "home":
            return "pages/HomePage.qml"
        case "explore":
            return "pages/ExplorePage.qml"
        case "library":
            return "pages/LibraryPage.qml"
        case "settings":
            return "pages/SettingsPage.qml"
        case "nowplaying":
            return "pages/NowPlayingPage.qml"
        case "recent":
            return "pages/RecentPage.qml"
        case "stats":
            return "pages/StatsPage.qml"
        case "downloads":
            return "pages/DownloadsPage.qml"
        case "localPlaylist":
            return "pages/LocalPlaylistPage.qml"
        case "localArtist":
            return "pages/LocalArtistPage.qml"
        case "onlineCollection":
            return "pages/OnlineCollectionPage.qml"
        case "remoteArtist":
            return "pages/RemoteArtistPage.qml"
        default:
            return "pages/HomePage.qml"
        }
    }
}
