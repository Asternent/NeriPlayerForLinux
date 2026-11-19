import QtQuick

/**
 * Flickable 的竖向滚动条。
 *
 * QML 的 Flickable 没有自带的滚动条，桌面端又需要「能看见当前在哪、能拖动」，
 * 这里做成一个可复用组件：右侧细条 + 可拖动滑块，滚轮/惯性滚动时同步更新。
 *
 * 用法：
 *   Flickable { id: flick; ... }
 *   ScrollBar { flickable: flick }
 */
Item {
    id: bar

    /** 需要显示滚动条的目标 Flickable。 */
    property Item flickable: null
    /** 滚动条槽宽度。 */
    property real trackWidth: 10
    /** 滑块最小高度（内容很长时仍然可抓）。 */
    property real minThumbHeight: 32

    readonly property real maxScroll: flickable
        ? Math.max(0, flickable.contentHeight - flickable.height) : 0
    readonly property bool needed: maxScroll > 0.5
    /** 滑块高度：按可视比例计算。 */
    readonly property real thumbHeight: {
        if (!flickable || flickable.contentHeight <= 0)
            return minThumbHeight
        const ratio = Math.max(0.05, flickable.height / flickable.contentHeight)
        return Math.max(minThumbHeight, bar.availableHeight * ratio)
    }
    readonly property real availableHeight: Math.max(0, bar.height - 2)
    /** 滑块位置（0..availableHeight-thumbHeight）。 */
    readonly property real thumbY: {
        const travel = Math.max(0, bar.availableHeight - bar.thumbHeight)
        if (maxScroll <= 0 || travel <= 0)
            return 1
        return 1 + travel * (flickable.contentY / maxScroll)
    }

    visible: needed
    width: trackWidth

    // 滚动条本身不接收焦点，也不该抢滚轮
    z: 100

    /** 拖动滑块时按像素位移换算回 contentY。 */
    function scrollToThumbY(y) {
        if (!flickable)
            return
        const travel = Math.max(1, bar.availableHeight - bar.thumbHeight)
        const ratio = Math.max(0, Math.min(1, (y - 1) / travel))
        flickable.contentY = ratio * bar.maxScroll
    }

    Rectangle {
        anchors.fill: parent
        radius: width / 2
        color: "transparent"

        Rectangle {
            id: thumb
            x: 0
            y: bar.thumbY
            width: bar.trackWidth
            height: bar.thumbHeight
            radius: width / 2
            // 悬停/拖动时加深，平时保持半透明不抢视觉
            color: Theme.withAlpha(Theme.onSurfaceColor,
                                   (thumbArea.dragging || thumbArea.containsMouse) ? 0.45 : 0.25)

            Behavior on color {
                ColorAnimation { duration: Theme.motionShort }
            }
        }
    }

    MouseArea {
        id: thumbArea
        x: 0
        y: bar.thumbY
        width: bar.trackWidth
        height: bar.thumbHeight
        hoverEnabled: true
        cursorShape: Qt.PointingHandCursor
        preventStealing: true

        property real pressContentY: 0
        property real pressMouseY: 0
        readonly property bool dragging: pressed

        onPressed: function(mouse) {
            pressContentY = bar.flickable ? bar.flickable.contentY : 0
            pressMouseY = mouse.y
        }
        onPositionChanged: function(mouse) {
            if (!pressed || !bar.flickable)
                return
            const travel = Math.max(1, bar.availableHeight - bar.thumbHeight)
            // 鼠标位移 → 内容位移（按轨道与内容的映射比例放大）
            const ratio = bar.maxScroll / travel
            bar.flickable.contentY = Math.max(0, Math.min(bar.maxScroll,
                                                          pressContentY + (mouse.y - pressMouseY) * ratio))
        }
    }
}
