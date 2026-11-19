import QtQuick

// 可换行的胶囊标签流：搜索历史 / 热门搜索 / 风格标签共用。
// 用 Flow 自行换行，宽窗口一行放更多、窄窗口自动折行。
Flow {
    id: flow

    property var items: []

    signal itemClicked(string text)

    width: parent ? parent.width : 0
    height: childrenRect.height
    leftPadding: 16
    rightPadding: 16
    topPadding: 4
    bottomPadding: 5
    spacing: 8

    Repeater {
        model: flow.items

        delegate: TagChip {
            text: modelData
            onClicked: flow.itemClicked(modelData)
        }
    }
}
