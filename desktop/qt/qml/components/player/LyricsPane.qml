import QtQuick
import QtQuick.Effects
import ".."

// 播放页歌词栏（Refined Now Playing 风格）：
// 当前行放大高亮、上下行渐隐、边缘淡出、翻译副行、点击行 seek、当前行自动滚到阅读位置。
// 数据直接来自全局 Player / Settings，宽屏侧栏与全屏歌词共用。
//
// 滚动自己做：ListView 的 highlightRangeMode 在可变行高 + 上下留白时位置会算漂，
// 这里改用 positionViewAtIndex 求出目标 contentY 再补一段动画，行为可控。
Item {
    id: pane

    /** 主行基础字号（未含 Settings 的歌词字号缩放）。 */
    property real baseFontSize: 22
    /** 翻译行相对主行的字号比例。 */
    property real translationScale: 0.58
    /** 行水平内边距。 */
    property real horizontalPadding: 12
    /** 右侧给滚动条留的空隙：NeriScrollBar 占 8px，右对齐时文字不能贴上去。 */
    property real scrollBarReserve: 12

    property bool autoScroll: true
    property bool edgeFade: true
    /** 用户手动滚动后暂停自动跟随，静止一会儿再恢复。 */
    property bool autoScrollPaused: false
    /** 手动滚动停止后，等待多久再平滑回当前行。 */
    property int resumeDelay: 2000
    /** 用户正在拖动（歌词内容或滚动条）；拖动期间绝不自动跟随，避免抢位置。 */
    property bool _dragging: false

    // 只把「真的在动」的鼠标算作活动：歌词重排时 Qt 会向静止的指针补发 hover 事件，
    // 若不过滤，控制条会被反复唤醒、永远不自动隐藏。
    property real _lastMouseX: -9999
    property real _lastMouseY: -9999

    function reportMouseActivity(mouseX, mouseY) {
        if (Math.abs(mouseX - _lastMouseX) + Math.abs(mouseY - _lastMouseY) < 2)
            return
        _lastMouseX = mouseX
        _lastMouseY = mouseY
        activity()
    }

    signal activity()

    readonly property var lines: Player.lyrics && Player.lyrics.lines ? Player.lyrics.lines : []
    readonly property int currentIndex: Player.currentLyricIndex()
    readonly property real fontScale: {
        const scale = Settings.get("lyricsFontScale", 1.0)
        return (typeof scale === "number" && scale > 0) ? scale : 1.0
    }
    readonly property bool showTranslation: Settings.get("showLyricTranslation", true) !== false
    readonly property bool wordHighlight: Settings.get("lyricsHighlight", "LINE") === "WORD"
    /** 当前行柔光；关掉后不再创建模糊效果，省 GPU。 */
    readonly property bool lyricsGlow: Settings.get("nowPlayingLyricsGlow", true) !== false
    readonly property int highlightDuration: {
        const d = Settings.get("lyricsHighlightDuration", 220)
        return (typeof d === "number" && d >= 0) ? d : 220
    }

    /** 歌词水平对齐（Settings.lyricsAlignment：LEFT / CENTER / RIGHT）。 */
    readonly property string alignment: {
        const value = Settings.get("lyricsAlignment", "LEFT")
        return (value === "CENTER" || value === "RIGHT") ? value : "LEFT"
    }
    readonly property int alignmentMode: alignment === "CENTER" ? 1 : (alignment === "RIGHT" ? 2 : 0)
    readonly property int textAlignment: alignmentMode === 1 ? Text.AlignHCenter
                                         : (alignmentMode === 2 ? Text.AlignRight : Text.AlignLeft)
    // 当前行放大是用 scale 做的，transformOrigin 不跟着对齐方式走的话文字会往反方向漂。
    readonly property int scaleOrigin: alignmentMode === 1 ? Item.Center
                                       : (alignmentMode === 2 ? Item.Right : Item.Left)

    readonly property real mainFontSize: Math.max(12, baseFontSize * fontScale)
    readonly property real mainLineHeight: Math.round(mainFontSize * 1.42)
    readonly property real translationFontSize: Math.round(mainFontSize * translationScale)
    readonly property real translationLineHeight: Math.round(translationFontSize * 1.34)

    // 逐词高亮的宽度测量：当前行是 DemiBold 加粗，必须用同一字重测量才准。
    FontMetrics {
        id: wordMetrics

        font: Qt.font({
            family: Theme.fontFamily,
            pixelSize: pane.mainFontSize,
            weight: Font.DemiBold
        })
    }

    /**
     * 当前行已播放比例（逐字高亮模式用）。
     *
     * 有 yrc 逐词时间时按真实词时间与词宽累计：唱到第几个词的哪个位置就亮多少，
     * 最后一个词结束即整行亮满，并保持到下一行开始（不会动画一跑完就切行）。
     * 没有逐词时间时回退整行线性进度（行有结束时间用 endMs，否则用下一行开始时间）。
     */
    readonly property real lineProgress: {
        if (!wordHighlight || currentIndex < 0 || currentIndex >= lines.length)
            return 0
        const line = lines[currentIndex]
        if (!line)
            return 0
        // 和逐字高亮同一个时钟：4Hz 快照的 positionMs 会滞后，直接用会让高亮一跳一跳
        const clock = Player.smoothPositionMs
        const words = line.words
        if (words && words.length > 0) {
            // 词宽来自 FontMetrics（不是按词数均分，中文词可能 1~2 个字、还可能带空格/标点）；
            // 和 words 在同一次求值里测量，避免切行瞬间宽度数组与词数组错位。
            const widths = []
            let totalWidth = 0
            for (let i = 0; i < words.length; i++) {
                const width = wordMetrics.advanceWidth(words[i].text)
                widths.push(width)
                totalWidth += width
            }
            if (totalWidth <= 0)
                return 0
            let passedWidth = 0
            for (let i = 0; i < words.length; i++) {
                const word = words[i]
                const start = word.startMs
                const duration = Math.max(0, word.durationMs)
                if (clock < start)
                    return Math.max(0, Math.min(1, passedWidth / totalWidth))
                if (clock < start + duration) {
                    const inner = duration > 0 ? (clock - start) / duration : 1
                    return Math.max(0, Math.min(1, (passedWidth + widths[i] * inner) / totalWidth))
                }
                passedWidth += widths[i]
            }
            // 最后一个词已唱完：整行亮满并保持到下一行开始
            return 1
        }
        const next = lines[currentIndex + 1]
        const start = line.timeMs
        const end = (line.endMs && line.endMs > start)
                    ? line.endMs
                    : (next ? next.timeMs : start + 3000)
        if (end <= start)
            return 0
        return Math.max(0, Math.min(1, (clock - start) / (end - start)))
    }

    NowPlayingTheme { id: np }

    function hasTranslation(line) {
        return pane.showTranslation && line
                && line.translation !== undefined && line.translation !== null
                && line.translation !== ""
    }

    // ---------------------------------------------------------------- 手动滚动

    /** 开始拖动（歌词内容拖动 / 滚动条拖动）：立刻暂停跟随并停掉正在跑的滚动动画。 */
    function beginUserScroll() {
        if (!autoScroll)
            return
        _dragging = true
        autoScrollPaused = true
        resumeTimer.stop()
        scrollAnimation.stop()
    }

    /** 拖动结束：留出静置时间后再平滑回当前行。 */
    function endUserScroll() {
        _dragging = false
        if (!autoScroll)
            return
        autoScrollPaused = true
        resumeTimer.restart()
    }

    /** 非拖动式的用户滚动（滚轮 / 点击滚动条轨道）：每次动作都重新计时。 */
    function noteUserScroll() {
        if (!autoScroll || _dragging)
            return
        autoScrollPaused = true
        resumeTimer.restart()
    }

    // ---------------------------------------------------------------- 状态占位

    Item {
        anchors.centerIn: parent
        width: 40
        height: 40
        visible: pane.lines.length === 0 && Player.lyricsLoading

        NeriIcon {
            anchors.centerIn: parent
            name: "progress_activity"
            iconSize: 30
            color: np.accent

            RotationAnimation on rotation {
                from: 0
                to: 360
                duration: 900
                loops: Animation.Infinite
                running: parent.visible
            }
        }
    }

    PlayerEmptyState {
        anchors.centerIn: parent
        visible: pane.lines.length === 0 && !Player.lyricsLoading
        icon: "lyrics"
        title: "暂无歌词"
        hint: "本地歌曲可放置同名 .lrc 文件，在线歌曲会自动获取歌词"
        contentWidth: Math.min(360, pane.width - 32)
        iconColor: Theme.withAlpha(np.foreground, 0.4)
        titleColor: np.foregroundDim
        hintColor: Theme.withAlpha(np.foreground, 0.45)
    }

    // ---------------------------------------------------------------- 歌词列表

    ListView {
        id: list

        anchors.fill: parent
        visible: pane.lines.length > 0
        clip: true
        model: pane.lines
        boundsBehavior: Flickable.StopAtBounds
        // 上下留白：让第一行 / 最后一行也能滚到阅读位置
        topMargin: height * 0.34
        bottomMargin: height * 0.62
        cacheBuffer: Math.max(0, height * 1.5)

        NeriScrollBar {
            id: lyricsScrollBar

            anchors.right: list.right
            anchors.top: list.top
            anchors.bottom: list.bottom
            width: 8
            flickable: list

            // 滚动条是直接改 contentY 的，不会触发 ListView 的 movementStarted/Ended，
            // 必须在这里把拖动状态转给 LyricsPane，否则自动跟随会一直和用户抢位置。
            onDraggingChanged: {
                if (dragging)
                    pane.beginUserScroll()
                else
                    pane.endUserScroll()
            }
            onScrolled: pane.noteUserScroll()
        }

        delegate: Item {
            id: lineDelegate

            required property var modelData
            required property int index

            readonly property bool active: index === pane.currentIndex
            readonly property bool translated: pane.hasTranslation(modelData)
            readonly property real viewY: y - list.contentY
            readonly property real edgeOpacity: {
                if (!pane.edgeFade)
                    return 1
                const zone = Math.max(48, Math.min(130, list.height * 0.16))
                const top = Math.min(1, Math.max(0, viewY / zone))
                const bottom = Math.min(1, Math.max(0, (list.height - viewY - height) / zone))
                return Math.max(0, Math.min(top, bottom))
            }

            width: list.width
            height: lineColumn.height + (active ? 30 : 20)
            opacity: edgeOpacity

            Rectangle {
                anchors.fill: parent
                anchors.leftMargin: Theme.spacingSm
                anchors.rightMargin: Theme.spacingSm
                anchors.topMargin: 2
                anchors.bottomMargin: 2
                radius: Theme.radiusMd
                color: lineMouse.containsMouse ? Theme.withAlpha(np.foreground, 0.07) : "transparent"

                Behavior on color {
                    ColorAnimation { duration: Theme.motionShort }
                }
            }

            Column {
                id: lineColumn

                x: pane.horizontalPadding
                width: Math.max(0, list.width - pane.horizontalPadding * 2 - pane.scrollBarReserve)
                anchors.verticalCenter: parent.verticalCenter
                spacing: 3

                // 当前行放大、其余行略缩小，用 scale 做平滑过渡（font.pixelSize 不能动画）
                scale: active ? 1.0 : 0.9
                transformOrigin: pane.scaleOrigin
                opacity: active ? 1.0 : 0.46

                Behavior on scale {
                    NumberAnimation { duration: pane.highlightDuration + 180; easing.type: Easing.OutCubic }
                }
                Behavior on opacity {
                    NumberAnimation { duration: pane.highlightDuration + 180; easing.type: Easing.OutCubic }
                }

                Item {
                    id: mainLine

                    readonly property real glowPad: Math.round(pane.mainFontSize * 0.9)
                    // 逐字高亮的裁切窗口要落在文字实际起始位置，否则中间/右对齐时会裁错边
                    readonly property real textWidth: Math.min(width, mainText.contentWidth)
                    readonly property real highlightX: pane.alignmentMode === 1
                                                       ? Math.max(0, (width - textWidth) / 2)
                                                       : (pane.alignmentMode === 2
                                                          ? Math.max(0, width - textWidth) : 0)

                    width: parent.width
                    height: mainText.implicitHeight

                    // 当前行柔光：把同一行文字用强调色画一份再模糊，仅当前行参与渲染。
                    Item {
                        id: glowSource

                        x: -mainLine.glowPad
                        y: -mainLine.glowPad
                        width: mainLine.width + mainLine.glowPad * 2
                        height: mainLine.height + mainLine.glowPad * 2
                        visible: false

                        Text {
                            x: mainLine.glowPad
                            y: mainLine.glowPad
                            width: mainLine.width
                            text: lineDelegate.modelData.text !== "" ? lineDelegate.modelData.text : "♪"
                            wrapMode: Text.Wrap
                            textFormat: Text.PlainText
                            horizontalAlignment: pane.textAlignment
                            color: np.accent
                            font.family: Theme.fontFamily
                            font.pixelSize: pane.mainFontSize
                            font.weight: Font.DemiBold
                            lineHeightMode: Text.FixedHeight
                            lineHeight: pane.mainLineHeight
                        }
                    }

                    MultiEffect {
                        anchors.fill: glowSource
                        visible: pane.lyricsGlow && lineDelegate.active
                        source: glowSource
                        // 关掉自动 padding，避免效果项缩放整行文字（这里已经手动留了边）
                        autoPaddingEnabled: false
                        blurEnabled: true
                        blur: 0.7
                        blurMax: 22
                        opacity: 0.6
                    }

                    Text {
                        id: mainText

                        width: parent.width
                        text: lineDelegate.modelData.text !== "" ? lineDelegate.modelData.text : "♪"
                        wrapMode: Text.Wrap
                        textFormat: Text.PlainText
                        horizontalAlignment: pane.textAlignment
                        color: Theme.withAlpha(np.foreground,
                                              pane.wordHighlight && lineDelegate.active ? 0.42 : 1.0)
                        font.family: Theme.fontFamily
                        font.pixelSize: pane.mainFontSize
                        font.weight: lineDelegate.active ? Font.DemiBold : Font.Normal
                        lineHeightMode: Text.FixedHeight
                        lineHeight: pane.mainLineHeight

                        Behavior on color {
                            ColorAnimation { duration: pane.highlightDuration }
                        }
                    }

                    // 逐字高亮：按文字实际宽度的比例裁切，模拟「唱到哪」
                    // （按列宽算的话，短句会整行都被覆盖）
                    //
                    // 裁切窗口必须从 mainLine 的 x=0 开始：内层 Text 已经按 horizontalAlignment
                    // 把文字排到了 highlightX，窗口若再整体右移 highlightX，居中/右对齐时文字会被
                    // 偏移两次，高亮从句子中间才开始出现。宽度加回 highlightX 才能覆盖到文字起点。
                    Item {
                        visible: pane.wordHighlight && lineDelegate.active
                        x: 0
                        width: mainLine.highlightX + mainLine.textWidth * pane.lineProgress
                        height: parent.height
                        clip: true

                        Text {
                            width: mainLine.width
                            text: lineDelegate.modelData.text !== "" ? lineDelegate.modelData.text : "♪"
                            wrapMode: Text.Wrap
                            textFormat: Text.PlainText
                            horizontalAlignment: pane.textAlignment
                            color: np.accent
                            font.family: Theme.fontFamily
                            font.pixelSize: pane.mainFontSize
                            font.weight: Font.DemiBold
                            lineHeightMode: Text.FixedHeight
                            lineHeight: pane.mainLineHeight
                        }
                    }
                }

                Text {
                    width: parent.width
                    visible: lineDelegate.translated
                    text: lineDelegate.translated ? lineDelegate.modelData.translation : ""
                    wrapMode: Text.Wrap
                    textFormat: Text.PlainText
                    horizontalAlignment: pane.textAlignment
                    color: lineDelegate.active
                           ? Theme.withAlpha(np.foreground, 0.78)
                           : Theme.withAlpha(np.foreground, 0.45)
                    font.family: Theme.fontFamily
                    font.pixelSize: pane.translationFontSize
                    lineHeightMode: Text.FixedHeight
                    lineHeight: pane.translationLineHeight

                    Behavior on color {
                        ColorAnimation { duration: pane.highlightDuration }
                    }
                }
            }

            MouseArea {
                id: lineMouse

                anchors.fill: parent
                hoverEnabled: true
                cursorShape: Qt.PointingHandCursor
                onClicked: Player.seekTo(lineDelegate.modelData.timeMs)
                onPressed: pane.activity()
                onPositionChanged: function(mouse) {
                    pane.reportMouseActivity(mouse.x, mouse.y)
                }
                // 同页面跟踪层：行布局变化会产生 hover entered 噪声，不当作活动
                onEntered: {
                }
            }
        }

        NumberAnimation {
            id: scrollAnimation
            target: list
            property: "contentY"
            duration: 420
            easing.type: Easing.OutCubic
        }

        // 行高要等 delegate 实例化后才准，contentHeight 变化后补一次定位
        onContentHeightChanged: Qt.callLater(pane.scrollToCurrent, false)

        onMovementStarted: pane.beginUserScroll()
        onMovementEnded: pane.endUserScroll()
    }

    /** 把当前行滚到阅读位置（视口中上部）；animate=false 用于切歌/首次布局。 */
    function scrollToCurrent(animate) {
        if (!autoScroll || autoScrollPaused || !visible
                || list.height <= 0 || pane.lines.length === 0)
            return
        if (pane.currentIndex < 0) {
            scrollAnimation.stop()
            list.contentY = -list.topMargin
            return
        }
        const oldY = list.contentY
        list.positionViewAtIndex(pane.currentIndex, ListView.Center)
        // positionViewAtIndex 自己会做边界夹取（含 topMargin 造成的负 contentY），不要再二次夹
        const target = list.contentY
        list.contentY = oldY
        const distance = Math.abs(target - oldY)
        if (!animate) {
            scrollAnimation.stop()
            list.contentY = target
            return
        }
        if (distance < 1)
            return
        scrollAnimation.stop()
        scrollAnimation.from = oldY
        scrollAnimation.to = target
        // 远距离也是平滑动画，只是时长按距离放大（320~600ms），不再瞬移
        scrollAnimation.duration = Math.max(320, Math.min(600, Math.round(distance * 0.8)))
        scrollAnimation.start()
    }

    Timer {
        id: resumeTimer
        interval: pane.resumeDelay
        repeat: false
        onTriggered: {
            pane.autoScrollPaused = false
            Qt.callLater(pane.scrollToCurrent, true)
        }
    }

    onCurrentIndexChanged: Qt.callLater(scrollToCurrent, true)
    onLinesChanged: Qt.callLater(scrollToCurrent, false)
    onHeightChanged: Qt.callLater(scrollToCurrent, false)
    onVisibleChanged: if (visible) Qt.callLater(scrollToCurrent, false)
}
