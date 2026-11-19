import QtQuick
import ".."

// 播放页信息行：小图标 + 单行文字，可选点击（歌手/专辑入口）。
// 字号与配色由调用方通过 fontPixelSize / textColor 控制，方便宽窄布局复用。
Item {
    id: row

    property string icon: "person"
    property string text: ""
    property bool clickable: false
    property color textColor: np.foregroundDim
    property color hoverColor: np.foreground
    property real fontPixelSize: 15
    property real iconSize: 17

    NowPlayingTheme { id: np }

    signal clicked()
    signal activity()

    readonly property bool hovered: rowHover.hovered

    width: parent ? parent.width : 0
    implicitHeight: Math.max(textItem.implicitHeight, row.iconSize) + 6
    visible: text !== ""

    Row {
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.verticalCenter: parent.verticalCenter
        spacing: Theme.spacingSm

        NeriIcon {
            anchors.verticalCenter: parent.verticalCenter
            name: row.icon
            iconSize: row.iconSize
            color: row.clickable && row.hovered ? row.hoverColor
                                                : Theme.withAlpha(row.textColor, 0.8)
        }

        Text {
            id: textItem

            anchors.verticalCenter: parent.verticalCenter
            width: Math.max(0, parent.width - row.iconSize - Theme.spacingSm)
            text: row.text
            color: row.clickable && row.hovered ? row.hoverColor : row.textColor
            font.family: Theme.fontFamily
            font.pixelSize: row.fontPixelSize
            elide: Text.ElideRight
            wrapMode: Text.NoWrap
            textFormat: Text.PlainText
            // 可点击时加下划线，暗示这是跳转入口
            font.underline: row.clickable && row.hovered

            Behavior on color {
                ColorAnimation { duration: Theme.motionShort }
            }
        }
    }

    HoverHandler {
        id: rowHover
        cursorShape: row.clickable ? Qt.PointingHandCursor : Qt.ArrowCursor
    }

    MouseArea {
        id: rowMouse

        anchors.fill: parent
        enabled: row.clickable
        hoverEnabled: row.clickable

        property real lastX: -9999
        property real lastY: -9999

        onClicked: row.clicked()
        onPositionChanged: function(mouse) {
            if (Math.abs(mouse.x - lastX) + Math.abs(mouse.y - lastY) < 2)
                return
            lastX = mouse.x
            lastY = mouse.y
            row.activity()
        }
    }

    Connections {
        target: rowHover
        function onHoveredChanged() {
            if (rowHover.hovered)
                row.activity()
        }
    }
}
