import QtQuick
import "../"
import "../library"

// 可换行的歌手卡片流（复用 components/library/ArtistCard）。
Flow {
    id: grid

    property var items: []

    signal itemClicked(var item)

    width: parent ? parent.width : 0
    height: childrenRect.height
    leftPadding: 12
    rightPadding: 12
    topPadding: 4
    bottomPadding: 4
    spacing: 12

    Repeater {
        model: grid.items

        delegate: ArtistCard {
            readonly property var cardItem: modelData

            entry: cardItem
            onClicked: grid.itemClicked(cardItem)
        }
    }
}
