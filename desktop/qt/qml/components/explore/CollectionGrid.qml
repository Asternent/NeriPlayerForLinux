import QtQuick
import "../"
import "../library"

// 可换行的歌单 / 专辑卡片流（复用 components/library/CollectionCard）。
Flow {
    id: grid

    property var items: []
    property real cardWidth: 150

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

        delegate: CollectionCard {
            readonly property var cardItem: modelData

            cardWidth: grid.cardWidth
            entry: cardItem
            onClicked: grid.itemClicked(cardItem)
        }
    }
}
