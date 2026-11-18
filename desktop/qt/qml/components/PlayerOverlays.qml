import QtQuick

// 主窗口内的浮层面板宿主：根据 Nav.activePanel 渲染对应面板。
// 具体面板实现放在 components/player/ 下，由播放器页面负责 agent 维护。
Item {
    id: host

    readonly property string panel: Nav.activePanel
    visible: panel !== ""
}
