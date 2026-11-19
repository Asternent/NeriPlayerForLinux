import QtQuick
import "../"
import "../library"

// 搜索历史区块：标题 + 「清空」操作 + 可换行的历史关键词标签。
Item {
    id: root

    property var history: []

    signal keywordClicked(string keyword)
    signal clearRequested()

    implicitHeight: column.implicitHeight

    Column {
        id: column
        width: root.width

        SectionHeader {
            width: column.width
            title: "搜索历史"
            icon: "history"
            actionText: "清空"
            onActionClicked: root.clearRequested()
        }

        ChipFlow {
            width: column.width
            items: root.history
            onItemClicked: function(text) { root.keywordClicked(text) }
        }
    }
}
