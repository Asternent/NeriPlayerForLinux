import QtQuick
import QtQuick.Controls
import "components"

// 悬浮歌词窗口：无边框、透明、置顶、不抢焦点，可拖动；锁定时整窗鼠标穿透。
// 样式/位置都来自设置（Settings.values），不依赖主窗口状态。
Window {
    id: win

    /** 主窗口（用于「应用内隐藏」判断）。 */
    property Window mainWindow: null
    /** 拖动时的位置预览层（由 Main 创建并注入）。 */
    property Window previewWindow: null

    readonly property bool locked: Settings.get("floatingLyricsLocked", false) === true
    readonly property bool hideInApp: Settings.get("floatingLyricsHideInApp", false) === true
    readonly property bool enabledSetting: Settings.get("floatingLyricsEnabled", false) === true
    readonly property bool appActive: mainWindow ? mainWindow.active : false

    /**
     * 打开主窗口并跳到指定页面。
     *
     * 只调 Nav.push 的话，主窗口收在托盘时页面切换发生在后台，用户看不到内容，
     * 要等窗口自己弹出来之后才「突然」停在播放页。所以先显式显示主窗口。
     */
    function openInMainWindow(routeName) {
        if (win.mainWindow)
            win.mainWindow.showMainWindow()
        Nav.push(routeName)
    }

    readonly property var lyricLines: Player.lyrics && Player.lyrics.lines ? Player.lyrics.lines : []
    readonly property int currentIndex: Player.currentLyricIndex()
    readonly property var currentLine: currentIndex >= 0 && currentIndex < lyricLines.length ? lyricLines[currentIndex] : null
    readonly property string mainText: currentLine ? currentLine.text : (Player.hasSong ? (Player.title + " - " + Player.artist) : "NeriPlayer")
    readonly property string translationText: {
        if (!currentLine || !Settings.get("floatingLyricsShowTranslation", true))
            return ""
        const translated = Player.lyrics && Player.lyrics.translated ? Player.lyrics.translated : null
        if (translated && currentIndex < translated.length && translated[currentIndex].text)
            return translated[currentIndex].text
        return currentLine.translation ? String(currentLine.translation) : ""
    }

    readonly property real baseFontSize: (Settings.get("floatingLyricsFontSize", 30) || 30)
    readonly property string renderStyle: Settings.get("floatingLyricsRenderStyle", "SHADOW")
    readonly property string textColor: colorOf(Settings.get("floatingLyricsTextColor", "WHITE"))
    readonly property string shadowColor: colorOf(Settings.get("floatingLyricsShadowColor", "BLACK"))
    readonly property string outlineColor: colorOf(Settings.get("floatingLyricsOutlineColor", "BLACK"))
    readonly property real outlineWidth: Settings.get("floatingLyricsOutlineWidth", 2.0) || 2.0
    readonly property real shadowBlur: Settings.get("floatingLyricsShadowBlur", 6.0) || 6.0
    readonly property real lyricAlpha: Settings.get("floatingLyricsLyricAlpha", 1.0) || 1.0
    readonly property real translationAlpha: Settings.get("floatingLyricsTranslationAlpha", 0.75) || 0.75
    readonly property real backgroundAlpha: Settings.get("floatingLyricsBackgroundAlpha", 0.35) || 0.35
    readonly property string backgroundColor: colorOf(Settings.get("floatingLyricsBackgroundColor", "BLACK"))
    readonly property int alignment: {
        switch (Settings.get("floatingLyricsAlignment", "CENTER")) {
        case "LEFT": return Text.AlignLeft
        case "RIGHT": return Text.AlignRight
        default: return Text.AlignHCenter
        }
    }

    readonly property bool showTranslation: Settings.get("floatingLyricsShowTranslation", true) === true

    // 窗口尺寸固定（与旧 Compose 版一致）：宽度取设置的最大宽度，高度由字号推导。
    // 不要跟随歌词内容自适应——每换一行就改窗口尺寸会让合成器在拖动时出现残影，
    // 视觉上也会「忽大忽小」。超长歌词在固定宽度内换行/省略即可。
    readonly property real maxWidthDp: {
        const value = Number(Settings.get("floatingLyricsMaxWidthDp", 900))
        return isNaN(value) || value <= 0 ? 900 : value
    }
    readonly property real windowWidth: {
        const screenWidth = screen ? screen.width : maxWidthDp
        return Math.max(160, Math.min(maxWidthDp, screenWidth))
    }
    readonly property real windowHeight: {
        // 主歌词行高 + 可选翻译行高 + 上下内边距；未锁定时额外留出悬浮控制条的高度。
        const lineHeight = baseFontSize * 1.6
        const translation = showTranslation ? baseFontSize * 1.35 : 0
        const controlBarInset = locked ? 0 : 30
        return Math.max(64, lineHeight + translation + 28 + controlBarInset)
    }

    visible: enabledSetting && Player.hasSong && !(hideInApp && appActive)
    // 悬浮歌词常驻最顶层，不抢键盘焦点。
    //
    // Wayland 下普通 xdg-toplevel 窗口的位置和层级都由合成器说了算，写 x/y 无效、
    // WindowStaysOnTopHint 也可能被忽略（这正是「关掉重开回到中间」「不置顶」的原因）。
    // 因此优先用 wlr-layer-shell（KDE 的 LayerShellQt）：overlay 层 + 锚点 + margin，
    // 位置与层级都由我们声明。非 Wayland 或缺失依赖时回退到普通窗口。
    // Wayland 下唯一可靠的「置顶」手段是 wlr-layer-shell：KWin 不提供客户端置顶接口，
    // 而 KWin 窗口规则只在合成器启动时加载、运行期新增不生效（实测 keepAbove 仍为 false）。
    // 代价：layer-shell 窗口由合成器按锚点摆放，拖动只能通过改 margin 模拟。
    readonly property bool useLayerShell: LayerShell.available()
    flags: Qt.Window | Qt.FramelessWindowHint | Qt.WindowStaysOnTopHint
           | Qt.WindowDoesNotAcceptFocus
           | (locked ? Qt.WindowTransparentForInput : 0)
           | Qt.NoDropShadowWindowHint
    color: "transparent"
    title: "NeriPlayer 悬浮歌词"
    width: windowWidth
    height: windowHeight

    // layer-shell 窗口不存在「提到最前」的概念（层级由 layer 决定），
    // 仅在回退路径下需要周期性 raise。
    onVisibleChanged: {
        if (visible) {
            applyPlacement()
            if (!win.useLayerShell)
                keepOnTopTimer.restart()
        }
    }
    // 拖动跟随计时器。
    //
    // 不能只在 MouseArea 的 onPositionChanged 里更新位置：窗口靠改 margin 移动，
    // 合成器应用有延迟，指针一旦跑出窗口就再也收不到移动事件，表现为「窗口卡住、
    // 松手才跳过去」（像拉弹弓）。改成拖动期间按帧轮询全局指针位置，只要指针在动
    // 窗口就会持续跟随。
    /** 拖动中把目标位置画到全屏预览层上（窗口本身不动，避免合成器重排）。 */
    function updatePreview() {
        if (!win.previewWindow || !win._dragState || !win.screen)
            return
        const drag = win._dragState
        const availW = Math.max(0, win.screen.width - win.width)
        const availH = Math.max(0, win.screen.height - win.height)
        const px = Math.max(0, Math.min(availW, drag.pressMarginX + drag.dx))
        const py = Math.max(0, Math.min(availH, drag.pressMarginY + drag.dy))
        // 预览层铺满当前屏幕，内部绘制用「屏幕内相对坐标」
        win.previewWindow.boxX = px
        win.previewWindow.boxY = py
        win.previewWindow.boxW = win.width
        win.previewWindow.boxH = win.height
        win.previewWindow.active = true
    }

    /** 结束预览。 */
    function hidePreview() {
        if (win.previewWindow)
            win.previewWindow.active = false
    }

    property var _dragState: null

    property Timer keepOnTopTimer: Timer {
        interval: 60
        repeat: false
        onTriggered: WindowDrag.keepOnTop(win)
    }

    /** 目标左上角像素位置；屏幕未就绪时返回 -1（表示未知，不要写进规则）。 */
    function targetPixelX() {
        const scr = win.screen
        if (!scr || win.width <= 0)
            return -1
        return Math.max(0, Math.round((scr.width - win.width) * win.normalizedX))
    }
    function targetPixelY() {
        const scr = win.screen
        if (!scr || win.height <= 0)
            return -1
        return Math.max(0, Math.round((scr.height - win.height) * win.normalizedY))
    }

    /** 是否已经完成 layer-shell 附着（附着只需一次）。 */
    property bool _layerAttached: false

    /** 把当前归一化位置应用出去。 */
    function applyPlacement() {
        if (!win.useLayerShell)
            return
        if (!win._layerAttached) {
            // 首次：完整附着（层次/锚点/尺寸/边距一次设好）
            if (win.width <= 0 || win.height <= 0)
                return
            win._layerAttached = LayerShell.attach(win, win.normalizedX, win.normalizedY,
                                                   Math.round(win.width), Math.round(win.height))
            return
        }
        // 之后只改位置：重复 attach 会重设锚点/尺寸，导致合成器反复重排（画面闪烁）
        LayerShell.setPositionRatio(win, win.normalizedX, win.normalizedY)
    }


    readonly property real normalizedX: {
        const value = Settings.get("floatingLyricsPositionX", 0.5)
        return typeof value === "number" ? Math.max(0, Math.min(1, value)) : 0.5
    }
    readonly property real normalizedY: {
        const value = Settings.get("floatingLyricsPositionY", 0.85)
        return typeof value === "number" ? Math.max(0, Math.min(1, value)) : 0.85
    }

    // 位置绑定：拖动期间关闭，避免「写设置 → 归一化坐标变化 → x/y 重新绑定」把窗口拽回去。
    property bool dragging: false

    // Wayland 下 startSystemMove() 由合成器接管指针，MouseArea 收不到 released，
    // 拖完 dragging 会一直为 true（Binding 永久失效）、位置也存不下来。
    // 这里做兜底：位移事件停止后视为拖动结束，提交位置并解除 dragging。
    property Timer dragEndGuard: Timer {
        interval: 250
        repeat: false
        onTriggered: {
            if (!win.dragging)
                return
            dragArea.commitDrag()
        }
    }

    // 位置绑定只在「非 layer-shell」的回退路径下使用：
    // layer-shell 窗口的位置由 margin 决定，写 x/y 会被合成器忽略（甚至冲突）。
    Binding {
        target: win
        property: "x"
        when: !win.dragging && !win.useLayerShell
        value: Math.round(win.screen ? win.screen.virtualX + (win.screen.width - win.width) * win.normalizedX : 0)
    }
    Binding {
        target: win
        property: "y"
        when: !win.dragging && !win.useLayerShell
        value: Math.round(win.screen ? win.screen.virtualY + (win.screen.height - win.height) * win.normalizedY : 0)
    }

    function colorOf(name) {
        switch (String(name || "").toUpperCase()) {
        case "BLACK": return "#000000"
        case "WHITE": return "#FFFFFF"
        case "RED": return "#F44336"
        case "ORANGE": return "#FF9800"
        case "YELLOW": return "#FFEB3B"
        case "GREEN": return "#4CAF50"
        case "CYAN": return "#00BCD4"
        case "BLUE": return "#2196F3"
        case "PURPLE": return "#9C27B0"
        case "PINK": return "#E91E63"
        default: return "#FFFFFF"
        }
    }

    function savePosition() {
        if (!screen || width <= 0 || height <= 0)
            return
        const nx = Math.max(0, Math.min(1, (x - screen.virtualX) / Math.max(1, screen.width - width)))
        const ny = Math.max(0, Math.min(1, (y - screen.virtualY) / Math.max(1, screen.height - height)))
        Settings.patch({ "floatingLyricsPositionX": Number(nx.toFixed(3)), "floatingLyricsPositionY": Number(ny.toFixed(3)) })
    }

    // 拖动结束后的位置持久化。
    //
    // 之前用 onXChanged/onYChanged 触发保存，但 Wayland 下 win.x/win.y 由合成器决定、
    // 经常读到 0 或滞后值，会把 (0,0) 误存进设置，于是下次启动窗口跑到左上角。
    // 现在只在拖动真正结束时，用「拖动前的归一化位置 + 本次位移」算出目标比例再保存。
    function savePositionAt(nx, ny) {
        // 兜底：算出 NaN 时不要写设置（否则后端解析失败、位置被清成 0）
        if (!isFinite(nx) || !isFinite(ny))
            return
        const cx = Math.max(0, Math.min(1, nx))
        const cy = Math.max(0, Math.min(1, ny))
        Settings.patch({
            "floatingLyricsPositionX": Number(cx.toFixed(3)),
            "floatingLyricsPositionY": Number(cy.toFixed(3))
        })
        // layer-shell 下位置靠 margin 表达，保存后要立刻把新位置下发，
        // 否则窗口会停在被拖到的像素位置、与设置里的比例不一致。
        if (win.useLayerShell)
            LayerShell.setPositionRatio(win, cx, cy)
    }

    // 悬浮窗默认不抢焦点（由 WindowDoesNotAcceptFocus 保证，无需再重设 flags）
    // 同时脱离父窗口：主窗口最小化到托盘时，悬浮歌词应当继续显示。
    Component.onCompleted: {
        WindowDrag.detachFromParent(win)
        // layer-shell 的角色在窗口首次显示前附着最稳，这里先放一次
        win.applyPlacement()
    }

    // 设置里的位置被外部改动（设置页滑杆、其它实例）时也要重新定位。
    // layer-shell 下位置靠 margin，x/y 绑定已停用，不会自动跟随设置变化。
    property Connections settingsConnection: Connections {
        target: Settings
        function onValuesChanged() {
            if (win.dragging)
                return
            win.applyPlacement()
        }
    }

    // 窗口级 hover 检测：放在 Window 根上，覆盖整个窗口区域。
    HoverHandler {
        id: windowHover
        enabled: !win.locked
    }

    Rectangle {
        id: background

        anchors.fill: parent
        radius: 14
        color: Theme.withAlpha(win.backgroundColor, win.backgroundAlpha)

        Column {
            id: content

            anchors.centerIn: parent
            width: Math.max(0, win.width - 28)
            spacing: 2

            // 主歌词（带阴影或描边）
            Item {
                id: mainItem
                width: parent.width
                // 固定窗口下最多显示两行，超出部分不参与布局（避免内容撑破窗口）
                height: Math.min(mainLine.implicitHeight, win.baseFontSize * 1.6 * 2)

                // SHADOW 样式：单个偏移副本。不要用多个 Repeater 副本叠阴影——
                // 透明置顶窗口里每换一行都要重建/重绘十几层文本，拖动时会留下残影。
                Text {
                    visible: win.renderStyle === "SHADOW"
                    x: (mainItem.width - width) / 2 + win.shadowBlur * 0.4
                    y: win.shadowBlur * 0.4
                    width: mainItem.width
                    text: mainLine.text
                    horizontalAlignment: win.alignment
                    wrapMode: Text.Wrap
                    maximumLineCount: 2
                    elide: Text.ElideRight
                    color: win.shadowColor
                    opacity: win.lyricAlpha * 0.6
                    font.family: Theme.fontFamily
                    font.pixelSize: win.baseFontSize
                    font.weight: Font.Bold
                }

                Text {
                    id: mainLine
                    width: parent.width
                    text: win.mainText
                    horizontalAlignment: win.alignment
                    wrapMode: Text.Wrap
                    maximumLineCount: 2
                    elide: Text.ElideRight
                    color: win.textColor
                    opacity: win.lyricAlpha
                    font.family: Theme.fontFamily
                    font.pixelSize: win.baseFontSize
                    font.weight: Font.Bold
                    // OUTLINE 样式交回 Qt 文字引擎（单个 item，描边随字形一起光栅化）
                    style: win.renderStyle === "OUTLINE" ? Text.Outline : Text.Normal
                    styleColor: win.outlineColor
                    // Text.Outline 的描边宽度只能取整数像素
                    font.preferShaping: true
                }
            }

            Text {
                id: translationLine
                width: parent.width
                visible: win.translationText !== ""
                text: win.translationText
                horizontalAlignment: win.alignment
                wrapMode: Text.Wrap
                maximumLineCount: 2
                elide: Text.ElideRight
                color: win.textColor
                opacity: win.translationAlpha
                font.family: Theme.fontFamily
                font.pixelSize: Math.round(win.baseFontSize * 0.78)
            }
        }

        // 鼠标交互层（锁定状态下窗口整体穿透，不会收到事件）
        MouseArea {
            id: dragArea
            anchors.fill: parent
            enabled: !win.locked
            hoverEnabled: true
            cursorShape: pressed ? Qt.ClosedHandCursor : Qt.OpenHandCursor
            onPressed: function(mouse) {
                // 拖动期间窗口保持静止，因此 mouse.x/y 就是「指针相对窗口」的准确坐标，
                // 用它算总位移不会受窗口移动影响（这是本方案的关键）。
                win._dragState = {
                    pressMouseX: mouse.x,
                    pressMouseY: mouse.y,
                    pressMarginX: win.targetPixelX(),
                    pressMarginY: win.targetPixelY(),
                    dx: 0,
                    dy: 0
                }
                win.dragging = true
                if (!win.useLayerShell && WindowDrag.usesSystemMove()) {
                    WindowDrag.startMove(win)
                    dragEndGuard.restart()
                }
            }
            onPositionChanged: function(mouse) {
                if (!win.dragging)
                    return
                const drag = win._dragState
                if (!drag || !win.screen)
                    return
                drag.dx = mouse.x - drag.pressMouseX
                drag.dy = mouse.y - drag.pressMouseY
                win.updatePreview()
            }
            onReleased: commitDrag()
            onCanceled: commitDrag()
            onDoubleClicked: win.openInMainWindow("nowplaying")

            /** 拖动结束后把位置换算成屏幕比例保存。 */
            function commitDrag() {
                if (!win.dragging)
                    return
                win.dragging = false
                if (!win.screen)
                    return
                const availW = Math.max(1, win.screen.width - win.width)
                const availH = Math.max(1, win.screen.height - win.height)

                // 位移来自「指针相对窗口」的差值（拖动期间窗口静止，因此精确）
                const drag = win._dragState
                const dx = drag ? (drag.dx || 0) : 0
                const dy = drag ? (drag.dy || 0) : 0
                const baseX = drag ? drag.pressMarginX : win.targetPixelX()
                const baseY = drag ? drag.pressMarginY : win.targetPixelY()
                // 按下时屏幕信息可能还没就绪（得到 -1），这时算出来的位置会
                // 被钳到 0，把记忆位置写成「左上角」。这种情况直接放弃本次提交。
                if (baseX < 0 || baseY < 0) {
                    hidePreview()
                    win._dragState = null
                    return
                }
                const px = Math.max(0, Math.min(availW, baseX + dx))
                const py = Math.max(0, Math.min(availH, baseY + dy))
                win.savePositionAt(px / availW, py / availH)
                hidePreview()
                win._dragState = null
            }
        }

        // 锁定时也能通过快捷键（Ctrl+Alt+L）解锁；这里显示一个很小的提示点
        Rectangle {
            anchors.right: parent.right
            anchors.bottom: parent.bottom
            anchors.margins: 6
            visible: win.locked
            width: 6
            height: 6
            radius: 3
            color: Theme.withAlpha(win.textColor, 0.35)
        }

        // hover 控制条（旧版行为：鼠标移到悬浮窗任意位置就浮出简易控制栏）
        // 注意：不能用「控制条自身的 HoverHandler」驱动显隐——它隐藏时收不到 hover，
        // 会形成死锁永远不显示。这里改用整窗的 HoverHandler 驱动。

        Rectangle {
            id: controlBar

            // 6 个 30x30 按钮 + 5 个 2px 间距 + 左右各 8px 内边距。
            // 不用 Row.implicitWidth：本 Rectangle 的宽度反过来会影响 Row 的布局，
            // 形成绑定循环时算出的宽度偏小，表现为只画出最后一个按钮。
            readonly property int buttonCount: 6
            readonly property real buttonSize: 30
            readonly property real buttonSpacing: 2

            anchors.horizontalCenter: parent.horizontalCenter
            anchors.bottom: parent.bottom
            anchors.bottomMargin: 4
            height: 30
            width: buttonCount * buttonSize + (buttonCount - 1) * buttonSpacing + 16
            radius: 10
            // 注意：不要把字符串色值传给 Theme.withAlpha（它按 .r/.g/.b 取分量，字符串会得到
            // undefined 并退化成纯黑）。控制栏背景本来就是半透明黑，直接写 rgba。
            color: Qt.rgba(0, 0, 0, 0.55)
            // 显示条件：鼠标在窗口内且未锁定
            // 用覆盖整窗的 dragArea 的 hover 状态驱动：MouseArea 设了 cursorShape 后会独占
            // hover 事件，单独的 HoverHandler 在这些场景下收不到。
            readonly property bool shown: (windowHover.hovered || dragArea.containsMouse) && !win.locked
            opacity: shown ? 1 : 0
            visible: opacity > 0
            Behavior on opacity { NumberAnimation { duration: Theme.motionShort } }

            Row {
                id: controlRow
                anchors.centerIn: parent
                spacing: 2

                // 固定 / 解锁：放第一位，让中间播放键保持视觉居中（与旧版一致）
                IconButton {
                    icon: win.locked ? "lock" : "lock_open"
                    iconSize: 17
                    size: 30
                    iconColor: Qt.rgba(1, 1, 1, 0.9)
                    tooltip: win.locked ? "取消固定（可拖动）" : "固定位置"
                    onClicked: Settings.set("floatingLyricsLocked", !win.locked)
                }
                IconButton {
                    icon: "skip_previous"
                    iconSize: 17
                    size: 30
                    iconColor: Qt.rgba(1, 1, 1, 0.9)
                    tooltip: "上一首"
                    onClicked: Player.previous()
                }
                IconButton {
                    icon: Player.playing ? "pause" : "play_arrow"
                    iconSize: 17
                    size: 30
                    iconColor: Qt.rgba(1, 1, 1, 0.95)
                    tooltip: Player.playing ? "暂停" : "播放"
                    onClicked: Player.togglePlayPause()
                }
                IconButton {
                    icon: "skip_next"
                    iconSize: 17
                    size: 30
                    iconColor: Qt.rgba(1, 1, 1, 0.9)
                    tooltip: "下一首"
                    onClicked: Player.next()
                }
                IconButton {
                    icon: "album"
                    iconSize: 17
                    size: 30
                    iconColor: Qt.rgba(1, 1, 1, 0.9)
                    tooltip: "打开播放页"
                    onClicked: win.openInMainWindow("nowplaying")
                }
                IconButton {
                    icon: "close"
                    iconSize: 17
                    size: 30
                    iconColor: "#FF8A80"
                    tooltip: "关闭悬浮歌词"
                    onClicked: Settings.set("floatingLyricsEnabled", false)
                }
            }
        }
    }
}
