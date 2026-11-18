import QtQuick
import "IconMap.js" as IconMap

// Material Symbols 图标。用法：NeriIcon { name: "home"; iconSize: 24; color: Theme.primary }
Text {
    id: icon

    property string name: "circle"
    property bool filled: false
    property real iconSize: 24

    text: String.fromCodePoint(IconMap.codepoint(name))
    color: Theme.onSurfaceColor
    font.family: Theme.iconFontFamily
    font.pixelSize: iconSize
    font.variableAxes: ({ "FILL": filled ? 1 : 0, "wght": 400, "opsz": 24 })
    horizontalAlignment: Text.AlignHCenter
    verticalAlignment: Text.AlignVCenter
    renderType: Text.QtRendering
    textFormat: Text.PlainText
}
