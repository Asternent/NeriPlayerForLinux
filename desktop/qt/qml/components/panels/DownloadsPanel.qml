import QtQuick
import QtQuick.Controls
import ".."
import "../player"
import "../library"

// 下载管理面板：任务队列（内存态）+ 已下载列表（持久索引）+ 下载目录入口。
// 协议见 desktop/docs/protocol-download.md；进度用 downloads.changed 事件 + 500ms 节流刷新。
PanelShell {
    id: panel

    property var tasks: []
    property var items: []
    property int activeCount: 0
    property int taskCount: 0
    property bool loading: true
    property string loadError: ""

    property bool _refreshingTasks: false
    property bool _refreshingItems: false
    property bool _refreshPending: false

    readonly property bool hasData: panel.tasks.length > 0 || panel.items.length > 0
    readonly property bool showEmpty: !panel.loading && panel.loadError === "" && !panel.hasData
    readonly property int finishedCount: {
        let count = 0
        for (let i = 0; i < panel.tasks.length; ++i) {
            const state = panel.tasks[i] ? String(panel.tasks[i].state) : ""
            if (state === "DONE" || state === "FAILED" || state === "CANCELED")
                count += 1
        }
        return count
    }

    title: "下载管理"
    cardWidth: 420

    PanelButton {
        text: "清空已完成"
        chip: true
        enabledButton: panel.finishedCount > 0
        onClicked: panel.clearFinished()
    }

    // ---------------------------------------------------------------- 定时器

    // downloads.changed 触发很频繁，500ms 内最多刷新一次。
    // 用 property 声明，避免落入 PanelShell 的默认属性（标题栏操作区）
    property Timer throttleTimer: Timer {
        interval: 500
        repeat: false
        onTriggered: {
            if (panel._refreshPending) {
                panel._refreshPending = false
                panel.refreshTasks()
            }
        }
    }

    // 进度兜底轮询：事件可能丢，进行中任务每 800ms 拉一次
    property Timer progressTimer: Timer {
        interval: 800
        repeat: true
        running: panel.activeCount > 0 && panel.visible
        onTriggered: panel.refreshTasks()
    }

    // ---------------------------------------------------------------- 事件

    // 面板可能在 Backend 连上之前就被打开（--panel 启动测试），连上后重新拉数据
    property Connections backendConnection: Connections {
        target: Backend
        function onConnectedChanged() {
            if (Backend.connected)
                panel.refreshAll()
        }
    }

    property var _onDownloadsChanged: function(data) {
        const wasActive = panel.activeCount
        if (data && data.activeCount !== undefined && data.activeCount !== null)
            panel.activeCount = Number(data.activeCount)
        if (data && data.taskCount !== undefined && data.taskCount !== null)
            panel.taskCount = Number(data.taskCount)
        // 下载完成/取消后已下载索引会变化
        if (wasActive > 0 && panel.activeCount === 0)
            panel.refreshItems()
        if (throttleTimer.running) {
            panel._refreshPending = true
            return
        }
        panel.refreshTasks()
        throttleTimer.start()
    }

    // ---------------------------------------------------------------- 工具函数

    function messageOf(error) {
        if (error && error.message)
            return String(error.message)
        if (typeof error === "string" && error !== "")
            return error
        return "未知错误"
    }

    /** C++ 桥下发的数组是 QVariantList 包装对象（Array.isArray 为 false），统一转成 JS 数组。 */
    function toArray(value) {
        if (value === undefined || value === null)
            return []
        const copy = []
        const length = value.length !== undefined ? Number(value.length) : 0
        for (let i = 0; i < length; ++i)
            copy.push(value[i])
        return copy
    }

    function countActive(list) {
        let count = 0
        for (let i = 0; i < list.length; ++i) {
            const state = list[i] ? String(list[i].state) : ""
            if (state === "QUEUED" || state === "RUNNING")
                count += 1
        }
        return count
    }

    function formatSize(bytes) {
        const value = Number(bytes)
        if (!isFinite(value) || value <= 0)
            return "0 B"
        if (value < 1024)
            return value.toFixed(0) + " B"
        if (value < 1024 * 1024)
            return (value / 1024).toFixed(1) + " KB"
        if (value < 1024 * 1024 * 1024)
            return (value / 1024 / 1024).toFixed(1) + " MB"
        return (value / 1024 / 1024 / 1024).toFixed(2) + " GB"
    }

    function totalDownloadedBytes() {
        let total = 0
        for (let i = 0; i < panel.items.length; ++i) {
            if (panel.items[i] && panel.items[i].sizeBytes)
                total += Number(panel.items[i].sizeBytes) || 0
        }
        return total
    }

    function summaryText() {
        let text = "已下载 " + panel.items.length + " 首"
        const size = panel.totalDownloadedBytes()
        if (size > 0)
            text += " · " + panel.formatSize(size)
        if (panel.activeCount > 0)
            text += " · 进行中 " + panel.activeCount
        return text
    }

    function directoryOf(filePath) {
        if (!filePath)
            return ""
        const path = String(filePath)
        const index = path.lastIndexOf("/")
        return index > 0 ? path.substring(0, index) : ""
    }

    function downloadDirectoryText() {
        const directory = Settings.get("downloadDirectory", "")
        if (directory && String(directory) !== "")
            return String(directory)
        return "~/Music/NeriPlayer（默认）"
    }

    // ---------------------------------------------------------------- 数据刷新

    function applyTasks(result) {
        const list = panel.toArray(result ? result.tasks : null)
        panel.tasks = list
        panel.taskCount = list.length
        panel.activeCount = result && result.activeCount !== undefined && result.activeCount !== null
                             ? Number(result.activeCount)
                             : panel.countActive(list)
        panel.loading = false
        panel.loadError = ""
    }

    function applyItems(result) {
        panel.items = panel.toArray(result ? result.items : null)
    }

    function refreshTasks() {
        if (panel._refreshingTasks)
            return
        panel._refreshingTasks = true
        return Api.call("download.tasks").then(function(result) {
            panel._refreshingTasks = false
            panel.applyTasks(result)
        }).catch(function(error) {
            panel._refreshingTasks = false
            if (!panel.hasData) {
                panel.loading = false
                panel.loadError = "下载任务读取失败：" + panel.messageOf(error)
            }
        })
    }

    function refreshItems() {
        if (panel._refreshingItems)
            return
        panel._refreshingItems = true
        return Api.call("download.items").then(function(result) {
            panel._refreshingItems = false
            panel.applyItems(result)
        }).catch(function(error) {
            panel._refreshingItems = false
            if (!panel.hasData) {
                panel.loading = false
                panel.loadError = "已下载列表读取失败：" + panel.messageOf(error)
            }
        })
    }

    function refreshAll() {
        panel.loading = !panel.hasData
        panel.loadError = ""
        return Promise.all([Api.call("download.tasks"), Api.call("download.items")]).then(function(results) {
            panel.applyTasks(results[0])
            panel.applyItems(results[1])
            panel.loading = false
        }).catch(function(error) {
            panel.loading = false
            panel.loadError = "下载数据读取失败：" + panel.messageOf(error)
        })
    }

    // ---------------------------------------------------------------- 操作

    function cancelTask(task) {
        if (!task || !task.id)
            return
        Api.call("download.cancel", { id: task.id }).then(function() {
            Toast.show("已取消下载", "info")
            panel.refreshTasks()
        }).catch(function(error) {
            Toast.show("取消失败：" + panel.messageOf(error), "error")
        })
    }

    function retryTask(task) {
        if (!task || !task.id)
            return
        Api.call("download.retry", { id: task.id }).then(function() {
            Toast.show("已重新加入下载队列", "info")
            panel.refreshTasks()
        }).catch(function(error) {
            Toast.show("重试失败：" + panel.messageOf(error), "error")
        })
    }

    function clearFinished() {
        Api.call("download.clearFinished").then(function() {
            Toast.show("已清空已完成的任务", "info")
            panel.refreshTasks()
            panel.refreshItems()
        }).catch(function(error) {
            Toast.show("清空失败：" + panel.messageOf(error), "error")
        })
    }

    function openDirectory(path) {
        const params = path && path !== "" ? { path: path } : {}
        Api.call("download.openDirectory", params).then(function(result) {
            if (!result || result.ok !== true)
                Toast.show(result && result.message ? String(result.message) : "无法打开下载目录", "error")
        }).catch(function(error) {
            Toast.show("打开目录失败：" + panel.messageOf(error), "error")
        })
    }

    function openTaskFolder(task) {
        if (!task)
            return
        const filePath = task.filePath
                         ? String(task.filePath)
                         : (task.song && task.song.filePath ? String(task.song.filePath) : "")
        panel.openDirectory(panel.directoryOf(filePath))
    }

    function openDownloadSettings() {
        Nav.closePanel()
        Nav.openTab("settings")
    }

    // ---------------------------------------------------------------- 生命周期

    Component.onCompleted: {
        Api.on("downloads.changed", panel._onDownloadsChanged)
        panel.refreshAll()
    }

    Component.onDestruction: {
        Api.off("downloads.changed", panel._onDownloadsChanged)
        throttleTimer.stop()
        progressTimer.stop()
    }

    onVisibleChanged: {
        if (!panel.visible) {
            throttleTimer.stop()
            progressTimer.stop()
        }
    }

    // ---------------------------------------------------------------- 界面

    content: Item {
        id: body
        anchors.fill: parent

        Flickable {
            id: scroll
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.top: parent.top
            anchors.bottom: footer.top
            contentWidth: width
            contentHeight: contentColumn.height
            clip: true
            boundsBehavior: Flickable.StopAtBounds

            ScrollBar.vertical: ScrollBar {
                policy: ScrollBar.AsNeeded
            }

            Column {
                id: contentColumn
                width: scroll.width

                // 首次加载
                Item {
                    width: contentColumn.width
                    height: panel.loading ? 160 : 0
                    visible: panel.loading

                    LoadingState {
                        anchors.centerIn: parent
                        text: "正在读取下载任务…"
                    }
                }

                // 读取失败（无任何数据时）
                Item {
                    width: contentColumn.width
                    height: panel.loadError !== "" ? 68 : 0
                    visible: panel.loadError !== ""

                    ErrorCard {
                        anchors.fill: parent
                        anchors.leftMargin: 14
                        anchors.rightMargin: 14
                        anchors.topMargin: 10
                        message: panel.loadError
                        onRetry: panel.refreshAll()
                    }
                }

                // 汇总
                Item {
                    width: contentColumn.width
                    height: visible ? 34 : 0
                    visible: !panel.loading && panel.loadError === "" && panel.hasData

                    Text {
                        x: 14
                        anchors.verticalCenter: parent.verticalCenter
                        text: panel.summaryText()
                        color: Theme.onSurfaceVariantColor
                        font.family: Theme.fontFamily
                        font.pixelSize: Theme.textStyle("labelMedium").size
                    }
                }

                // 下载任务
                SectionHeader {
                    width: contentColumn.width
                    visible: panel.tasks.length > 0
                    title: "下载任务（" + panel.tasks.length + "）"
                    icon: "download"
                }

                Repeater {
                    model: panel.tasks

                    delegate: DownloadTaskRow {
                        required property var modelData

                        task: modelData
                        onRequestCancel: panel.cancelTask(modelData)
                        onRequestRetry: panel.retryTask(modelData)
                        onRequestOpenFolder: panel.openTaskFolder(modelData)
                    }
                }

                // 已下载
                SectionHeader {
                    width: contentColumn.width
                    visible: panel.items.length > 0
                    title: "已下载（" + panel.items.length + "）"
                    icon: "download_done"
                }

                Repeater {
                    model: panel.items

                    delegate: Item {
                        id: itemRow
                        required property var modelData

                        readonly property string titleText: modelData && modelData.title
                                                          ? String(modelData.title) : "未知歌曲"
                        readonly property string subtitleText: {
                            const artist = modelData && modelData.artist ? String(modelData.artist) : "未知艺术家"
                            const path = modelData && modelData.filePath ? String(modelData.filePath) : ""
                            return path !== "" ? artist + " · " + path : artist
                        }

                        width: contentColumn.width
                        height: 56

                        Rectangle {
                            anchors.fill: parent
                            color: itemHover.hovered ? Theme.withAlpha(Theme.onSurfaceColor, 0.05) : "transparent"

                            Behavior on color {
                                ColorAnimation {
                                    duration: Theme.motionShort
                                }
                            }
                        }

                        HoverHandler {
                            id: itemHover
                        }

                        Column {
                            anchors.left: parent.left
                            anchors.leftMargin: 14
                            anchors.right: itemFolderButton.left
                            anchors.rightMargin: 8
                            anchors.verticalCenter: parent.verticalCenter
                            spacing: 2

                            Text {
                                width: parent.width
                                text: itemRow.titleText
                                elide: Text.ElideRight
                                color: Theme.onSurfaceColor
                                font.family: Theme.fontFamily
                                font.pixelSize: Theme.textStyle("bodyMedium").size
                                font.letterSpacing: Theme.textStyle("bodyMedium").letterSpacing
                            }

                            Text {
                                width: parent.width
                                text: itemRow.subtitleText
                                elide: Text.ElideRight
                                color: Theme.onSurfaceVariantColor
                                font.family: Theme.fontFamily
                                font.pixelSize: Theme.textStyle("labelSmall").size
                                font.letterSpacing: Theme.textStyle("labelSmall").letterSpacing
                            }
                        }

                        IconButton {
                            id: itemFolderButton
                            anchors.right: parent.right
                            anchors.rightMargin: 8
                            anchors.verticalCenter: parent.verticalCenter
                            size: 32
                            iconSize: 17
                            icon: "folder_open"
                            tooltip: "打开所在目录"
                            onClicked: panel.openDirectory(panel.directoryOf(itemRow.modelData.filePath))
                        }
                    }
                }

                // 空状态
                Item {
                    width: contentColumn.width
                    height: panel.showEmpty ? scroll.height : 0
                    visible: panel.showEmpty

                    EmptyState {
                        anchors.centerIn: parent
                        width: parent.width - 48
                        icon: "download"
                        title: "暂无下载任务"
                        hint: "在歌曲或歌单的更多菜单中选择「下载」，任务会出现在这里"
                    }
                }

                Item {
                    width: 1
                    height: 12
                }
            }
        }

        // 底部：下载目录 + 打开目录 / 下载设置
        Rectangle {
            id: footer
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.bottom: parent.bottom
            height: 84
            color: "transparent"

            Rectangle {
                anchors.top: parent.top
                width: parent.width
                height: 1
                color: Theme.withAlpha(Theme.outlineVariant, 0.55)
            }

            Item {
                anchors.fill: parent
                anchors.leftMargin: 14
                anchors.rightMargin: 14
                anchors.topMargin: 10
                anchors.bottomMargin: 10

                Column {
                    anchors.left: parent.left
                    anchors.right: footerButtons.left
                    anchors.rightMargin: 12
                    anchors.verticalCenter: parent.verticalCenter
                    spacing: 2

                    Text {
                        text: "下载目录"
                        color: Theme.onSurfaceVariantColor
                        font.family: Theme.fontFamily
                        font.pixelSize: Theme.textStyle("labelSmall").size
                    }

                    Text {
                        width: parent.width
                        text: panel.downloadDirectoryText()
                        elide: Text.ElideRight
                        color: Theme.onSurfaceColor
                        font.family: Theme.fontFamily
                        font.pixelSize: Theme.textStyle("bodySmall").size
                    }
                }

                Row {
                    id: footerButtons
                    anchors.right: parent.right
                    anchors.verticalCenter: parent.verticalCenter
                    spacing: 8

                    PanelButton {
                        text: "打开目录"
                        chip: true
                        outlined: true
                        onClicked: panel.openDirectory("")
                    }

                    PanelButton {
                        text: "下载设置"
                        chip: true
                        filled: true
                        onClicked: panel.openDownloadSettings()
                    }
                }
            }
        }
    }
}
