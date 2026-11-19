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

    // 圆角遮罩用内联 SVG 生成：MultiEffect 默认把遮罩 alpha 当二值处理，Rectangle 的圆角在
    // 离屏纹理里也没有抗锯齿，42px 迷你条上圆角几乎看不出、大封面上是明显台阶。
    // QSvgRenderer 光栅化自带抗锯齿，配合下面 MultiEffect 的软阈值就能得到平滑边缘。
    readonly property int maskWidth: Math.max(1, Math.round(width))
    readonly property int maskHeight: Math.max(1, Math.round(height))
    readonly property real maskRadius: Math.max(0, Math.min(radius, Math.min(maskWidth, maskHeight) / 2))
    readonly property string maskUri: "data:image/svg+xml;utf8," + encodeURIComponent(
        "<svg xmlns='http://www.w3.org/2000/svg' width='" + maskWidth + "' height='" + maskHeight
        + "'><rect width='" + maskWidth + "' height='" + maskHeight + "' rx='" + maskRadius
        + "' ry='" + maskRadius + "' fill='#ffffff'/></svg>")

    /**
     * 解码尺寸：固定按显示尺寸的 2 倍解码，**不跟随 Screen.devicePixelRatio**。
     *
     * 窗口在不同缩放的屏幕之间移动时 devicePixelRatio 会变化，如果解码尺寸跟着变，
     * 所有封面的 Image.sourceSize 都会变，Qt 会重新解码/重传纹理，视觉上像整页封面重新加载。
     * 固定 2 倍采样在 1x / 2x 屏幕上都不糊，同时尺寸稳定不会触发重载；大图封顶 1024 控制内存。
     */
    readonly property int decodeWidth: width > 0
        ? Math.min(1024, Math.max(1, Math.round(width * 2))) : 0
    readonly property int decodeHeight: height > 0
        ? Math.min(1024, Math.max(1, Math.round(height * 2))) : 0

    function resolveRemote() {
        if ((path !== "" && path !== undefined) || url === "" || url === undefined) {
            resolvedPath = ""
            return
        }
        if (ArtworkCache.resolved[url] !== undefined) {
            resolvedPath = ArtworkCache.resolved[url]
            return
        }
        if (ArtworkCache.pending[url] === true) {
            // 同一封面 URL 可能被多个 CoverArt 实例同时使用（播放页大封面 + 迷你条等），
            // 只有第一个实例会真正发起请求；其余实例等缓存写入后自己取回路径。
            retryTimer.watch(url)
            return
        }
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

    /**
     * 其他实例正在解析同一个 URL 时，轮询共享缓存等它完成。
     * pending 标记由发起请求的实例在回调里清除，所以仅凭它无法触发本实例的重新解析。
     */
    Timer {
        id: retryTimer

        property string watchUrl: ""
        property int tries: 0

        interval: 120
        repeat: true

        function watch(url) {
            if (url === "" || url === undefined)
                return
            watchUrl = url
            tries = 0
            restart()
        }

        onTriggered: {
            const cached = ArtworkCache.resolved[watchUrl]
            if (cached !== undefined) {
                if (cover.url === watchUrl)
                    cover.resolvedPath = cached
                stop()
                return
            }
            if (++tries > 50 || ArtworkCache.pending[watchUrl] !== true)
                stop()
        }
    }

    // 占位背景（无图时）：有图时完全交给遮罩输出，避免半透明边缘露出底色形成一圈暗边
    Rectangle {
        anchors.fill: parent
        radius: cover.radius
        color: Theme.surfaceContainerHighest
        antialiasing: true
        visible: !cover.hasImage

        NeriIcon {
            anchors.centerIn: parent
            name: "music_note"
            iconSize: cover.placeholderIconSize
            color: Theme.onSurfaceVariantColor
        }
    }

    // 圆角遮罩：作为纹理交给 MultiEffect 采样
    Image {
        id: mask

        anchors.fill: parent
        source: cover.maskUri
        sourceSize.width: cover.maskWidth
        sourceSize.height: cover.maskHeight
        visible: false
        // 尺寸变化要立刻重新光栅化，避免拖动窗口时圆角闪一下方形
        asynchronous: false
        cache: true
    }

    Image {
        id: image

        anchors.fill: parent
        source: cover.source
        // 按实际显示尺寸解码，而不是原图分辨率。
        //
        // 封面原图常常是 1000+ 的方图，而卡片里只显示 160~190 px；不限制的话
        // Qt 会按原尺寸解码成纹理常驻内存，首页几十张卡片叠起来就是几百 MB。
        // 乘以屏幕缩放因子保证高 DPI 下依然清晰。
        sourceSize.width: cover.decodeWidth
        sourceSize.height: cover.decodeHeight
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
        // 默认阈值会把遮罩 alpha 当二值（>0 即完全不透明），抗锯齿的灰边被吃掉；
        // 阈值 0.5 + 展开 0.5 让边缘 alpha 0.25~0.75 映射成渐变，圆角才真正平滑。
        maskThresholdMin: 0.5
        maskSpreadAtMin: 0.5
    }
}
