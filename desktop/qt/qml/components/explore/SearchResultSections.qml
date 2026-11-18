import QtQuick
import "../"
import "../library" as Lib

// 探索页搜索结果：歌曲（列表）/ 歌单·专辑（卡片网格）/ 歌手（卡片网格）三组。
Item {
    id: sections

    property var songs: []
    property var collections: []
    property var artists: []
    property string sourceName: "网易云"

    signal playAt(int index)
    signal songMore(var song)
    signal openCollection(var collection)
    signal openArtist(var artist)

    implicitHeight: column.implicitHeight

    // 包成 {song, idx}，避免 delegate 上下文里的 index 与 SongRow.index 同名冲突。
    readonly property var songEntries: {
        const list = sections.songs || []
        const output = []
        for (let i = 0; i < list.length; i++)
            output.push({ "song": list[i], "idx": i })
        return output
    }

    Column {
        id: column
        width: sections.width

        SectionHeader {
            width: column.width
            visible: (sections.songs || []).length > 0
            title: sections.sourceName + "歌曲（" + sections.songs.length + "）"
            icon: "music_note"
        }

        Column {
            id: songsColumn
            width: column.width
            visible: (sections.songs || []).length > 0

            Repeater {
                model: sections.songEntries

                delegate: Lib.SongRow {
                    width: songsColumn.width
                    song: modelData.song
                    index: modelData.idx
                    playing: Player.song !== null && Player.song.key === modelData.song.key
                    onActivated: function(index) { sections.playAt(index) }
                    onMoreClicked: function(song) { sections.songMore(song) }
                    onRightClicked: function(song) { sections.songMore(song) }
                }
            }
        }

        SectionHeader {
            width: column.width
            visible: (sections.collections || []).length > 0
            title: "歌单 / 专辑（" + sections.collections.length + "）"
            icon: "album"
        }

        CollectionGrid {
            width: column.width
            visible: (sections.collections || []).length > 0
            items: sections.collections
            onItemClicked: function(item) { sections.openCollection(item) }
        }

        SectionHeader {
            width: column.width
            visible: (sections.artists || []).length > 0
            title: "歌手（" + sections.artists.length + "）"
            icon: "person"
        }

        ArtistGrid {
            width: column.width
            visible: (sections.artists || []).length > 0
            items: sections.artists
            onItemClicked: function(item) { sections.openArtist(item) }
        }
    }
}
