import QtQuick
import ".."
import "Format.js" as Format

// 单条评论卡片：头像 + 昵称/时间 + 正文 + 引用/配图 + 点赞/回复操作。
// 楼中楼回复区用内联样式渲染（不再递归 CommentRow），避免组件自引用与过深嵌套。
Item {
    id: root

    property var comment: null
    property bool liked: false
    property real likedCount: 0
    /** 点赞的本地覆盖表（id -> {liked, likedCount}），由面板维护，用于即时反馈。 */
    property var likeOverrides: ({})
    property bool interactive: true
    /** 是否展示「展开回复」入口（楼中楼回复自身不再展示）。 */
    property bool showReplies: true
    property bool expanded: false
    /** 已从后端加载的回复；为空时退回列表接口自带的预览回复。 */
    property var loadedReplies: []
    property bool repliesLoading: false
    property string repliesError: ""
    property bool repliesHasMore: false
    property real indent: 0

    signal likeClicked()
    signal repliesToggleClicked()
    signal loadMoreRepliesClicked()
    signal replyLikeClicked(var reply)

    readonly property string commentId: root.comment && root.comment.id ? String(root.comment.id) : ""
    readonly property var override: root.commentId !== "" && root.likeOverrides[root.commentId] !== undefined
                                     ? root.likeOverrides[root.commentId] : null
    readonly property bool effectiveLiked: root.override !== null ? root.override.liked === true : root.liked
    readonly property real effectiveLikedCount: root.override !== null
                                                 ? Number(root.override.likedCount || 0)
                                                 : root.likedCount
    readonly property var previewReplies: root.comment && root.comment.replies ? root.comment.replies : []
    readonly property var displayReplies: root.loadedReplies.length > 0 ? root.loadedReplies : root.previewReplies
    readonly property var quotes: root.comment && root.comment.quotes ? root.comment.quotes : []
    readonly property var images: root.comment && root.comment.images ? root.comment.images : []
    readonly property real replyCount: root.comment && root.comment.replyCount !== null
                                        && root.comment.replyCount !== undefined
                                        ? Number(root.comment.replyCount) : 0
    readonly property bool hasReplyAction: root.showReplies
                                           && (root.replyCount > 0 || root.previewReplies.length > 0)
    readonly property string replyActionText: {
        if (root.expanded)
            return "收起回复"
        if (root.replyCount > 0)
            return Format.formatCount(root.replyCount) + "条回复"
        return "查看回复"
    }

    width: parent ? parent.width : 320
    implicitHeight: layout.implicitHeight + 14
    height: implicitHeight

    // 评论之间的细分隔线（最后一条由 ListView 的 footer 收尾）
    Rectangle {
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.leftMargin: 14 + root.indent
        anchors.bottom: parent.bottom
        height: 1
        color: Theme.withAlpha(Theme.outlineVariant, 0.35)
    }

    Column {
        id: layout

        anchors.left: parent.left
        anchors.leftMargin: 14 + root.indent
        anchors.right: parent.right
        anchors.rightMargin: 14
        anchors.top: parent.top
        anchors.topMargin: 7
        spacing: 5

        // ------------------------------------------------------------ 头部 + 正文
        Row {
            width: parent.width
            spacing: 9

            CoverArt {
                id: avatar

                width: 34
                height: 34
                radius: Theme.radiusFull
                url: root.comment && root.comment.avatarUrl ? String(root.comment.avatarUrl) : ""
                placeholderIconSize: 16
            }

            Column {
                width: parent.width - avatar.width - parent.spacing
                spacing: 3

                Row {
                    width: parent.width
                    spacing: 6

                    Text {
                        id: nicknameText

                        width: Math.max(30, parent.width - timeText.implicitWidth - levelText.implicitWidth - 24)
                        text: root.comment && root.comment.nickname
                              ? String(root.comment.nickname) : "匿名用户"
                        elide: Text.ElideRight
                        maximumLineCount: 1
                        color: Theme.onSurfaceColor
                        font.family: Theme.fontFamily
                        font.pixelSize: Theme.textStyle("labelLarge").size
                        font.weight: Font.Medium
                    }

                    Text {
                        id: levelText

                        visible: !!(root.comment && root.comment.userLevel)
                        text: "Lv" + (root.comment && root.comment.userLevel ? String(root.comment.userLevel) : "")
                        color: Theme.primary
                        font.family: Theme.fontFamily
                        font.pixelSize: Theme.textStyle("labelSmall").size
                    }

                    Text {
                        id: timeText

                        visible: text !== ""
                        text: root.comment ? Format.formatTime(root.comment.timeMs) : ""
                        color: Theme.withAlpha(Theme.onSurfaceVariantColor, 0.8)
                        font.family: Theme.fontFamily
                        font.pixelSize: Theme.textStyle("labelSmall").size
                    }

                    Text {
                        visible: !!(root.comment && root.comment.location)
                        text: root.comment && root.comment.location ? String(root.comment.location) : ""
                        elide: Text.ElideRight
                        color: Theme.withAlpha(Theme.onSurfaceVariantColor, 0.7)
                        font.family: Theme.fontFamily
                        font.pixelSize: Theme.textStyle("labelSmall").size
                    }
                }

                // 正文
                Text {
                    width: parent.width
                    text: root.comment && root.comment.content ? String(root.comment.content) : ""
                    wrapMode: Text.Wrap
                    lineHeight: 1.3
                    color: Theme.onSurfaceColor
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.textStyle("bodyMedium").size
                }

                // 被回复的评论（网易云 beReplied）
                Column {
                    width: parent.width
                    spacing: 4
                    visible: root.quotes.length > 0

                    Repeater {
                        model: root.quotes

                        delegate: Rectangle {
                            required property var modelData

                            width: parent.width
                            height: quoteLabel.implicitHeight + 12
                            radius: Theme.radiusSm
                            color: Theme.surfaceContainerHigh

                            Text {
                                id: quoteLabel

                                anchors.left: parent.left
                                anchors.right: parent.right
                                anchors.verticalCenter: parent.verticalCenter
                                anchors.leftMargin: 10
                                anchors.rightMargin: 10
                                text: "@" + (modelData.nickname ? String(modelData.nickname) : "用户") + "："
                                      + (modelData.content ? String(modelData.content) : "评论已删除")
                                wrapMode: Text.Wrap
                                maximumLineCount: 2
                                elide: Text.ElideRight
                                color: Theme.withAlpha(Theme.onSurfaceVariantColor, 0.9)
                                font.family: Theme.fontFamily
                                font.pixelSize: Theme.textStyle("bodySmall").size
                            }
                        }
                    }
                }

                // 评论配图（B 站）
                Row {
                    visible: root.images.length > 0
                    spacing: 6

                    Repeater {
                        model: root.images

                        delegate: CoverArt {
                            required property var modelData

                            width: 84
                            height: 84
                            radius: Theme.radiusSm
                            url: modelData && modelData.url ? String(modelData.url) : ""
                            placeholderIconSize: 20
                        }
                    }
                }

                // 操作行：点赞 / 展开回复
                Row {
                    width: parent.width
                    height: 30
                    spacing: 4

                    Item {
                        id: likeButton

                        width: likeRow.implicitWidth + 14
                        height: parent.height

                        Row {
                            id: likeRow

                            anchors.centerIn: parent
                            spacing: 4

                            NeriIcon {
                                name: "thumb_up"
                                filled: root.effectiveLiked
                                iconSize: 16
                                color: root.effectiveLiked ? Theme.primary : Theme.onSurfaceVariantColor
                                anchors.verticalCenter: parent.verticalCenter
                            }

                            Text {
                                visible: root.effectiveLikedCount > 0
                                text: Format.formatCount(root.effectiveLikedCount)
                                color: root.effectiveLiked ? Theme.primary : Theme.onSurfaceVariantColor
                                font.family: Theme.fontFamily
                                font.pixelSize: Theme.textStyle("labelMedium").size
                                anchors.verticalCenter: parent.verticalCenter
                            }
                        }

                        HoverHandler {
                            cursorShape: root.interactive ? Qt.PointingHandCursor : Qt.ArrowCursor
                        }

                        MouseArea {
                            anchors.fill: parent
                            enabled: root.interactive
                            onClicked: root.likeClicked()
                        }
                    }

                    Item {
                        id: replyToggle

                        visible: root.hasReplyAction
                        width: replyToggleRow.implicitWidth + 14
                        height: parent.height

                        Row {
                            id: replyToggleRow

                            anchors.centerIn: parent
                            spacing: 2

                            Text {
                                text: root.replyActionText
                                color: Theme.primary
                                font.family: Theme.fontFamily
                                font.pixelSize: Theme.textStyle("labelMedium").size
                                anchors.verticalCenter: parent.verticalCenter
                            }

                            NeriIcon {
                                name: root.expanded ? "expand_less" : "expand_more"
                                iconSize: 16
                                color: Theme.primary
                                anchors.verticalCenter: parent.verticalCenter
                            }
                        }

                        HoverHandler {
                            cursorShape: Qt.PointingHandCursor
                        }

                        MouseArea {
                            anchors.fill: parent
                            onClicked: root.repliesToggleClicked()
                        }
                    }
                }
            }
        }

        // ------------------------------------------------------------ 楼中楼回复
        Item {
            visible: root.showReplies && root.expanded
            width: parent.width
            height: visible ? repliesColumn.implicitHeight + 2 : 0

            Column {
                id: repliesColumn

                x: 43
                width: parent.width - 43
                spacing: 0

                Repeater {
                    model: root.displayReplies

                    delegate: Item {
                        id: replyRow

                        required property var modelData

                        readonly property string replyId: modelData && modelData.id ? String(modelData.id) : ""
                        readonly property var replyOverride: replyRow.replyId !== ""
                                                           && root.likeOverrides[replyRow.replyId] !== undefined
                                                           ? root.likeOverrides[replyRow.replyId] : null
                        readonly property bool replyLiked: replyRow.replyOverride !== null
                                                          ? replyRow.replyOverride.liked === true
                                                          : (modelData && modelData.liked === true)
                        readonly property real replyLikedCount: replyRow.replyOverride !== null
                                                                ? Number(replyRow.replyOverride.likedCount || 0)
                                                                : (modelData && modelData.likedCount
                                                                   ? Number(modelData.likedCount) : 0)

                        width: repliesColumn.width
                        height: replyLayout.implicitHeight + 8

                        Column {
                            id: replyLayout

                            anchors.left: parent.left
                            anchors.right: parent.right
                            anchors.top: parent.top
                            anchors.topMargin: 4
                            spacing: 3

                            Row {
                                width: parent.width
                                spacing: 6

                                CoverArt {
                                    width: 24
                                    height: 24
                                    radius: Theme.radiusFull
                                    url: modelData && modelData.avatarUrl ? String(modelData.avatarUrl) : ""
                                    placeholderIconSize: 12
                                }

                                Text {
                                    text: modelData && modelData.nickname ? String(modelData.nickname) : "匿名用户"
                                    elide: Text.ElideRight
                                    maximumLineCount: 1
                                    color: Theme.onSurfaceVariantColor
                                    font.family: Theme.fontFamily
                                    font.pixelSize: Theme.textStyle("labelMedium").size
                                    font.weight: Font.Medium
                                    anchors.verticalCenter: parent.verticalCenter
                                }

                                Text {
                                    text: modelData ? Format.formatTime(modelData.timeMs) : ""
                                    color: Theme.withAlpha(Theme.onSurfaceVariantColor, 0.7)
                                    font.family: Theme.fontFamily
                                    font.pixelSize: Theme.textStyle("labelSmall").size
                                    anchors.verticalCenter: parent.verticalCenter
                                }
                            }

                            Text {
                                width: parent.width
                                text: modelData && modelData.content ? String(modelData.content) : ""
                                wrapMode: Text.Wrap
                                lineHeight: 1.3
                                color: Theme.onSurfaceColor
                                font.family: Theme.fontFamily
                                font.pixelSize: Theme.textStyle("bodySmall").size
                            }

                            Row {
                                width: parent.width
                                height: 26

                                Item {
                                    width: replyLikeRow.implicitWidth + 12
                                    height: parent.height

                                    Row {
                                        id: replyLikeRow

                                        anchors.verticalCenter: parent.verticalCenter
                                        spacing: 3

                                        NeriIcon {
                                            name: "thumb_up"
                                            filled: replyRow.replyLiked
                                            iconSize: 14
                                            color: replyRow.replyLiked ? Theme.primary : Theme.onSurfaceVariantColor
                                        }

                                        Text {
                                            visible: replyRow.replyLikedCount > 0
                                            text: Format.formatCount(replyRow.replyLikedCount)
                                            color: replyRow.replyLiked ? Theme.primary : Theme.onSurfaceVariantColor
                                            font.family: Theme.fontFamily
                                            font.pixelSize: Theme.textStyle("labelSmall").size
                                        }
                                    }

                                    HoverHandler {
                                        cursorShape: root.interactive ? Qt.PointingHandCursor : Qt.ArrowCursor
                                    }

                                    MouseArea {
                                        anchors.fill: parent
                                        enabled: root.interactive
                                        onClicked: root.replyLikeClicked(modelData)
                                    }
                                }
                            }
                        }
                    }
                }

                // 加载中
                Item {
                    visible: root.repliesLoading
                    width: parent.width
                    height: visible ? 34 : 0

                    Row {
                        anchors.verticalCenter: parent.verticalCenter
                        spacing: 6

                        NeriIcon {
                            name: "progress_activity"
                            iconSize: 16
                            color: Theme.onSurfaceVariantColor
                            anchors.verticalCenter: parent.verticalCenter

                            RotationAnimation on rotation {
                                running: root.repliesLoading
                                loops: Animation.Infinite
                                from: 0
                                to: 360
                                duration: 900
                            }
                        }

                        Text {
                            text: "正在加载回复…"
                            color: Theme.onSurfaceVariantColor
                            font.family: Theme.fontFamily
                            font.pixelSize: Theme.textStyle("labelSmall").size
                            anchors.verticalCenter: parent.verticalCenter
                        }
                    }
                }

                // 错误
                Text {
                    visible: root.repliesError !== ""
                    width: parent.width
                    text: root.repliesError
                    wrapMode: Text.Wrap
                    color: Theme.error
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.textStyle("labelSmall").size
                }

                // 更多回复
                Item {
                    visible: root.repliesHasMore && !root.repliesLoading
                    width: parent.width
                    height: visible ? 30 : 0

                    Text {
                        text: "查看更多回复"
                        color: Theme.primary
                        font.family: Theme.fontFamily
                        font.pixelSize: Theme.textStyle("labelMedium").size
                        anchors.verticalCenter: parent.verticalCenter
                    }

                    HoverHandler {
                        cursorShape: Qt.PointingHandCursor
                    }

                    MouseArea {
                        anchors.fill: parent
                        onClicked: root.loadMoreRepliesClicked()
                    }
                }
            }
        }
    }
}
