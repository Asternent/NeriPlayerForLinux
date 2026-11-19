import QtQuick

// 轻提示队列（对应 Compose 版的 Snackbar / player.messages）。
QtObject {
    id: toast

    property var items: []
    property int durationMs: 3600

    function show(text, kind) {
        if (!text)
            return
        const item = {
            "id": _nextId++,
            "text": String(text),
            "kind": kind || "info",
            "expiresAt": Date.now() + toast.durationMs
        }
        items = items.concat([item])
    }

    function dismiss(id) {
        items = items.filter(function(item) { return item.id !== id })
    }

    function clear() {
        items = []
    }

    property int _nextId: 1

    property Timer cleanupTimer: Timer {
        interval: 250
        repeat: true
        running: toast.items.length > 0
        onTriggered: {
            const now = Date.now()
            toast.items = toast.items.filter(function(item) { return item.expiresAt > now })
        }
    }

    property Connections backendConnection: Connections {
        target: Backend
        function onEvent(name, data) {
            if (name === "toast")
                toast.show(data.message, data.kind)
        }
    }
}
