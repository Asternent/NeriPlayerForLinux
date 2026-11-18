import QtQuick
import QtQuick.Controls
import "components"

// 悬浮歌词窗口：无边框、透明、置顶、不抢焦点，可拖动；锁定时整窗鼠标穿透。
// 样式/位置都来自设置（Settings.values），不依赖主窗口状态。
Window {
    id: win

    /** 主窗口（用于「应用内隐藏」判断）。 */
    property Window mainWindow: null

    readonly property bool locked: Settings.get("floatingLyricsLocked", false) === true
    readonly property bool hideInApp: Settings.get("floatingLyricsHideInApp", false) === true
    readonly property bool enabledSetting: Settings.get("floatingLyricsEnabled", false) === true
    readonly property bool appActive: mainWindow ? mainWindow.active : false

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

    visible: enabledSetting && Player.hasSong && !(hideInApp && appActive)
    flags: Qt.Window | Qt.FramelessWindowHint | Qt.WindowStaysOnTopHint | Qt.Tool
           | (locked ? Qt.WindowTransparentForInput : 0)
    color: "transparent"
    title: "NeriPlayer 悬浮歌词"
    width: Math.min(Settings.get("floatingLyricsMaxWidthDp", 900) || 900, content.implicitWidth + 32)
    height: content.implicitHeight + 18 + (locked ? 0 : 30)
    x: Math.round(screen.virtualX + (screen.width - width) * normalizedX)
    y: Math.round(screen.virtualY + (screen.height - height) * normalizedY)

    readonly property real normalizedX: {
        const value = Settings.get("floatingLyricsPositionX", 0.5)
        return typeof value === "number" ? Math.max(0, Math.min(1, value)) : 0.5
    }
    readonly property real normalizedY: {
        const value = Settings.get("floatingLyricsPositionY", 0.85)
        return typeof value === "number" ? Math.max(0, Math.min(1, value)) : 0.85
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

    // 悬浮窗默认不抢焦点
    onVisibleChanged: if (visible) flags = flags

    Rectangle {
        id: background

        anchors.fill: parent
        radius: 14
        color: Theme.withAlpha(win.backgroundColor, win.backgroundAlpha)

        Column {
            id: content

            anchors.centerIn: parent
            width: Math.min(win.width - 24, Math.max(200, mainLine.implicitWidth, translationLine.implicitWidth))
            spacing: 2

            // 主歌词（带阴影或描边）
            Item {
                id: mainItem
                width: parent.width
                height: mainLine.implicitHeight

                // OUTLINE 样式：四方向偏移的描边副本
                Repeater {
                    model: win.renderStyle === "OUTLINE" ? 8 : 0
                    delegate: Text {
                        x: (mainItem.width - width) / 2 + Math.cos(index * Math.PI / 4) * win.outlineWidth
                        y: Math.sin(index * Math.PI / 4) * win.outlineWidth
                        width: mainItem.width
                        text: mainLine.text
                        horizontalAlignment: win.alignment
                        wrapMode: Text.Wrap
                        color: win.outlineColor
                        font.family: Theme.fontFamily
                        font.pixelSize: win.baseFontSize
                        font.weight: Font.Bold
                        opacity: win.lyricAlpha
                    }
                }

                // SHADOW 样式：用几层偏移副本模拟阴影（不用 shader，软件/无 GPU 环境也能渲染）
                Repeater {
                    model: win.renderStyle === "SHADOW" ? 3 : 0
                    delegate: Text {
                        x: (mainItem.width - width) / 2 + (index + 1) * Math.max(1, win.shadowBlur / 3)
                        y: (index + 1) * Math.max(1, win.shadowBlur / 3)
                        width: mainItem.width
                        text: mainLine.text
                        horizontalAlignment: win.alignment
                        wrapMode: Text.Wrap
                        color: win.shadowColor
                        opacity: win.lyricAlpha * 0.45 / (index + 1)
                        font.family: Theme.fontFamily
                        font.pixelSize: win.baseFontSize
                        font.weight: Font.Bold
                    }
                }

                Text {
                    id: mainLine
                    width: parent.width
                    text: win.mainText
                    horizontalAlignment: win.alignment
                    wrapMode: Text.Wrap
                    color: win.textColor
                    opacity: win.lyricAlpha
                    font.family: Theme.fontFamily
                    font.pixelSize: win.baseFontSize
                    font.weight: Font.Bold
                }
            }

            Text {
                id: translationLine
                width: parent.width
                visible: win.translationText !== ""
                text: win.translationText
                horizontalAlignment: win.alignment
                wrapMode: Text.Wrap
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
            cursorShape: pressed ? Qt.ClosedHandCursor : Qt.OpenHandCursor
            property real pressX: 0
            property real pressY: 0
            property real startX: 0
            property real startY: 0

            onPressed: function(mouse) {
                pressX = mouse.x
                pressY = mouse.y
                startX = win.x
                startY = win.y
            }
            onPositionChanged: function(mouse) {
                if (!pressed)
                    return
                win.x = startX + (mouse.x - pressX)
                win.y = startY + (mouse.y - pressY)
            }
            onReleased: win.savePosition()
            onDoubleClicked: Nav.push("nowplaying")
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

        // hover 控制条
        Rectangle {
            id: controlBar

            anchors.horizontalCenter: parent.horizontalCenter
            anchors.bottom: parent.bottom
            anchors.bottomMargin: 4
            height: 30
            width: controlRow.implicitWidth + 16
            radius: 10
            color: Theme.withAlpha("#000000", 0.55)
            opacity: controlHover.hovered && !win.locked ? 1 : 0
            visible: opacity > 0
            Behavior on opacity { NumberAnimation { duration: Theme.motionShort } }

            HoverHandler { id: controlHover }

            Row {
                id: controlRow
                anchors.centerIn: parent
                spacing: 2

                IconButton {
                    icon: "drag_indicator"
                    iconSize: 17
                    size: 30
                    iconColor: Theme.withAlpha("#FFFFFF", 0.8)
                }
                IconButton {
                    icon: "album"
                    iconSize: 17
                    size: 30
                    iconColor: Theme.withAlpha("#FFFFFF", 0.9)
                    onClicked: Nav.push("nowplaying")
                }
                IconButton {
                    icon: win.locked ? "lock" : "lock_open"
                    iconSize: 17
                    size: 30
                    iconColor: Theme.withAlpha("#FFFFFF", 0.9)
                    onClicked: Settings.set("floatingLyricsLocked", !win.locked)
                }
                IconButton {
                    icon: "close"
                    iconSize: 17
                    size: 30
                    iconColor: "#FF8A80"
                    onClicked: Settings.set("floatingLyricsEnabled", false)
                }
            }
        }
    }
}
