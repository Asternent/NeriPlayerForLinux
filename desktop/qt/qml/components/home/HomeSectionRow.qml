import QtQuick
import "../"
import "../library" as Library
import "../library"

// 首页栏目容器：标题 + 内容。由 HomePage 按后端 sections 顺序实例化。
// kind:
//   "songs"       横向歌曲卡（TrackCard）
//   "continue"    横向「继续播放」卡（ContinuePlayingCard）
//   "collections" 横向歌单/专辑卡（components/library/CollectionCard）
//   "list"        竖向歌曲行卡片（components/library/SongRow，最多 6 行，对应旧 Compose 的 SongListCard）
Item {
    id: row

    property string title: ""
    property string icon: ""
    property string kind: "songs"
    property var items: []
    property string actionText: ""

    signal actionClicked()
    signal songActivated(int index)
    signal collectionActivated(var collection)

    readonly property int maxItems: kind === "list" ? 6 : 12
    readonly property int count: items ? Math.min(items.length, maxItems) : 0
    readonly property var displayItems: {
        if (!items)
            return []
        return items.length > maxItems ? items.slice(0, maxItems) : items
    }

    readonly property real cardRowHeight: {
        switch (kind) {
        case "continue":
            return 72
        case "list":
            return count * 58 + 12
        default:
            return 204
        }
    }

    implicitHeight: sectionHeader.height + Theme.spacingXs + cardRowHeight

    SectionHeader {
        id: sectionHeader

        width: row.width
        height: 44
        title: row.title
        icon: row.icon
        actionText: row.actionText
        onActionClicked: row.actionClicked()
    }

    // ---- 横向卡片（歌曲 / 继续播放 / 歌单） ----
    Flickable {
        id: hFlick

        anchors.top: sectionHeader.bottom
        anchors.topMargin: Theme.spacingXs
        width: row.width
        height: row.cardRowHeight
        visible: row.kind !== "list"
        contentWidth: cardRow.width + 24
        contentHeight: height
        clip: true
        boundsBehavior: Flickable.StopAtBounds
        flickableDirection: Flickable.HorizontalFlick

        // 滚轮横向滚动；已到两端时把事件让给外层纵向滚动
        WheelHandler {
            onWheel: function(wheel) {
                const delta = wheel.angleDelta.y !== 0 ? wheel.angleDelta.y : wheel.angleDelta.x
                if (delta === 0)
                    return
                const maxX = Math.max(0, hFlick.contentWidth - hFlick.width)
                const next = Math.max(0, Math.min(maxX, hFlick.contentX - delta))
                if (next !== hFlick.contentX) {
                    hFlick.contentX = next
                    wheel.accepted = true
                }
            }
        }

        Row {
            id: cardRow

            x: 12
            spacing: 10

            Repeater {
                model: row.kind === "continue" ? row.displayItems : []

                delegate: ContinuePlayingCard {
                    width: implicitWidth
                    height: implicitHeight
                    song: modelData
                    playing: Player.hasSong && Player.song !== null && Player.song.key === modelData.key && Player.playing
                    onActivated: row.songActivated(index)
                }
            }

            Repeater {
                model: row.kind === "songs" ? row.displayItems : []

                delegate: TrackCard {
                    width: implicitWidth
                    height: implicitHeight
                    song: modelData
                    playing: Player.hasSong && Player.song !== null && Player.song.key === modelData.key && Player.playing
                    onActivated: row.songActivated(index)
                }
            }

            Repeater {
                model: row.kind === "collections" ? row.displayItems : []

                delegate: Library.CollectionCard {
                    width: 150
                    height: implicitHeight
                    name: modelData.name ? modelData.name : ""
                    subtitle: modelData.trackCount > 0 ? modelData.trackCount + " 首"
                                                      : (modelData.creator ? modelData.creator : "")
                    coverUrl: modelData.coverUrl ? modelData.coverUrl : ""
                    onClicked: row.collectionActivated(modelData)
                }
            }
        }
    }

    // ---- 竖向歌曲列表卡 ----
    Rectangle {
        id: listCard

        anchors.top: sectionHeader.bottom
        anchors.topMargin: Theme.spacingXs
        width: row.width
        height: row.cardRowHeight
        visible: row.kind === "list"
        radius: Theme.radiusXl
        color: Theme.surfaceContainerLow

        Column {
            id: listColumn

            anchors.fill: parent
            anchors.margins: 6

            Repeater {
                model: row.kind === "list" ? row.displayItems : []

                delegate: Item {
                    id: songRowDelegate

                    // 取 Repeater 的 index（SongRow 自己也有 index 属性，不能直接写 index: index）
                    property int rowIndex: index

                    width: listColumn.width
                    height: 58

                    Library.SongRow {
                        anchors.fill: parent
                        song: modelData
                        index: songRowDelegate.rowIndex
                        showIndex: true
                        showAlbum: false
                        showMore: false
                        playing: Player.hasSong && Player.song !== null
                                 && Player.song.key === modelData.key && Player.playing
                        onActivated: function(rowIndex) {
                            row.songActivated(songRowDelegate.rowIndex)
                        }
                    }
                }
            }
        }
    }
}
