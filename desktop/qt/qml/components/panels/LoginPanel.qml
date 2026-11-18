import QtQuick
import ".."
import "../player"
import "../library"

// 扫码登录面板：网易云 / 哔哩哔哩共用，二维码图片由后端以 base64 PNG 下发。
// 协议见 desktop/docs/protocol-login.md。
PanelShell {
    id: panel

    // ---- 数据状态 ----
    property string source: "NETEASE"
    property var accounts: []
    property bool accountsLoaded: false
    property string accountsError: ""

    // ---- 扫码状态 ----
    // idle / loading / waiting / scanned / success / expired / error
    property string qrState: "idle"
    property string qrImageSource: ""
    property string qrKey: ""
    property string statusText: ""
    property string qrError: ""

    property bool confirmLogout: false
    property bool _pollBusy: false
    property bool _qrStarting: false
    property bool _loggingOut: false
    property int _sessionToken: 0

    readonly property var currentAccount: {
        for (let i = 0; i < panel.accounts.length; ++i) {
            const account = panel.accounts[i]
            if (account && account.source === panel.source)
                return account
        }
        return null
    }
    readonly property bool loggedIn: panel.currentAccount !== null
    readonly property string sourceLabel: panel.source === "BILIBILI" ? "哔哩哔哩" : "网易云音乐"

    title: "登录"
    cardWidth: 380
    alignRight: false
    cardHeight: Math.max(320, Math.min(panel.height - panel.cardMargin * 2,
                                     panel.titleBarHeight + (body ? body.implicitHeight : 0)))

    // ---------------------------------------------------------------- 定时器

    // 用 property 声明，避免落入 PanelShell 的默认属性（标题栏操作区）
    property Timer pollTimer: Timer {
        interval: 1500
        repeat: true
        onTriggered: panel.poll()
    }

    property Timer successCloseTimer: Timer {
        interval: 900
        repeat: false
        onTriggered: Nav.closePanel()
    }

    // ---------------------------------------------------------------- 事件

    // 面板可能在 Backend 连上之前就被打开（--panel 启动测试），连上后重试失败的加载
    property Connections backendConnection: Connections {
        target: Backend
        function onConnectedChanged() {
            if (Backend.connected)
                panel.retryAfterConnect()
        }
    }

    function retryAfterConnect() {
        panel.loadAccounts().then(function() {
            if (!panel.loggedIn && panel.qrImageSource === "" && panel.qrState !== "loading")
                panel.startQr()
        })
    }

    property var _onAccountsChanged: function(list) {
        // C++ 桥会把事件 data 统一转成对象，数组事件（accounts.changed）拿不到内容，直接重新查询
        panel.loadAccounts().then(function() {
            if (!panel.loggedIn && panel.qrState === "idle" && panel.visible)
                panel.startQr()
        })
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

    function findAccount(list, sourceName) {
        for (let i = 0; i < list.length; ++i) {
            const account = list[i]
            if (account && account.source === sourceName)
                return account
        }
        return null
    }

    function upsertAccount(account) {
        if (!account)
            return
        const next = panel.accounts.slice()
        let replaced = false
        for (let i = 0; i < next.length; ++i) {
            if (next[i] && next[i].source === account.source) {
                next[i] = account
                replaced = true
                break
            }
        }
        if (!replaced)
            next.push(account)
        panel.accounts = next
    }

    function accountName() {
        const account = panel.currentAccount
        if (!account)
            return "未登录"
        if (account.nickname && String(account.nickname) !== "")
            return String(account.nickname)
        if (account.userId && String(account.userId) !== "")
            return "UID " + String(account.userId)
        return "已登录"
    }

    function accountUid() {
        const account = panel.currentAccount
        return account && account.userId ? "UID " + String(account.userId) : ""
    }

    function accountAvatar() {
        const account = panel.currentAccount
        return account && account.avatarUrl ? String(account.avatarUrl) : ""
    }

    function accountVip() {
        return panel.currentAccount !== null && panel.currentAccount.vip === true
    }

    function hintText() {
        switch (panel.qrState) {
        case "loading":
            return "正在向" + panel.sourceLabel + "申请二维码…"
        case "waiting":
            return "请使用「" + panel.sourceLabel + "」App 扫码"
        case "scanned":
            return "在手机端确认后即可完成登录"
        case "expired":
            return "二维码有效期约 2 分钟，请刷新后重试"
        }
        return ""
    }

    // ---------------------------------------------------------------- 账号

    function loadAccounts() {
        return Api.call("login.accounts").then(function(result) {
            panel.accounts = panel.toArray(result ? result.accounts : null)
            panel.accountsLoaded = true
            panel.accountsError = ""
        }).catch(function(error) {
            panel.accountsLoaded = true
            panel.accountsError = "账号信息加载失败：" + panel.messageOf(error)
        })
    }

    function switchSource(nextSource) {
        const normalized = nextSource === "BILIBILI" ? "BILIBILI" : "NETEASE"
        if (panel.source === normalized)
            return
        panel.stopPolling()
        successCloseTimer.stop()
        panel.source = normalized
        Nav.loginSource = normalized
        panel.confirmLogout = false
        panel.resetQrSession()
        if (!panel.loggedIn)
            panel.startQr()
    }

    function logout() {
        if (panel._loggingOut)
            return
        panel._loggingOut = true
        const target = panel.source
        Api.call("login.logout", { source: target }).then(function() {
            panel._loggingOut = false
            panel.confirmLogout = false
            Toast.show("已退出" + (target === "BILIBILI" ? "哔哩哔哩" : "网易云音乐") + "登录", "info")
            panel.accounts = panel.accounts.filter(function(item) {
                return !item || item.source !== target
            })
            panel.loadAccounts()
            panel.startQr()
        }).catch(function(error) {
            panel._loggingOut = false
            panel.confirmLogout = false
            Toast.show("退出登录失败：" + panel.messageOf(error), "error")
        })
    }

    // ---------------------------------------------------------------- 扫码

    function resetQrSession() {
        panel.qrKey = ""
        panel.qrImageSource = ""
        panel.qrError = ""
        panel.statusText = ""
        panel.qrState = "idle"
    }

    function stopPolling() {
        pollTimer.stop()
        panel._pollBusy = false
        panel._sessionToken += 1
        panel.qrKey = ""
    }

    function retryAll() {
        panel.loadAccounts()
        panel.startQr()
    }

    function startQr() {
        if (panel._qrStarting)
            return
        panel._qrStarting = true
        panel.stopPolling()
        const token = ++panel._sessionToken
        panel.confirmLogout = false
        panel.qrState = "loading"
        panel.qrError = ""
        panel.qrImageSource = ""
        panel.statusText = "正在生成二维码…"

        Api.call("login.qrStart", { source: panel.source }).then(function(result) {
            panel._qrStarting = false
            if (token !== panel._sessionToken)
                return
            if (!result || !result.qrImageDataUrl) {
                panel.qrState = "error"
                panel.qrError = "二维码获取失败，请重试"
                panel.statusText = ""
                return
            }
            panel.qrKey = result.key ? String(result.key) : ""
            panel.qrImageSource = String(result.qrImageDataUrl)
            panel.qrState = "waiting"
            panel.statusText = "等待扫码…"
            pollTimer.start()
        }).catch(function(error) {
            panel._qrStarting = false
            if (token !== panel._sessionToken)
                return
            panel.qrState = "error"
            panel.qrError = "二维码获取失败：" + panel.messageOf(error)
            panel.statusText = ""
        })
    }

    function poll() {
        if (panel._pollBusy || panel.qrKey === "")
            return
        if (panel.qrState !== "waiting" && panel.qrState !== "scanned")
            return
        panel._pollBusy = true
        const token = panel._sessionToken
        const roundSource = panel.source

        Api.call("login.qrPoll", { source: roundSource, key: panel.qrKey }).then(function(result) {
            panel._pollBusy = false
            if (token !== panel._sessionToken || roundSource !== panel.source)
                return
            const status = result && result.status ? String(result.status) : "WAITING"
            if (status === "SUCCESS") {
                panel.handleLoginSuccess(result.account)
            } else if (status === "SCANNED") {
                panel.qrState = "scanned"
                panel.statusText = "已扫码，请在手机上确认"
            } else if (status === "WAITING") {
                panel.qrState = "waiting"
                panel.statusText = "等待扫码…"
            } else if (status === "EXPIRED") {
                panel.stopPolling()
                panel.qrState = "expired"
                panel.statusText = "二维码已过期"
            } else {
                panel.stopPolling()
                panel.qrState = "error"
                panel.qrError = result && result.message ? String(result.message) : "登录失败，请重试"
                panel.statusText = ""
            }
        }).catch(function(error) {
            panel._pollBusy = false
            if (token !== panel._sessionToken || roundSource !== panel.source)
                return
            panel.stopPolling()
            panel.qrState = "error"
            panel.qrError = "轮询登录状态失败：" + panel.messageOf(error)
            panel.statusText = ""
        })
    }

    function handleLoginSuccess(account) {
        panel.stopPolling()
        panel.qrState = "success"
        panel.statusText = "登录成功"
        if (account && account.source)
            panel.source = account.source
        Nav.loginSource = panel.source
        panel.upsertAccount(account)
        const label = panel.sourceLabel
        if (account && account.nickname && String(account.nickname) !== "")
            Toast.show("已登录" + label + "：" + String(account.nickname), "info")
        else
            Toast.show("已登录" + label, "info")
        panel.loadAccounts()
        successCloseTimer.start()
    }

    // ---------------------------------------------------------------- 生命周期

    Component.onCompleted: {
        Api.on("accounts.changed", panel._onAccountsChanged)
        panel.source = Nav.loginSource === "BILIBILI" ? "BILIBILI" : "NETEASE"
        Nav.loginSource = panel.source
        panel.loadAccounts().then(function() {
            if (!panel.loggedIn)
                panel.startQr()
        })
    }

    Component.onDestruction: {
        panel.stopPolling()
        successCloseTimer.stop()
        Api.off("accounts.changed", panel._onAccountsChanged)
    }

    onVisibleChanged: {
        if (!panel.visible) {
            panel.stopPolling()
            successCloseTimer.stop()
        }
    }

    // ---------------------------------------------------------------- 界面

    content: Item {
        id: body
        anchors.fill: parent
        implicitHeight: bodyColumn.implicitHeight + 32

        Column {
            id: bodyColumn
            x: 20
            y: 16
            width: body.width - 40
            spacing: 12

            // 平台切换
            Row {
                width: parent.width
                spacing: 8

                PanelButton {
                    text: "网易云音乐"
                    chip: true
                    selected: panel.source === "NETEASE"
                    onClicked: panel.switchSource("NETEASE")
                }

                PanelButton {
                    text: "哔哩哔哩"
                    chip: true
                    selected: panel.source === "BILIBILI"
                    onClicked: panel.switchSource("BILIBILI")
                }
            }

            // 账号信息加载失败提示
            ErrorCard {
                width: parent.width
                // 后端整体不可用时只显示二维码错误卡（重试会同时重试账号加载）
                visible: panel.accountsError !== "" && panel.qrState !== "error"
                message: panel.accountsError
                onRetry: panel.retryAll()
            }

            // ---------------- 未登录：二维码 ----------------
            Column {
                id: qrColumn
                width: parent.width
                visible: !panel.loggedIn
                spacing: 10

                Item {
                    width: parent.width
                    height: 240

                    QrCodeCard {
                        anchors.centerIn: parent
                        loading: panel.qrState === "loading"
                        dimmed: panel.qrState === "expired"
                        imageSource: panel.qrImageSource
                        placeholderText: panel.qrState === "error" ? "二维码生成失败" : ""
                    }
                }

                Text {
                    width: parent.width
                    visible: text !== ""
                    horizontalAlignment: Text.AlignHCenter
                    wrapMode: Text.Wrap
                    text: panel.statusText
                    color: panel.qrState === "expired"
                           ? Theme.error
                           : panel.qrState === "success" ? Theme.primary : Theme.onSurfaceColor
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.textStyle("bodyMedium").size
                    font.weight: panel.qrState === "success" ? Font.Medium : Font.Normal
                }

                Text {
                    width: parent.width
                    visible: text !== ""
                    horizontalAlignment: Text.AlignHCenter
                    wrapMode: Text.Wrap
                    text: panel.qrState === "success" ? "" : panel.hintText()
                    color: Theme.onSurfaceVariantColor
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.textStyle("bodySmall").size
                }

                ErrorCard {
                    width: parent.width
                    visible: panel.qrState === "error"
                    message: panel.qrError
                    onRetry: panel.retryAll()
                }

                Item {
                    width: parent.width
                    height: panel.qrState === "expired" ? 40 : 0

                    PanelButton {
                        anchors.centerIn: parent
                        visible: panel.qrState === "expired"
                        text: "刷新二维码"
                        filled: true
                        onClicked: panel.startQr()
                    }
                }

                Text {
                    width: parent.width
                    horizontalAlignment: Text.AlignHCenter
                    wrapMode: Text.Wrap
                    text: "登录后可访问你的歌单 / 收藏夹，并使用更高音质；账号凭据仅保存在本机配置目录。"
                    color: Theme.withAlpha(Theme.onSurfaceVariantColor, 0.85)
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.textStyle("labelSmall").size
                }
            }

            // ---------------- 已登录：账号卡片 ----------------
            Column {
                id: accountColumn
                width: parent.width
                visible: panel.loggedIn
                spacing: 10

                Item {
                    width: parent.width
                    height: 96

                    CoverArt {
                        anchors.centerIn: parent
                        width: 96
                        height: 96
                        radius: 48
                        placeholderIconSize: 40
                        url: panel.accountAvatar()
                    }
                }

                Text {
                    width: parent.width
                    horizontalAlignment: Text.AlignHCenter
                    elide: Text.ElideRight
                    text: panel.accountName()
                    color: Theme.onSurfaceColor
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.textStyle("titleMedium").size
                    font.weight: Font.DemiBold
                }

                Text {
                    width: parent.width
                    visible: text !== ""
                    horizontalAlignment: Text.AlignHCenter
                    elide: Text.ElideRight
                    text: panel.accountUid()
                    color: Theme.onSurfaceVariantColor
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.textStyle("bodySmall").size
                }

                Item {
                    width: parent.width
                    height: panel.accountVip() ? 24 : 0

                    Rectangle {
                        anchors.centerIn: parent
                        width: vipRow.implicitWidth + 16
                        height: 22
                        radius: 11
                        color: Theme.primaryContainer

                        Row {
                            id: vipRow
                            anchors.centerIn: parent
                            spacing: 4

                            NeriIcon {
                                name: "workspace_premium"
                                iconSize: 14
                                color: Theme.onPrimaryContainerColor
                            }

                            Text {
                                text: "VIP"
                                color: Theme.onPrimaryContainerColor
                                font.family: Theme.fontFamily
                                font.pixelSize: Theme.textStyle("labelSmall").size
                                font.weight: Font.Medium
                            }
                        }
                    }
                }

                Item {
                    width: parent.width
                    height: 40

                    PanelButton {
                        anchors.centerIn: parent
                        visible: !panel.confirmLogout
                        text: "退出登录"
                        outlined: true
                        onClicked: panel.confirmLogout = true
                    }
                }

                Item {
                    width: parent.width
                    height: panel.confirmLogout ? 76 : 0
                    visible: panel.confirmLogout

                    Column {
                        anchors.centerIn: parent
                        spacing: 6

                        Text {
                            anchors.horizontalCenter: parent.horizontalCenter
                            text: "确定退出 " + panel.accountName() + " 吗？"
                            color: Theme.onSurfaceVariantColor
                            font.family: Theme.fontFamily
                            font.pixelSize: Theme.textStyle("bodySmall").size
                        }

                        Row {
                            spacing: 8

                            PanelButton {
                                text: "确认退出"
                                filled: true
                                onClicked: panel.logout()
                            }

                            PanelButton {
                                text: "取消"
                                outlined: true
                                onClicked: panel.confirmLogout = false
                            }
                        }
                    }
                }
            }
        }
    }
}
