import QtQuick
import QtQuick.Controls
import ".."

// 播放页信息行：小图标 + 单行文字。
//
// 文字是可选中的（右键菜单可复制歌手 / 专辑），单击文字或左侧小图标都能跳转详情：
// 文字上叠 TapHandler(gesturePolicy=DragThreshold)，拖动超过阈值时仍交给可选中文字做拖选。
// alignment 跟随播放页设置：LEFT 时图标贴左、文字填满剩余宽度；
// CENTER / RIGHT 时「图标 + 文字」作为一个整体居中 / 靠右，文字按内容宽度排版。
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
    /** LEFT / CENTER / RIGHT */
    property string alignment: "LEFT"

    NowPlayingTheme { id: np }

    signal clicked()
    signal activity()

    readonly property bool hovered: rowHover.hovered
    readonly property int alignmentMode: alignment === "CENTER" ? 1 : (alignment === "RIGHT" ? 2 : 0)
    readonly property real gap: Theme.spacingSm
    readonly property real maxTextWidth: Math.max(0, row.width - iconBox.width - gap)
    readonly property real textWidth: alignmentMode === 0
                                        ? maxTextWidth
                                        : Math.min(textItem.naturalWidth, maxTextWidth)

    width: parent ? parent.width : 0
    implicitHeight: Math.max(textItem.implicitHeight, row.iconSize) + 6
    visible: text !== ""

    // 图标本身很小，扩大点击热区但仍占原来的布局宽度
    Item {
        id: iconBox

        width: row.iconSize
        height: Math.max(row.iconSize, textItem.implicitHeight)
        y: (row.height - height) / 2
        // 非左对齐时图标跟随文字整体移动
        x: row.alignmentMode === 0 ? 0
           : row.alignmentMode === 1 ? (row.width - row.gap - row.textWidth - width) / 2
                                     : row.width - row.gap - row.textWidth - width

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

        x: row.alignmentMode === 0 ? iconBox.width + row.gap
                                   : iconBox.x + iconBox.width + row.gap
        width: row.textWidth
        anchors.verticalCenter: parent.verticalCenter
        text: row.text
        color: row.clickable && row.hovered ? row.hoverColor : row.textColor
        fontPixelSize: row.fontPixelSize
        wrap: false
        underlined: row.clickable && row.hovered
        handCursor: row.clickable
        tapEnabled: row.clickable
        horizontalAlignment: row.alignmentMode === 1 ? Text.AlignHCenter
                                                     : (row.alignmentMode === 2 ? Text.AlignRight
                                                                                : Text.AlignLeft)
        onActivity: row.activity()
        // 未拖动的单击 = 跳转；拖动仍交给可选中文字做拖选。
        onTapped: {
            row.activity()
            row.clicked()
        }
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
