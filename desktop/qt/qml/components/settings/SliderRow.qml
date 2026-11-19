import QtQuick
import "../"

// 带标题与实时数值的滑杆（M3 风格：4dp 轨道 + 20dp 圆形拇指）。
// 拖动过程中只更新本地显示，松手后才发 committed，避免高频 IPC 写入设置。
Item {
    id: root

    property string label: ""
    // 可选：function(value) -> 右侧数值文案
    property var formatValue: null
    property real externalValue: 0
    property real from: 0
    property real to: 1
    property int decimals: 2

    signal committed(real value)

    width: parent ? parent.width : 0
    implicitHeight: labelRow.height + 6 + 28

    property real _value: externalValue
    property bool _dragging: false

    onExternalValueChanged: _syncValue()
    Component.onCompleted: _syncValue()

    function _syncValue() {
        if (!_dragging)
            _value = externalValue
    }

    function _setFromX(mouseX) {
        const usable = Math.max(1, sliderArea.width - 20)
        const ratio = Math.max(0, Math.min(1, (mouseX - 10) / usable))
        _value = from + ratio * (to - from)
    }

    function _valueText() {
        if (formatValue)
            return formatValue(_value)
        return _value.toFixed(decimals)
    }

    Item {
        id: labelRow
        width: parent.width
        height: Math.max(labelText.implicitHeight, valueText.implicitHeight)

        Text {
            id: labelText
            anchors.left: parent.left
            anchors.verticalCenter: parent.verticalCenter
            text: root.label
            color: root.enabled ? Theme.onSurfaceColor : Theme.withAlpha(Theme.onSurfaceVariantColor, 0.6)
            font.family: Theme.fontFamily
            font.pixelSize: 14
            font.weight: Font.Medium
        }

        Text {
            id: valueText
            anchors.right: parent.right
            anchors.verticalCenter: parent.verticalCenter
            text: root._valueText()
            color: root.enabled ? Theme.primary : Theme.outline
            font.family: Theme.fontFamily
            font.pixelSize: 14
            font.weight: Font.Medium
        }
    }

    Item {
        id: sliderArea
        x: 0
        y: labelRow.height + 6
        width: parent.width
        height: 28
        opacity: root.enabled ? 1 : 0.38

        readonly property real usable: Math.max(1, width - 20)
        readonly property real ratio: Math.max(0, Math.min(1, (root._value - root.from) / (root.to - root.from)))

        Rectangle {
            id: track
            anchors.verticalCenter: parent.verticalCenter
            x: 10
            width: sliderArea.usable
            height: 4
            radius: 2
            color: Theme.surfaceVariant
        }

        Rectangle {
            anchors.verticalCenter: parent.verticalCenter
            x: 10
            width: sliderArea.usable * sliderArea.ratio
            height: 4
            radius: 2
            color: root.enabled ? Theme.primary : Theme.outline
        }

        Rectangle {
            id: thumb
            anchors.verticalCenter: parent.verticalCenter
            x: 10 + sliderArea.usable * sliderArea.ratio - width / 2
            width: 20
            height: 20
            radius: 10
            color: root.enabled ? Theme.primary : Theme.outline

            Rectangle {
                anchors.centerIn: parent
                width: 8
                height: 8
                radius: 4
                color: Theme.onPrimaryColor
                opacity: 0
            }
        }

        HoverHandler {
            cursorShape: root.enabled ? Qt.PointingHandCursor : Qt.ArrowCursor
        }

        MouseArea {
            anchors.fill: parent
            enabled: root.enabled

            onPressed: function(mouse) {
                root._dragging = true
                root._setFromX(mouse.x)
            }
            onPositionChanged: function(mouse) {
                if (root._dragging)
                    root._setFromX(mouse.x)
            }
            onReleased: function(mouse) {
                if (!root._dragging)
                    return
                root._dragging = false
                root._setFromX(mouse.x)
                root.committed(root._value)
            }
            onCanceled: root._dragging = false
        }
    }
}
