import QtQuick
import QtQuick.Effects
import "../ArtworkCache.js" as ArtworkCache

// 播放页背景：当前封面放大 + 高斯模糊 + 暗色叠加。
// Settings.nowPlayingBackground 可选 BLUR（默认）/ GRADIENT / SOLID；
// 没有封面或没有歌曲时退化为带主题色的深色底，避免纯黑一片。
Item {
    id: root

    readonly property string mode: {
        const m = Settings.get("nowPlayingBackground", "BLUR")
        return (m === "GRADIENT" || m === "SOLID") ? m : "BLUR"
    }

    readonly property string coverPath: Player.coverPath
    readonly property string coverUrl: Player.song && Player.song.artworkUrl ? Player.song.artworkUrl : ""

    // 远程封面要先经后端缓存成本地文件（与 CoverArt 相同的解析逻辑）
    property string resolvedPath: ""

    readonly property bool hasCover: source !== ""

    readonly property string source: coverPath !== "" ? "file://" + coverPath
                                            : (resolvedPath !== "" ? "file://" + resolvedPath : "")

    readonly property color accent: np.accent

    clip: true

    NowPlayingTheme { id: np }

    function resolveRemote() {
        if (coverPath !== "" || coverUrl === "") {
            resolvedPath = ""
            return
        }
        if (ArtworkCache.resolved[coverUrl] !== undefined) {
            resolvedPath = ArtworkCache.resolved[coverUrl]
            return
        }
        if (ArtworkCache.pending[coverUrl] === true)
            return
        ArtworkCache.pending[coverUrl] = true
        const requested = coverUrl
        Api.get("artwork.resolve", { url: requested }, function(result, error) {
            delete ArtworkCache.pending[requested]
            if (!error && result && result.path) {
                ArtworkCache.resolved[requested] = result.path
                if (root.coverUrl === requested)
                    root.resolvedPath = result.path
            }
        })
    }

    onCoverUrlChanged: resolveRemote()
    onCoverPathChanged: resolveRemote()
    Component.onCompleted: resolveRemote()

    // ---- 底层：模糊封面 / 渐变 / 纯色 ----
    Item {
        anchors.fill: parent

        Rectangle {
            anchors.fill: parent
            color: root.hasCover ? Theme.surfaceContainerLowest
                                 : Theme.mix(Theme.surface, Theme.primary, 0.22)
        }

        // BLUR：封面放大到超出窗口，模糊后不会露出硬边
        Item {
            id: blurLayer

            anchors.fill: parent
            visible: root.mode === "BLUR" && root.hasCover

            Image {
                id: bgImage

                anchors.fill: parent
                anchors.margins: -Math.max(root.width, root.height) * 0.15
                source: root.source
                fillMode: Image.PreserveAspectCrop
                asynchronous: true
                cache: true
                smooth: true
                // 背景本来就是糊的，解码小图即可：既省内存，放大本身也自带柔化
                sourceSize.width: 320
                sourceSize.height: 320
                visible: false
            }

            MultiEffect {
                anchors.fill: bgImage
                source: bgImage
                blurEnabled: true
                blur: 1.0
                blurMax: 64
            }
        }

        // GRADIENT：不用封面，改用强调色做深色渐变（背景图缺失时也能成立）
        Rectangle {
            id: gradientLayer

            anchors.fill: parent
            visible: root.mode === "GRADIENT" || !root.hasCover
            gradient: Gradient {
                GradientStop { position: 0.0; color: Theme.mix(np.accent, np.scrim, 0.5) }
                GradientStop { position: 0.3; color: Theme.mix(np.accent, np.scrim, 0.72) }
                GradientStop { position: 0.55; color: Theme.mix(Theme.primary, np.scrim, 0.62) }
                GradientStop { position: 0.8; color: Theme.mix(np.accent, np.scrim, 0.68) }
                GradientStop { position: 1.0; color: Theme.mix(np.accent, np.scrim, 0.82) }
            }
        }

        // 渐变模式下加一团柔光，避免整块颜色太平
        Item {
            anchors.fill: parent
            visible: root.mode === "GRADIENT" || !root.hasCover
            opacity: 0.55

            Rectangle {
                id: gradientGlow

                width: parent.width * 0.55
                height: width
                x: -width * 0.18
                y: -height * 0.32
                radius: width / 2
                color: Theme.withAlpha(np.accent, 0.55)
                visible: false
            }

            MultiEffect {
                anchors.fill: gradientGlow
                source: gradientGlow
                blurEnabled: true
                blur: 1.0
                blurMax: 64
            }
        }

        // SOLID：主题底色轻染强调色
        Rectangle {
            anchors.fill: parent
            visible: root.mode === "SOLID"
            color: Theme.mix(Theme.surface, np.accent, 0.16)
        }

        // ---- 暗色叠加：保证前景（浅色文字）有足够对比度 ----
        Rectangle {
            anchors.fill: parent
            color: Theme.withAlpha(np.scrim, root.mode === "GRADIENT" ? 0.3 : 0.56)
        }

        // 强调色调和：让背景与封面/进度条属于同一个色相家族
        Rectangle {
            anchors.fill: parent
            visible: root.hasCover && root.mode === "BLUR"
            color: Theme.withAlpha(np.accent, 0.1)
        }
    }

    // ---- 上下压暗：顶栏与底部控制条压住歌词时仍可读 ----
    Rectangle {
        anchors.fill: parent
        gradient: Gradient {
            GradientStop { position: 0.0; color: Theme.withAlpha(np.scrim, 0.42) }
            GradientStop { position: 0.22; color: Theme.withAlpha(np.scrim, 0.0) }
            GradientStop { position: 0.68; color: Theme.withAlpha(np.scrim, 0.0) }
            GradientStop { position: 1.0; color: Theme.withAlpha(np.scrim, 0.55) }
        }
    }
}
