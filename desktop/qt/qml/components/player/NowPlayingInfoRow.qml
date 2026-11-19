import QtQuick
import QtQuick.Controls
import ".."

// 播放页信息行：小图标 + 单行文字。
//
// 文字是可选中的（右键菜单可复制歌手 / 专辑）；跳转详情改由左侧小图标触发，
// 否则鼠标按下会被可选中文字吃掉，无法同时支持「拖选复制」和「单击跳转」。
Item {
    id: row

    property string icon: "person"
    property string text: ""
    property bool clickable: false
    property string clickTooltip: ""
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

    // 图标本身很小，扩大点击热区但仍占原来的布局宽度
    Item {
        id: iconBox

        width: row.iconSize
        height: Math.max(row.iconSize, textItem.implicitHeight)
        anchors.verticalCenter: parent.verticalCenter

        NeriIcon {
            anchors.centerIn: parent
            name: row.icon
            iconSize: row.iconSize
            color: row.clickable && row.hovered ? row.hoverColor
                                                : Theme.withAlpha(row.textColor, 0.8)
        }

        HoverHandler {
            id: iconHover
            cursorShape: row.clickable ? Qt.PointingHandCursor : Qt.ArrowCursor
        }

        MouseArea {
            anchors.fill: parent
            anchors.leftMargin: -6
            anchors.rightMargin: -6
            enabled: row.clickable
            hoverEnabled: row.clickable
            cursorShape: Qt.PointingHandCursor
            onClicked: row.clicked()
            onEntered: row.activity()
        }

        ToolTip.visible: row.clickable && row.clickTooltip !== "" && iconHover.hovered
        ToolTip.text: row.clickTooltip
    }

    NowPlayingSelectableText {
        id: textItem

        anchors.left: iconBox.right
        anchors.right: parent.right
        anchors.leftMargin: Theme.spacingSm
        anchors.verticalCenter: parent.verticalCenter
        text: row.text
        color: row.clickable && row.hovered ? row.hoverColor : row.textColor
        fontPixelSize: row.fontPixelSize
        wrap: false
        underlined: row.clickable && row.hovered
        onActivity: row.activity()
    }

    HoverHandler {
        id: rowHover
        cursorShape: row.clickable ? Qt.PointingHandCursor : Qt.ArrowCursor
    }

    Connections {
        target: rowHover
        function onHoveredChanged() {
            if (rowHover.hovered)
                row.activity()
        }
    }
}
