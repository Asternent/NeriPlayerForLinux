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

    Item {
        id: contentArea
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.top: parent.top
        anchors.bottom: bottomBar.top
        clip: true

        Loader {
            id: pageLoader
            anchors.fill: parent
            asynchronous: false
            source: shell.sourceForRoute(Nav.current)

            // 页面切换：淡入 + 轻微上浮
            opacity: 0
            transform: Translate { id: pageShift; y: 10 }

            onSourceChanged: pageEnter.restart()
            onLoaded: pageEnter.restart()

            SequentialAnimation {
                id: pageEnter
                ParallelAnimation {
                    NumberAnimation {
                        target: pageLoader
                        property: "opacity"
                        to: 1
                        duration: Theme.motionMedium
                        easing.type: Easing.OutCubic
                    }
                    NumberAnimation {
                        target: pageShift
                        property: "y"
                        to: 0
                        duration: Theme.motionMedium
                        easing.type: Easing.OutCubic
                    }
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
