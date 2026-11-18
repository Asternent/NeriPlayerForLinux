import QtQuick

// 应用设置。后端通过 "settings" 事件下发完整设置对象；修改用 Settings.set(key, value)。
QtObject {
    id: settings

    property var values: ({})

    function get(key, fallback) {
        const value = settings.values[key]
        return value === undefined ? fallback : value
    }

    /** 设置任意一个字段，例如 Settings.set("dynamicColor", true)。 */
    function set(key, value) {
        const patch = {}
        patch[key] = value
        return Api.call("settings.set", { patch: patch }).then(function(result) {
            if (result && result.settings)
                settings.values = result.settings
            return result
        }).catch(function(error) {
            console.warn("[settings] set", key, "failed:", error.message)
            Toast.show(error.message, "error")
        })
    }

    /** 一次设置多个字段。 */
    function patch(changes) {
        return Api.call("settings.set", { patch: changes }).then(function(result) {
            if (result && result.settings)
                settings.values = result.settings
            return result
        }).catch(function(error) {
            console.warn("[settings] patch failed:", error.message)
            Toast.show(error.message, "error")
        })
    }

    function reload() {
        return Api.call("settings.get").then(function(result) {
            if (result && result.settings)
                settings.values = result.settings
            return result
        })
    }

    // ---- 常用设置项的便捷读取 ----
    readonly property bool dynamicColor: values.dynamicColor === true
    readonly property bool darkModeAuto: values.darkMode === "AUTO" || values.darkMode === undefined
    readonly property string darkMode: values.darkMode || "AUTO"
    readonly property string themeSeedColor: values.themeSeedColor || "0061A4"
    readonly property string paletteStyle: values.paletteStyle || "TonalSpot"
    readonly property string colorSpec: values.colorSpec || "SPEC_2021"
    readonly property bool onboarded: values.onboardingAccepted === true
    readonly property bool showLyricTranslation: values.showLyricTranslation !== false

    property Connections backendConnection: Connections {
        target: Backend
        function onEvent(name, data) {
            if (name === "settings")
                settings.values = data
        }
    }
}
