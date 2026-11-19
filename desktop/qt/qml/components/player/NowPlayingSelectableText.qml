import QtQuick
import QtQuick.Controls
import ".."

// 播放页可选中复制的文字（标题 / 歌手 / 专辑）。
//
// 这里用只读 TextEdit 而不是 Text：Text 无法用鼠标选区，用户也就没法直接选中复制。
// 为了保留原来的「最多两行 + 超长裁切」效果，用 FontMetrics 算出行高后限制组件高度，
// TextEdit 自身没有 elide / maximumLineCount。
// 右击弹出菜单：复制歌曲信息 / 复制歌名 / 复制歌手 / 复制专辑。
Item {
    id: root

    property string text: ""
    property real fontPixelSize: 15
    property int fontWeight: Font.Normal
    property color color: np.foreground
    property bool wrap: true
    /** 最多显示的行数；0 表示不限。 */
    property int maximumLines: 0
    /** 可跳转入口（歌手 / 专辑）悬停时的下划线提示。 */
    property bool underlined: false
    /** 可跳转文字悬停时用手型光标（TextEdit 默认是 I 型光标）。 */
    property bool handCursor: false
    /** 允许「未拖动的单击」发出 tapped（用于在可选中文字上叠加跳转）。 */
    property bool tapEnabled: false

    signal tapped()
    /** 水平对齐（跟随设置里的信息对齐方式）。 */
    property int horizontalAlignment: Text.AlignLeft

    signal activity()

    NowPlayingTheme { id: np }

    readonly property bool hasSelection: editor.selectedText !== ""
    /** 文字本身的自然宽度（不换行时用于按内容宽度排版，例如居中/右对齐的信息行）。 */
    readonly property real naturalWidth: metrics.advanceWidth(String(root.text))

    Behavior on color {
        ColorAnimation { duration: Theme.motionShort }
    }

    implicitWidth: parent ? parent.width : 0
    implicitHeight: {
        if (!root.wrap)
            return Math.max(1, Math.round(metrics.height))
        const full = Math.max(0, editor.contentHeight)
        if (root.maximumLines <= 0)
            return full
        return Math.min(full, Math.ceil(metrics.height) * root.maximumLines)
    }
    clip: true

    FontMetrics {
        id: metrics

        font: editor.font
    }

    TextEdit {
        id: editor

        anchors.fill: parent
        text: root.text
        // 只读 + 允许鼠标选区：用户可以直接拖选后 Ctrl+C
        readOnly: true
        selectByMouse: true
        persistentSelection: true
        textFormat: TextEdit.PlainText
        wrapMode: root.wrap ? TextEdit.Wrap : TextEdit.NoWrap
        color: root.color
        selectionColor: Theme.withAlpha(np.accent, 0.42)
        selectedTextColor: np.foreground
        font.family: Theme.fontFamily
        font.pixelSize: root.fontPixelSize
        font.weight: root.fontWeight
        font.underline: root.underlined
        horizontalAlignment: root.horizontalAlignment
        cursorVisible: false
        // 按下取焦点：选中文字后 Ctrl+C 需要编辑器本身有焦点
        activeFocusOnPress: true
        bottomPadding: 0
        topPadding: 0
        leftPadding: 0
        rightPadding: 0

        // TextEdit 会自己把鼠标光标设成 I 型；handler 光标优先级更高，悬停可跳转文字时显示手型。
        HoverHandler {
            enabled: root.handCursor
            cursorShape: Qt.PointingHandCursor
        }

        // handler 必须挂在 TextEdit 自己身上：子项会先吃掉 press，挂在父 Item 上的 handler 收不到。
        // DragThreshold：超过拖动阈值就交给 TextEdit 做拖选，不触发单击跳转。
        TapHandler {
            enabled: root.tapEnabled
            acceptedButtons: Qt.LeftButton
            gesturePolicy: TapHandler.DragThreshold
            onSingleTapped: root.tapped()
        }
    }

    TapHandler {
        acceptedButtons: Qt.RightButton
        onSingleTapped: function(eventPoint) {
            root.activity()
            contextMenu.popup(root, eventPoint.position.x, eventPoint.position.y)
        }
    }

    // 复制用剪贴板：TextEdit.copy() 走系统剪贴板，Qt 6 的 QML 没有直接的 Clipboard 单例。
    TextEdit {
        id: clipboard

        visible: false
        width: 1
        height: 1
    }

    function copyText(value) {
        const content = String(value === undefined || value === null ? "" : value)
        if (content === "")
            return
        clipboard.text = content
        clipboard.selectAll()
        clipboard.copy()
        Toast.show("已复制", "info")
    }

    /** 歌名 - 歌手 - 专辑，空字段自动跳过。 */
    function songInfoText() {
        const parts = []
        if (Player.title !== "") parts.push(Player.title)
        if (Player.artist !== "") parts.push(Player.artist)
        if (Player.album !== "") parts.push(Player.album)
        return parts.join(" - ")
    }

    Menu {
        id: contextMenu

        width: 224
        padding: Theme.spacingXs

        background: Rectangle {
            radius: Theme.radiusMd
            color: Theme.surfaceContainerHigh
            border.width: 1
            border.color: Theme.withAlpha(Theme.outlineVariant, 0.6)
        }

        component ContextItem: MenuItem {
            id: contextItem

            property string iconName: "content_copy"

            implicitHeight: 42
            padding: Theme.spacingSm

            contentItem: Row {
                spacing: Theme.spacingSm

                NeriIcon {
                    anchors.verticalCenter: parent.verticalCenter
                    name: contextItem.iconName
                    iconSize: 18
                    color: contextItem.enabled ? Theme.onSurfaceVariantColor
                                               : Theme.withAlpha(Theme.onSurfaceVariantColor, 0.38)
                }

                Text {
                    anchors.verticalCenter: parent.verticalCenter
                    text: contextItem.text
                    color: contextItem.enabled ? Theme.onSurfaceColor
                                               : Theme.withAlpha(Theme.onSurfaceColor, 0.38)
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.textStyle("bodyMedium").size
                }
            }

            background: Rectangle {
                radius: Theme.radiusSm
                color: contextItem.highlighted ? Theme.withAlpha(Theme.onSurfaceColor, 0.08)
                                               : "transparent"
            }
        }

        ContextItem {
            text: "复制歌曲信息"
            iconName: "content_copy"
            enabled: root.songInfoText() !== ""
            onTriggered: root.copyText(root.songInfoText())
        }

        ContextItem {
            text: "复制歌名"
            iconName: "music_note"
            enabled: Player.title !== ""
            onTriggered: root.copyText(Player.title)
        }

        ContextItem {
            text: "复制歌手"
            iconName: "person"
            enabled: Player.artist !== ""
            onTriggered: root.copyText(Player.artist)
        }

        ContextItem {
            text: "复制专辑"
            iconName: "album"
            enabled: Player.album !== ""
            onTriggered: root.copyText(Player.album)
        }
    }
}
