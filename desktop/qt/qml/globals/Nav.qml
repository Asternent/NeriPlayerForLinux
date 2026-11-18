import QtQuick

// 页面导航。路由栈最底层的元素一定是 tab；详情页/播放页压在栈顶。
// 路由对象：{ kind: "tab"|"page", name: "home", arg: <任意> }
QtObject {
    id: nav

    property var stack: ([{ "kind": "tab", "name": "home" }])

    readonly property var current: stack.length > 0 ? stack[stack.length - 1] : null
    readonly property string currentName: current ? current.name : ""
    readonly property string currentKind: current ? current.kind : "tab"
    readonly property var currentArg: current ? current.arg : undefined

    readonly property string tab: {
        for (let i = stack.length - 1; i >= 0; i--) {
            if (stack[i].kind === "tab")
                return stack[i].name
        }
        return "home"
    }

    readonly property bool canGoBack: stack.length > 1

    // ---- 主窗口内的浮层面板（队列/音量/音效/睡眠/下载/登录）----
    property string activePanel: ""
    function openPanel(name) { activePanel = name }
    function closePanel() { activePanel = "" }
    function openVolumePanel() { openPanel("volume") }
    function openEffectsPanel() { openPanel("effects") }
    function openQueuePanel() { openPanel("queue") }
    function openSleepPanel() { openPanel("sleep") }
    function openDownloadsPanel() { openPanel("downloads") }
    function openLoginPanel(source) { loginSource = source; openPanel("login") }
    property string loginSource: ""

    function openTab(name) {
        stack = [{ "kind": "tab", "name": name }]
    }

    function push(name, arg) {
        const next = stack.slice()
        next.push({ "kind": "page", "name": name, "arg": arg })
        stack = next
    }

    function replace(name, arg) {
        const next = stack.slice()
        next[next.length - 1] = { "kind": "page", "name": name, "arg": arg }
        stack = next
    }

    function back() {
        if (stack.length > 1)
            stack = stack.slice(0, stack.length - 1)
        else
            openTab("home")
    }

    /** 命名路由（快捷键/测试/通知点击都走这里）。 */
    function openByName(name, arg) {
        switch (name) {
        case "home":
        case "explore":
        case "library":
        case "settings":
            openTab(name)
            break
        case "nowplaying":
        case "recent":
        case "stats":
        case "downloads":
        case "localPlaylist":
        case "onlineCollection":
        case "localArtist":
        case "remoteArtist":
            push(name, arg)
            break
        default:
            console.warn("[nav] unknown route:", name)
        }
    }
}
