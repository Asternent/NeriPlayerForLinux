import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import ".."

// 通用确认对话框（共享组件）：标题 + 说明 + 取消/确定。
//
// 用法：confirm.openFor("清空历史", "确定要清空全部最近播放记录吗？此操作不可恢复。", "清空")
// 信号：confirmed()
Dialog {
    id: root

    property string titleText: "确认操作"
    property string message: ""
    property string confirmText: "确定"
    property string cancelText: "取消"
    property bool busy: false

    signal confirmed()

    parent: Overlay.overlay
    anchors.centerIn: parent
    modal: true
    width: 420
    padding: Theme.spacingXl
    closePolicy: Popup.CloseOnEscape

    function openFor(title, message, confirmText) {
        root.titleText = title !== undefined && title !== null && title !== "" ? title : "确认操作"
        root.message = message || ""
        if (confirmText)
            root.confirmText = confirmText
        root.busy = false
        root.open()
    }

    background: Rectangle {
        radius: Theme.radiusXxl
        color: Theme.surfaceContainerHigh
        implicitWidth: 420
        implicitHeight: confirmColumn.implicitHeight + Theme.spacingXl * 2
    }

    contentItem: ColumnLayout {
        id: confirmColumn

        spacing: Theme.spacingLg

        Text {
            Layout.fillWidth: true
            text: root.titleText
            color: Theme.onSurfaceColor
            font.family: Theme.fontFamily
            font.pixelSize: Theme.textStyle("titleMedium").size
            font.weight: Font.DemiBold
        }

        Text {
            Layout.fillWidth: true
            text: root.message
            wrapMode: Text.Wrap
            color: Theme.onSurfaceVariantColor
            font.family: Theme.fontFamily
            font.pixelSize: Theme.textStyle("bodyMedium").size
        }

        RowLayout {
            Layout.fillWidth: true
            spacing: Theme.spacingSm

            Item {
                Layout.fillWidth: true
                implicitHeight: 1
            }

            LibraryButton {
                text: root.cancelText
                onClicked: root.close()
            }

            LibraryButton {
                text: root.confirmText
                filled: true
                enabled: !root.busy
                onClicked: {
                    root.confirmed()
                    root.close()
                }
            }
        }
    }
}
