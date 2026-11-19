import QtQuick
import QtQuick.Layouts
import ".."

// 歌曲列表卡（共享组件）：圆角 20 的 surfaceContainerLow 卡片 + 可选标题栏 + 若干 SongRow。
// 首页「最近播放 / 最常播放」等区块可直接复用。
//
// 属性：
//   title           标题（showHeader 为 false 时忽略）
//   icon            标题图标（Material Symbols 名，可空）
//   songs           Song 数组
//   showHeader      是否显示标题栏（默认 true）
//   showPlayAll     标题栏右侧是否显示「播放全部」按钮（默认 true）
//   showIndex       行内是否显示序号列（默认 true）
//   maxRows         最多显示行数（-1 = 全部；超出时标题栏显示“查看全部”提示）
//   highlightKey    当前播放歌曲的 key（用于行高亮）
// 信号：
//   playAllClicked()
//   songActivated(int index)            行点击（页面用当前列表 setQueue）
//   songMoreClicked(var song, int index) 行「更多」按钮
//   songRightClicked(var song, int index) 行右键
Item {
    id: root

    property string title: ""
    property string icon: "music_note"
    property var songs: []
    property bool showHeader: true
    property bool showPlayAll: true
    property bool showIndex: true
    property int maxRows: -1
    property string highlightKey: Player.hasSong && Player.song ? Player.song.key : ""

    signal playAllClicked()
    signal songActivated(int index)
    signal songMoreClicked(var song, int index)
    signal songRightClicked(var song, int index)

    readonly property int visibleCount: {
        if (!songs)
            return 0
        return root.maxRows >= 0 ? Math.min(root.maxRows, songs.length) : songs.length
    }

    implicitWidth: parent ? parent.width : 0
    implicitHeight: card.height

    Rectangle {
        id: card

        anchors.left: parent.left
        anchors.right: parent.right
        anchors.leftMargin: Theme.spacingMd
        anchors.rightMargin: Theme.spacingMd
        anchors.top: parent.top
        height: body.implicitHeight + Theme.spacingSm * 2
        radius: Theme.radiusXl
        color: Theme.surfaceContainerLow

        Column {
            id: body

            width: parent.width
            y: Theme.spacingSm
            spacing: 0

            Item {
                visible: root.showHeader
                width: parent.width
                height: headerRow.implicitHeight + 20

                RowLayout {
                    id: headerRow

                    anchors.left: parent.left
                    anchors.right: parent.right
                    anchors.verticalCenter: parent.verticalCenter
                    anchors.leftMargin: Theme.spacingLg
                    anchors.rightMargin: Theme.spacingMd
                    spacing: Theme.spacingSm

                    NeriIcon {
                        visible: root.icon !== ""
                        name: root.icon
                        iconSize: 20
                        color: Theme.primary
                        Layout.alignment: Qt.AlignVCenter
                    }

                    Text {
                        Layout.fillWidth: true
                        text: root.title
                        elide: Text.ElideRight
                        maximumLineCount: 1
                        color: Theme.onSurfaceColor
                        font.family: Theme.fontFamily
                        font.pixelSize: Theme.textStyle("titleMedium").size
                        font.weight: Font.DemiBold
                    }

                    Text {
                        visible: root.maxRows >= 0 && root.songs.length > root.maxRows
                        text: "共 " + root.songs.length + " 首"
                        color: Theme.onSurfaceVariantColor
                        font.family: Theme.fontFamily
                        font.pixelSize: Theme.textStyle("labelMedium").size
                    }

                    LibraryButton {
                        visible: root.showPlayAll
                        text: "播放全部"
                        icon: "play_arrow"
                        onClicked: root.playAllClicked()
                    }
                }
            }

            Repeater {
                model: root.visibleCount

                delegate: Item {
                    id: rowWrapper

                    // Repeater 注入的 index；包一层避免与 SongRow.index 同名
                    property int itemIndex: index

                    width: body.width
                    height: 58

                    SongRow {
                        anchors.fill: parent
                        song: root.songs && root.songs[rowWrapper.itemIndex]
                              ? root.songs[rowWrapper.itemIndex] : null
                        index: rowWrapper.itemIndex
                        playing: !!root.highlightKey && song && song.key === root.highlightKey
                        showIndex: root.showIndex
                        onActivated: function(index) { root.songActivated(rowWrapper.itemIndex) }
                        onMoreClicked: function(song) { root.songMoreClicked(song, rowWrapper.itemIndex) }
                        onRightClicked: function(song) { root.songRightClicked(song, rowWrapper.itemIndex) }
                    }
                }
            }
        }
    }
}
