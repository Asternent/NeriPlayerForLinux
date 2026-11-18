import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../"

// 全屏歌词覆盖层：返回、字号调节、逐行高亮、点击行 seek。
// 覆盖整个播放页（含顶部栏），Escape / 返回按钮关闭。
Item {
    id: root

    visible: false
    signal closeRequested()

    Rectangle {
        anchors.fill: parent
        color: Theme.background
    }

    Item {
        id: scope

        anchors.fill: parent

        Item {
            id: topBar

            anchors.top: parent.top
            anchors.left: parent.left
            anchors.right: parent.right
            height: 64

            IconButton {
                id: backButton

                anchors.left: parent.left
                anchors.leftMargin: 8
                anchors.verticalCenter: parent.verticalCenter
                icon: "arrow_back"
                iconSize: 24
                size: 48
                onClicked: root.closeRequested()
            }

            Item {
                id: rightControls

                anchors.right: parent.right
                anchors.rightMargin: 8
                anchors.verticalCenter: parent.verticalCenter
                width: rightRow.width
                height: 48

                RowLayout {
                    id: rightRow

                    anchors.verticalCenter: parent.verticalCenter
                    spacing: 2

                    Text {
                        Layout.alignment: Qt.AlignVCenter
                        text: "字号 " + root.currentScale.toFixed(2) + "x"
                        color: Theme.onSurfaceVariantColor
                        font.family: Theme.fontFamily
                        font.pixelSize: Theme.textStyle("labelSmall").size
                        font.weight: Theme.textStyle("labelSmall").weight
                    }

                    IconButton {
                        Layout.alignment: Qt.AlignVCenter
                        icon: "text_decrease"
                        iconSize: 22
                        size: 40
                        tooltip: "缩小歌词字号"
                        onClicked: root.adjustScale(-0.1)
                    }

                    IconButton {
                        Layout.alignment: Qt.AlignVCenter
                        icon: "text_increase"
                        iconSize: 22
                        size: 40
                        tooltip: "放大歌词字号"
                        onClicked: root.adjustScale(0.1)
                    }
                }
            }

            Column {
                anchors.left: backButton.right
                anchors.leftMargin: 8
                anchors.right: rightControls.left
                anchors.rightMargin: 12
                anchors.verticalCenter: parent.verticalCenter
                spacing: 2

                Text {
                    width: parent.width
                    text: Player.hasSong ? Player.title : "歌词"
                    elide: Text.ElideRight
                    color: Theme.onSurfaceColor
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.textStyle("titleMedium").size
                    font.weight: Font.DemiBold
                }

                Text {
                    width: parent.width
                    visible: Player.hasSong && Player.artist !== ""
                    text: Player.artist
                    elide: Text.ElideRight
                    color: Theme.onSurfaceVariantColor
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.textStyle("labelSmall").size
                }
            }
        }

        LyricsPane {
            id: lyricsPane

            anchors.top: topBar.bottom
            anchors.bottom: parent.bottom
            anchors.horizontalCenter: parent.horizontalCenter
            width: Math.min(parent.width, 960)
            horizontalPadding: 32
            verticalPadding: 24
        }
    }

    // 用 Shortcut 而不是焦点 Keys，避免抢走全局快捷键的焦点（关闭后无需恢复焦点）。
    Shortcut {
        enabled: root.visible
        sequence: "Escape"
        onActivated: root.closeRequested()
    }

    readonly property real currentScale: {
        const scale = Settings.get("lyricsFontScale", 1.0)
        return (typeof scale === "number" && scale > 0) ? scale : 1.0
    }

    function adjustScale(delta) {
        const next = Math.max(0.7, Math.min(1.8, Math.round((currentScale + delta) * 10) / 10))
        Settings.set("lyricsFontScale", next)
    }
}
