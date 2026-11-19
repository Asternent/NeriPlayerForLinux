import QtQuick

// 主窗口内的浮层面板宿主：根据 Nav.activePanel 加载对应面板。
// 具体面板实现放在 components/player/ 下（下载/登录面板在 components/panels/）。
// z 要高于底部迷你播放条与导航栏，遮罩才能铺满整个窗口。
Item {
    id: host

    readonly property string panel: Nav.activePanel
    visible: panel !== ""
    z: 50

    Loader {
        id: panelLoader

        anchors.fill: parent
        active: host.panel !== ""
        source: {
            switch (host.panel) {
            case "queue":
                return "player/QueuePanel.qml"
            case "volume":
                return "player/VolumePanel.qml"
            case "effects":
                return "player/EffectsPanel.qml"
            case "sleep":
                return "player/SleepTimerPanel.qml"
            case "downloads":
                return "panels/DownloadsPanel.qml"
            case "login":
                return "panels/LoginPanel.qml"
            case "comments":
                return "panels/CommentsPanel.qml"
            case "listenTogether":
                return "panels/ListenTogetherPanel.qml"
            case "youtubeLogin":
                return "panels/YouTubeLoginPanel.qml"
            default:
                return ""
            }
        }
    }
}
