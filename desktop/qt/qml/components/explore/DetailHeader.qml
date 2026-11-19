import QtQuick
import "../"

// 在线详情页头部：封面/头像 + 标题 + 副标题 + 描述 + 操作按钮。
// 歌单/专辑用圆角封面，歌手用圆形头像。
Item {
    id: header

    property string coverUrl: ""
    property string localCoverPath: ""
    property bool circular: false
    property string title: ""
    property string subtitle: ""
    property string description: ""
    property bool showPlayAll: true
    property bool showCollect: false
    property string playAllText: "播放全部"
    property string collectText: "收藏到本地歌单"

    signal playAllClicked()
    signal collectClicked()

    implicitHeight: content.height + 32

    readonly property real coverSize: 132

    Row {
        id: content
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.top: parent.top
        anchors.leftMargin: 16
        anchors.rightMargin: 16
        anchors.topMargin: 16
        spacing: 16

        CoverArt {
            id: cover
            width: header.coverSize
            height: header.coverSize
            radius: header.circular ? width / 2 : Theme.radiusXl
            path: header.localCoverPath
            url: header.coverUrl
            placeholderIconSize: 42
        }

        Column {
            id: info
            width: content.width - cover.width - content.spacing
            spacing: 6

            Text {
                width: parent.width
                visible: header.title !== ""
                text: header.title
                wrapMode: Text.Wrap
                maximumLineCount: 2
                elide: Text.ElideRight
                color: Theme.onSurfaceColor
                font.family: Theme.fontFamily
                font.pixelSize: 24
                font.weight: Font.DemiBold
            }

            Text {
                width: parent.width
                visible: header.subtitle !== ""
                text: header.subtitle
                wrapMode: Text.Wrap
                maximumLineCount: 2
                elide: Text.ElideRight
                color: Theme.onSurfaceVariantColor
                font.family: Theme.fontFamily
                font.pixelSize: 13
            }

            Text {
                width: parent.width
                visible: header.description !== ""
                text: header.description
                wrapMode: Text.Wrap
                maximumLineCount: 2
                elide: Text.ElideRight
                color: Theme.onSurfaceVariantColor
                font.family: Theme.fontFamily
                font.pixelSize: 12
            }

            Item { width: 1; height: 2 }

            Row {
                id: actions
                spacing: 8

                // 播放全部
                Rectangle {
                    id: playButton
                    height: 36
                    width: playLabel.implicitWidth + 32
                    radius: 18
                    visible: header.showPlayAll
                    color: playMouse.containsMouse ? Theme.mix(Theme.primary, Theme.onPrimaryColor, 0.1) : Theme.primary

                    Behavior on color {
                        ColorAnimation { duration: Theme.motionShort }
                    }

                    Row {
                        id: playLabel
                        anchors.centerIn: parent
                        height: 20
                        spacing: 6

                        NeriIcon {
                            width: 20
                            height: 20
                            name: "play_arrow"
                            filled: true
                            iconSize: 20
                            color: Theme.onPrimaryColor
                        }

                        Text {
                            height: 20
                            text: header.playAllText
                            verticalAlignment: Text.AlignVCenter
                            color: Theme.onPrimaryColor
                            font.family: Theme.fontFamily
                            font.pixelSize: 13
                            font.weight: Font.Medium
                        }
                    }

                    MouseArea {
                        id: playMouse
                        anchors.fill: parent
                        hoverEnabled: true
                        cursorShape: Qt.PointingHandCursor
                        onClicked: header.playAllClicked()
                    }
                }

                // 收藏到本地歌单
                Rectangle {
                    id: collectButton
                    height: 36
                    width: collectLabel.implicitWidth + 32
                    radius: 18
                    visible: header.showCollect
                    color: collectMouse.containsMouse ? Theme.withAlpha(Theme.primary, 0.1) : "transparent"
                    border.width: 1
                    border.color: Theme.outlineVariant

                    Row {
                        id: collectLabel
                        anchors.centerIn: parent
                        height: 20
                        spacing: 6

                        NeriIcon {
                            width: 18
                            height: 18
                            name: "playlist_add"
                            iconSize: 18
                            color: Theme.primary
                        }

                        Text {
                            height: 20
                            text: header.collectText
                            verticalAlignment: Text.AlignVCenter
                            color: Theme.primary
                            font.family: Theme.fontFamily
                            font.pixelSize: 13
                            font.weight: Font.Medium
                        }
                    }

                    MouseArea {
                        id: collectMouse
                        anchors.fill: parent
                        hoverEnabled: true
                        cursorShape: Qt.PointingHandCursor
                        onClicked: header.collectClicked()
                    }
                }
            }
        }
    }
}
