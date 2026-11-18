import QtQuick
import "../"

// 快捷键展示：应用内 / 全局两列，读取 Settings.values.shortcuts（未覆盖的动作显示默认键位）。
// 目前只做展示，键位录制交互待后续接入（写入 shortcuts.local / shortcuts.global 即可）。
Item {
    id: root

    property var localBindings: ({})
    property var globalBindings: ({})

    width: parent ? parent.width : 0
    implicitHeight: layout.height

    // 与 core/Shortcuts.kt 的 ShortcutAction 保持一致。
    readonly property var actions: [
        { id: "play_pause", label: "播放 / 暂停", category: "播放控制", systemKey: true, globalSupported: true, defaultLocal: "Space" },
        { id: "next_song", label: "下一首", category: "播放控制", systemKey: false, globalSupported: true, defaultLocal: "媒体下一首" },
        { id: "previous_song", label: "上一首", category: "播放控制", systemKey: false, globalSupported: true, defaultLocal: "媒体上一首" },
        { id: "volume_up", label: "音量 +", category: "播放控制", systemKey: false, globalSupported: true, defaultLocal: "Ctrl+↑" },
        { id: "volume_down", label: "音量 −", category: "播放控制", systemKey: false, globalSupported: true, defaultLocal: "Ctrl+↓" },
        { id: "seek_forward", label: "快进 5 秒", category: "播放控制", systemKey: false, globalSupported: false, defaultLocal: "Ctrl+→" },
        { id: "seek_backward", label: "后退 5 秒", category: "播放控制", systemKey: false, globalSupported: false, defaultLocal: "Ctrl+←" },
        { id: "toggle_floating_lyrics", label: "开关悬浮歌词", category: "功能与窗口", systemKey: false, globalSupported: true, defaultLocal: "Ctrl+L" },
        { id: "toggle_floating_lyrics_lock", label: "固定 / 取消固定歌词", category: "功能与窗口", systemKey: false, globalSupported: true, defaultLocal: "Ctrl+Alt+L" },
        { id: "open_now_playing", label: "打开播放页", category: "功能与窗口", systemKey: false, globalSupported: true, defaultLocal: "Ctrl+P" },
        { id: "toggle_tray_panel", label: "打开 / 关闭控制面板", category: "功能与窗口", systemKey: false, globalSupported: true, defaultLocal: "Ctrl+M" },
        { id: "toggle_downloads", label: "打开下载管理", category: "功能与窗口", systemKey: false, globalSupported: false, defaultLocal: "Ctrl+D" },
        { id: "nav_back", label: "返回上一页", category: "页面导航", systemKey: true, globalSupported: false, defaultLocal: "Esc" },
        { id: "nav_home", label: "回到首页", category: "页面导航", systemKey: false, globalSupported: false, defaultLocal: "Ctrl+H" },
        { id: "nav_explore", label: "切换到探索", category: "页面导航", systemKey: false, globalSupported: false, defaultLocal: "Ctrl+N" },
        { id: "nav_library", label: "切换到媒体库", category: "页面导航", systemKey: false, globalSupported: false, defaultLocal: "Ctrl+J" },
        { id: "nav_settings", label: "切换到设置", category: "页面导航", systemKey: false, globalSupported: false, defaultLocal: "Ctrl+S" }
    ]

    readonly property real capsuleWidth: 132
    readonly property real capsuleHeight: 30

    function keyName(code) {
        const value = Number(code)
        if (!value)
            return "未设置"
        if (value === 32) return "Space"
        if (value === 27) return "Esc"
        if (value === 10 || value === 13) return "Enter"
        if (value === 9) return "Tab"
        if (value === 8) return "Backspace"
        if (value === 127 || value === 46) return "Delete"
        if (value === 37) return "←"
        if (value === 38) return "↑"
        if (value === 39) return "→"
        if (value === 40) return "↓"
        if (value === 36) return "Home"
        if (value === 35) return "End"
        if (value === 33) return "PageUp"
        if (value === 34) return "PageDown"
        if (value === 45 || value === 155) return "Insert"
        if (value >= 65 && value <= 90) return String.fromCharCode(value)
        if (value >= 48 && value <= 57) return String.fromCharCode(value)
        if (value >= 112 && value <= 123) return "F" + (value - 111)
        return "键 #" + value
    }

    function formatBinding(binding) {
        if (!binding || !binding.keyCode)
            return "未设置"
        const parts = []
        if (binding.ctrl) parts.push("Ctrl")
        if (binding.alt) parts.push("Alt")
        if (binding.shift) parts.push("Shift")
        if (binding.meta) parts.push("Meta")
        parts.push(keyName(binding.keyCode))
        return parts.join("+")
    }

    function localText(action) {
        const stored = root.localBindings ? root.localBindings[action.id] : undefined
        if (stored && stored.keyCode)
            return formatBinding(stored)
        return action.defaultLocal
    }

    function globalText(action) {
        const stored = root.globalBindings ? root.globalBindings[action.id] : undefined
        if (stored && stored.keyCode)
            return formatBinding(stored)
        return "未设置"
    }

    Column {
        id: layout
        width: parent.width
        spacing: 0

        // 表头
        Item {
            width: layout.width
            height: 30

            Text {
                x: 0
                anchors.verticalCenter: parent.verticalCenter
                text: "动作"
                color: Theme.onSurfaceVariantColor
                font.family: Theme.fontFamily
                font.pixelSize: 12
                font.weight: Font.Medium
            }

            Text {
                anchors.right: parent.right
                anchors.rightMargin: root.capsuleWidth + Theme.spacingSm
                anchors.verticalCenter: parent.verticalCenter
                width: root.capsuleWidth
                horizontalAlignment: Text.AlignHCenter
                text: "应用内"
                color: Theme.onSurfaceVariantColor
                font.family: Theme.fontFamily
                font.pixelSize: 12
                font.weight: Font.Medium
            }

            Text {
                anchors.right: parent.right
                anchors.verticalCenter: parent.verticalCenter
                width: root.capsuleWidth
                horizontalAlignment: Text.AlignHCenter
                text: "全局"
                color: Theme.onSurfaceVariantColor
                font.family: Theme.fontFamily
                font.pixelSize: 12
                font.weight: Font.Medium
            }
        }

        Repeater {
            model: root.actions

            delegate: Item {
                id: shortcutRow
                required property var modelData
                width: layout.width
                height: 42

                Column {
                    x: 0
                    width: Math.max(0, parent.width - root.capsuleWidth * 2 - Theme.spacingSm - Theme.spacingMd)
                    anchors.verticalCenter: parent.verticalCenter
                    spacing: 1

                    Text {
                        width: parent.width
                        text: shortcutRow.modelData.label
                        color: Theme.onSurfaceColor
                        font.family: Theme.fontFamily
                        font.pixelSize: 14
                        elide: Text.ElideRight
                    }

                    Text {
                        width: parent.width
                        text: shortcutRow.modelData.category
                              + (shortcutRow.modelData.systemKey ? " · 固定按键" : "")
                        color: Theme.onSurfaceVariantColor
                        font.family: Theme.fontFamily
                        font.pixelSize: 11
                        elide: Text.ElideRight
                    }
                }

                Rectangle {
                    anchors.right: parent.right
                    anchors.rightMargin: root.capsuleWidth + Theme.spacingSm
                    anchors.verticalCenter: parent.verticalCenter
                    width: root.capsuleWidth
                    height: root.capsuleHeight
                    radius: 10
                    color: shortcutRow.modelData.systemKey
                           ? Theme.surfaceContainerHigh
                           : Theme.surfaceContainer
                    border.width: 1
                    border.color: Theme.outlineVariant
                    opacity: shortcutRow.modelData.systemKey ? 0.75 : 1

                    Text {
                        anchors.centerIn: parent
                        width: parent.width - 12
                        horizontalAlignment: Text.AlignHCenter
                        text: root.localText(shortcutRow.modelData)
                        elide: Text.ElideRight
                        color: shortcutRow.modelData.systemKey
                               ? Theme.onSurfaceVariantColor
                               : Theme.onSurfaceColor
                        font.family: Theme.fontFamily
                        font.pixelSize: 14
                        font.weight: Font.Medium
                    }
                }

                Rectangle {
                    anchors.right: parent.right
                    anchors.verticalCenter: parent.verticalCenter
                    width: root.capsuleWidth
                    height: root.capsuleHeight
                    radius: 10
                    color: Theme.surfaceContainer
                    border.width: 1
                    border.color: Theme.outlineVariant
                    opacity: shortcutRow.modelData.globalSupported ? 1 : 0.45

                    Text {
                        anchors.centerIn: parent
                        width: parent.width - 12
                        horizontalAlignment: Text.AlignHCenter
                        text: shortcutRow.modelData.globalSupported
                              ? root.globalText(shortcutRow.modelData)
                              : "不支持"
                        elide: Text.ElideRight
                        color: root.globalText(shortcutRow.modelData) === "未设置"
                               ? Theme.onSurfaceVariantColor
                               : Theme.onSurfaceColor
                        font.family: Theme.fontFamily
                        font.pixelSize: 14
                        font.weight: Font.Medium
                    }
                }
            }
        }

        Item {
            width: 1
            height: 8
        }

        Text {
            width: layout.width
            text: "「固定按键」为系统基础操作（空格播放 / Esc 返回），只读展示；键位录制与全局快捷键注册将在后续版本接入。"
            color: Theme.onSurfaceVariantColor
            font.family: Theme.fontFamily
            font.pixelSize: 11
            lineHeightMode: Text.FixedHeight
            lineHeight: 16
            wrapMode: Text.Wrap
        }
    }
}
