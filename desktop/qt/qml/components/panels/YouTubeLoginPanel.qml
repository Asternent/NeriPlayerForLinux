import QtQuick
import ".."
import "../youtube"

// YouTube Music 登录浮层：桌面没有 WebView，只能“从浏览器复制 Cookie 粘贴”。
//
// 挂载方式（集成方）：PlayerOverlays 里按 Nav.activePanel === "youtubeLogin" 加载
// `components/panels/YouTubeLoginPanel.qml`，入口 `Nav.openPanel("youtubeLogin")`。
// 协议：desktop/docs/protocol-youtube.md（ytmusic.status / setCookies / logout）。
PanelShell {
    id: panel

    title: "YouTube Music 账号"
    cardWidth: 460
    alignRight: false
    cardHeight: Math.max(360, Math.min(panel.height - panel.cardMargin * 2,
                                     panel.titleBarHeight + (body ? body.implicitHeight : 0)))

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

            YouTubeLoginCard {
                id: loginCard

                width: parent.width
                compact: false
                onLoginStateChanged: Nav.closePanel()
            }
        }
    }
}
