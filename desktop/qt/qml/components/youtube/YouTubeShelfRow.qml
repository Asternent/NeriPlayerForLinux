import QtQuick
import ".."
import "../home"
import "../library"

// YouTube Music 首页横向栏目：标题 + 可横向滚动的卡片行。
// items 是 ytmusic.home 的 YouTubeHomeItem 数组（kind = song / album / playlist / artist）。
//
// 卡片复用首页与媒体库的既有组件（TrackCard / CollectionCard / ArtistCard），
// 点击后的跳转交给父级（探索页 / 首页区块）处理。
Item {
    id: row

    property string title: ""
    property var items: []
    property int maxItems: 12

    /** 播放歌曲：song + 同栏目内歌曲队列 + 下标。 */
    signal playSong(var song, var songs, int index)
    signal openCollection(var collection)
    signal openArtist(var artist)

    readonly property var displayItems: {
        const list = row.items || []
        return list.length > row.maxItems ? list.slice(0, row.maxItems) : list
    }

    readonly property var shelfSongs: {
        const out = []
        for (let i = 0; i < row.displayItems.length; i++) {
            const entry = row.displayItems[i]
            if (entry && entry.kind === "song" && entry.song)
                out.push(entry.song)
        }
        return out
    }

    readonly property real cardRowHeight: 214

    implicitHeight: sectionHeader.height + Theme.spacingXs + cardRowHeight

    SectionHeader {
        id: sectionHeader

        width: row.width
        height: 44
        title: row.title
        icon: "smart_display"
    }

    Flickable {
        id: hFlick

        anchors.top: sectionHeader.bottom
        anchors.topMargin: Theme.spacingXs
        width: row.width
        height: row.cardRowHeight
        contentWidth: cardRow.width + 24
        contentHeight: height
        clip: true
        boundsBehavior: Flickable.StopAtBounds
        flickableDirection: Flickable.HorizontalFlick

        WheelHandler {
            onWheel: function(wheel) {
                let dy = wheel.pixelDelta.y !== 0 ? wheel.pixelDelta.y : wheel.angleDelta.y / 120 * 60
                let dx = wheel.pixelDelta.x !== 0 ? wheel.pixelDelta.x : wheel.angleDelta.x / 120 * 60
                const amount = Math.abs(dx) > Math.abs(dy) ? dx : dy
                if (amount === 0)
                    return
                const maxX = Math.max(0, hFlick.contentWidth - hFlick.width)
                const target = Math.max(0, Math.min(maxX, hFlick.contentX - amount))
                if (Math.abs(target - hFlick.contentX) < 0.01) {
                    wheel.accepted = false
                    return
                }
                hFlick.contentX = target
                wheel.accepted = true
            }
        }

        Row {
            id: cardRow

            x: 12
            spacing: 10

            Repeater {
                model: row.displayItems

                delegate: Item {
                    id: cardHost

                    readonly property var entry: modelData
                    readonly property string kind: entry && entry.kind ? entry.kind : ""

                    width: kind === "artist" ? 136 : 150
                    height: row.cardRowHeight

                    TrackCard {
                        anchors.top: parent.top
                        anchors.left: parent.left
                        width: parent.width
                        visible: cardHost.kind === "song" && cardHost.entry.song !== null
                        song: cardHost.entry ? cardHost.entry.song : null
                        playing: Player.hasSong && Player.song !== null && cardHost.entry.song !== null
                                 && Player.song.key === cardHost.entry.song.key && Player.playing
                        onActivated: {
                            if (!cardHost.entry || !cardHost.entry.song)
                                return
                            const songs = row.shelfSongs
                            const index = songs.findIndex(function(s) {
                                return s.key === cardHost.entry.song.key
                            })
                            row.playSong(cardHost.entry.song, songs, index < 0 ? 0 : index)
                        }
                    }

                    CollectionCard {
                        anchors.top: parent.top
                        anchors.left: parent.left
                        width: parent.width
                        visible: cardHost.kind === "album" || cardHost.kind === "playlist"
                        entry: cardHost.entry ? cardHost.entry.collection : null
                        placeholderIcon: cardHost.kind === "album" ? "album" : "queue_music"
                        onClicked: {
                            if (cardHost.entry && cardHost.entry.collection)
                                row.openCollection(cardHost.entry.collection)
                        }
                    }

                    ArtistCard {
                        anchors.top: parent.top
                        anchors.left: parent.left
                        visible: cardHost.kind === "artist"
                        entry: cardHost.entry ? cardHost.entry.artist : null
                        onClicked: {
                            if (cardHost.entry && cardHost.entry.artist)
                                row.openArtist(cardHost.entry.artist)
                        }
                    }
                }
            }
        }
    }
}
