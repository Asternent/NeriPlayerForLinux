import QtQuick
import "../"

// 音乐文件夹列表：每行路径 + 移除按钮，底部「添加文件夹 / 重新扫描」。
Item {
    id: root

    property var folders: []

    signal addRequested()
    signal removeRequested(string path)
    signal rescanRequested()

    width: parent ? parent.width : 0
    implicitHeight: layout.height

    Column {
        id: layout
        width: parent.width
        spacing: 0

        Repeater {
            model: root.folders

            delegate: Item {
                id: folderRow
                required property var modelData
                width: layout.width
                height: 40

                NeriIcon {
                    x: 0
                    anchors.verticalCenter: parent.verticalCenter
                    name: "folder"
                    iconSize: 18
                    color: Theme.onSurfaceVariantColor
                }

                Text {
                    x: 26
                    width: Math.max(0, parent.width - 26 - removeButton.width - Theme.spacingSm)
                    anchors.verticalCenter: parent.verticalCenter
                    text: folderRow.modelData
                    color: Theme.onSurfaceColor
                    font.family: Theme.fontFamily
                    font.pixelSize: 12
                    elide: Text.ElideMiddle
                }

                SettingsButton {
                    id: removeButton
                    anchors.right: parent.right
                    anchors.verticalCenter: parent.verticalCenter
                    text: "移除"
                    onClicked: root.removeRequested(folderRow.modelData)
                }
            }
        }

        Item {
            width: 1
            height: 40
            visible: root.folders.length === 0

            Text {
                anchors.verticalCenter: parent.verticalCenter
                text: "还没有添加音乐文件夹"
                color: Theme.onSurfaceVariantColor
                font.family: Theme.fontFamily
                font.pixelSize: 12
            }
        }

        Item {
            width: 1
            height: 6
        }

        Row {
            spacing: Theme.spacingSm

            SettingsButton {
                text: "添加文件夹"
                icon: "add"
                onClicked: root.addRequested()
            }

            SettingsButton {
                text: "重新扫描"
                icon: "refresh"
                onClicked: root.rescanRequested()
            }
        }
    }
}
