import QtQuick

// 所有与后端通信的统一入口。
// 用法：
//   Api.get("library.songs", {}, function(result) { ... })
//   Api.call("player.togglePlayPause").then(...)   // Promise 风格
//   Api.on("library.changed", function(data) { ... })
QtObject {
    id: api

    readonly property bool connected: Backend.connected

    property var _handlers: ({})
    property var _pending: ({})

    // ---- 请求 ----

    /** 回调风格：callback(result, error)。error 为 null 表示成功。 */
    function get(method, params, callback) {
        return Backend.call(method, params === undefined || params === null ? ({}) : params,
                            function(result, error) {
            if (callback)
                callback(error ? null : result, error ? error : null)
        })
    }

    /** Promise 风格。 */
    function call(method, params) {
        return new Promise(function(resolve, reject) {
            Backend.call(method, params === undefined || params === null ? ({}) : params,
                         function(result, error) {
                if (error)
                    reject(error)
                else
                    resolve(result)
            })
        })
    }

    // ---- 事件订阅 ----

    function on(name, handler) {
        if (!_handlers[name])
            _handlers[name] = []
        _handlers[name].push(handler)
    }

    function off(name, handler) {
        const list = _handlers[name]
        if (!list)
            return
        const index = list.indexOf(handler)
        if (index >= 0)
            list.splice(index, 1)
    }

    function _dispatch(name, data) {
        const list = _handlers[name]
        if (list) {
            // 拷贝一份，避免回调里增删订阅导致遍历异常
            const snapshot = list.slice()
            for (let i = 0; i < snapshot.length; i++) {
                try {
                    snapshot[i](data)
                } catch (e) {
                    console.error("[api] handler for", name, "failed:", e)
                }
            }
        }
    }

    property Connections backendConnection: Connections {
        target: Backend
        function onEvent(name, data) {
            api._dispatch(name, data)
        }
    }
}
