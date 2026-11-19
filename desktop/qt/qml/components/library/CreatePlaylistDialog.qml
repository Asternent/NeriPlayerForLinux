import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import ".."

// 新建 / 重命名歌单对话框（共享组件）。内部调用 collections.create / collections.rename 并 Toast 提示。
//
// 用法：
//   createDialog.openFor("可选预填名称")               // 新建
//   createDialog.openFor("原歌单名", "playlist-id")    // 重命名
// 信号：
//   created(var playlist)      — 新建成功
//   renamed(string id, string name) — 重命名成功
Dialog {
    id: root

    property bool busy: false
    property string renameId: ""

    signal created(var playlist)
    signal renamed(string id, string name)

    parent: Overlay.overlay
    anchors.centerIn: parent
    modal: true
    width: 400
    padding: Theme.spacingXl
    closePolicy: Popup.CloseOnEscape

    function openFor(prefillName, renameId) {
        root.renameId = renameId || ""
        nameField.text = prefillName || ""
        root.busy = false
        root.open()
        nameField.forceActiveFocus()
    }

    function submit() {
        const name = nameField.text.trim()
        if (name === "" || root.busy)
            return
        root.busy = true
        if (root.renameId !== "") {
            const playlistId = root.renameId
            Api.call("collections.rename", { id: playlistId, name: name }).then(function() {
                root.busy = false
                Toast.show("已重命名为「" + name + "」")
                root.renamed(playlistId, name)
                root.close()
            }).catch(function(error) {
                root.busy = false
                Toast.show(error && error.message ? error.message : "重命名失败", "error")
            })
            return
        }
        Api.call("collections.create", { name: name }).then(function(result) {
            root.busy = false
            Toast.show("已创建歌单「" + result.playlist.name + "」")
            root.created(result.playlist)
            root.close()
        }).catch(function(error) {
            root.busy = false
            Toast.show(error && error.message ? error.message : "创建歌单失败", "error")
        })
    }

    background: Rectangle {
        radius: Theme.radiusXxl
        color: Theme.surfaceContainerHigh
        implicitWidth: 400
        implicitHeight: contentLayout.implicitHeight + Theme.spacingXl * 2
    }

    contentItem: ColumnLayout {
        id: contentLayout

        spacing: Theme.spacingLg

        Text {
            Layout.fillWidth: true
            text: root.renameId !== "" ? "重命名歌单" : "新建歌单"
            color: Theme.onSurfaceColor
            font.family: Theme.fontFamily
            font.pixelSize: Theme.textStyle("titleMedium").size
            font.weight: Font.DemiBold
        }

        TextField {
            id: nameField

            Layout.fillWidth: true
            placeholderText: "歌单名称"
            selectByMouse: true
            color: Theme.onSurfaceColor
            placeholderTextColor: Theme.onSurfaceVariantColor
            font.family: Theme.fontFamily
            font.pixelSize: Theme.textStyle("bodyLarge").size
            leftPadding: Theme.spacingMd
            rightPadding: Theme.spacingMd
            topPadding: 10
            bottomPadding: 10

            background: Rectangle {
                radius: Theme.radiusSm
                color: Theme.surfaceContainerHighest
                border.width: nameField.activeFocus ? 1 : 0
                border.color: Theme.primary
            }

            onAccepted: root.submit()
        }

        RowLayout {
            Layout.fillWidth: true
            spacing: Theme.spacingSm

            Item {
                Layout.fillWidth: true
                implicitHeight: 1
            }

            LibraryButton {
                text: "取消"
                onClicked: root.close()
            }

            LibraryButton {
                text: root.renameId !== "" ? "保存" : "创建"
                filled: true
                enabled: nameField.text.trim() !== "" && !root.busy
                onClicked: root.submit()
            }
        }
    }

    onClosed: nameField.text = ""
}
