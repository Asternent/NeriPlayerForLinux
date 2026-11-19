import QtQuick
import QtQuick.Controls
import ".."

// 歌曲「更多」菜单（共享组件，QtQuick.Controls Menu）。
// 统一承载：下一首播放 / 添加到播放队列 / 添加到歌单 / 收藏 / 下载占位 /
// 从当前列表移除 / 上移 / 下移。
//
// 用法：
//   SongActionsMenu {
//       id: songMenu
//       onClickedRemoveRequested: ...   // 实际信号名为 onRemoveRequested
//   }
//   songMenu.openFor(song, { playlistId: "xxx", showMove: true, anchor: moreButton })
//
// 属性：
//   song        当前操作的 Song 对象
//   playlistId  非空时显示「从当前列表移除」，并在信号里回传（页面据此调用 collections.removeSong）
//   showMove    bool，显示「上移 / 下移」菜单项（歌单详情用）
//   favorite    当前收藏状态（打开时异步查询）
// 信号：
//   removeRequested(song)      从当前列表移除
//   moveRequested(song, delta) delta = -1 上移 / +1 下移
//   favoriteToggled(song, bool) 收藏状态切换后
Menu {
    id: root

    property var song: null
    property string playlistId: ""
    property bool showMove: false
    property bool favorite: false

    signal removeRequested(var song)
    signal moveRequested(var song, int delta)
    signal favoriteToggled(var song, bool favorite)

    width: 240
    padding: Theme.spacingXs

    /** 把当前歌曲加入下载队列（后端 download.enqueue，进度见下载面板）。 */
    function enqueueDownload() {
        const target = root.song
        if (!target)
            return
        Api.call("download.enqueue", { songs: [target] }).then(function(result) {
            const queued = result && result.queued !== undefined ? Number(result.queued) : 0
            if (result && result.message) {
                Toast.show(String(result.message), "error")
            } else if (queued > 0) {
                Toast.show("已加入下载队列：" + (target.title || "歌曲"), "info")
            } else {
                Toast.show("未能加入下载队列", "error")
            }
        }).catch(function(error) {
            Toast.show("下载失败：" + (error && error.message ? error.message : "未知错误"), "error")
        })
    }

    /** 打开菜单；options 可含 playlistId / showMove / anchor（用于定位）。 */
    function openFor(song, options) {
        if (!song)
            return
        root.song = song
        root.playlistId = options && options.playlistId ? String(options.playlistId) : ""
        root.showMove = !!(options && options.showMove)
        root.favorite = false

        Api.call("collections.isFavorite", { songKey: song.key }).then(function(result) {
            root.favorite = !!(result && result.favorite)
        }).catch(function() {})

        if (options && options.anchor) {
            const anchor = options.anchor
            const point = anchor.mapToItem(root.parent, anchor.width, 0)
            const parentWidth = root.parent ? root.parent.width : 1180
            root.x = Math.max(0, Math.min(point.x, parentWidth - root.width))
            root.y = Math.max(0, point.y)
        }
        root.popup()
    }

    function toggleFavorite() {
        if (!root.song)
            return
        const song = root.song
        Api.call("collections.toggleFavorite", { song: song }).then(function(result) {
            root.favorite = !!(result && result.favorite)
            Toast.show(root.favorite ? "已收藏" : "已取消收藏")
            root.favoriteToggled(song, root.favorite)
        }).catch(function(error) {
            Toast.show(error && error.message ? error.message : "操作失败", "error")
        })
    }

    background: Rectangle {
        radius: Theme.radiusMd
        color: Theme.surfaceContainerHigh
        border.width: 1
        border.color: Theme.withAlpha(Theme.outlineVariant, 0.6)
    }

    // 统一的菜单项样式（M3 文本项：高 44、圆角 8、hover 叠 onSurface α0.08）
    component ActionItem: MenuItem {
        id: actionItem

        padding: Theme.spacingMd
        implicitHeight: 44

        contentItem: Text {
            text: actionItem.text
            leftPadding: Theme.spacingSm
            verticalAlignment: Text.AlignVCenter
            color: actionItem.enabled ? Theme.onSurfaceColor
                                     : Theme.withAlpha(Theme.onSurfaceColor, 0.38)
            font.family: Theme.fontFamily
            font.pixelSize: Theme.textStyle("bodyMedium").size
        }

        background: Rectangle {
            radius: Theme.radiusSm
            color: actionItem.highlighted ? Theme.withAlpha(Theme.onSurfaceColor, 0.08) : "transparent"
        }
    }

    component Divider: MenuSeparator {
        contentItem: Rectangle {
            implicitWidth: 200
            implicitHeight: 1
            color: Theme.withAlpha(Theme.outlineVariant, 0.7)
        }
    }

    ActionItem {
        text: "下一首播放"
        onTriggered: {
            if (root.song)
                Player.enqueueNext([root.song])
        }
    }

    ActionItem {
        text: "添加到播放队列"
        onTriggered: {
            if (root.song)
                Player.enqueue([root.song])
        }
    }

    ActionItem {
        text: "添加到歌单…"
        onTriggered: {
            if (root.song)
                playlistPicker.openFor(root.song)
        }
    }

    ActionItem {
        text: root.favorite ? "取消收藏" : "收藏"
        onTriggered: root.toggleFavorite()
    }

    ActionItem {
        text: "下载"
        onTriggered: root.enqueueDownload()
    }

    Divider {
        visible: root.playlistId !== "" || root.showMove
    }

    ActionItem {
        visible: root.playlistId !== ""
        text: "从当前列表移除"
        onTriggered: {
            if (root.song)
                root.removeRequested(root.song)
        }
    }

    ActionItem {
        visible: root.showMove
        text: "上移"
        onTriggered: {
            if (root.song)
                root.moveRequested(root.song, -1)
        }
    }

    ActionItem {
        visible: root.showMove
        text: "下移"
        onTriggered: {
            if (root.song)
                root.moveRequested(root.song, 1)
        }
    }

    PlaylistPickerDialog {
        id: playlistPicker
    }
}
