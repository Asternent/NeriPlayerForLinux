import QtQuick
import ".."

// 下载任务行：封面 + 歌名/歌手 + 状态文字 + 进度条 + 取消/重试/打开目录。
// 对应 DownloadPanel.kt:101-177；进度条高 4dp，封面 38dp。
Item {
    id: row

    property var task: null

    signal requestCancel()
    signal requestRetry()
    signal requestOpenFolder()

    readonly property string taskState: task && task.state ? String(task.state) : ""
    readonly property var song: task && task.song ? task.song : null
    readonly property bool active: taskState === "QUEUED" || taskState === "RUNNING"
    readonly property real totalBytes: task && task.totalBytes !== undefined ? Number(task.totalBytes) : -1
    readonly property real downloadedBytes: task && task.downloadedBytes !== undefined ? Number(task.downloadedBytes) : 0
    readonly property bool knownSize: totalBytes > 0
    readonly property real progressValue: {
        if (taskState === "DONE")
            return 1
        if (knownSize)
            return Math.max(0, Math.min(1, downloadedBytes / totalBytes))
        return 0
    }
    readonly property bool showProgress: active

    readonly property string titleText: song && song.title ? String(song.title) : "未知歌曲"
    readonly property string artistText: song && song.artist ? String(song.artist) : "未知艺术家"
    readonly property string statusText: {
        switch (taskState) {
        case "QUEUED":
            return "排队中"
        case "RUNNING":
            return knownSize ? ("下载中 " + Math.round(progressValue * 100) + "%") : "下载中…"
        case "DONE":
            return "已完成"
        case "FAILED":
            return task && task.error ? String(task.error) : "下载失败"
        case "CANCELED":
            return "已取消"
        }
        return taskState
    }
    readonly property bool statusIsError: taskState === "FAILED"

    readonly property string actionIcon: {
        switch (taskState) {
        case "QUEUED":
        case "RUNNING":
            return "close"
        case "FAILED":
        case "CANCELED":
            return "refresh"
        case "DONE":
            return "folder_open"
        }
        return ""
    }
    readonly property string actionTooltip: {
        switch (taskState) {
        case "QUEUED":
        case "RUNNING":
            return "取消下载"
        case "FAILED":
        case "CANCELED":
            return "重试"
        case "DONE":
            return "打开所在目录"
        }
        return ""
    }

    height: showProgress ? 60 : 50
    width: parent ? parent.width : 0

    function triggerAction() {
        switch (row.taskState) {
        case "QUEUED":
        case "RUNNING":
            row.requestCancel()
            break
        case "FAILED":
        case "CANCELED":
            row.requestRetry()
            break
        case "DONE":
            row.requestOpenFolder()
            break
        }
    }

    Rectangle {
        anchors.fill: parent
        color: hover.hovered ? Theme.withAlpha(Theme.onSurfaceColor, 0.05) : "transparent"

        Behavior on color {
            ColorAnimation {
                duration: Theme.motionShort
            }
        }
    }

    HoverHandler {
        id: hover
    }

    CoverArt {
        id: cover
        x: 14
        y: 6
        width: 38
        height: 38
        radius: 9
        placeholderIconSize: 18
        path: row.song && row.song.artworkPath ? row.song.artworkPath : ""
        url: row.song && row.song.artworkUrl ? row.song.artworkUrl : ""
    }

    Item {
        id: labelColumn
        x: 62
        y: 6
        width: Math.max(0, row.width - 62 - 46)
        height: 38

        Text {
            width: parent.width
            text: row.titleText
            elide: Text.ElideRight
            color: Theme.onSurfaceColor
            font.family: Theme.fontFamily
            font.pixelSize: Theme.textStyle("bodyMedium").size
            font.letterSpacing: Theme.textStyle("bodyMedium").letterSpacing
        }

        Text {
            y: 20
            width: parent.width
            text: row.artistText + " · " + row.statusText
            elide: Text.ElideRight
            color: row.statusIsError ? Theme.error : Theme.onSurfaceVariantColor
            font.family: Theme.fontFamily
            font.pixelSize: Theme.textStyle("labelSmall").size
            font.letterSpacing: Theme.textStyle("labelSmall").letterSpacing
        }
    }

    IconButton {
        anchors.right: parent.right
        anchors.rightMargin: 8
        anchors.verticalCenter: cover.verticalCenter
        size: 32
        iconSize: 17
        icon: row.actionIcon
        iconColor: row.taskState === "DONE" ? Theme.onSurfaceVariantColor : Theme.primary
        tooltip: row.actionTooltip
        visible: row.actionIcon !== ""
        onClicked: row.triggerAction()
    }

    Rectangle {
        id: track
        x: 14
        y: 50
        width: Math.max(0, row.width - 28)
        height: 4
        radius: 2
        color: Theme.surfaceVariant
        clip: true
        visible: row.showProgress

        Rectangle {
            id: determinate
            visible: row.knownSize
            width: Math.max(0, track.width * row.progressValue)
            height: track.height
            radius: track.radius
            color: Theme.primary

            Behavior on width {
                NumberAnimation {
                    duration: 200
                    easing.type: Easing.OutCubic
                }
            }
        }

        Rectangle {
            id: indeterminate
            visible: !row.knownSize
            width: track.width * 0.35
            height: track.height
            radius: track.radius
            color: Theme.primary

            SequentialAnimation on x {
                running: row.showProgress && !row.knownSize
                loops: Animation.Infinite
                NumberAnimation {
                    from: -track.width * 0.35
                    to: track.width
                    duration: 1200
                    easing.type: Easing.InOutQuad
                }
            }
        }
    }
}
