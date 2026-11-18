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
        Api.get("settings.set", { patch: patch }, function(result, error) {
            if (error) {
                console.warn("[settings] set", key, "failed:", error.message)
                Toast.show(error.message, "error")
                return null
            }
            if (result && result.settings)
                settings.values = result.settings
            return result
        })
    }

    /** 一次设置多个字段。 */
    function patch(changes) {
        Api.get("settings.set", { patch: changes }, function(result, error) {
            if (error) {
                console.warn("[settings] patch failed:", error.message)
                Toast.show(error.message, "error")
                return null
            }
            if (result && result.settings)
                settings.values = result.settings
            return result
        })
    }

    function reload() {
        Api.get("settings.get", {}, function(result, error) {
            if (!error && result && result.settings)
                settings.values = result.settings
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
