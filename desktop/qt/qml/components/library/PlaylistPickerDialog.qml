import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import ".."

// 「添加到歌单」对话框（共享组件）：列出用户歌单（collections.list 的 playlists，不含系统收藏），
// 点击即调用 collections.addSongs；底部提供「新建歌单」。
//
// 用法：picker.openFor(song)
// 信号：added(string playlistId, string playlistName)
Dialog {
    id: root

    property var song: null
    property var playlists: []
    property bool loading: false

    signal added(string playlistId, string playlistName)

    parent: Overlay.overlay
    anchors.centerIn: parent
    modal: true
    width: 420
    padding: Theme.spacingXl
    closePolicy: Popup.CloseOnEscape

    function openFor(song) {
        root.song = song
        root.reload()
        root.open()
    }

    function reload() {
        root.loading = true
        Api.get("collections.list", {}, function(result, error) {
            root.loading = false
            if (error) {
                root.playlists = []
                Toast.show(error.message || "加载歌单失败", "error")
                return
            }
            root.playlists = result.playlists || []
        })
    }

    function addTo(playlist) {
        if (!playlist || !root.song)
            return
        const song = root.song
        Api.call("collections.addSongs", { id: playlist.id, songs: [song] }).then(function() {
            Toast.show("已添加到「" + playlist.name + "」")
            root.added(playlist.id, playlist.name)
            root.close()
        }).catch(function(error) {
            Toast.show(error && error.message ? error.message : "添加到歌单失败", "error")
        })
    }

    background: Rectangle {
        radius: Theme.radiusXxl
        color: Theme.surfaceContainerHigh
        implicitWidth: 420
        implicitHeight: body.implicitHeight + Theme.spacingXl * 2
    }

    contentItem: ColumnLayout {
        id: body

        spacing: Theme.spacingMd

        Text {
            Layout.fillWidth: true
            text: "添加到歌单"
            color: Theme.onSurfaceColor
            font.family: Theme.fontFamily
            font.pixelSize: Theme.textStyle("titleMedium").size
            font.weight: Font.DemiBold
        }

        Text {
            Layout.fillWidth: true
            visible: root.loading || root.playlists.length === 0
            text: root.loading ? "正在加载歌单…" : "还没有歌单，先新建一个吧"
            color: Theme.onSurfaceVariantColor
            font.family: Theme.fontFamily
            font.pixelSize: Theme.textStyle("bodyMedium").size
        }

        ListView {
            id: pickerList

            Layout.fillWidth: true
            Layout.preferredHeight: Math.min(320, Math.max(0, root.playlists.length) * 48)
            visible: root.playlists.length > 0
            clip: true
            model: root.playlists
            boundsBehavior: Flickable.StopAtBounds

            delegate: Rectangle {
                id: pickerRow

                required property var modelData

                width: ListView.view ? ListView.view.width : 0
                height: 48
                radius: Theme.radiusSm
                color: pickerHover.hovered ? Theme.withAlpha(Theme.onSurfaceColor, 0.06) : "transparent"

                Text {
                    anchors.left: parent.left
                    anchors.leftMargin: Theme.spacingMd
                    anchors.right: countText.left
                    anchors.rightMargin: Theme.spacingSm
                    anchors.verticalCenter: parent.verticalCenter
                    text: pickerRow.modelData.name
                    elide: Text.ElideRight
                    color: Theme.onSurfaceColor
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.textStyle("bodyLarge").size
                }

                Text {
                    id: countText

                    anchors.right: parent.right
                    anchors.rightMargin: Theme.spacingMd
                    anchors.verticalCenter: parent.verticalCenter
                    text: (pickerRow.modelData.songs ? pickerRow.modelData.songs.length : 0) + " 首"
                    color: Theme.onSurfaceVariantColor
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.textStyle("labelMedium").size
                }

                HoverHandler {
                    id: pickerHover
                    cursorShape: Qt.PointingHandCursor
                }

                MouseArea {
                    anchors.fill: parent
                    onClicked: root.addTo(pickerRow.modelData)
                }
            }
        }

        RowLayout {
            Layout.fillWidth: true
            spacing: Theme.spacingSm

            LibraryButton {
                text: "新建歌单"
                icon: "add"
                onClicked: createDialog.openFor("")
            }

            Item {
                Layout.fillWidth: true
                implicitHeight: 1
            }

            LibraryButton {
                text: "取消"
                onClicked: root.close()
            }
        }
    }

    CreatePlaylistDialog {
        id: createDialog

        onCreated: function(playlist) {
            root.reload()
            if (playlist && root.song)
                root.addTo(playlist)
        }
    }
}
