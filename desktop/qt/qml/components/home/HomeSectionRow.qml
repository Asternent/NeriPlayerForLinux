import QtQuick
import "../"
import "../library" as Library

// 首页栏目容器：标题（可选 tab 组）+ 内容。由 HomePage 按后端 sections 顺序实例化。
// kind:
//   "songs"       横向歌曲卡（TrackCard）
//   "continue"    横向「继续播放」卡（ContinuePlayingCard）
//   "collections" 横向歌单/专辑卡（components/library/CollectionCard）
//   "list"        竖向歌曲行卡片（components/library/SongRow，最多 6 行）
//   "groups"      可切换 tab 的栏目（排行榜 / 推荐歌单 / B站音乐），每个 tab 是一组
//                 {id,title,kind:"songs"|"collections",songs|collections}
Item {
    id: root

    property string title: ""
    property string icon: ""
    property string kind: "songs"
    property var items: []
    property var groups: []
    property string actionText: ""

    /** 当前选中的 tab（仅 kind==="groups"）。 */
    property int currentGroupIndex: 0

    // 数据刷新后 tab 数量可能变少（例如登录后多出「每日推荐歌单」），把越界下标收回。
    onGroupsChanged: {
        if (!groups || groups.length === 0)
            currentGroupIndex = 0
        else if (currentGroupIndex >= groups.length)
            currentGroupIndex = 0
    }

    readonly property bool isGroups: kind === "groups" && groups !== null && groups !== undefined
                                 && groups.length > 0
    readonly property var currentGroup: {
        if (!isGroups)
            return null
        const index = Math.max(0, Math.min(currentGroupIndex, groups.length - 1))
        return groups[index]
    }
    readonly property string effectiveKind: {
        if (!isGroups)
            return kind
        return currentGroup && currentGroup.kind === "collections" ? "collections" : "songs"
    }
    readonly property var effectiveItems: {
        if (isGroups) {
            if (!currentGroup)
                return []
            return effectiveKind === "collections"
                    ? (currentGroup.collections || [])
                    : (currentGroup.songs || [])
        }
        return items || []
    }
    readonly property int maxItems: effectiveKind === "list" ? 6 : 12
    readonly property int count: Math.min(effectiveItems.length, maxItems)
    readonly property var displayItems: effectiveItems.length > maxItems
                                    ? effectiveItems.slice(0, maxItems)
                                    : effectiveItems
    /** 标题右侧操作：歌曲栏目「播放全部」，歌单栏目「更多」，继续播放「最近播放」。 */
    readonly property string effectiveAction: effectiveItems.length === 0
                                              ? ""
                                              : (effectiveKind === "collections"
                                                 ? "更多"
                                                 : (effectiveKind === "continue" ? "最近播放" : "播放全部"))

    readonly property var tabOptions: {
        const result = []
        if (!groups)
            return result
        for (let i = 0; i < groups.length; i++)
            result.push({ "value": i, "label": groups[i] && groups[i].title ? groups[i].title : "" })
        return result
    }

    readonly property real tabsHeight: isGroups ? 34 + Theme.spacingSm : 0
    readonly property real cardRowHeight: {
        switch (effectiveKind) {
        case "continue":
            return 72
        case "list":
            return Math.max(1, count) * 58 + 12
        default:
            return 204
        }
    }
    readonly property real contentHeight: effectiveItems.length === 0 ? 0 : cardRowHeight

    implicitHeight: 44 + Theme.spacingXs + tabsHeight + contentHeight

    signal actionClicked(string kind, var items)
    signal songActivated(var items, int index)
    signal collectionActivated(var collection)

    Library.SectionHeader {
        id: sectionHeader

        width: root.width
        height: 44
        title: root.title
        icon: root.icon
        actionText: root.effectiveAction
        onActionClicked: root.actionClicked(root.effectiveKind, root.effectiveItems)
    }

    // ---- tab 组切换 ----
    Item {
        id: tabsArea

        anchors.top: sectionHeader.bottom
        anchors.left: parent.left
        anchors.right: parent.right
        height: root.tabsHeight
        visible: root.isGroups

        Flickable {
            id: tabsFlick

            anchors.left: parent.left
            anchors.right: parent.right
            anchors.top: parent.top
            height: 34
            contentWidth: Math.max(width, tabBar.width)
            contentHeight: 34
            clip: true
            boundsBehavior: Flickable.StopAtBounds
            flickableDirection: Flickable.HorizontalFlick

            Library.TabBar {
                id: tabBar

                options: root.tabOptions
                value: root.currentGroupIndex
                onSelected: function(value) {
                    root.currentGroupIndex = Number(value)
                }
            }
        }
    }

    // ---- 横向卡片（歌曲 / 继续播放 / 歌单） ----
    Flickable {
        id: hFlick

        anchors.top: tabsArea.bottom
        anchors.topMargin: root.isGroups ? 0 : Theme.spacingXs
        width: root.width
        height: root.cardRowHeight
        visible: root.effectiveItems.length > 0 && root.effectiveKind !== "list"
        contentWidth: cardRow.width + 24
        contentHeight: height
        clip: true
        boundsBehavior: Flickable.StopAtBounds
        flickableDirection: Flickable.HorizontalFlick

        // 滚轮横向滚动；到两端时把事件让给外层纵向滚动。
        // 不用 flick()：Qt 6.11 里对程序化调用它是空操作，直接改 contentX 才有效。
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
                    // 到边界，交给外层纵向滚动
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
                model: root.effectiveKind === "continue" ? root.displayItems : []

                delegate: ContinuePlayingCard {
                    width: implicitWidth
                    height: implicitHeight
                    song: modelData
                    playing: Player.hasSong && Player.song !== null && Player.song.key === modelData.key && Player.playing
                    onActivated: root.songActivated(root.displayItems, index)
                }
            }

            Repeater {
                model: root.effectiveKind === "songs" ? root.displayItems : []

                delegate: TrackCard {
                    width: implicitWidth
                    height: implicitHeight
                    song: modelData
                    playing: Player.hasSong && Player.song !== null && Player.song.key === modelData.key && Player.playing
                    onActivated: root.songActivated(root.displayItems, index)
                }
            }

            Repeater {
                model: root.effectiveKind === "collections" ? root.displayItems : []

                delegate: Library.CollectionCard {
                    width: 150
                    height: implicitHeight
                    name: modelData.name ? modelData.name : ""
                    subtitle: modelData.trackCount > 0 ? modelData.trackCount + " 首"
                                                       : (modelData.creator ? modelData.creator : "")
                    coverUrl: modelData.coverUrl ? modelData.coverUrl : ""
                    onClicked: root.collectionActivated(modelData)
                }
            }
        }
    }

    // ---- 竖向歌曲列表卡 ----
    Rectangle {
        id: listCard

        anchors.top: tabsArea.bottom
        anchors.topMargin: root.isGroups ? 0 : Theme.spacingXs
        width: root.width
        height: root.cardRowHeight
        visible: root.effectiveItems.length > 0 && root.effectiveKind === "list"
        radius: Theme.radiusXl
        color: Theme.surfaceContainerLow

        Column {
            id: listColumn

            anchors.fill: parent
            anchors.margins: 6

            Repeater {
                model: root.effectiveKind === "list" ? root.displayItems : []

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
                            root.songActivated(root.displayItems, songRowDelegate.rowIndex)
                        }
                    }
                }
            }
        }
    }
}
