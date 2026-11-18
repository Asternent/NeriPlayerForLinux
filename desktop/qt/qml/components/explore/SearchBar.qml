import QtQuick
import "../"

// 探索页搜索框：回车提交、Esc 放弃焦点、右侧清空按钮。
Item {
    id: root

    property alias text: input.text
    property string placeholder: "搜索歌曲、歌单、歌手、专辑"

    signal submitted(string keyword)
    signal cleared()

    implicitHeight: 44

    function submit() {
        const keyword = input.text.trim()
        if (keyword !== "")
            root.submitted(keyword)
    }

    function clear() {
        input.text = ""
        root.cleared()
    }

    function takeFocus() {
        input.forceActiveFocus()
    }

    Rectangle {
        anchors.fill: parent
        radius: height / 2
        color: Theme.surfaceContainerHigh
        border.width: input.activeFocus ? 1 : 0
        border.color: Theme.primary

        Behavior on border.width {
            NumberAnimation { duration: Theme.motionShort }
        }

        NeriIcon {
            id: searchIcon
            anchors.left: parent.left
            anchors.leftMargin: 14
            anchors.verticalCenter: parent.verticalCenter
            name: "search"
            iconSize: 20
            color: input.activeFocus ? Theme.primary : Theme.onSurfaceVariantColor
        }

        Text {
            anchors.left: searchIcon.right
            anchors.leftMargin: 8
            anchors.right: clearButton.left
            anchors.verticalCenter: parent.verticalCenter
            visible: input.text === ""
            text: root.placeholder
            elide: Text.ElideRight
            color: Theme.onSurfaceVariantColor
            font.family: Theme.fontFamily
            font.pixelSize: 14
        }

        TextInput {
            id: input
            anchors.left: searchIcon.right
            anchors.leftMargin: 8
            anchors.right: clearButton.left
            anchors.verticalCenter: parent.verticalCenter
            clip: true
            selectByMouse: true
            color: Theme.onSurfaceColor
            selectionColor: Theme.primary
            selectedTextColor: Theme.onPrimaryColor
            font.family: Theme.fontFamily
            font.pixelSize: 14
            onAccepted: root.submit()
            Keys.onEscapePressed: function(event) {
                input.focus = false
                event.accepted = true
            }
        }

        IconButton {
            id: clearButton
            anchors.right: parent.right
            anchors.rightMargin: 3
            anchors.verticalCenter: parent.verticalCenter
            visible: input.text !== ""
            icon: "close"
            iconSize: 18
            size: 36
            tooltip: "清空"
            onClicked: root.clear()
        }
    }
}
