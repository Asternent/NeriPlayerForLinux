import QtQuick

// 拖动悬浮歌词时的位置预览层。
//
// 关键设计：这是个**全屏常驻、透明、不接收输入**的 layer-shell 覆盖层，窗口本身
// 尺寸固定、永不移动；拖动时只改里面矩形的位置。这样既能看到实时跟随的预览框，
// 又不会像「移动窗口」那样每帧触发合成器重排（那才会闪）。
Window {
    id: preview

    /** 预览框在屏幕坐标系下的位置与尺寸。 */
    property real boxX: 0
    property real boxY: 0
    property real boxW: 900
    property real boxH: 146
    property bool active: false

    // 铺满屏幕（全屏覆盖层锚定四边后由合成器定尺寸，这里给个初值兜底）
    width: screen ? screen.width : 1920
    height: screen ? screen.height : 1080

    /** layer-shell 附着是否成功；失败就不显示，避免出现居中的错误预览框。 */
    property bool _ready: false
    visible: active && _ready
    color: "transparent"
    flags: Qt.Window | Qt.FramelessWindowHint | Qt.WindowStaysOnTopHint
           | Qt.WindowDoesNotAcceptFocus | Qt.WindowTransparentForInput
           | Qt.NoDropShadowWindowHint
    title: "NeriPlayer 拖动预览"

    // 只在创建时附着一次：layer-shell 的 shell 集成一旦建立就不能重复设置
    // （重复调用会报 "already has a shell integration"）。
    Component.onCompleted: {
        _ready = LayerShell.attachOverlayFullscreen(preview)
        if (!_ready)
            console.warn("[preview] layer-shell overlay 附着失败，拖动预览不可用")
    }

    Rectangle {
        x: preview.boxX
        y: preview.boxY
        width: preview.boxW
        height: preview.boxH
        radius: 14
        color: Theme.withAlpha(Theme.primary, 0.16)
        border.width: 2
        border.color: Theme.primary
    }
}
