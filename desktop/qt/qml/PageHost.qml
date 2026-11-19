import QtQuick

/**
 * 页面宿主：一个常驻的 Loader。AppShell 为每个访问过的路由创建一个 PageHost，
 * 实例不会被销毁，从而保留页面内的滚动位置、搜索词等状态，也避免每次进入都重新请求网络。
 */
Item {
    id: host

    property string cacheKey: ""
    property string pageSource: ""

    anchors.fill: parent
    visible: false

    Loader {
        id: pageLoader
        anchors.fill: parent
        asynchronous: false
        source: Qt.resolvedUrl(host.pageSource)

        opacity: 0
        transform: Translate { id: pageShift; y: 10 }

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

    onVisibleChanged: {
        if (visible && pageLoader.item)
            pageEnter.restart()
    }
}
