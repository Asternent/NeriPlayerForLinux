import QtQuick

/**
 * Flickable 的竖向滚动条。
 *
 * QML 的 Flickable 没有自带的滚动条，桌面端又需要「能看见当前在哪、能拖动」，
 * 这里做成一个可复用组件：右侧细条 + 可拖动滑块，滚轮/惯性滚动时同步更新。
 * 拖滑块 / 点击轨道都是直接改 flickable.contentY，不经过 Flickable 的 movement 信号，
 * 所以这里额外暴露 dragging 与 scrolled 信号，让歌词等页面能暂停自动滚动。
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

    /** 正在按住滑块拖动（松手后变 false）。 */
    readonly property bool dragging: thumbArea.pressed
    /** 用户通过滚动条改变了 contentY（拖动中每次移动、点击轨道时各发一次）。 */
    signal scrolled()

    // 滚动范围要带上 Flickable 的 topMargin / bottomMargin：
    // 歌词列表用上下留白把首尾行也顶到阅读位置，只看 contentHeight - height 会算成「不需要滚动条」。
    readonly property real rangeStart: flickable ? -flickable.topMargin : 0
    readonly property real rangeEnd: flickable
        ? Math.max(rangeStart, flickable.contentHeight + flickable.bottomMargin - flickable.height) : 0
    readonly property real scrollRange: Math.max(0, rangeEnd - rangeStart)
    readonly property bool needed: scrollRange > 0.5
    /** 滑块高度：按可视比例计算。 */
    readonly property real thumbHeight: {
        if (!flickable || flickable.contentHeight <= 0)
            return minThumbHeight
        const full = flickable.contentHeight + flickable.topMargin + flickable.bottomMargin
        const ratio = Math.max(0.05, flickable.height / Math.max(1, full))
        return Math.max(minThumbHeight, bar.availableHeight * ratio)
    }
    readonly property real availableHeight: Math.max(0, bar.height - 2)
    /** 滑块位置（0..availableHeight-thumbHeight）。 */
    readonly property real thumbY: {
        const travel = Math.max(0, bar.availableHeight - bar.thumbHeight)
        if (scrollRange <= 0 || travel <= 0)
            return 1
        return 1 + travel * ((flickable.contentY - rangeStart) / scrollRange)
    }

    visible: needed
    width: trackWidth

    // 滚动条本身不接收焦点，也不该抢滚轮
    z: 100

    /** 把滑块上沿放到 y 处（点击轨道时用）。 */
    function scrollToThumbY(y) {
        if (!flickable)
            return
        const travel = Math.max(1, bar.availableHeight - bar.thumbHeight)
        const ratio = Math.max(0, Math.min(1, (y - 1) / travel))
        flickable.contentY = rangeStart + ratio * bar.scrollRange
    }

    Rectangle {
        anchors.fill: parent
        radius: width / 2
        color: "transparent"

        // 轨道点击：跳到点击位置，作为「点一下就翻到那里」的快捷方式
        MouseArea {
            id: trackArea
            anchors.fill: parent
            acceptedButtons: Qt.LeftButton
            onClicked: function(mouse) {
                if (!bar.flickable)
                    return
                bar.scrollToThumbY(mouse.y - bar.thumbHeight / 2)
                bar.scrolled()
            }
        }

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
            const ratio = bar.scrollRange / travel
            bar.flickable.contentY = Math.max(bar.rangeStart, Math.min(bar.rangeEnd,
                                                          pressContentY + (mouse.y - pressMouseY) * ratio))
            bar.scrolled()
        }
    }
}
