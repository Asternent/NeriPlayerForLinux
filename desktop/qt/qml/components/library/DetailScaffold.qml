import QtQuick
import ".."

// 二级页面骨架（共享组件）：64px 顶部栏（返回按钮 + 标题 + 右侧动作）+ 内容区。
// 对齐旧 Compose 的 DetailScaffold / M3 TopAppBar。
//
// 用法：
//   DetailScaffold {
//       title: "歌单名"
//       subtitle: "12 首 · 45 分钟"
//       actions: [                     // 右侧动作（数组，可省略）
//           IconButton { icon: "delete"; tooltip: "删除"; onClicked: ... }
//       ]
//       ListView { anchors.fill: parent; ... }   // 默认属性 → 内容区
//   }
//
// 属性：title、subtitle、backEnabled、backAction（自定义返回逻辑，默认 Nav.back()）
// 默认属性 content：顶部栏下方的内容区；命名属性 actions：顶部栏右侧动作数组
Item {
    id: root

    property string title: ""
    property string subtitle: ""
    property bool backEnabled: true
    /** 自定义返回逻辑；缺省时调用 Nav.back()。 */
    property var backAction: null

    default property alias content: contentHost.data
    property alias actions: actionsRow.data

    implicitWidth: parent ? parent.width : 0
    implicitHeight: parent ? parent.height : 0

    Item {
        id: topBar

        anchors.top: parent.top
        anchors.left: parent.left
        anchors.right: parent.right
        height: 64

        IconButton {
            id: backButton

            visible: root.backEnabled
            anchors.left: parent.left
            anchors.leftMargin: Theme.spacingSm
            anchors.verticalCenter: parent.verticalCenter
            icon: "arrow_back"
            iconSize: 24
            size: 48
            tooltip: "返回"
            onClicked: {
                if (root.backAction)
                    root.backAction()
                else
                    Nav.back()
            }
        }

        Column {
            id: titleColumn

            anchors.left: root.backEnabled ? backButton.right : parent.left
            anchors.leftMargin: root.backEnabled ? Theme.spacingSm : Theme.spacingLg
            anchors.right: actionsRow.left
            anchors.rightMargin: Theme.spacingSm
            anchors.verticalCenter: parent.verticalCenter
            spacing: 0

            Text {
                width: parent.width
                text: root.title
                elide: Text.ElideRight
                maximumLineCount: 1
                color: Theme.onSurfaceColor
                font.family: Theme.fontFamily
                font.pixelSize: Theme.textStyle("titleLarge").size
            }

            Text {
                visible: root.subtitle !== ""
                width: parent.width
                text: root.subtitle
                elide: Text.ElideRight
                maximumLineCount: 1
                color: Theme.onSurfaceVariantColor
                font.family: Theme.fontFamily
                font.pixelSize: Theme.textStyle("labelMedium").size
            }
        }

        Row {
            id: actionsRow

            anchors.right: parent.right
            anchors.rightMargin: Theme.spacingSm
            anchors.verticalCenter: parent.verticalCenter
            spacing: 0
        }
    }

    Item {
        id: contentHost

        anchors.top: topBar.bottom
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.bottom: parent.bottom
        clip: true
    }
}
