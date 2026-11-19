import QtQuick
import "../player"
import ".."

// 一起听房间设置行：左侧标题+说明，右侧 M3 开关。
Item {
    id: root

    property string title: ""
    property string description: ""
    property bool checked: false
    property bool enabledSwitch: true

    signal toggled(bool value)

    implicitHeight: Math.max(52, labelColumn.implicitHeight + Theme.spacingSm)

    Column {
        id: labelColumn

        anchors.left: parent.left
        anchors.right: toggle.left
        anchors.rightMargin: Theme.spacingMd
        anchors.verticalCenter: parent.verticalCenter
        spacing: 2

        Text {
            width: parent.width
            text: root.title
            color: root.enabledSwitch ? Theme.onSurfaceColor
                                      : Theme.withAlpha(Theme.onSurfaceColor, 0.45)
            font.family: Theme.fontFamily
            font.pixelSize: Theme.textStyle("bodyMedium").size
            elide: Text.ElideRight
        }

        Text {
            width: parent.width
            visible: root.description !== ""
            text: root.description
            color: Theme.onSurfaceVariantColor
            font.family: Theme.fontFamily
            font.pixelSize: Theme.textStyle("labelSmall").size
            wrapMode: Text.WordWrap
        }
    }

    PanelSwitch {
        id: toggle

        anchors.right: parent.right
        anchors.verticalCenter: parent.verticalCenter
        checked: root.checked
        enabledSwitch: root.enabledSwitch
        onToggled: function(value) { root.toggled(value) }
    }
}
