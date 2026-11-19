import QtQuick
import QtQuick.Layouts
import "components"

// 主界面骨架：左侧导航栏 + 内容区 + 底部迷你播放条。
//
// 导航原来学手机版放在底部居中，桌面窗口里显得像移动端 App；改为左侧竖向侧边栏，
// 窗口收窄时自动收起为纯图标。
Item {
    id: shell

    readonly property real contentMaxWidth: 1180
    readonly property real wideBreakpoint: 900
    readonly property bool wide: width >= wideBreakpoint

    // 侧边栏窄于该宽度时收起为纯图标
    readonly property real navWidth: navBar.width

    // 底部只留迷你播放条
    readonly property real bottomInset: playerBar.height + Theme.spacingSm

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

    /** 当前缓存了多少个页面。 */
    readonly property int cachedPageCount: Object.keys(shell.pageCache).length

    /**
     * 释放所有已缓存的页面。
     *
     * 页面缓存是为了切换标签时保留滚动位置与内部状态，但每个页面都持有图片纹理、
     * 列表模型和 delegate 实例，长期累积占用可观。窗口收进托盘（长时间不显示）时
     * 整个释放掉，重新显示时再按需重建 —— 恢复成本只有一次页面构建。
     */
    function releasePages() {
        const cache = shell.pageCache
        for (const key in cache) {
            const host = cache[key]
            if (host) {
                host.visible = false
                host.destroy()
            }
        }
        shell.pageCache = ({})
        contentArea.currentHost = null
        // 触发一次垃圾回收，让刚销毁的 QML 对象尽快归还内存
        gc()
    }

    /** 重新构建当前页面（释放后调用）。 */
    function rebuildCurrentPage() {
        if (Object.keys(shell.pageCache).length === 0)
            contentArea.showCurrentPage()
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

    // 播放页沉浸模式：侧边栏隐藏，内容占满宽度
    readonly property bool immersiveChromeHidden: shell.onNowPlaying && Nav.immersive

    NavigationBar {
        id: navBar
        anchors.left: parent.left
        anchors.top: parent.top
        anchors.bottom: parent.bottom
        // 沉浸时完全隐藏
        opacity: shell.immersiveChromeHidden ? 0 : 1
        x: shell.immersiveChromeHidden ? -width : 0
        visible: opacity > 0.01

        Behavior on opacity {
            NumberAnimation { duration: 220; easing.type: Easing.OutCubic }
        }
        Behavior on x {
            NumberAnimation { duration: 220; easing.type: Easing.OutCubic }
        }
    }

    Item {
        id: contentArea
        anchors.left: shell.immersiveChromeHidden ? parent.left : navBar.right
        anchors.right: parent.right
        anchors.top: parent.top
        // 播放页自带完整控制区，不需要底部迷你条，内容延伸到底部
        anchors.bottom: shell.onNowPlaying ? parent.bottom : bottomBar.top
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

    /** 当前是否在播放页。 */
    readonly property bool onNowPlaying: Nav.currentName === "nowplaying"

    // 离开播放页时自动退出沉浸模式，避免其它页面也处于隐藏 chrome 状态
    onOnNowPlayingChanged: function(now) {
        if (!now && Nav.immersive) {
            Nav.setImmersive(false)
            const w = shell.Window.window
            if (w)
                w.showNormal()
        }
    }

    Item {
        id: bottomBar
        visible: !shell.onNowPlaying
        anchors.left: navBar.right
        anchors.right: parent.right
        anchors.bottom: parent.bottom
        height: playerBar.height

        MiniPlayerBar {
            id: playerBar
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.top: parent.top
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
        case "albumDetail":
            return "pages/AlbumDetailPage.qml"
        default:
            return "pages/HomePage.qml"
        }
    }
}
