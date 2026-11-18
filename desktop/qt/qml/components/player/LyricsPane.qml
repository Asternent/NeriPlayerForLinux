import QtQuick
import QtQuick.Controls
import "../"

// 歌词面板：逐行高亮 + 自动滚动 + 点击行 seek + 可选翻译行。
// 宽屏侧栏与全屏歌词页共用；数据直接来自全局 Player / Settings。
Item {
    id: pane

    readonly property var lines: Player.lyrics && Player.lyrics.lines ? Player.lyrics.lines : []
    readonly property int currentIndex: Player.currentLyricIndex()
    readonly property real fontScale: {
        const scale = Settings.get("lyricsFontScale", 1.0)
        return (typeof scale === "number" && scale > 0) ? scale : 1.0
    }
    readonly property bool showTranslation: Settings.get("showLyricTranslation", true) !== false
    readonly property bool loading: Player.lyricsLoading

    property bool autoScroll: true
    property real horizontalPadding: 24
    property real verticalPadding: 24

    function ensureVisible() {
        if (!autoScroll || lines.length === 0)
            return
        if (currentIndex < 0) {
            list.positionViewAtBeginning()
            return
        }
        list.positionViewAtIndex(Math.max(0, currentIndex - 2), ListView.Beginning)
    }

    onCurrentIndexChanged: ensureVisible()
    onLinesChanged: Qt.callLater(ensureVisible)

    // 加载中
    Item {
        anchors.centerIn: parent
        width: 40
        height: 40
        visible: pane.loading && pane.lines.length === 0

        NeriIcon {
            id: spinner
            anchors.centerIn: parent
            name: "progress_activity"
            iconSize: 30
            color: Theme.primary

            RotationAnimation on rotation {
                from: 0
                to: 360
                duration: 900
                loops: Animation.Infinite
                running: spinner.visible
            }
        }
    }

    // 无歌词
    PlayerEmptyState {
        anchors.centerIn: parent
        visible: !pane.loading && pane.lines.length === 0
        icon: "lyrics"
        title: "暂无歌词"
        hint: "本地歌曲可放置同名 .lrc 文件，在线歌曲会自动获取歌词"
        contentWidth: Math.min(360, pane.width - 32)
    }

    ListView {
        id: list

        anchors.fill: parent
        anchors.topMargin: pane.verticalPadding
        anchors.bottomMargin: pane.verticalPadding
        visible: pane.lines.length > 0 && !pane.loading
        clip: true
        model: pane.lines
        boundsBehavior: Flickable.StopAtBounds

        ScrollBar.vertical: ScrollBar {
            policy: ScrollBar.AsNeeded
        }

        delegate: Item {
            id: lineRow

            required property var modelData
            required property int index

            readonly property bool active: index === pane.currentIndex

            width: list.width
            height: lineColumn.implicitHeight + 14 + 4

            Rectangle {
                anchors.fill: parent
                anchors.leftMargin: 8
                anchors.rightMargin: 8
                radius: 10
                color: lineMouse.containsMouse ? Theme.withAlpha(Theme.onSurfaceColor, 0.06) : "transparent"

                Behavior on color {
                    ColorAnimation {
                        duration: Theme.motionShort
                    }
                }
            }

            Column {
                id: lineColumn

                anchors.left: parent.left
                anchors.right: parent.right
                anchors.leftMargin: pane.horizontalPadding
                anchors.rightMargin: pane.horizontalPadding
                anchors.verticalCenter: parent.verticalCenter
                spacing: 2

                Text {
                    width: parent.width
                    text: lineRow.modelData.text !== "" ? lineRow.modelData.text : "♪"
                    wrapMode: Text.Wrap
                    textFormat: Text.PlainText
                    color: lineRow.active ? Theme.primary : Theme.onSurfaceVariantColor
                    font.family: Theme.fontFamily
                    font.pixelSize: Math.round(20 * pane.fontScale)
                    font.weight: lineRow.active ? Font.Bold : Font.Normal
                    lineHeightMode: Text.FixedHeight
                    lineHeight: Math.round(28 * pane.fontScale)

                    Behavior on color {
                        ColorAnimation {
                            duration: Theme.motionShort
                        }
                    }
                }

                Text {
                    width: parent.width
                    visible: pane.showTranslation && lineRow.modelData.translation !== undefined
                             && lineRow.modelData.translation !== null && lineRow.modelData.translation !== ""
                    text: lineRow.modelData.translation || ""
                    wrapMode: Text.Wrap
                    textFormat: Text.PlainText
                    color: lineRow.active
                           ? Theme.withAlpha(Theme.primary, 0.8)
                           : Theme.withAlpha(Theme.onSurfaceVariantColor, 0.7)
                    font.family: Theme.fontFamily
                    font.pixelSize: Math.round(15 * pane.fontScale)
                    lineHeightMode: Text.FixedHeight
                    lineHeight: Math.round(21 * pane.fontScale)
                }
            }

            MouseArea {
                id: lineMouse

                anchors.fill: parent
                hoverEnabled: true
                cursorShape: Qt.PointingHandCursor
                onClicked: Player.seekTo(lineRow.modelData.timeMs)
            }
        }
    }
}
