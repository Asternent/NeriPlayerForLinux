import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../"

// 音效面板：播放速度、音调、响度增强、10 段均衡器、预设与重置。
PanelScaffold {
    id: panel

    title: "音效与倍速"
    panelWidth: 420

    readonly property var frequencies: ["31Hz", "62Hz", "125Hz", "250Hz", "500Hz",
                                       "1kHz", "2kHz", "4kHz", "8kHz", "16kHz"]
    readonly property var presetLabels: ["平直", "原声", "低音增强", "高频增强",
                                         "人声增强", "摇滚", "爵士", "古典"]
    readonly property var presetBands: ({
        "平直": [0, 0, 0, 0, 0, 0, 0, 0, 0, 0],
        "原声": [3, 2, 1, 0.5, 0.5, 1.5, 2, 2.5, 2, 1],
        "低音增强": [6, 5, 4, 2, 0, -1, -2, -1, 0, 0],
        "高频增强": [0, 0, -1, -1, 0, 1, 3, 4, 5, 5],
        "人声增强": [-2, -1, 0, 2, 4, 4, 3, 1.5, 0, -1],
        "摇滚": [5, 4, 2, 0, -1, 0, 2, 4, 4, 3],
        "爵士": [3, 2, 1, 2, -1, -1, 0, 1, 2, 3],
        "古典": [4, 3, 2, 0, 0, 0, -1, 0, 2, 3]
    })
    readonly property string currentPreset: {
        const value = Settings.get("equalizerPreset", "平直")
        return typeof value === "string" ? value : "平直"
    }

    function formatGain(value) {
        const gain = (typeof value === "number") ? value : 0
        return (gain >= 0 ? "+" : "") + gain.toFixed(1)
    }

    function presetChip(label, rowWidth) {
        return label
    }

    Item {
        anchors.fill: parent

        Flickable {
            id: flick

            anchors.fill: parent
            contentWidth: width
            contentHeight: contentColumn.implicitHeight + 28
            clip: true
            boundsBehavior: Flickable.StopAtBounds

            ScrollBar.vertical: ScrollBar {
                policy: ScrollBar.AsNeeded
            }

            Column {
                id: contentColumn

                x: 20
                y: 12
                width: flick.width - 40
                spacing: 8

                // ---- 依赖提示 ----
                Rectangle {
                    width: contentColumn.width
                    height: warningRow.implicitHeight + 28
                    radius: 16
                    visible: !Player.supportsEffects
                    color: Theme.errorContainer

                    RowLayout {
                        id: warningRow

                        x: 14
                        y: 14
                        width: parent.width - 28
                        spacing: 10

                        NeriIcon {
                            Layout.alignment: Qt.AlignTop
                            name: "error"
                            iconSize: 20
                            color: Theme.onErrorContainerColor
                        }

                        Text {
                            Layout.fillWidth: true
                            text: "当前环境未安装 ffmpeg，倍速、变调、响度与均衡器不可用。安装 ffmpeg 后重启应用即可启用。"
                            wrapMode: Text.Wrap
                            color: Theme.onErrorContainerColor
                            font.family: Theme.fontFamily
                            font.pixelSize: Theme.textStyle("bodySmall").size
                        }
                    }
                }

                // ---- 播放速度 ----
                Item {
                    width: 1
                    height: 6
                }

                Text {
                    text: "播放速度"
                    color: Theme.onSurfaceColor
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.textStyle("titleSmall").size
                    font.weight: Font.Medium
                }

                PlayerSlider {
                    width: contentColumn.width
                    from: 0.5
                    to: 3
                    stepSize: 0.05
                    value: Player.speed
                    enabledSlider: Player.supportsEffects
                    onMoved: Player.setSpeed(value)
                }

                Text {
                    text: Player.speed.toFixed(2) + "x"
                    color: Theme.onSurfaceVariantColor
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.textStyle("labelMedium").size
                    font.weight: Theme.textStyle("labelMedium").weight
                }

                // ---- 音调 ----
                Item {
                    width: 1
                    height: 10
                }

                Text {
                    text: "音调"
                    color: Theme.onSurfaceColor
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.textStyle("titleSmall").size
                    font.weight: Font.Medium
                }

                PlayerSlider {
                    width: contentColumn.width
                    from: -12
                    to: 12
                    stepSize: 1
                    value: Player.pitch
                    enabledSlider: Player.supportsEffects
                    onMoved: Player.setPitch(value)
                }

                Text {
                    text: Player.pitch.toFixed(1) + " 半音"
                    color: Theme.onSurfaceVariantColor
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.textStyle("labelMedium").size
                    font.weight: Theme.textStyle("labelMedium").weight
                }

                // ---- 响度增强 ----
                Item {
                    width: 1
                    height: 12
                }

                Item {
                    width: contentColumn.width
                    height: loudnessText.implicitHeight

                    Column {
                        id: loudnessText

                        width: parent.width - 68
                        spacing: 2

                        Text {
                            width: parent.width
                            text: "响度增强"
                            color: Theme.onSurfaceColor
                            font.family: Theme.fontFamily
                            font.pixelSize: Theme.textStyle("titleSmall").size
                            font.weight: Font.Medium
                        }

                        Text {
                            width: parent.width
                            text: "补一点厚度和能量，但不会改变音高"
                            wrapMode: Text.Wrap
                            color: Theme.onSurfaceVariantColor
                            font.family: Theme.fontFamily
                            font.pixelSize: Theme.textStyle("labelSmall").size
                        }
                    }

                    PanelSwitch {
                        anchors.right: parent.right
                        anchors.verticalCenter: parent.verticalCenter
                        checked: Player.loudness
                        enabledSwitch: Player.supportsEffects
                        onToggled: function (value) {
                            Player.setLoudness(value)
                        }
                    }
                }

                // ---- 均衡器 ----
                Item {
                    width: 1
                    height: 16
                }

                Item {
                    width: contentColumn.width
                    height: 32

                    Text {
                        anchors.left: parent.left
                        anchors.verticalCenter: parent.verticalCenter
                        text: "均衡器"
                        color: Theme.onSurfaceColor
                        font.family: Theme.fontFamily
                        font.pixelSize: Theme.textStyle("titleSmall").size
                        font.weight: Font.Medium
                    }

                    PanelSwitch {
                        anchors.right: parent.right
                        anchors.verticalCenter: parent.verticalCenter
                        checked: Player.equalizerEnabled
                        enabledSwitch: Player.supportsEffects
                        onToggled: function (value) {
                            Player.setEqualizerEnabled(value)
                        }
                    }
                }

                Item {
                    width: 1
                    height: 4
                }

                Row {
                    id: presetRowA

                    width: contentColumn.width
                    spacing: 6

                    Repeater {
                        model: panel.presetLabels.slice(0, 4)

                        delegate: PanelButton {
                            required property var modelData

                            width: (presetRowA.width - 18) / 4
                            text: modelData
                            chip: true
                            selected: panel.currentPreset === modelData
                            enabledButton: Player.supportsEffects
                            onClicked: Player.setEqualizerPreset(modelData, panel.presetBands[modelData])
                        }
                    }
                }

                Row {
                    id: presetRowB

                    width: contentColumn.width
                    spacing: 6

                    Repeater {
                        model: panel.presetLabels.slice(4, 8)

                        delegate: PanelButton {
                            required property var modelData

                            width: (presetRowB.width - 18) / 4
                            text: modelData
                            chip: true
                            selected: panel.currentPreset === modelData
                            enabledButton: Player.supportsEffects
                            onClicked: Player.setEqualizerPreset(modelData, panel.presetBands[modelData])
                        }
                    }
                }

                Item {
                    width: 1
                    height: 8
                }

                Text {
                    text: "频段微调（dB）"
                    color: Theme.onSurfaceColor
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.textStyle("labelLarge").size
                    font.weight: Theme.textStyle("labelLarge").weight
                }

                Repeater {
                    model: 10

                    delegate: Item {
                        id: bandRow

                        required property int index

                        width: contentColumn.width
                        height: 40

                        Text {
                            anchors.left: parent.left
                            anchors.verticalCenter: parent.verticalCenter
                            width: 58
                            text: panel.frequencies[bandRow.index]
                            color: Theme.onSurfaceVariantColor
                            font.family: Theme.fontFamily
                            font.pixelSize: Theme.textStyle("labelSmall").size
                            font.weight: Theme.textStyle("labelSmall").weight
                        }

                        PlayerSlider {
                            anchors.left: parent.left
                            anchors.leftMargin: 58
                            anchors.right: gainLabel.left
                            anchors.rightMargin: 8
                            anchors.verticalCenter: parent.verticalCenter
                            from: -12
                            to: 12
                            stepSize: 0.5
                            value: {
                                const bands = Player.equalizerBands || []
                                return bandRow.index < bands.length ? bands[bandRow.index] : 0
                            }
                            enabledSlider: Player.supportsEffects && Player.equalizerEnabled
                            onMoved: Player.setEqualizerBand(bandRow.index, value)
                        }

                        Text {
                            id: gainLabel

                            anchors.right: parent.right
                            anchors.verticalCenter: parent.verticalCenter
                            width: 46
                            horizontalAlignment: Text.AlignRight
                            text: {
                                const bands = Player.equalizerBands || []
                                return panel.formatGain(bandRow.index < bands.length ? bands[bandRow.index] : 0)
                            }
                            color: Theme.onSurfaceVariantColor
                            font.family: Theme.fontFamily
                            font.pixelSize: Theme.textStyle("labelSmall").size
                            font.weight: Theme.textStyle("labelSmall").weight
                        }
                    }
                }

                Item {
                    width: 1
                    height: 12
                }

                PanelButton {
                    text: "重置音效与倍速"
                    onClicked: Player.resetEffects()
                }

                Item {
                    width: 1
                    height: 8
                }
            }
        }
    }
}
