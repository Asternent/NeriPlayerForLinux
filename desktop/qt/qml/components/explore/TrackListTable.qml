import QtQuick
import QtQuick.Controls
import "../"
import "../library" as Lib

// 虚拟化歌曲列表：可选顶部自定义内容（详情页头部）+ 歌曲行。
// 大歌单 / B 站收藏夹可能有上千首，用 ListView 只创建可见行。
Item {
    id: table

    property var songs: []
    property bool showIndex: true
    property bool showAlbum: true
    property Component headerContent: null
    property int bottomPadding: 24

    signal songActivated(int index)
    signal songMoreClicked(int index, var song)

    // 把歌曲数组包成 {song, idx}，避免 delegate 上下文里的 index 与 SongRow.index 同名冲突。
    readonly property var rows: {
        const list = table.songs || []
        const output = []
        for (let i = 0; i < list.length; i++)
            output.push({ "song": list[i], "idx": i })
        return output
    }

    ListView {
        id: listView
        anchors.fill: parent
        clip: true
        model: table.rows
        boundsBehavior: Flickable.StopAtBounds
        bottomMargin: table.bottomPadding
        ScrollBar.vertical: ScrollBar { policy: ScrollBar.AsNeeded }

        header: Column {
            width: listView.width

            Loader {
                id: headerLoader
                width: parent.width
                height: item ? item.implicitHeight : 0
                sourceComponent: table.headerContent
                visible: table.headerContent !== null
            }
        }

        delegate: Lib.SongRow {
            width: listView.width
            song: modelData.song
            index: modelData.idx
            playing: Player.song !== null && Player.song.key === modelData.song.key
            showIndex: table.showIndex
            showAlbum: table.showAlbum
            onActivated: function(index) { table.songActivated(index) }
            onMoreClicked: function(song) { table.songMoreClicked(modelData.idx, song) }
            onRightClicked: function(song) { table.songMoreClicked(modelData.idx, song) }
        }
    }
}
