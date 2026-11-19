import QtQuick
import QtQuick.Controls
import ".."
import "../library"

// 评论输入框：未登录时显示登录引导；已登录时是可输入的卡片 + 发送按钮。
// 发送结果由面板处理，成功后面板把 resetToken 自增来清空草稿（避免发布失败丢字）。
Item {
    id: root

    property bool loggedIn: false
    property string sourceLabel: ""
    property int maxLength: 140
    property bool posting: false
    /** 当前歌曲是否支持评论（不支持时不显示输入区）。 */
    property bool available: true
    property int resetToken: 0
    property string draft: input.text

    signal sendRequested(string text)
    signal loginRequested()

    readonly property bool canSend: root.loggedIn && !root.posting
                                    && input.text.trim().length > 0
                                    && input.text.length <= root.maxLength

    implicitWidth: parent ? parent.width : 320
    implicitHeight: content.implicitHeight + 12
    height: implicitHeight

    function clear() {
        input.text = ""
    }

    function submit() {
        if (!root.canSend)
            return
        root.sendRequested(input.text)
    }

    onResetTokenChanged: clear()

    Rectangle {
        id: divider

        anchors.left: parent.left
        anchors.right: parent.right
        anchors.top: parent.top
        height: 1
        color: Theme.withAlpha(Theme.outlineVariant, 0.4)
    }

    Column {
        id: content

        anchors.left: parent.left
        anchors.right: parent.right
        anchors.top: divider.bottom
        anchors.leftMargin: 14
        anchors.rightMargin: 14
        anchors.topMargin: 8
        spacing: 6

        // ---------------------------------------------------------- 未登录引导
        Item {
            id: loginRow

            visible: !root.loggedIn
            width: parent.width
            height: visible ? 44 : 0

            LibraryButton {
                id: loginButton

                visible: root.available
                text: "去登录"
                icon: "login"
                filled: true
                anchors.right: parent.right
                anchors.verticalCenter: parent.verticalCenter
                onClicked: root.loginRequested()
            }

            Row {
                anchors.left: parent.left
                anchors.right: loginButton.left
                anchors.rightMargin: 8
                anchors.verticalCenter: parent.verticalCenter
                spacing: 8

                NeriIcon {
                    name: "account_circle"
                    iconSize: 20
                    color: Theme.onSurfaceVariantColor
                    anchors.verticalCenter: parent.verticalCenter
                }

                Text {
                    width: Math.max(0, parent.width - 28)
                    text: root.available
                          ? "登录" + (root.sourceLabel !== "" ? root.sourceLabel : "") + "后参与评论"
                          : "当前歌曲暂不支持评论"
                    elide: Text.ElideRight
                    maximumLineCount: 1
                    color: Theme.onSurfaceVariantColor
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.textStyle("bodySmall").size
                    anchors.verticalCenter: parent.verticalCenter
                }
            }
        }

        // ---------------------------------------------------------- 输入区
        Rectangle {
            id: inputCard

            visible: root.loggedIn && root.available
            width: parent.width
            height: visible ? 74 : 0
            radius: Theme.radiusLg
            color: Theme.surfaceContainerHigh

            TextArea {
                id: input

                anchors.left: parent.left
                anchors.right: sendButton.left
                anchors.top: parent.top
                anchors.bottom: parent.bottom
                anchors.margins: 10
                padding: 0
                placeholderText: "说点什么…（Ctrl+Enter 发送）"
                placeholderTextColor: Theme.withAlpha(Theme.onSurfaceVariantColor, 0.7)
                color: Theme.onSurfaceColor
                selectedTextColor: Theme.onPrimaryColor
                selectionColor: Theme.primary
                font.family: Theme.fontFamily
                font.pixelSize: Theme.textStyle("bodyMedium").size
                wrapMode: TextArea.Wrap
                selectByMouse: true
                background: Rectangle {
                    color: "transparent"
                }
                onTextChanged: {
                    if (input.text.length > root.maxLength)
                        input.text = input.text.slice(0, root.maxLength)
                }
                Keys.onPressed: function(event) {
                    const isEnter = event.key === Qt.Key_Return || event.key === Qt.Key_Enter
                    if (isEnter && (event.modifiers & Qt.ControlModifier)) {
                        root.submit()
                        event.accepted = true
                    }
                }
            }

            // TextArea 的 placeholder 在离屏渲染里没有绘制出来，用一层文本兜底保证提示可见
            Text {
                visible: input.text.length === 0
                anchors.left: input.left
                anchors.right: input.right
                anchors.top: input.top
                text: "说点什么…（Ctrl+Enter 发送）"
                elide: Text.ElideRight
                maximumLineCount: 1
                color: Theme.withAlpha(Theme.onSurfaceVariantColor, 0.7)
                font.family: Theme.fontFamily
                font.pixelSize: Theme.textStyle("bodyMedium").size
            }

            IconButton {
                id: sendButton

                anchors.right: parent.right
                anchors.bottom: parent.bottom
                anchors.margins: 6
                icon: "send"
                iconSize: 20
                size: 38
                iconColor: root.canSend ? Theme.primary : Theme.withAlpha(Theme.onSurfaceVariantColor, 0.5)
                tooltip: root.posting ? "正在发布…" : "发表评论"
                onClicked: root.submit()
            }
        }

        // ---------------------------------------------------------- 字数与发布状态
        Row {
            visible: root.loggedIn && root.available
            width: parent.width
            height: visible ? 16 : 0

            Text {
                visible: root.posting
                text: "正在发布…"
                color: Theme.primary
                font.family: Theme.fontFamily
                font.pixelSize: Theme.textStyle("labelSmall").size
                anchors.verticalCenter: parent.verticalCenter
            }

            Item {
                width: Math.max(0, parent.width - counter.implicitWidth)
                height: 1
            }

            Text {
                id: counter

                text: input.text.length + "/" + root.maxLength
                color: input.text.length >= root.maxLength
                       ? Theme.error : Theme.withAlpha(Theme.onSurfaceVariantColor, 0.75)
                font.family: Theme.fontFamily
                font.pixelSize: Theme.textStyle("labelSmall").size
                anchors.verticalCenter: parent.verticalCenter
            }
        }
    }
}
