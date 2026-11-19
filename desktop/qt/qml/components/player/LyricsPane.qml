import QtQuick
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

    property bool autoScroll: true
    property bool edgeFade: true
    /** 用户手动滚动后暂停自动跟随，静止一会儿再恢复。 */
    property bool autoScrollPaused: false

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
    readonly property int highlightDuration: {
        const d = Settings.get("lyricsHighlightDuration", 220)
        return (typeof d === "number" && d >= 0) ? d : 220
    }

    readonly property real mainFontSize: Math.max(12, baseFontSize * fontScale)
    readonly property real mainLineHeight: Math.round(mainFontSize * 1.42)
    readonly property real translationFontSize: Math.round(mainFontSize * translationScale)
    readonly property real translationLineHeight: Math.round(translationFontSize * 1.34)

    /** 当前行已播放比例（逐字高亮模式用）。 */
    readonly property real lineProgress: {
        if (!wordHighlight || currentIndex < 0 || currentIndex >= lines.length)
            return 0
        const line = lines[currentIndex]
        if (!line)
            return 0
        const next = lines[currentIndex + 1]
        const start = line.timeMs
        const end = next ? next.timeMs : start + 3000
        if (end <= start)
            return 0
        return Math.max(0, Math.min(1, (Player.positionMs - start) / (end - start)))
    }

    NowPlayingTheme { id: np }

    function hasTranslation(line) {
        return pane.showTranslation && line
                && line.translation !== undefined && line.translation !== null
                && line.translation !== ""
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
            anchors.right: list.right
            anchors.top: list.top
            anchors.bottom: list.bottom
            width: 8
            flickable: list
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
                width: Math.max(0, list.width - pane.horizontalPadding * 2)
                anchors.verticalCenter: parent.verticalCenter
                spacing: 3

                // 当前行放大、其余行略缩小，用 scale 做平滑过渡（font.pixelSize 不能动画）
                scale: active ? 1.0 : 0.9
                transformOrigin: Item.Left
                opacity: active ? 1.0 : 0.46

                Behavior on scale {
                    NumberAnimation { duration: pane.highlightDuration + 180; easing.type: Easing.OutCubic }
                }
                Behavior on opacity {
                    NumberAnimation { duration: pane.highlightDuration + 180; easing.type: Easing.OutCubic }
                }

                Item {
                    id: mainLine

                    width: parent.width
                    height: mainText.implicitHeight

                    Text {
                        id: mainText

                        width: parent.width
                        text: lineDelegate.modelData.text !== "" ? lineDelegate.modelData.text : "♪"
                        wrapMode: Text.Wrap
                        textFormat: Text.PlainText
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
                    Item {
                        visible: pane.wordHighlight && lineDelegate.active
                        width: Math.min(mainLine.width, mainText.contentWidth) * pane.lineProgress
                        height: parent.height
                        clip: true

                        Text {
                            width: mainLine.width
                            text: lineDelegate.modelData.text !== "" ? lineDelegate.modelData.text : "♪"
                            wrapMode: Text.Wrap
                            textFormat: Text.PlainText
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

        onMovementStarted: {
            pane.autoScrollPaused = true
            resumeTimer.stop()
        }
        onMovementEnded: resumeTimer.restart()
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
        if (!animate || distance > list.height * 1.5) {
            scrollAnimation.stop()
            list.contentY = target
            return
        }
        if (distance < 1)
            return
        scrollAnimation.stop()
        scrollAnimation.from = oldY
        scrollAnimation.to = target
        scrollAnimation.start()
    }

    Timer {
        id: resumeTimer
        interval: 2500
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
