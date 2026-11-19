import QtQuick
import QtQuick.Controls
import QtQuick.Dialogs
import "../components"
import "../components/settings"

// 设置页：卡片式分组，对齐旧 Compose 设置页（主题 / 媒体库 / 播放 / 下载 / 悬浮歌词 /
// 后台与系统控制 / 账号 / 同步 / 快捷键 / 关于）。所有修改即时通过 Settings / Api 生效。
Item {
    id: page

    // 与其它页面一致的宽窄布局判定（设置页只需用于信息展示）。
    readonly property bool wide: width >= Theme.wideBreakpoint

    // ---------------------------------------------------------------- 常量表

    readonly property var presetSeedColors: [
        "0061A4", "6750A4", "B3261E", "C425A8",
        "00897B", "388E3C", "FBC02D", "E65100"
    ]

    readonly property var darkModeOptions: [
        { value: "AUTO", label: "跟随系统" },
        { value: "LIGHT", label: "浅色" },
        { value: "DARK", label: "深色" }
    ]

    readonly property var uiScaleOptions: [
        { value: 0, label: "跟随系统" },
        { value: 1.0, label: "100%" },
        { value: 1.25, label: "125%" },
        { value: 1.5, label: "150%" },
        { value: 1.75, label: "175%" },
        { value: 2.0, label: "200%" },
        { value: 2.5, label: "250%" }
    ]

    readonly property var paletteStyles: [
        { value: "TonalSpot", label: "柔和色点", description: "接近 Android 默认动态色，平衡耐看。" },
        { value: "Neutral", label: "低饱和", description: "减少彩度，页面更克制。" },
        { value: "Vibrant", label: "鲜明", description: "主色更亮更突出。" },
        { value: "Expressive", label: "表现力", description: "色相更跳跃，适合个性化主题。" },
        { value: "Rainbow", label: "彩虹", description: "色相分布更宽，色彩更丰富。" },
        { value: "FruitSalad", label: "果味", description: "互补色更强，风格活泼。" },
        { value: "Monochrome", label: "单色", description: "接近黑白灰，弱化主题色。" },
        { value: "Fidelity", label: "忠实取色", description: "更贴近你选择的种子色。" },
        { value: "Content", label: "内容色", description: "沿用内容本身的取色。" }
    ]

    readonly property var colorSpecOptions: [
        { value: "SPEC_2021", label: "2021" },
        { value: "SPEC_2025", label: "2025" }
    ]

    readonly property var qualityOptions: [
        { value: "standard", label: "标准" },
        { value: "higher", label: "较高" },
        { value: "exhigh", label: "极高" },
        { value: "lossless", label: "无损" }
    ]

    readonly property var concurrencyOptions: [
        { value: 1, label: "1 个" },
        { value: 2, label: "2 个" },
        { value: 3, label: "3 个" },
        { value: 4, label: "4 个" }
    ]

    readonly property var renderStyleOptions: [
        { value: "SHADOW", label: "阴影" },
        { value: "OUTLINE", label: "描边" }
    ]

    readonly property var alignmentOptions: [
        { value: "LEFT", label: "居左" },
        { value: "CENTER", label: "居中" },
        { value: "RIGHT", label: "居右" }
    ]

    readonly property var floatingLyricColors: [
        { name: "WHITE", label: "白色", color: "#FFFFFF" },
        { name: "BLACK", label: "黑色", color: "#000000" },
        { name: "RED", label: "红色", color: "#F44336" },
        { name: "ORANGE", label: "橙色", color: "#FF9800" },
        { name: "YELLOW", label: "黄色", color: "#FFEB3B" },
        { name: "GREEN", label: "绿色", color: "#4CAF50" },
        { name: "CYAN", label: "青色", color: "#00BCD4" },
        { name: "BLUE", label: "蓝色", color: "#2196F3" },
        { name: "PURPLE", label: "紫色", color: "#9C27B0" },
        { name: "PINK", label: "粉色", color: "#E91E63" }
    ]

    // ---------------------------------------------------------------- 页面状态

    property var accounts: []
    property var syncConfig: ({})
    property var syncState: ({})
    property bool syncUnsupported: false
    property bool syncBusy: false
    property string syncStatusMessage: ""
    property string appVersion: "1.4.7"

    property int songCount: 0
    property bool scanning: false
    property int scanTotal: 0
    property int scanProcessed: 0
    property int scanFound: 0
    property string scanError: ""
    property bool clearingCache: false

    property var _onScan: null
    property var _onLibraryChanged: null
    property var _onAccountsChanged: null
    property var _onSyncState: null

    // 仅供截图验证：>0 时把设置页滚动到该位置；-1 表示不启用（--screenshot 模式下自动定位到快捷键卡片）。
    property int __debugScrollTo: -1

    // ---------------------------------------------------------------- 工具函数

    function sv(key, fallback) {
        const values = Settings.values
        if (!values)
            return fallback
        const value = values[key]
        return (value === undefined || value === null) ? fallback : value
    }

    function sb(key, fallback) {
        const value = page.sv(key, fallback)
        return value === true
    }

    function cliArgument(name) {
        const args = Qt.application.arguments
        const index = args.indexOf(name)
        return (index >= 0 && index + 1 < args.length) ? args[index + 1] : ""
    }

    // 截图验证辅助：滚动到快捷键卡片（__debugScrollTo >= 0 时滚动到指定位置）。
    function scrollToDebugTarget() {
        if (!flick || !shortcutSection)
            return
        const maxY = Math.max(0, flick.contentHeight - flick.height)
        const target = page.__debugScrollTo >= 0 ? page.__debugScrollTo
                                                 : Math.max(0, shortcutSection.y - 8)
        flick.contentY = Math.max(0, Math.min(target, maxY))
    }

    function setSetting(key, value) {
        Settings.set(key, value)
    }

    /** 界面缩放：设置值 <= 0 表示跟随系统，这里换算成实际百分比文案。 */
    function effectiveScaleText() {
        const value = Number(page.sv("uiScale", 0))
        if (!(value > 0))
            return Math.round((screen ? screen.devicePixelRatio : 1) * 100) + "% · 跟随系统"
        return Math.round(value * 100) + "% · 设置中手动指定"
    }

    function screenText() {
        if (!screen)
            return "未知"
        return Math.round(screen.width) + "×" + Math.round(screen.height) + " 像素"
    }

    function patchSettings(changes) {
        Settings.patch(changes)
    }

    /** 读取首页栏目开关（Settings.homeCards 的嵌套字段）。 */
    function homeCardEnabled(key, fallback) {
        const cards = page.sv("homeCards", null)
        if (!cards)
            return fallback !== false
        return cards[key] !== false
    }

    /** 写入首页栏目开关：后端只认整个 homeCards 对象，需整体 patch。 */
    function setHomeCard(key, value) {
        const cards = page.sv("homeCards", null) || {}
        const next = {}
        for (const name in cards)
            next[name] = cards[name]
        next[key] = value
        page.patchSettings({ "homeCards": next })
    }

    function sanitizeHex(text) {
        if (text === undefined || text === null)
            return ""
        const value = String(text).trim().replace(/^#/, "").toUpperCase()
        return /^[0-9A-F]{6}$/.test(value) ? value : ""
    }

    function formatTime(millis) {
        if (!millis)
            return ""
        const date = new Date(millis)
        function pad(value) {
            return value < 10 ? "0" + value : "" + value
        }
        return date.getFullYear() + "-" + pad(date.getMonth() + 1) + "-" + pad(date.getDate())
                + " " + pad(date.getHours()) + ":" + pad(date.getMinutes())
    }

    function pathFromUrl(url) {
        let text = String(url)
        if (text.indexOf("file://") === 0)
            text = text.substring(7)
        try {
            return decodeURIComponent(text)
        } catch (error) {
            return text
        }
    }

    function paletteDescription(style) {
        for (let i = 0; i < page.paletteStyles.length; i++) {
            if (page.paletteStyles[i].value === style)
                return page.paletteStyles[i].description
        }
        return ""
    }

    // ---------------------------------------------------------------- 主题

    function selectPresetSeed(hex) {
        if (page.sb("followSystemTheme", true)) {
            Toast.show("「跟随系统主题色」已开启，请先关闭后再选择主题色", "info")
            return
        }
        page.patchSettings({ themeSeedColor: hex, followSystemTheme: false, dynamicColor: false })
    }

    function applyCustomSeed() {
        if (page.sb("followSystemTheme", true)) {
            Toast.show("「跟随系统主题色」已开启，请先关闭后再应用自定义主题色", "info")
            return
        }
        const sanitized = page.sanitizeHex(seedField.text)
        if (sanitized === "") {
            Toast.show("请输入 6 位十六进制色值，例如 0061A4", "error")
            return
        }
        page.patchSettings({ themeSeedColor: sanitized, followSystemTheme: false, dynamicColor: false })
        seedField.text = sanitized
        Toast.show("主题色已更新为 #" + sanitized, "info")
    }

    // ---------------------------------------------------------------- 媒体库

    function applyScanState(state) {
        if (!state)
            return
        page.scanning = state.running === true
        page.scanTotal = state.total !== undefined ? state.total : 0
        page.scanProcessed = state.processed !== undefined ? state.processed : 0
        page.scanFound = state.found !== undefined ? state.found : 0
        page.scanError = state.error ? String(state.error) : ""
    }

    readonly property string libraryStatusText: {
        if (page.scanning)
            return "正在扫描 " + page.scanProcessed + " / " + page.scanTotal
                    + "，已找到 " + page.scanFound + " 首"
        if (page.scanError !== "")
            return "上次扫描出错：" + page.scanError
        return "媒体库共 " + page.songCount + " 首歌曲"
    }

    function chooseMusicFolder() {
        try {
            musicFolderDialog.open()
        } catch (error) {
            Toast.show("无法打开文件夹选择器：" + error, "error")
        }
    }

    function addMusicFolder(path) {
        if (!path)
            return
        Api.call("library.addFolder", { path: path }).then(function(result) {
            if (result && result.settings)
                Settings.values = result.settings
            Toast.show("已添加文件夹：" + path, "info")
        }).catch(function(error) {
            Toast.show("添加文件夹失败：" + (error && error.message ? error.message : "未知错误"), "error")
        })
    }

    function removeMusicFolder(path) {
        Api.call("library.removeFolder", { path: path }).then(function(result) {
            if (result && result.settings)
                Settings.values = result.settings
            Toast.show("已移除文件夹", "info")
        }).catch(function(error) {
            Toast.show("移除文件夹失败：" + (error && error.message ? error.message : "未知错误"), "error")
        })
    }

    function rescanLibrary() {
        page.scanning = true
        page.scanError = ""
        Api.call("library.scan").then(function(result) {
            page.scanning = false
            if (result && result.count !== undefined)
                page.songCount = result.count
            Toast.show("扫描完成，共 " + (result && result.count !== undefined ? result.count : 0) + " 首歌曲", "info")
        }).catch(function(error) {
            page.scanning = false
            Toast.show("扫描失败：" + (error && error.message ? error.message : "未知错误"), "error")
        })
    }

    /** 清空本机封面缓存（后端 artwork.clearCache）。 */
    function clearArtworkCache() {
        page.clearingCache = true
        Api.call("artwork.clearCache").then(function(result) {
            page.clearingCache = false
            const removed = result && result.removed !== undefined ? Number(result.removed) : 0
            Toast.show("已清除 " + removed + " 个封面缓存文件", "info")
        }).catch(function(error) {
            page.clearingCache = false
            Toast.show("清除缓存失败：" + (error && error.message ? error.message : "未知错误"), "error")
        })
    }

    // ---------------------------------------------------------------- 下载

    function chooseDownloadFolder() {
        try {
            downloadFolderDialog.open()
        } catch (error) {
            Toast.show("无法打开文件夹选择器：" + error, "error")
        }
    }

    // ---------------------------------------------------------------- 账号

    function applyAccounts(list) {
        // C++ 桥现在会下发真正的 JS 数组；同时兼容旧式 QVariantList 序列对象。
        const result = []
        if (!list)
            return void (page.accounts = result)
        for (let i = 0; i < list.length; ++i)
            result.push(list[i])
        page.accounts = result
    }

    function findAccount(source) {
        for (let i = 0; i < page.accounts.length; i++) {
            const account = page.accounts[i]
            if (account && account.source === source)
                return account
        }
        return null
    }

    function accountName(account) {
        if (!account)
            return "未登录"
        if (account.nickname)
            return account.nickname
        if (account.userId)
            return "UID " + account.userId
        return "已登录"
    }

    function accountDetail(account, fallback) {
        if (!account)
            return fallback
        let text = account.userId ? ("UID " + account.userId) : "已登录"
        if (account.vip)
            text += " · 会员"
        return text
    }

    function loadAccounts() {
        Api.call("login.accounts").then(function(result) {
            page.applyAccounts(result && result.accounts)
        }).catch(function(error) {
            console.warn("[settings] login.accounts failed:", error && error.message)
        })
    }

    function logoutAccount(source) {
        Api.call("login.logout", { source: source }).then(function() {
            Toast.show("已退出登录", "info")
            page.loadAccounts()
        }).catch(function(error) {
            Toast.show("退出登录失败：" + (error && error.message ? error.message : "未知错误"), "error")
        })
    }

    // ---------------------------------------------------------------- 同步

    function syncErrorText(error, prefix) {
        const message = error && error.message ? error.message : "未知错误"
        return prefix + "：" + message
    }

    function handleSyncError(error, prefix) {
        if (error && error.code === "unknown_method") {
            page.syncUnsupported = true
            page.syncStatusMessage = "后端暂不支持同步命令（sync.*）"
            Toast.show("后端暂不支持同步命令", "error")
        } else {
            page.syncStatusMessage = page.syncErrorText(error, prefix)
            Toast.show(page.syncStatusMessage, "error")
        }
    }

    function applySyncConfig(config) {
        if (!config)
            return
        page.syncConfig = config
        tokenField.text = ""
        tokenField.placeholderText = config.tokenSet
                ? "已保存（留空保持不变）"
                : "ghp_xxx（需要 repo 权限）"
        ownerField.text = config.owner ? config.owner : ""
        repoField.text = config.repo ? config.repo : ""
    }

    function loadSyncConfig() {
        Api.call("sync.config").then(function(config) {
            page.syncUnsupported = false
            page.syncStatusMessage = ""
            page.applySyncConfig(config)
        }).catch(function(error) {
            if (error && error.code === "unknown_method") {
                page.syncUnsupported = true
                page.syncStatusMessage = "后端暂不支持同步命令（sync.*）"
            } else {
                page.syncStatusMessage = page.syncErrorText(error, "读取同步配置失败")
            }
        })
    }

    function saveSyncConfig() {
        if (page.syncUnsupported) {
            Toast.show("后端暂不支持同步命令", "error")
            return
        }
        const payload = {
            "owner": ownerField.text.trim(),
            "repo": repoField.text.trim()
        }
        const token = tokenField.text.trim()
        if (token !== "")
            payload.token = token
        page.syncBusy = true
        Api.call("sync.configure", payload).then(function(config) {
            page.syncBusy = false
            page.applySyncConfig(config)
            page.syncStatusMessage = "同步配置已保存"
            Toast.show("同步配置已保存", "info")
            page.loadSyncConfig()
        }).catch(function(error) {
            page.syncBusy = false
            page.handleSyncError(error, "保存同步配置失败")
        })
    }

    function performSync() {
        if (page.syncUnsupported) {
            Toast.show("后端暂不支持同步命令", "error")
            return
        }
        page.syncBusy = true
        page.syncStatusMessage = "正在同步…"
        Api.call("sync.perform").then(function(result) {
            page.syncBusy = false
            const message = result && result.message ? result.message : "同步完成"
            page.syncStatusMessage = message
            Toast.show(message, result && result.success ? "info" : "error")
            page.loadSyncConfig()
        }).catch(function(error) {
            page.syncBusy = false
            page.handleSyncError(error, "同步失败")
        })
    }

    /** 断开同步：清掉本地保存的仓库配置与 Token。 */
    function disableSync() {
        if (page.syncUnsupported) {
            Toast.show("后端暂不支持同步命令", "error")
            return
        }
        page.syncBusy = true
        Api.call("sync.disable").then(function() {
            page.syncBusy = false
            page.syncStatusMessage = ""
            Toast.show("已断开同步", "info")
            page.loadSyncConfig()
        }).catch(function(error) {
            page.syncBusy = false
            page.handleSyncError(error, "断开同步失败")
        })
    }

    function syncConfigValue(key, fallback) {
        const config = page.syncConfig
        if (!config)
            return fallback
        const value = config[key]
        return (value === undefined || value === null) ? fallback : value
    }

    function updateSyncFlag(key, value) {
        if (page.syncUnsupported) {
            Toast.show("后端暂不支持同步命令", "error")
            return
        }
        const payload = {}
        payload[key] = value
        page.syncBusy = true
        Api.call("sync.configure", payload).then(function(config) {
            page.syncBusy = false
            page.applySyncConfig(config)
            Toast.show(value ? "已开启" : "已关闭", "info")
        }).catch(function(error) {
            page.syncBusy = false
            page.handleSyncError(error, "更新同步选项失败")
        })
    }

    readonly property bool syncConfigured: page.syncConfig && page.syncConfig.configured === true

    readonly property string syncStatusText: {
        if (page.syncUnsupported)
            return "后端暂不支持同步命令（sync.*）"
        if (page.syncBusy || (page.syncState && page.syncState.running))
            return "正在同步…"
        if (page.syncStatusMessage !== "")
            return page.syncStatusMessage
        const last = page.syncConfig && page.syncConfig.lastSyncAt
                ? page.formatTime(page.syncConfig.lastSyncAt)
                : (page.syncState && page.syncState.lastSyncAt ? page.formatTime(page.syncState.lastSyncAt) : "")
        let text = last !== "" ? ("上次同步：" + last) : "尚未同步"
        if (page.syncState && page.syncState.message)
            text += " · " + page.syncState.message
        return text
    }

    readonly property color syncStatusColor: {
        if (page.syncUnsupported)
            return Theme.error
        if (page.syncState && page.syncState.success === false && page.syncStatusMessage !== "")
            return Theme.error
        return Theme.onSurfaceVariantColor
    }

    // ---------------------------------------------------------------- 数据加载

    function loadSnapshot() {
        Api.call("app.bootstrap").then(function(snapshot) {
            if (!snapshot)
                return
            if (snapshot.appVersion)
                page.appVersion = snapshot.appVersion
            if (snapshot.library) {
                page.songCount = snapshot.library.songCount !== undefined ? snapshot.library.songCount : page.songCount
                page.applyScanState({
                    "running": snapshot.library.scanning,
                    "total": snapshot.library.scanTotal,
                    "processed": snapshot.library.scanProcessed,
                    "found": snapshot.library.scanFound
                })
            }
            if (snapshot.accounts)
                page.applyAccounts(snapshot.accounts)
            if (snapshot.sync)
                page.syncState = snapshot.sync
        }).catch(function(error) {
            console.warn("[settings] bootstrap failed:", error && error.message)
        })
    }

    function refreshFromEvents() {
        page.loadSnapshot()
        page.loadAccounts()
        page.loadSyncConfig()
    }

    Component.onCompleted: {
        if (Backend.connected) {
            page.loadSnapshot()
            page.loadAccounts()
            page.loadSyncConfig()
        }

        page._onScan = function(data) {
            page.applyScanState(data)
        }
        page._onLibraryChanged = function(data) {
            if (data && data.songCount !== undefined)
                page.songCount = data.songCount
        }
        page._onAccountsChanged = function(data) {
            page.applyAccounts(data)
        }
        page._onSyncState = function(data) {
            if (!data)
                return
            page.syncState = data
            if (data.message)
                page.syncStatusMessage = ""
        }
        // 截图验证辅助：离屏截图时内容较长，自动滚动到快捷键卡片；正常启动不受影响。
        if (page.cliArgument("--page") === "settings"
                && Qt.application.arguments.indexOf("--screenshot") >= 0)
            shortcutDebugScroll.start()
        Api.on("library.scan", page._onScan)
        Api.on("library.changed", page._onLibraryChanged)
        Api.on("accounts.changed", page._onAccountsChanged)
        Api.on("sync.state", page._onSyncState)
    }

    Component.onDestruction: {
        Api.off("library.scan", page._onScan)
        Api.off("library.changed", page._onLibraryChanged)
        Api.off("accounts.changed", page._onAccountsChanged)
        Api.off("sync.state", page._onSyncState)
    }

    Connections {
        target: Settings
        function onValuesChanged() {
            if (seedField.activeFocus)
                return
            const seed = page.sv("themeSeedColor", "0061A4").toUpperCase()
            if (seedField.text.toUpperCase() !== seed)
                seedField.text = seed
        }
    }

    Connections {
        target: Backend
        function onConnectedChanged() {
            if (Backend.connected)
                page.refreshFromEvents()
        }
    }

    // ---------------------------------------------------------------- 文件夹选择器

    FolderDialog {
        id: musicFolderDialog
        title: "选择音乐文件夹"
        onAccepted: page.addMusicFolder(page.pathFromUrl(selectedFolder))
    }

    FolderDialog {
        id: downloadFolderDialog
        title: "选择下载目录"
        onAccepted: {
            const path = page.pathFromUrl(selectedFolder)
            if (!path)
                return
            page.setSetting("downloadDirectory", path)
            Toast.show("下载目录已设为：" + path, "info")
        }
    }

    // ---------------------------------------------------------------- 页面骨架

    Flickable {
        WheelFlickHandler { }
        id: flick
        anchors.fill: parent
        contentWidth: width
        contentHeight: contentColumn.height
        clip: true
        boundsBehavior: Flickable.StopAtBounds

        Column {
            id: contentColumn
            x: Math.max(0, (flick.width - width) / 2)
            width: Math.min(flick.width, 1180)
            spacing: 12

            // 大标题
            Item {
                width: parent.width
                height: 64

                Text {
                    x: 28
                    anchors.verticalCenter: parent.verticalCenter
                    text: "设置"
                    color: Theme.onSurfaceColor
                    font.family: Theme.fontFamily
                    font.pixelSize: 28
                    font.weight: Font.DemiBold
                }
            }

            // ============================================================ 主题设置
            SettingsSection {
                width: parent.width - 24
                x: 12
                title: "主题设置"
                description: "深浅色、动态取色与调色风格"
                icon: "color_lens"

                SettingsLabel {
                    width: parent.width
                    text: "深浅色模式"
                }

                SegmentedOptions {
                    width: parent.width
                    options: page.darkModeOptions
                    value: page.sv("darkMode", "AUTO")
                    onSelected: function(value) {
                        page.setSetting("darkMode", value)
                    }
                }

                Item {
                    width: 1
                    height: 6
                }

                SettingsNote {
                    width: parent.width
                    text: "选择「跟随系统」时，深浅色与主题强调色都会使用桌面环境（KDE / GNOME）的配置。"
                }

                Item {
                    width: 1
                    height: 6
                }

                SettingsSwitchRow {
                    width: parent.width
                    title: "跟随系统主题色"
                    description: "使用桌面环境的强调色作为主题种子色（优先于下方主题色）"
                    checked: page.sb("followSystemTheme", true)
                    onToggled: function(checked) {
                        page.setSetting("followSystemTheme", checked)
                    }
                }

                SettingsSwitchRow {
                    width: parent.width
                    title: "动态取色（封面）"
                    description: "跟随当前歌曲封面主题色，无封面时回落到下方主题色"
                    checked: page.sb("dynamicColor", false)
                    onToggled: function(checked) {
                        page.setSetting("dynamicColor", checked)
                    }
                }

                Item {
                    width: 1
                    height: 10
                }

                SettingsLabel {
                    width: parent.width
                    text: "预设主题色"
                }

                Row {
                    width: parent.width
                    spacing: 10

                    Repeater {
                        model: page.presetSeedColors

                        delegate: ColorSwatch {
                            required property var modelData
                            swatchSize: 34
                            swatchColor: "#" + modelData
                            enabled: !page.sb("followSystemTheme", true)
                            selected: page.sv("themeSeedColor", "0061A4").toUpperCase() === String(modelData).toUpperCase()
                            onClicked: page.selectPresetSeed(String(modelData))
                        }
                    }
                }

                Item {
                    width: 1
                    height: 6
                }

                SettingsNote {
                    width: parent.width
                    visible: page.sb("followSystemTheme", true)
                    text: "「跟随系统主题色」已开启，预设与自定义主题色暂时不可选；关闭该开关后即可手动指定。"
                }

                Item {
                    width: 1
                    height: 10
                }

                SettingsLabel {
                    width: parent.width
                    text: "自定义主题色（6 位 HEX）"
                }

                Row {
                    width: parent.width
                    spacing: 10

                    SettingsTextField {
                        id: seedField
                        width: Math.min(240, parent.width - 96)
                        text: page.sv("themeSeedColor", "0061A4").toUpperCase()
                        placeholderText: "RRGGBB"
                        enabled: !page.sb("followSystemTheme", true)
                        maximumLength: 7
                    }

                    SettingsButton {
                        text: "应用"
                        enabled: !page.sb("followSystemTheme", true)
                        onClicked: page.applyCustomSeed()
                    }
                }

                Item {
                    width: 1
                    height: 12
                }

                SettingsLabel {
                    width: parent.width
                    text: "调色风格"
                }

                SegmentedOptions {
                    width: parent.width
                    options: page.paletteStyles.map(function(item) {
                        return { "value": item.value, "label": item.label }
                    })
                    value: page.sv("paletteStyle", "TonalSpot")
                    onSelected: function(value) {
                        page.setSetting("paletteStyle", value)
                    }
                }

                Item {
                    width: 1
                    height: 4
                }

                SettingsNote {
                    width: parent.width
                    text: page.paletteDescription(page.sv("paletteStyle", "TonalSpot"))
                }

                Item {
                    width: 1
                    height: 12
                }

                SettingsLabel {
                    width: parent.width
                    text: "色彩规范"
                }

                SegmentedOptions {
                    width: parent.width
                    options: page.colorSpecOptions
                    value: page.sv("colorSpec", "SPEC_2021")
                    onSelected: function(value) {
                        page.setSetting("colorSpec", value)
                    }
                }

                Item {
                    width: 1
                    height: 12
                }

                SettingsLabel {
                    width: parent.width
                    text: "界面缩放"
                }

                SegmentedOptions {
                    width: parent.width
                    options: page.uiScaleOptions
                    value: page.sv("uiScale", 0)
                    onSelected: function(value) {
                        page.setSetting("uiScale", value)
                    }
                }

                Item {
                    width: 1
                    height: 4
                }

                SettingsNote {
                    width: parent.width
                    text: "界面立即按比例缩放，窗口尺寸在下次启动时生效。桌面本身已开启缩放时（例如 KDE 200%），可在此手动指定。"
                }

                Item {
                    width: 1
                    height: 6
                }

                // 与旧 Compose 版一致：展示当前实际生效的缩放/布局/屏幕信息
                SettingsInfoRow {
                    width: parent.width
                    label: "当前生效"
                    value: page.effectiveScaleText()
                }

                SettingsInfoRow {
                    width: parent.width
                    label: "布局"
                    value: wide ? "横屏（宽窗口，内容铺满 + 多栏）" : "紧凑（窄窗口，单栏）"
                }

                SettingsInfoRow {
                    width: parent.width
                    label: "屏幕"
                    value: page.screenText()
                }
            }

            // ============================================================ 媒体库
            SettingsSection {
                width: parent.width - 24
                x: 12
                title: "媒体库"
                description: "本地音乐文件夹与扫描"
                icon: "library_music"

                SettingsLabel {
                    width: parent.width
                    text: "音乐文件夹"
                }

                FolderList {
                    width: parent.width
                    folders: page.sv("musicFolders", [])
                    onAddRequested: page.chooseMusicFolder()
                    onRemoveRequested: function(path) {
                        page.removeMusicFolder(path)
                    }
                    onRescanRequested: page.rescanLibrary()
                }

                Item {
                    width: 1
                    height: 8
                }

                Text {
                    width: parent.width
                    text: page.libraryStatusText
                    color: page.scanError !== "" ? Theme.error : Theme.onSurfaceVariantColor
                    font.family: Theme.fontFamily
                    font.pixelSize: 12
                    font.weight: Font.Medium
                    wrapMode: Text.Wrap
                }

                Item {
                    width: 1
                    height: 8
                }

                SettingsRow {
                    width: parent.width
                    title: "封面缓存"
                    description: "在线歌曲封面会缓存在本机，占用空间异常时可清空"

                    SettingsButton {
                        text: page.clearingCache ? "清理中…" : "清除缓存"
                        enabled: !page.clearingCache
                        onClicked: page.clearArtworkCache()
                    }
                }
            }

            // ============================================================ 播放
            SettingsSection {
                width: parent.width - 24
                x: 12
                title: "播放"
                description: "默认倍速、变调与音效开关"
                icon: "graphic_eq"

                SliderRow {
                    width: parent.width
                    label: "默认播放速度"
                    externalValue: page.sv("playbackSpeed", 1.0)
                    from: 0.5
                    to: 3.0
                    formatValue: function(value) {
                        return Number(value).toFixed(2) + "x"
                    }
                    onCommitted: function(value) {
                        Player.setSpeed(value)
                    }
                }

                SliderRow {
                    width: parent.width
                    label: "音调"
                    externalValue: page.sv("pitchSemitone", 0)
                    from: -12
                    to: 12
                    formatValue: function(value) {
                        const rounded = Number(value).toFixed(1)
                        return (value > 0 ? "+" : "") + rounded + " 半音"
                    }
                    onCommitted: function(value) {
                        Player.setPitch(value)
                    }
                }

                SettingsSwitchRow {
                    width: parent.width
                    title: "响度增强"
                    description: "使用动态归一化提升整体响度（需要 ffmpeg 音效支持）"
                    checked: page.sb("loudnessEnhancer", false)
                    onToggled: function(checked) {
                        Player.setLoudness(checked)
                    }
                }

                SettingsSwitchRow {
                    width: parent.width
                    title: "均衡器"
                    description: "启用十段均衡器，可在播放页的音效面板中细调"
                    checked: page.sb("equalizerEnabled", false)
                    onToggled: function(checked) {
                        Player.setEqualizerEnabled(checked)
                    }
                }

                Item {
                    width: 1
                    height: 4
                }

                SettingsNote {
                    width: parent.width
                    text: "倍速、变调与响度增强需要后端具备 ffmpeg 支持；不可用时设置仍会保存，下次具备条件时生效。"
                }
            }

            // ============================================================ 下载设置
            SettingsSection {
                width: parent.width - 24
                x: 12
                title: "下载设置"
                description: "下载目录、音质与并发数"
                icon: "download"

                SettingsRow {
                    width: parent.width
                    title: "下载目录"
                    description: {
                        const directory = page.sv("downloadDirectory", "")
                        return directory !== "" ? directory : "默认：~/Music/NeriPlayer"
                    }

                    SettingsButton {
                        text: "选择目录"
                        onClicked: page.chooseDownloadFolder()
                    }

                    SettingsButton {
                        text: "恢复默认"
                        onClicked: page.setSetting("downloadDirectory", "")
                    }
                }

                Item {
                    width: 1
                    height: 10
                }

                SettingsLabel {
                    width: parent.width
                    text: "下载音质"
                }

                SegmentedOptions {
                    width: parent.width
                    options: page.qualityOptions
                    value: page.sv("downloadQuality", "exhigh")
                    onSelected: function(value) {
                        page.setSetting("downloadQuality", value)
                    }
                }

                Item {
                    width: 1
                    height: 12
                }

                SettingsLabel {
                    width: parent.width
                    text: "并发下载"
                }

                SegmentedOptions {
                    width: parent.width
                    options: page.concurrencyOptions
                    value: page.sv("downloadConcurrency", 2)
                    onSelected: function(value) {
                        page.setSetting("downloadConcurrency", value)
                    }
                }

                Item {
                    width: 1
                    height: 6
                }

                SettingsSwitchRow {
                    width: parent.width
                    title: "下载完成时通知"
                    description: "全部任务完成后发送系统通知"
                    checked: page.sb("downloadNotifyOnComplete", true)
                    onToggled: function(checked) {
                        page.setSetting("downloadNotifyOnComplete", checked)
                    }
                }

                Item {
                    width: 1
                    height: 4
                }

                SettingsButton {
                    text: "打开下载管理"
                    icon: "download"
                    onClicked: Nav.openDownloadsPanel()
                }
            }

            // ============================================================ 悬浮歌词
            SettingsSection {
                width: parent.width - 24
                x: 12
                title: "悬浮歌词"
                description: "桌面悬浮歌词的样式与配色"
                icon: "lyrics"

                SettingsSwitchRow {
                    width: parent.width
                    title: "启用悬浮歌词"
                    description: "在桌面上显示轻量悬浮歌词（快捷键 Ctrl+L）"
                    checked: page.sb("floatingLyricsEnabled", false)
                    onToggled: function(checked) {
                        page.setSetting("floatingLyricsEnabled", checked)
                    }
                }

                SettingsSwitchRow {
                    width: parent.width
                    title: "应用内隐藏"
                    description: "主窗口获得焦点时隐藏悬浮歌词，避免遮挡页面"
                    checked: page.sb("floatingLyricsHideInApp", false)
                    onToggled: function(checked) {
                        page.setSetting("floatingLyricsHideInApp", checked)
                    }
                }

                SettingsSwitchRow {
                    width: parent.width
                    title: "锁定位置"
                    description: "锁定后无法拖动悬浮歌词"
                    checked: page.sb("floatingLyricsLocked", false)
                    onToggled: function(checked) {
                        page.setSetting("floatingLyricsLocked", checked)
                    }
                }

                SettingsSwitchRow {
                    width: parent.width
                    title: "显示翻译"
                    description: "有翻译歌词时一起显示在第二行"
                    checked: page.sb("floatingLyricsShowTranslation", true)
                    onToggled: function(checked) {
                        page.setSetting("floatingLyricsShowTranslation", checked)
                    }
                }

                Item {
                    width: 1
                    height: 8
                }

                SliderRow {
                    width: parent.width
                    label: "字号"
                    externalValue: page.sv("floatingLyricsFontSize", 30)
                    from: 14
                    to: 72
                    decimals: 0
                    formatValue: function(value) {
                        return Math.round(value) + " px"
                    }
                    onCommitted: function(value) {
                        page.setSetting("floatingLyricsFontSize", value)
                    }
                }

                Item {
                    width: 1
                    height: 6
                }

                SettingsLabel {
                    width: parent.width
                    text: "渲染方式"
                }

                SegmentedOptions {
                    width: parent.width
                    options: page.renderStyleOptions
                    value: page.sv("floatingLyricsRenderStyle", "SHADOW")
                    onSelected: function(value) {
                        page.setSetting("floatingLyricsRenderStyle", value)
                    }
                }

                Item {
                    width: 1
                    height: 10
                }

                SettingsLabel {
                    width: parent.width
                    text: "歌词颜色"
                }

                Row {
                    width: parent.width
                    spacing: Theme.spacingSm

                    Repeater {
                        model: page.floatingLyricColors

                        delegate: ColorSwatch {
                            required property var modelData
                            swatchSize: 28
                            swatchColor: modelData.color
                            selected: page.sv("floatingLyricsTextColor", "WHITE") === modelData.name
                            onClicked: page.setSetting("floatingLyricsTextColor", modelData.name)
                        }
                    }
                }

                Item {
                    width: 1
                    height: 8
                }

                SliderRow {
                    width: parent.width
                    label: "主歌词不透明度"
                    externalValue: page.sv("floatingLyricsLyricAlpha", 1.0)
                    from: 0.1
                    to: 1.0
                    formatValue: function(value) {
                        return Math.round(value * 100) + "%"
                    }
                    onCommitted: function(value) {
                        page.setSetting("floatingLyricsLyricAlpha", value)
                    }
                }

                SliderRow {
                    width: parent.width
                    label: "翻译不透明度"
                    externalValue: page.sv("floatingLyricsTranslationAlpha", 0.75)
                    from: 0.1
                    to: 1.0
                    formatValue: function(value) {
                        return Math.round(value * 100) + "%"
                    }
                    onCommitted: function(value) {
                        page.setSetting("floatingLyricsTranslationAlpha", value)
                    }
                }

                Item {
                    width: 1
                    height: 8
                }

                SettingsLabel {
                    width: parent.width
                    text: "背景颜色"
                }

                Row {
                    width: parent.width
                    spacing: Theme.spacingSm

                    Repeater {
                        model: page.floatingLyricColors

                        delegate: ColorSwatch {
                            required property var modelData
                            swatchSize: 28
                            swatchColor: modelData.color
                            selected: page.sv("floatingLyricsBackgroundColor", "BLACK") === modelData.name
                            onClicked: page.setSetting("floatingLyricsBackgroundColor", modelData.name)
                        }
                    }
                }

                Item {
                    width: 1
                    height: 8
                }

                SliderRow {
                    width: parent.width
                    label: "背景不透明度"
                    externalValue: page.sv("floatingLyricsBackgroundAlpha", 0.35)
                    from: 0
                    to: 1
                    formatValue: function(value) {
                        return Math.round(value * 100) + "%"
                    }
                    onCommitted: function(value) {
                        page.setSetting("floatingLyricsBackgroundAlpha", value)
                    }
                }

                Item {
                    width: 1
                    height: 6
                }

                SettingsLabel {
                    width: parent.width
                    text: "对齐方式"
                }

                SegmentedOptions {
                    width: parent.width
                    options: page.alignmentOptions
                    value: page.sv("floatingLyricsAlignment", "CENTER")
                    onSelected: function(value) {
                        page.setSetting("floatingLyricsAlignment", value)
                    }
                }

                Item {
                    width: 1
                    height: 6
                }

                SettingsNote {
                    width: parent.width
                    text: "悬浮歌词是独立的桌面窗口；未锁定时可直接拖动，锁定时鼠标穿透（Ctrl+Alt+L 解锁）。"
                }
            }

            // ============================================================ 歌词与显示
            SettingsSection {
                width: parent.width - 24
                x: 12
                title: "歌词与显示"
                description: "歌词翻译、封面歌词与字号"
                icon: "subtitles"

                SettingsSwitchRow {
                    width: parent.width
                    title: "显示歌词翻译"
                    description: "在线歌词包含翻译时同时显示"
                    checked: page.sb("showLyricTranslation", true)
                    onToggled: function(checked) { page.setSetting("showLyricTranslation", checked) }
                }

                SettingsSwitchRow {
                    width: parent.width
                    title: "封面页显示歌词"
                    description: "在播放页封面下方显示逐行歌词"
                    checked: page.sb("coverShowsLyrics", true)
                    onToggled: function(checked) { page.setSetting("coverShowsLyrics", checked) }
                }

                SettingsSwitchRow {
                    width: parent.width
                    title: "显示“正在播放”标题"
                    description: "控制播放页顶部是否显示标题"
                    checked: page.sb("showNowPlayingTitle", true)
                    onToggled: function(checked) { page.setSetting("showNowPlayingTitle", checked) }
                }

                SettingsSwitchRow {
                    width: parent.width
                    title: "滚动显示长歌曲名"
                    description: "标题超出宽度时平滑滚动"
                    checked: page.sb("songTitleMarquee", true)
                    onToggled: function(checked) { page.setSetting("songTitleMarquee", checked) }
                }

                Item {
                    width: 1
                    height: 4
                }

                SliderRow {
                    width: parent.width
                    label: "歌词字号 " + Number(page.sv("lyricsFontScale", 1.0)).toFixed(2) + "x"
                    from: 0.7
                    to: 1.8
                    decimals: 2
                    externalValue: Number(page.sv("lyricsFontScale", 1.0))
                    onCommitted: function(value) { page.setSetting("lyricsFontScale", value) }
                }
            }

            // ============================================================ 首页栏目
            SettingsSection {
                width: parent.width - 24
                x: 12
                title: "首页栏目"
                description: "选择首页展示哪些推荐栏目"
                icon: "dashboard"

                SettingsSwitchRow {
                    width: parent.width
                    title: "继续播放"
                    description: "最近播放的歌曲"
                    checked: page.homeCardEnabled("continuePlaying", true)
                    onToggled: function(checked) { page.setHomeCard("continuePlaying", checked) }
                }

                SettingsSwitchRow {
                    width: parent.width
                    title: "为你推荐"
                    description: "根据口味生成的推荐歌单"
                    checked: page.homeCardEnabled("recommended", true)
                    onToggled: function(checked) { page.setHomeCard("recommended", checked) }
                }

                SettingsSwitchRow {
                    width: parent.width
                    title: "热歌榜"
                    description: "热门歌曲榜单"
                    checked: page.homeCardEnabled("hotTracks", true)
                    onToggled: function(checked) { page.setHomeCard("hotTracks", checked) }
                }

                SettingsSwitchRow {
                    width: parent.width
                    title: "私人雷达"
                    description: "个性化探索"
                    checked: page.homeCardEnabled("radar", true)
                    onToggled: function(checked) { page.setHomeCard("radar", checked) }
                }
            }

            // ============================================================ 后台与系统控制
            SettingsSection {
                width: parent.width - 24
                x: 12
                title: "后台与系统控制"
                description: "托盘、通知与系统媒体控制"
                icon: "desktop_windows"

                SettingsSwitchRow {
                    width: parent.width
                    title: "关闭窗口时最小化到托盘"
                    description: "关闭窗口只是隐藏，音乐继续播放；点击托盘图标可打开控制面板"
                    checked: page.sb("closeToTray", true)
                    onToggled: function(checked) {
                        page.setSetting("closeToTray", checked)
                    }
                }

                SettingsSwitchRow {
                    width: parent.width
                    title: "最小化时隐藏到托盘"
                    description: "把窗口最小化时收进托盘，继续后台播放"
                    checked: page.sb("minimizeToTray", true)
                    onToggled: function(checked) {
                        page.setSetting("minimizeToTray", checked)
                    }
                }

                SettingsSwitchRow {
                    width: parent.width
                    title: "切歌时发送系统通知"
                    description: "仅当窗口隐藏 / 最小化时提示，避免打扰"
                    checked: page.sb("notifyOnSongChange", true)
                    onToggled: function(checked) {
                        page.setSetting("notifyOnSongChange", checked)
                    }
                }

                SettingsSwitchRow {
                    width: parent.width
                    title: "启用系统媒体控制（MPRIS）"
                    description: "注册 org.mpris.MediaPlayer2.neriplayer，可用媒体键与桌面媒体组件控制"
                    checked: page.sb("mprisEnabled", true)
                    onToggled: function(checked) {
                        page.setSetting("mprisEnabled", checked)
                    }
                }

                SettingsSwitchRow {
                    width: parent.width
                    title: "启动时恢复上次队列"
                    description: "打开应用后继续显示上次的播放队列与进度"
                    checked: page.sb("resumeLastQueue", true)
                    onToggled: function(checked) {
                        page.setSetting("resumeLastQueue", checked)
                    }
                }

                Item {
                    width: 1
                    height: 4
                }

                SettingsNote {
                    width: parent.width
                    text: "托盘与 MPRIS 需要桌面环境提供系统托盘 / D-Bus 会话总线；无托盘的极简桌面下关闭窗口将直接退出。"
                }
            }

            // ============================================================ 在线音源
            SettingsSection {
                width: parent.width - 24
                x: 12
                title: "在线音源"
                description: "网易云、哔哩哔哩与 YouTube 开关"
                icon: "cloud"

                SettingsSwitchRow {
                    width: parent.width
                    title: "网易云音乐"
                    description: "搜索、推荐、播放与歌词（无需登录即可使用公开接口）"
                    checked: page.sb("neteaseEnabled", true)
                    onToggled: function(checked) { page.setSetting("neteaseEnabled", checked) }
                }

                SettingsSwitchRow {
                    width: parent.width
                    title: "哔哩哔哩"
                    description: "视频搜索与音频轨道播放"
                    checked: page.sb("bilibiliEnabled", true)
                    onToggled: function(checked) { page.setSetting("bilibiliEnabled", checked) }
                }

                SettingsSwitchRow {
                    width: parent.width
                    title: "YouTube Music"
                    description: "桌面端暂未接入（需要账号授权与专用解析）"
                    checked: false
                    enabled: false
                    onToggled: function(checked) {}
                }
            }

            // ============================================================ 账号
            SettingsSection {
                width: parent.width - 24
                x: 12
                title: "账号"
                description: "网易云与哔哩哔哩扫码登录"
                icon: "account_circle"

                SettingsRow {
                    width: parent.width
                    title: {
                        const account = page.findAccount("NETEASE")
                        return account ? page.accountName(account) : "网易云音乐"
                    }
                    description: page.accountDetail(page.findAccount("NETEASE"),
                                                   "未登录：仅能使用公开搜索、推荐与试听音质")

                    SettingsButton {
                        text: page.findAccount("NETEASE") ? "退出登录" : "扫码登录"
                        onClicked: {
                            if (page.findAccount("NETEASE"))
                                page.logoutAccount("NETEASE")
                            else
                                Nav.openLoginPanel("NETEASE")
                        }
                    }
                }

                SettingsRow {
                    width: parent.width
                    title: {
                        const account = page.findAccount("BILIBILI")
                        return account ? page.accountName(account) : "哔哩哔哩"
                    }
                    description: page.accountDetail(page.findAccount("BILIBILI"),
                                                   "未登录：可搜索与播放视频音频轨，收藏夹需要登录")

                    SettingsButton {
                        text: page.findAccount("BILIBILI") ? "退出登录" : "扫码登录"
                        onClicked: {
                            if (page.findAccount("BILIBILI"))
                                page.logoutAccount("BILIBILI")
                            else
                                Nav.openLoginPanel("BILIBILI")
                        }
                    }
                }

                Item {
                    width: 1
                    height: 6
                }

                SettingsNote {
                    width: parent.width
                    text: "登录后可浏览收藏与我的歌单。登录凭据（Cookie）仅保存在本机配置目录。"
                }
            }

            // ============================================================ 同步
            SettingsSection {
                width: parent.width - 24
                x: 12
                title: "同步"
                description: "通过自己的 GitHub 仓库跨设备同步歌单、收藏、最近播放与统计"
                icon: "sync"

                SettingsRow {
                    width: parent.width
                    title: page.syncUnsupported
                           ? "后端暂不支持"
                           : (page.syncConfigured
                              ? "已配置：" + (page.syncConfig.repoFullName
                                             ? page.syncConfig.repoFullName
                                             : ((page.syncConfig.owner || "") + "/" + (page.syncConfig.repo || "")))
                              : "未配置")
                    description: page.syncStatusText
                    descriptionColor: page.syncStatusColor

                    SettingsButton {
                        text: page.syncBusy ? "处理中…" : "立即同步"
                        enabled: !page.syncBusy && !page.syncUnsupported
                        onClicked: page.performSync()
                    }

                    SettingsButton {
                        text: "断开同步"
                        enabled: !page.syncBusy && page.syncConfigured
                        onClicked: page.disableSync()
                    }
                }

                Item {
                    width: 1
                    height: 10
                    visible: !page.syncUnsupported
                }

                SettingsLabel {
                    width: parent.width
                    text: "GitHub Token"
                    visible: !page.syncUnsupported
                }

                SettingsTextField {
                    id: tokenField
                    width: parent.width
                    visible: !page.syncUnsupported
                    placeholderText: "ghp_xxx（需要 repo 权限）"
                    echoMode: TextInput.Password
                }

                SettingsNote {
                    width: parent.width
                    visible: !page.syncUnsupported
                    text: "在 GitHub → Settings → Developer settings → Personal access tokens 生成 Token，建议 fine-grained 且只授权同步仓库。Token 只保存在本机，回显时打码。"
                }

                Item {
                    width: 1
                    height: 10
                    visible: !page.syncUnsupported
                }

                SettingsLabel {
                    width: parent.width
                    text: "仓库账号 / 组织"
                    visible: !page.syncUnsupported
                }

                SettingsTextField {
                    id: ownerField
                    width: parent.width
                    visible: !page.syncUnsupported
                    placeholderText: "GitHub 用户名或组织"
                }

                Item {
                    width: 1
                    height: 8
                    visible: !page.syncUnsupported
                }

                SettingsLabel {
                    width: parent.width
                    text: "仓库名"
                    visible: !page.syncUnsupported
                }

                SettingsTextField {
                    id: repoField
                    width: parent.width
                    visible: !page.syncUnsupported
                    placeholderText: "neriplayer-sync"
                }

                Item {
                    width: 1
                    height: 6
                    visible: !page.syncUnsupported
                }

                SettingsSwitchRow {
                    width: parent.width
                    visible: !page.syncUnsupported
                    title: "自动同步"
                    description: "歌单、收藏、最近播放或统计变化后自动同步"
                    checked: page.syncConfigValue("autoSync", true)
                    onToggled: function(checked) {
                        page.updateSyncFlag("autoSync", checked)
                    }
                }

                SettingsSwitchRow {
                    width: parent.width
                    visible: !page.syncUnsupported
                    title: "省流模式"
                    description: "只同步必要的结构化数据，减少流量占用"
                    checked: page.syncConfigValue("useDataSaver", false)
                    onToggled: function(checked) {
                        page.updateSyncFlag("useDataSaver", checked)
                    }
                }

                Item {
                    width: 1
                    height: 4
                    visible: !page.syncUnsupported
                }

                Row {
                    spacing: Theme.spacingSm
                    visible: !page.syncUnsupported

                    SettingsButton {
                        text: "保存配置"
                        enabled: !page.syncBusy
                        onClicked: page.saveSyncConfig()
                    }

                    SettingsButton {
                        text: "立即同步"
                        enabled: !page.syncBusy && page.syncConfigured
                        onClicked: page.performSync()
                    }
                }
            }

            // ============================================================ 快捷键
            SettingsSection {
                id: shortcutSection
                width: parent.width - 24
                x: 12
                title: "快捷键"
                description: "应用内与全局快捷键"
                icon: "keyboard"

                // 动作表、录制与保存都在 ShortcutEditor 内部完成（读取 Settings.values.shortcuts）。
                ShortcutEditor {
                    width: parent.width
                }
            }

            // ============================================================ 关于
            SettingsSection {
                width: parent.width - 24
                x: 12
                title: "关于"
                description: "版本信息与开源地址"
                icon: "info"

                SettingsInfoRow {
                    width: parent.width
                    label: "应用版本"
                    value: "NeriPlayer Desktop " + page.appVersion
                }

                SettingsInfoRow {
                    width: parent.width
                    label: "前端框架"
                    value: "Qt 6 / QML（Material 3 视觉复刻）"
                }

                SettingsInfoRow {
                    width: parent.width
                    label: "后端"
                    value: "Kotlin 无头后端 · Unix socket 行分隔 JSON"
                }

                SettingsInfoRow {
                    width: parent.width
                    label: "许可证"
                    value: "GNU General Public License v3.0"
                }

                SettingsInfoRow {
                    width: parent.width
                    label: "项目主页"
                    value: "https://github.com/cwuom/NeriPlayer"
                }

                Item {
                    width: 1
                    height: 10
                }

                Row {
                    width: parent.width
                    spacing: Theme.spacingSm

                    SettingsButton {
                        text: "项目主页"
                        icon: "open_in_new"
                        onClicked: Qt.openUrlExternally("https://github.com/cwuom/NeriPlayer")
                    }

                    SettingsButton {
                        text: "Linux 版仓库"
                        onClicked: Qt.openUrlExternally("https://github.com/Asternent/NeriPlayerForLinux")
                    }

                    SettingsButton {
                        text: "问题反馈"
                        onClicked: Qt.openUrlExternally("https://github.com/Asternent/NeriPlayerForLinux/issues")
                    }
                }

                Item {
                    width: 1
                    height: 4
                }

                SettingsNote {
                    width: parent.width
                    text: "本页面所有设置会立即写入后端 settings.json；主题相关修改会触发 material-kolor 重新计算调色板。"
                }
            }

            Item {
                width: 1
                height: 24
            }
        }
    }

    // 截图验证辅助：等动作表加载、布局稳定后再滚动。
    Timer {
        id: shortcutDebugScroll
        interval: 1500
        repeat: false
        onTriggered: page.scrollToDebugTarget()
    }
}
