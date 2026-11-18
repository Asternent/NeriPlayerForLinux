import QtQuick
import QtQuick.Effects
import "ArtworkCache.js" as ArtworkCache

// 封面图：优先本地文件路径 / 后端缓存路径，其次远程 URL（经后端 artwork.resolve 下载缓存），
// 都没有时显示占位图标。
// 圆角用 MultiEffect 遮罩实现（Qt Quick 的 Item.clip 只能裁矩形，会盖住圆角）。
Item {
    id: cover

    property string path: ""
    property string url: ""
    property int radius: 10
    property real placeholderIconSize: 22

    // 远程 URL 解析后的本地缓存路径
    property string resolvedPath: ""

    readonly property string effectivePath: {
        if (path !== "" && path !== undefined)
            return path
        return resolvedPath
    }
    readonly property url source: effectivePath !== "" ? ("file://" + effectivePath) : ""
    readonly property bool hasImage: image.status === Image.Ready

    function resolveRemote() {
        if ((path !== "" && path !== undefined) || url === "" || url === undefined) {
            resolvedPath = ""
            return
        }
        if (ArtworkCache.resolved[url] !== undefined) {
            resolvedPath = ArtworkCache.resolved[url]
            return
        }
        if (ArtworkCache.pending[url] === true)
            return
        ArtworkCache.pending[url] = true
        const requestedUrl = url
        Api.get("artwork.resolve", { "url": requestedUrl }, function(result, error) {
            delete ArtworkCache.pending[requestedUrl]
            if (!error && result && result.path) {
                ArtworkCache.resolved[requestedUrl] = result.path
                if (cover.url === requestedUrl)
                    cover.resolvedPath = result.path
            }
        })
    }

    onUrlChanged: resolveRemote()
    onPathChanged: resolveRemote()
    Component.onCompleted: resolveRemote()

    Rectangle {
        id: background
        anchors.fill: parent
        radius: cover.radius
        color: Theme.surfaceContainerHighest
        clip: true

        // 圆角遮罩（只在有图时参与渲染）
        Rectangle {
            id: mask
            anchors.fill: parent
            radius: cover.radius
            visible: false
            layer.enabled: true
        }

        Image {
            id: image
            anchors.fill: parent
            source: cover.source
            fillMode: Image.PreserveAspectCrop
            asynchronous: true
            cache: true
            visible: false
        }

        MultiEffect {
            anchors.fill: parent
            source: image
            visible: cover.hasImage
            maskEnabled: true
            maskSource: mask
        }

        NeriIcon {
            anchors.centerIn: parent
            visible: !cover.hasImage
            name: "music_note"
            iconSize: cover.placeholderIconSize
            color: Theme.onSurfaceVariantColor
        }
    }
}
