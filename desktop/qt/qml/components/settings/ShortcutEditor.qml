import QtQuick
import "../"

// 快捷键设置：从后端 shortcuts.catalog 加载动作表，按分类分组展示。
// 应用内键位可点击录制（写入 Settings.shortcuts.local，保留其它条目）；
// 全局键位由桌面系统（xdg portal）管理，这里只读展示默认值并提供跳转入口。
Item {
    id: root

    // 后端动作表与加载状态。
    property var catalog: []
    property bool loading: true
    property string errorText: ""

    // 正在录制的动作 id（同一时间只允许一个），空串表示没有在录制。
    property string recordingActionId: ""

    readonly property real capsuleWidth: 132
    readonly property real capsuleHeight: 36
    readonly property real columnGap: 8
    readonly property real rowHeight: 42

    // 两列左侧起始 x，表头与每一行共用，保证列对齐。
    readonly property real localColumnX: Math.max(0, width - capsuleWidth * 2 - columnGap)
    readonly property real globalColumnX: Math.max(0, width - capsuleWidth)

    width: parent ? parent.width : 0
    implicitHeight: content.height

    // 后端动作表按 category 分组（保持后端顺序）。
    readonly property var groups: {
        const order = []
        const buckets = {}
        for (let i = 0; i < root.catalog.length; ++i) {
            const action = root.catalog[i]
            const category = action.category || "其它"
            if (!buckets[category]) {
                buckets[category] = []
                order.push(category)
            }
            buckets[category].push(action)
        }
        const result = []
        for (let i = 0; i < order.length; ++i)
            result.push({ category: order[i], actions: buckets[order[i]] })
        return result
    }

    Component.onCompleted: {
        if (Backend.connected)
            root.loadCatalog()
    }

    // 页面可能在后端连上之前就创建：连上后再加载动作表。
    property Connections backendConnection: Connections {
        target: Backend
        function onConnectedChanged() {
            if (Backend.connected && root.catalog.length === 0)
                root.loadCatalog()
        }
    }

    // ---------------------------------------------------------------- 数据加载

    function loadCatalog() {
        root.loading = true
        root.errorText = ""
        Api.get("shortcuts.catalog", {}, function(result, error) {
            if (error) {
                root.loading = false
                root.errorText = "快捷键列表加载失败：" + (error.message || "未知错误")
                return
            }
            root.catalog = (result && result.actions) ? result.actions : []
            root.loading = false
        })
    }

    function openGlobalSettings() {
        Api.call("shortcut.openGlobalSettings").then(function(result) {
            if (result && result.ok === false)
                Toast.show("当前桌面环境不支持全局快捷键设置", "error")
        }).catch(function(error) {
            Toast.show("打开系统快捷键设置失败：" + (error && error.message ? error.message : error), "error")
        })
    }

    // ---------------------------------------------------------------- 绑定读取

    function storedLocal(actionId) {
        const values = Settings.values
        const shortcuts = values ? values.shortcuts : null
        if (!shortcuts || !shortcuts.local)
            return null
        return shortcuts.local[actionId] ? shortcuts.local[actionId] : null
    }

    function localText(action) {
        const stored = root.storedLocal(action.id)
        if (stored)
            return root.formatBinding(stored)
        return action.localDefault ? action.localDefault : "未设置"
    }

    function globalText(action) {
        if (!action.globalSupported)
            return "不支持"
        return action.globalDefault ? action.globalDefault : "未设置"
    }

    function actionHint(action) {
        if (action.systemKey)
            return "固定键 · 不可修改"
        return action.globalSupported ? "应用内可录制 · 支持全局快捷键"
                                      : "应用内可录制 · 不支持全局快捷键"
    }

    // ---------------------------------------------------------------- 键位格式化

    // xkb 键名 → 界面展示名（与 core/Shortcuts.kt 的 displayKeyName 一致）。
    function formatKeyName(key) {
        if (!key)
            return ""
        switch (key) {
        case "space": return "Space"
        case "Return": return "Enter"
        case "Escape": return "Esc"
        case "Tab": return "Tab"
        case "BackSpace": return "Backspace"
        case "Delete": return "Delete"
        case "Up": return "↑"
        case "Down": return "↓"
        case "Left": return "←"
        case "Right": return "→"
        case "XF86AudioPrev": return "媒体上一首"
        case "XF86AudioNext": return "媒体下一首"
        case "XF86AudioStop": return "媒体停止"
        case "XF86AudioPlay": return "媒体播放"
        case "XF86AudioPause": return "媒体暂停"
        case "XF86AudioPlayPause": return "媒体播放/暂停"
        default: return key.length === 1 ? key.toUpperCase() : key
        }
    }

    // {key,ctrl,alt,shift,meta} → 「Ctrl+Shift+L」。
    function formatBinding(binding) {
        if (!binding || !binding.key)
            return "未设置"
        const parts = []
        if (binding.ctrl) parts.push("Ctrl")
        if (binding.alt) parts.push("Alt")
        if (binding.shift) parts.push("Shift")
        if (binding.meta) parts.push("Meta")
        parts.push(root.formatKeyName(binding.key))
        return parts.join("+")
    }

    // Qt 按键 → xkb 键名；不支持的键返回空串。
    function qtKeyToXkb(key) {
        if (key === Qt.Key_Space) return "space"
        if (key >= Qt.Key_A && key <= Qt.Key_Z) return String.fromCharCode(key)
        if (key >= Qt.Key_0 && key <= Qt.Key_9) return String.fromCharCode(key)
        switch (key) {
        case Qt.Key_Up: return "Up"
        case Qt.Key_Down: return "Down"
        case Qt.Key_Left: return "Left"
        case Qt.Key_Right: return "Right"
        case Qt.Key_Escape: return "Escape"
        case Qt.Key_Return: return "Return"
        case Qt.Key_Enter: return "Return"
        case Qt.Key_Tab: return "Tab"
        case Qt.Key_MediaNext: return "XF86AudioNext"
        case Qt.Key_MediaPrevious: return "XF86AudioPrev"
        case Qt.Key_MediaTogglePlayPause: return "XF86AudioPlayPause"
        case Qt.Key_MediaPlay: return "XF86AudioPlay"
        default: return ""
        }
    }

    function isModifierKey(key) {
        return key === Qt.Key_Shift || key === Qt.Key_Control || key === Qt.Key_Alt
                || key === Qt.Key_Meta || key === Qt.Key_AltGr
                || key === Qt.Key_Super_L || key === Qt.Key_Super_R
                || key === Qt.Key_CapsLock || key === Qt.Key_NumLock
                || key === Qt.Key_ScrollLock
    }

    // ---------------------------------------------------------------- 录制 / 保存

    function startRecording(action, scope) {
        if (action.systemKey) {
            Toast.show("「" + action.label + "」是固定键，不能修改", "info")
            return
        }
        root.recordingActionId = action.id
        if (scope)
            scope.forceActiveFocus()
    }

    function stopRecording() {
        root.recordingActionId = ""
    }

    function handleRecordKey(action, event) {
        if (root.recordingActionId !== action.id)
            return
        // Esc 取消录制；修饰键本身不录制，继续等下一个非修饰键。
        if (event.key === Qt.Key_Escape) {
            event.accepted = true
            root.stopRecording()
            return
        }
        if (root.isModifierKey(event.key)) {
            event.accepted = true
            return
        }
        const xkbKey = root.qtKeyToXkb(event.key)
        if (!xkbKey) {
            event.accepted = true
            Toast.show("暂不支持这个按键，请换一个", "info")
            return
        }
        const binding = {
            key: xkbKey,
            ctrl: (event.modifiers & Qt.ControlModifier) !== 0,
            alt: (event.modifiers & Qt.AltModifier) !== 0,
            shift: (event.modifiers & Qt.ShiftModifier) !== 0,
            meta: (event.modifiers & Qt.MetaModifier) !== 0
        }
        event.accepted = true
        root.stopRecording()
        root.saveLocal(action.id, binding)
    }

    // 保存单个应用内键位：必须带上完整的 local/global 两份 map，
    // 因为 settings.set 是顶层浅合并，只传本次动作会丢掉其它覆盖项。
    function saveLocal(actionId, binding) {
        const values = Settings.values || ({})
        const current = values.shortcuts || ({})
        const local = {}
        const global = {}
        if (current.local) {
            for (const id in current.local)
                local[id] = current.local[id]
        }
        if (current.global) {
            for (const id in current.global)
                global[id] = current.global[id]
        }
        local[actionId] = binding
        Settings.patch({ shortcuts: { local: local, global: global } })
    }

    function clearLocal(action) {
        if (action.systemKey) {
            Toast.show("「" + action.label + "」是固定键，不能修改", "info")
            return
        }
        if (!root.storedLocal(action.id))
            return
        root.stopRecording()
        root.saveLocal(action.id, { key: "", ctrl: false, alt: false, shift: false, meta: false })
    }

    // ---------------------------------------------------------------- 内容

    Column {
        id: content
        width: root.width
        spacing: 0

        // 分区操作按钮（全局快捷键由桌面系统管理，跳转系统配置界面）。
        Item {
            width: content.width
            height: 40

            SettingsButton {
                anchors.right: parent.right
                anchors.verticalCenter: parent.verticalCenter
                text: "系统设置"
                icon: "settings"
                onClicked: root.openGlobalSettings()
            }
        }

        // 表头
        Item {
            width: content.width
            height: 28

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
                x: root.localColumnX
                width: root.capsuleWidth
                anchors.verticalCenter: parent.verticalCenter
                horizontalAlignment: Text.AlignHCenter
                text: "应用内"
                color: Theme.onSurfaceVariantColor
                font.family: Theme.fontFamily
                font.pixelSize: 12
                font.weight: Font.Medium
            }

            Text {
                x: root.globalColumnX
                width: root.capsuleWidth
                anchors.verticalCenter: parent.verticalCenter
                horizontalAlignment: Text.AlignHCenter
                text: "全局"
                color: Theme.onSurfaceVariantColor
                font.family: Theme.fontFamily
                font.pixelSize: 12
                font.weight: Font.Medium
            }
        }

        // 加载中
        Item {
            width: content.width
            height: 36
            visible: root.loading

            Text {
                anchors.verticalCenter: parent.verticalCenter
                text: "正在加载快捷键…"
                color: Theme.onSurfaceVariantColor
                font.family: Theme.fontFamily
                font.pixelSize: 12
            }
        }

        // 加载失败
        Column {
            width: content.width
            visible: !root.loading && root.errorText !== ""
            spacing: Theme.spacingSm

            SettingsNote {
                width: parent.width
                text: root.errorText
                textColor: Theme.error
            }

            SettingsButton {
                text: "重试"
                onClicked: root.loadCatalog()
            }
        }

        // 动作分组
        Repeater {
            model: root.groups

            delegate: Column {
                id: groupColumn
                required property var modelData
                readonly property var groupActions: modelData.actions

                width: content.width
                spacing: 0

                // 分类标题
                Item {
                    width: groupColumn.width
                    height: 32

                    Text {
                        x: 0
                        anchors.verticalCenter: parent.verticalCenter
                        text: groupColumn.modelData.category
                        color: Theme.onSurfaceVariantColor
                        font.family: Theme.fontFamily
                        font.pixelSize: 12
                        font.weight: Font.Medium
                    }

                    Rectangle {
                        anchors.left: parent.left
                        anchors.right: parent.right
                        anchors.bottom: parent.bottom
                        height: 1
                        color: Theme.withAlpha(Theme.outlineVariant, 0.6)
                    }
                }

                Repeater {
                    model: groupColumn.groupActions

                    delegate: Item {
                        id: shortcutRow
                        required property var modelData
                        readonly property var action: modelData
                        readonly property bool recording: root.recordingActionId === action.id
                        readonly property bool isSystem: action.systemKey === true

                        width: groupColumn.width
                        height: root.rowHeight

                        // 行 hover 底色
                        Rectangle {
                            anchors.fill: parent
                            anchors.leftMargin: -Theme.spacingSm
                            anchors.rightMargin: -Theme.spacingSm
                            radius: Theme.radiusSm
                            color: Theme.withAlpha(Theme.onSurfaceColor, 0.04)
                            visible: rowHover.hovered && !shortcutRow.recording
                        }

                        HoverHandler {
                            id: rowHover
                        }

                        // 动作名 + 说明
                        Column {
                            x: 0
                            width: Math.max(0, root.localColumnX - 56)
                            anchors.verticalCenter: parent.verticalCenter
                            spacing: 1

                            Text {
                                width: parent.width
                                text: shortcutRow.action.label
                                color: Theme.onSurfaceColor
                                font.family: Theme.fontFamily
                                font.pixelSize: 14
                                elide: Text.ElideRight
                            }

                            Text {
                                width: parent.width
                                text: root.actionHint(shortcutRow.action)
                                color: Theme.onSurfaceVariantColor
                                font.family: Theme.fontFamily
                                font.pixelSize: 11
                                elide: Text.ElideRight
                            }
                        }

                        // 清除按钮（hover 且已覆盖默认键位时出现；右键胶囊也可清除）
                        Item {
                            id: clearButton
                            width: 40
                            height: 24
                            x: root.localColumnX - width - Theme.spacingSm
                            anchors.verticalCenter: parent.verticalCenter
                            visible: rowHover.hovered && !shortcutRow.isSystem && !shortcutRow.recording
                                     && root.storedLocal(shortcutRow.action.id) !== null

                            Rectangle {
                                anchors.fill: parent
                                radius: height / 2
                                color: clearMouse.pressed
                                       ? Theme.withAlpha(Theme.primary, 0.16)
                                       : (clearHover.hovered ? Theme.withAlpha(Theme.primary, 0.08) : "transparent")
                            }

                            Text {
                                anchors.centerIn: parent
                                text: "清除"
                                color: Theme.primary
                                font.family: Theme.fontFamily
                                font.pixelSize: 11
                                font.weight: Font.Medium
                            }

                            HoverHandler {
                                id: clearHover
                                cursorShape: Qt.PointingHandCursor
                            }

                            MouseArea {
                                id: clearMouse
                                anchors.fill: parent
                                onClicked: root.clearLocal(shortcutRow.action)
                            }
                        }

                        // ---------------- 应用内键位（可录制）
                        Rectangle {
                            id: localCapsule
                            x: root.localColumnX
                            anchors.verticalCenter: parent.verticalCenter
                            width: root.capsuleWidth
                            height: root.capsuleHeight
                            radius: 10
                            color: shortcutRow.recording
                                   ? Theme.primaryContainer
                                   : (localHover.hovered && !shortcutRow.isSystem
                                      ? Theme.surfaceContainerHighest
                                      : Theme.surfaceContainerHigh)
                            border.width: 1
                            border.color: shortcutRow.recording ? Theme.primary : Theme.outlineVariant

                            Behavior on color {
                                ColorAnimation {
                                    duration: Theme.motionShort
                                }
                            }

                            Text {
                                anchors.centerIn: parent
                                width: parent.width - 16
                                horizontalAlignment: Text.AlignHCenter
                                text: shortcutRow.recording ? "按下新键…" : root.localText(shortcutRow.action)
                                elide: Text.ElideRight
                                color: shortcutRow.recording
                                       ? Theme.onPrimaryContainerColor
                                       : (shortcutRow.isSystem ? Theme.onSurfaceVariantColor : Theme.onSurfaceColor)
                                font.family: Theme.fontFamily
                                font.pixelSize: 14
                                font.weight: Font.Medium
                            }

                            // 录制态焦点：捕获下一个非修饰键。
                            FocusScope {
                                id: recordScope
                                anchors.fill: parent
                                focus: shortcutRow.recording

                                Keys.onPressed: function(event) {
                                    root.handleRecordKey(shortcutRow.action, event)
                                }

                                onActiveFocusChanged: {
                                    if (!activeFocus && shortcutRow.recording)
                                        root.stopRecording()
                                }
                            }

                            HoverHandler {
                                id: localHover
                                cursorShape: Qt.PointingHandCursor
                            }

                            MouseArea {
                                anchors.fill: parent
                                acceptedButtons: Qt.LeftButton | Qt.RightButton
                                onClicked: function(mouse) {
                                    if (mouse.button === Qt.RightButton) {
                                        root.clearLocal(shortcutRow.action)
                                        return
                                    }
                                    root.startRecording(shortcutRow.action, recordScope)
                                }
                            }
                        }

                        // ---------------- 全局键位（只读，由桌面系统管理）
                        Rectangle {
                            x: root.globalColumnX
                            anchors.verticalCenter: parent.verticalCenter
                            width: root.capsuleWidth
                            height: root.capsuleHeight
                            radius: 10
                            color: Theme.surfaceContainer
                            border.width: 1
                            border.color: Theme.outlineVariant
                            opacity: shortcutRow.action.globalSupported ? 1 : 0.55

                            Text {
                                anchors.centerIn: parent
                                width: parent.width - 16
                                horizontalAlignment: Text.AlignHCenter
                                text: root.globalText(shortcutRow.action)
                                elide: Text.ElideRight
                                color: root.globalText(shortcutRow.action) === "未设置"
                                       ? Theme.onSurfaceVariantColor
                                       : Theme.onSurfaceColor
                                font.family: Theme.fontFamily
                                font.pixelSize: 14
                                font.weight: Font.Medium
                            }
                        }
                    }
                }
            }
        }

        // 无动作
        Item {
            width: content.width
            height: 36
            visible: !root.loading && root.errorText === "" && root.catalog.length === 0

            Text {
                anchors.verticalCenter: parent.verticalCenter
                text: "没有可用的快捷键动作"
                color: Theme.onSurfaceVariantColor
                font.family: Theme.fontFamily
                font.pixelSize: 12
            }
        }

        Item {
            width: 1
            height: 12
        }

        SettingsNote {
            width: content.width
            text: "「固定键」是系统基础操作，只读展示。点击「应用内」键位可录制新快捷键，按 Esc 取消，右键可清除；全局键位由桌面系统管理，点击右上角「系统设置」调整。"
        }
    }
}
