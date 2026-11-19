import QtQuick
import "../"

// 播放页外观设置（供设置页「播放页外观」分组内嵌）。
//
// 这里只有设置行本身，不带标题与卡片容器：外层由 SettingsPage 的 SettingsSection 提供，
// 集成方按 `PlayerDesignSettings { width: parent.width }` 实例化即可。
// 字段与后端 AppSettings.nowPlayingCoverShape / nowPlayingBackground /
// nowPlayingAutoHideControls / nowPlayingAutoHideDelayMs 一一对应。
Item {
    id: root

    readonly property string coverShape: {
        const shape = Settings.get("nowPlayingCoverShape", "ROUNDED")
        return (shape === "CIRCLE" || shape === "SQUARE") ? shape : "ROUNDED"
    }
    readonly property string background: {
        const mode = Settings.get("nowPlayingBackground", "BLUR")
        return (mode === "GRADIENT" || mode === "SOLID") ? mode : "BLUR"
    }
    readonly property bool autoHide: Settings.get("nowPlayingAutoHideControls", true) !== false
    readonly property real autoHideDelayMs: {
        const value = Settings.get("nowPlayingAutoHideDelayMs", 2600)
        return typeof value === "number" && value > 0 ? value : 2600
    }

    width: parent ? parent.width : 0
    implicitHeight: column.implicitHeight


    Column {
        id: column

        width: parent.width
        spacing: Theme.spacingSm

        SettingsLabel {
            text: "封面形状"
        }

        SegmentedOptions {
            options: [
                { value: "CIRCLE", label: "圆形" },
                { value: "ROUNDED", label: "圆角" },
                { value: "SQUARE", label: "方形" }
            ]
            value: root.coverShape
            onSelected: function(value) {
                Settings.patch({ "nowPlayingCoverShape": value })
            }
        }

        Item {
            width: 1
            height: Theme.spacingSm
        }

        SettingsLabel {
            text: "背景样式"
        }

        SegmentedOptions {
            options: [
                { value: "BLUR", label: "封面模糊" },
                { value: "GRADIENT", label: "渐变" },
                { value: "SOLID", label: "纯色" }
            ]
            value: root.background
            onSelected: function(value) {
                Settings.patch({ "nowPlayingBackground": value })
            }
        }

        SettingsNote {
            text: "封面模糊取自当前歌曲封面并叠加暗色；渐变与纯色使用封面主色/主题色生成。"
        }

        Item {
            width: 1
            height: Theme.spacingSm
        }

        SettingsSwitchRow {
            title: "自动隐藏控制条"
            description: "鼠标静止一段时间后淡出底部控制条，只保留封面与歌词"
            checked: root.autoHide
            onToggled: function(checked) {
                Settings.patch({ "nowPlayingAutoHideControls": checked })
            }
        }

        SliderRow {
            label: "自动隐藏延迟"
            enabled: root.autoHide
            from: 1000
            to: 8000
            externalValue: root.autoHideDelayMs
            formatValue: function(value) {
                return (value / 1000).toFixed(1) + " 秒"
            }
            onCommitted: function(value) {
                Settings.patch({ "nowPlayingAutoHideDelayMs": Math.round(value) })
            }
        }
    }
}
