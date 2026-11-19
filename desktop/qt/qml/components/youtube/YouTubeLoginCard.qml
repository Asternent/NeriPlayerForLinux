import QtQuick
import QtQuick.Controls
import ".."
import "../library"

// YouTube Music 登录卡（桌面没有 WebView，走“从浏览器复制 Cookie 粘贴”）。
//
// 数据来源：ytmusic.status / ytmusic.setCookies / ytmusic.logout（desktop/docs/protocol-youtube.md）。
// 用法：
//   - 探索页 / 在线音源页内嵌：YouTubeLoginCard { width: … }
//   - 浮层面板：components/panels/YouTubeLoginPanel.qml 包一层 PanelShell 后由集成方挂载。
// 登录成功 / 退出后发出 accountChanged()，父级可据此刷新首页 / 我的歌单。
Item {
    id: card

    /** 父级是否要求紧凑布局（面板里用大间距，页面内嵌时可设 compact=false 默认即可）。 */
    property bool compact: false
    /** 当前账号（AccountInfo），未登录为 null。 */
    property var account: null
    property bool loggedIn: false
    property bool busy: false
    property string errorMessage: ""
    property bool statusLoaded: false
    property int _statusSeq: 0

    /** 登录 / 退出成功后发出（account 属性本身的变化信号名为 accountChanged，不能复用）。 */
    signal loginStateChanged()

    implicitHeight: column.implicitHeight

    readonly property string accountName: {
        if (!card.account)
            return ""
        if (card.account.nickname && String(card.account.nickname) !== "")
            return String(card.account.nickname)
        if (card.account.userId && String(card.account.userId) !== "")
            return String(card.account.userId)
        return "已登录"
    }

    function messageOf(error) {
        if (error && error.message)
            return String(error.message)
        if (typeof error === "string" && error !== "")
            return error
        return "未知错误"
    }

    function refresh() {
        const seq = ++card._statusSeq
        Api.call("ytmusic.status").then(function(result) {
            if (seq !== card._statusSeq)
                return
            const status = result && result.status ? result.status : null
            card.statusLoaded = true
            card.loggedIn = !!(status && status.loggedIn)
            card.account = status && status.account ? status.account : null
            card.errorMessage = ""
        }).catch(function(error) {
            if (seq !== card._statusSeq)
                return
            card.statusLoaded = true
            card.errorMessage = "读取 YouTube 登录状态失败：" + card.messageOf(error)
        })
    }

    function login() {
        const raw = cookieField.text ? String(cookieField.text).trim() : ""
        if (raw === "") {
            card.errorMessage = "请先粘贴浏览器里 youtube.com 的 Cookie"
            return
        }
        if (card.busy)
            return
        card.busy = true
        card.errorMessage = ""
        Api.call("ytmusic.setCookies", { "cookies": raw }).then(function(result) {
            card.busy = false
            cookieField.text = ""
            card.loggedIn = true
            card.account = result && result.account ? result.account : null
            Toast.show("已登录 YouTube Music", "info")
            card.loginStateChanged()
        }).catch(function(error) {
            card.busy = false
            card.errorMessage = "登录失败：" + card.messageOf(error)
        })
    }

    function logout() {
        if (card.busy)
            return
        card.busy = true
        Api.call("ytmusic.logout").then(function() {
            card.busy = false
            card.loggedIn = false
            card.account = null
            Toast.show("已退出 YouTube Music 登录", "info")
            card.loginStateChanged()
        }).catch(function(error) {
            card.busy = false
            card.errorMessage = "退出登录失败：" + card.messageOf(error)
        })
    }

    Component.onCompleted: card.refresh()

    // 页面可能先于后端连接创建：连上后补一次状态读取，避免停在“后端未连接”的错误上。
    Connections {
        target: Backend

        function onConnectedChanged() {
            if (Backend.connected) {
                card.errorMessage = ""
                card.refresh()
            }
        }
    }

    Rectangle {
        id: background

        width: parent.width
        height: column.implicitHeight + (card.compact ? 32 : 40)
        radius: Theme.radiusXl
        color: Theme.surfaceContainerLow

        Column {
            id: column

            x: card.compact ? 16 : 20
            y: card.compact ? 16 : 20
            width: background.width - x * 2
            spacing: 12

            // ---------------- 标题 ----------------
            Row {
                width: parent.width
                spacing: 10

                Rectangle {
                    anchors.verticalCenter: parent.verticalCenter
                    width: 40
                    height: 40
                    radius: Theme.radiusFull
                    color: Theme.withAlpha(Theme.error, 0.12)

                    NeriIcon {
                        anchors.centerIn: parent
                        name: "smart_display"
                        iconSize: 24
                        color: Theme.error
                    }
                }

                Column {
                    anchors.verticalCenter: parent.verticalCenter
                    width: parent.width - 50
                    spacing: 2

                    Text {
                        width: parent.width
                        text: "YouTube Music"
                        color: Theme.onSurfaceColor
                        font.family: Theme.fontFamily
                        font.pixelSize: Theme.textStyle("titleMedium").size
                        font.weight: Font.DemiBold
                    }

                    Text {
                        width: parent.width
                        text: card.loggedIn ? "已登录账号" : "桌面版通过粘贴浏览器 Cookie 登录"
                        color: Theme.onSurfaceVariantColor
                        font.family: Theme.fontFamily
                        font.pixelSize: Theme.textStyle("bodySmall").size
                    }
                }
            }

            // ---------------- 已登录 ----------------
            Rectangle {
                width: parent.width
                height: loggedInRow.implicitHeight + 20
                radius: Theme.radiusLg
                color: Theme.surfaceContainerHigh
                visible: card.loggedIn

                Row {
                    id: loggedInRow

                    anchors.left: parent.left
                    anchors.right: parent.right
                    anchors.verticalCenter: parent.verticalCenter
                    anchors.leftMargin: 14
                    anchors.rightMargin: 10
                    spacing: 10

                    NeriIcon {
                        anchors.verticalCenter: parent.verticalCenter
                        name: "account_circle"
                        iconSize: 26
                        color: Theme.primary
                    }

                    Text {
                        anchors.verticalCenter: parent.verticalCenter
                        width: Math.max(0, parent.width - 170)
                        text: card.accountName
                        elide: Text.ElideRight
                        color: Theme.onSurfaceColor
                        font.family: Theme.fontFamily
                        font.pixelSize: Theme.textStyle("bodyMedium").size
                    }

                    LibraryButton {
                        anchors.verticalCenter: parent.verticalCenter
                        text: card.busy ? "退出中…" : "退出登录"
                        onClicked: card.logout()
                    }
                }
            }

            // ---------------- 未登录：粘贴 Cookie ----------------
            Column {
                width: parent.width
                spacing: 10
                visible: !card.loggedIn

                Text {
                    width: parent.width
                    text: "从浏览器登录 music.youtube.com 后，按 F12 → Network（网络）→ 任选一个请求，" +
                          "复制请求头里的 Cookie 整行粘贴到下面。桌面版不会打开网页，因此不支持扫码 / 账号密码登录。"
                    wrapMode: Text.Wrap
                    color: Theme.onSurfaceVariantColor
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.textStyle("bodySmall").size
                }

                Rectangle {
                    width: parent.width
                    height: 92
                    radius: Theme.radiusMd
                    color: Theme.surfaceContainerHigh
                    border.width: cookieField.activeFocus ? 1 : 0
                    border.color: Theme.primary

                    ScrollView {
                        anchors.fill: parent
                        anchors.margins: 6
                        clip: true

                        TextArea {
                            id: cookieField

                            placeholderText: "SAPISID=…; SID=…; HSID=…; LOGIN_INFO=…"
                            placeholderTextColor: Theme.withAlpha(Theme.onSurfaceVariantColor, 0.7)
                            color: Theme.onSurfaceColor
                            font.family: Theme.fontFamily
                            font.pixelSize: Theme.textStyle("bodySmall").size
                            wrapMode: TextArea.WrapAnywhere
                            selectByMouse: true
                            background: null
                            padding: 0
                        }
                    }
                }

                Row {
                    width: parent.width
                    spacing: 10

                    LibraryButton {
                        text: card.busy ? "登录中…" : "粘贴并登录"
                        icon: "login"
                        filled: true
                        onClicked: card.login()
                    }

                    LibraryButton {
                        text: "从剪贴板粘贴"
                        icon: "content_paste"
                        onClicked: {
                            cookieField.forceActiveFocus()
                            cookieField.paste()
                        }
                    }
                }
            }

            ErrorCard {
                width: parent.width
                visible: card.errorMessage !== ""
                message: card.errorMessage
                onRetry: {
                    card.errorMessage = ""
                    if (card.statusLoaded && !card.loggedIn)
                        card.refresh()
                }
            }

            Text {
                width: parent.width
                visible: !card.loggedIn
                text: "Cookie 仅保存在本机配置目录（accounts.json，权限 600）。" +
                      "登录后可使用「我的歌单」、个性化首页与播放直链；未登录时只能匿名搜索与浏览。"
                wrapMode: Text.Wrap
                color: Theme.withAlpha(Theme.onSurfaceVariantColor, 0.85)
                font.family: Theme.fontFamily
                font.pixelSize: Theme.textStyle("labelSmall").size
            }
        }
    }
}
