import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../"

// 播放队列面板：当前项高亮、点击播放、上移/下移/移除、底部清空。
PanelScaffold {
    id: panel

    title: "播放队列（" + Player.queue.length + " 首）"
    panelWidth: 420

    Item {
        id: body

        anchors.fill: parent

        PlayerEmptyState {
            anchors.centerIn: parent
            visible: Player.queue.length === 0
            icon: "queue_music"
            title: "播放队列是空的"
            hint: "在媒体库或探索中选择歌曲后会自动加入队列"
            contentWidth: Math.min(340, panel.width - 48)
        }

        ListView {
            id: queueList

            anchors.top: parent.top
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.bottom: footer.top
            visible: Player.queue.length > 0
            clip: true
            model: Player.queue
            boundsBehavior: Flickable.StopAtBounds

            ScrollBar.vertical: ScrollBar {
                policy: ScrollBar.AsNeeded
            }

            delegate: Item {
                id: queueRow

                required property var modelData
                required property int index

                readonly property bool current: index === Player.queueIndex

                width: queueList.width
                height: 56

                Rectangle {
                    anchors.fill: parent
                    color: queueRow.current
                           ? Theme.withAlpha(Theme.secondaryContainer, 0.5)
                           : rowMouse.containsMouse ? Theme.withAlpha(Theme.onSurfaceColor, 0.05) : "transparent"

                    Behavior on color {
                        ColorAnimation {
                            duration: Theme.motionShort
                        }
                    }

                    // 当前项左侧强调条
                    Rectangle {
                        anchors.left: parent.left
                        anchors.verticalCenter: parent.verticalCenter
                        width: 3
                        height: 26
                        radius: 1.5
                        visible: queueRow.current
                        color: Theme.primary
                    }
                }

                MouseArea {
                    id: rowMouse

                    anchors.fill: parent
                    hoverEnabled: true
                    cursorShape: Qt.PointingHandCursor
                    onClicked: Player.playQueueIndex(queueRow.index)
                }

                RowLayout {
                    anchors.fill: parent
                    anchors.leftMargin: 14
                    anchors.rightMargin: 6
                    spacing: 8

                    Item {
                        Layout.preferredWidth: 26
                        Layout.fillHeight: true

                        NeriIcon {
                            anchors.centerIn: parent
                            visible: queueRow.current
                            name: Player.playing ? "graphic_eq" : "pause"
                            iconSize: 16
                            color: Theme.primary
                        }

                        Text {
                            anchors.centerIn: parent
                            visible: !queueRow.current
                            text: (queueRow.index + 1).toString()
                            color: Theme.onSurfaceVariantColor
                            font.family: Theme.fontFamily
                            font.pixelSize: Theme.textStyle("labelMedium").size
                            font.weight: Theme.textStyle("labelMedium").weight
                        }
                    }

                    ColumnLayout {
                        Layout.fillWidth: true
                        Layout.alignment: Qt.AlignVCenter
                        spacing: 2

                        Text {
                            Layout.fillWidth: true
                            text: queueRow.modelData.title !== "" ? queueRow.modelData.title : "未知歌曲"
                            elide: Text.ElideRight
                            color: queueRow.current ? Theme.primary : Theme.onSurfaceColor
                            font.family: Theme.fontFamily
                            font.pixelSize: Theme.textStyle("bodyMedium").size
                        }

                        Text {
                            Layout.fillWidth: true
                            text: queueRow.modelData.artist !== "" ? queueRow.modelData.artist : "未知艺术家"
                            elide: Text.ElideRight
                            color: Theme.onSurfaceVariantColor
                            font.family: Theme.fontFamily
                            font.pixelSize: Theme.textStyle("labelSmall").size
                        }
                    }

                    IconButton {
                        Layout.alignment: Qt.AlignVCenter
                        icon: "arrow_upward"
                        iconSize: 16
                        size: 30
                        enabled: queueRow.index > 0
                        opacity: enabled ? 1 : 0.35
                        tooltip: "上移"
                        onClicked: Player.moveInQueue(queueRow.index, queueRow.index - 1)
                    }

                    IconButton {
                        Layout.alignment: Qt.AlignVCenter
                        icon: "arrow_downward"
                        iconSize: 16
                        size: 30
                        enabled: queueRow.index < Player.queue.length - 1
                        opacity: enabled ? 1 : 0.35
                        tooltip: "下移"
                        onClicked: Player.moveInQueue(queueRow.index, queueRow.index + 1)
                    }

                    IconButton {
                        Layout.alignment: Qt.AlignVCenter
                        icon: "delete"
                        iconSize: 16
                        size: 30
                        tooltip: "从队列移除"
                        onClicked: Player.removeFromQueue(queueRow.index)
                    }
                }
            }
        }

        Item {
            id: footer

            anchors.left: parent.left
            anchors.right: parent.right
            anchors.bottom: parent.bottom
            height: 64
            visible: Player.queue.length > 0

            Rectangle {
                anchors.top: parent.top
                anchors.left: parent.left
                anchors.right: parent.right
                height: 1
                color: Theme.withAlpha(Theme.outlineVariant, 0.6)
            }

            PanelButton {
                anchors.right: parent.right
                anchors.rightMargin: 16
                anchors.verticalCenter: parent.verticalCenter
                text: "清空队列"
                onClicked: Player.clearQueue()
            }
        }

        // 打开面板/切歌时定位到正在播放的项
        function scrollToCurrent() {
            if (Player.queueIndex >= 0 && Player.queueIndex < Player.queue.length)
                queueList.positionViewAtIndex(Player.queueIndex, ListView.Contain)
        }

        Connections {
            target: Player

            function onQueueIndexChanged() {
                body.scrollToCurrent()
            }

            function onQueueChanged() {
                body.scrollToCurrent()
            }
        }

        Component.onCompleted: Qt.callLater(body.scrollToCurrent)
    }
}
