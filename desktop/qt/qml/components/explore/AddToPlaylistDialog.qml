import QtQuick
import QtQuick.Controls
import "../"

// 「收藏到本地歌单」弹窗：选择已有歌单或新建一个，再把当前在线歌曲批量加入。
Popup {
    id: dialog

    property var songs: []
    property var playlists: []
    property var favorites: null
    property bool loading: false
    property bool submitting: false
    property string newName: ""

    signal finished(string message)

    readonly property var allPlaylists: {
        const output = []
        if (dialog.favorites && dialog.favorites.id)
            output.push(dialog.favorites)
        const userPlaylists = dialog.playlists || []
        for (let i = 0; i < userPlaylists.length; i++)
            output.push(userPlaylists[i])
        return output
    }

    function openFor(songList) {
        songs = songList || []
        newName = ""
        open()
    }

    function reload() {
        loading = true
        Api.call("collections.list").then(function(result) {
            playlists = result && result.playlists ? result.playlists : []
            favorites = result && result.favorites ? result.favorites : null
            loading = false
        }).catch(function(error) {
            loading = false
            Toast.show("读取本地歌单失败：" + (error && error.message ? error.message : "未知错误"), "error")
        })
    }

    function addTo(playlistId, playlistName) {
        if (submitting)
            return
        if (!songs || songs.length === 0) {
            close()
            return
        }
        submitting = true
        Api.call("collections.addSongs", { id: playlistId, songs: songs }).then(function() {
            const message = "已添加到「" + playlistName + "」（" + songs.length + " 首）"
            submitting = false
            Toast.show(message)
            dialog.finished(message)
            dialog.close()
        }).catch(function(error) {
            submitting = false
            Toast.show(error && error.message ? error.message : "收藏失败", "error")
        })
    }

    function createAndAdd() {
        const name = newName.trim()
        if (name === "") {
            Toast.show("请输入歌单名称", "error")
            return
        }
        if (submitting)
            return
        submitting = true
        Api.call("collections.create", { name: name }).then(function(result) {
            submitting = false
            if (result && result.playlist)
                dialog.addTo(result.playlist.id, result.playlist.name)
            else
                Toast.show("创建歌单失败", "error")
        }).catch(function(error) {
            submitting = false
            Toast.show(error && error.message ? error.message : "创建歌单失败", "error")
        })
    }

    width: Math.min(440, parent ? parent.width - 48 : 440)
    height: Math.min(520, bodyColumn.implicitHeight + 40)
    x: parent ? Math.round((parent.width - width) / 2) : 0
    y: parent ? Math.round(Math.max(16, (parent.height - height) / 2)) : 0
    modal: true
    dim: true
    focus: true
    padding: 20
    closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside

    onOpened: reload()
    onClosed: newName = ""

    background: Rectangle {
        radius: Theme.radiusXxl
        color: Theme.surfaceContainerLow
    }

    contentItem: Column {
        id: bodyColumn
        spacing: 12

        Text {
            width: parent.width
            text: "收藏到本地歌单"
            color: Theme.onSurfaceColor
            font.family: Theme.fontFamily
            font.pixelSize: 18
            font.weight: Font.DemiBold
        }

        Text {
            width: parent.width
            text: "共 " + dialog.songs.length + " 首歌曲"
            color: Theme.onSurfaceVariantColor
            font.family: Theme.fontFamily
            font.pixelSize: 12
        }

        // 新建歌单
        Row {
            width: parent.width
            spacing: 8

            Rectangle {
                width: parent.width - createButton.width - 8
                height: 38
                radius: 19
                color: Theme.surfaceContainerHigh

                TextInput {
                    id: nameInput
                    anchors.fill: parent
                    anchors.leftMargin: 14
                    anchors.rightMargin: 14
                    verticalAlignment: TextInput.AlignVCenter
                    clip: true
                    color: Theme.onSurfaceColor
                    selectionColor: Theme.primary
                    selectedTextColor: Theme.onPrimaryColor
                    font.family: Theme.fontFamily
                    font.pixelSize: 13
                    text: dialog.newName
                    onTextChanged: dialog.newName = text
                }

                Text {
                    anchors.left: parent.left
                    anchors.leftMargin: 14
                    anchors.verticalCenter: parent.verticalCenter
                    visible: nameInput.text === ""
                    text: "新歌单名称"
                    color: Theme.onSurfaceVariantColor
                    font.family: Theme.fontFamily
                    font.pixelSize: 13
                }
            }

            Rectangle {
                id: createButton
                width: 90
                height: 38
                radius: 19
                color: createMouse.containsMouse ? Theme.mix(Theme.primary, Theme.onPrimaryColor, 0.1) : Theme.primary
                opacity: dialog.submitting ? 0.6 : 1

                Text {
                    anchors.centerIn: parent
                    text: "新建并添加"
                    color: Theme.onPrimaryColor
                    font.family: Theme.fontFamily
                    font.pixelSize: 12
                    font.weight: Font.Medium
                }

                MouseArea {
                    id: createMouse
                    anchors.fill: parent
                    hoverEnabled: true
                    cursorShape: Qt.PointingHandCursor
                    onClicked: dialog.createAndAdd()
                }
            }
        }

        Text {
            width: parent.width
            visible: !dialog.loading
            text: "选择已有歌单"
            color: Theme.onSurfaceVariantColor
            font.family: Theme.fontFamily
            font.pixelSize: 12
        }

        LoadingState {
            width: parent.width
            visible: dialog.loading
            text: "正在读取歌单…"
        }

        Flickable {
            width: parent.width
            height: dialog.loading ? 0 : Math.min(listColumn.implicitHeight, 220)
            contentHeight: listColumn.implicitHeight
            clip: true
            boundsBehavior: Flickable.StopAtBounds
            visible: !dialog.loading

            Column {
                id: listColumn
                width: parent.width

                Repeater {
                    model: dialog.allPlaylists

                    delegate: Item {
                        width: listColumn.width
                        height: 44

                        Rectangle {
                            anchors.fill: parent
                            radius: Theme.radiusSm
                            color: rowMouse.containsMouse ? Theme.surfaceContainerHigh : "transparent"
                        }

                        NeriIcon {
                            id: rowIcon
                            anchors.left: parent.left
                            anchors.leftMargin: 8
                            anchors.verticalCenter: parent.verticalCenter
                            name: "queue_music"
                            iconSize: 20
                            color: Theme.onSurfaceVariantColor
                        }

                        Text {
                            anchors.left: rowIcon.right
                            anchors.leftMargin: 10
                            anchors.right: countLabel.left
                            anchors.rightMargin: 8
                            anchors.verticalCenter: parent.verticalCenter
                            text: modelData.name
                            elide: Text.ElideRight
                            color: Theme.onSurfaceColor
                            font.family: Theme.fontFamily
                            font.pixelSize: 14
                        }

                        Text {
                            id: countLabel
                            anchors.right: parent.right
                            anchors.rightMargin: 10
                            anchors.verticalCenter: parent.verticalCenter
                            text: (modelData.songs ? modelData.songs.length : 0) + " 首"
                            color: Theme.onSurfaceVariantColor
                            font.family: Theme.fontFamily
                            font.pixelSize: 11
                        }

                        MouseArea {
                            id: rowMouse
                            anchors.fill: parent
                            hoverEnabled: true
                            cursorShape: Qt.PointingHandCursor
                            onClicked: dialog.addTo(modelData.id, modelData.name)
                        }
                    }
                }
            }
        }

        Text {
            width: parent.width
            visible: !dialog.loading && dialog.allPlaylists.length === 0
            text: "还没有本地歌单，输入名称新建一个。"
            color: Theme.onSurfaceVariantColor
            font.family: Theme.fontFamily
            font.pixelSize: 12
        }

        Item {
            width: parent.width
            height: 32

            Text {
                anchors.right: parent.right
                anchors.verticalCenter: parent.verticalCenter
                text: "取消"
                color: Theme.primary
                font.family: Theme.fontFamily
                font.pixelSize: 13
                font.weight: Font.Medium

                MouseArea {
                    anchors.fill: parent
                    anchors.margins: -10
                    cursorShape: Qt.PointingHandCursor
                    onClicked: dialog.close()
                }
            }
        }
    }
}
