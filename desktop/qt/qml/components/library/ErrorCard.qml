import QtQuick
import QtQuick.Layouts
import ".."

// 错误卡（共享组件）。对齐旧 Compose 的 ErrorCard：
// errorContainer 底色、圆角 16、内边距 14、图标 20 + 文案 bodyMedium + 可选「重试」。
//
// 属性：message（错误文案）、retryable（是否显示重试）
// 信号：retryClicked()；另有兼容旧版根目录 ErrorCard 的 retry()，点击重试时两者都会发出。
Item {
    id: root

    property string message: ""
    property bool retryable: true

    signal retryClicked()
    signal retry()

    implicitHeight: card.height
    implicitWidth: parent ? parent.width : 320

    Rectangle {
        id: card

        anchors.left: parent.left
        anchors.right: parent.right
        anchors.leftMargin: Theme.spacingLg
        anchors.rightMargin: Theme.spacingLg
        anchors.top: parent.top
        anchors.topMargin: Theme.spacingSm
        height: rowContent.implicitHeight + 28
        radius: Theme.radiusLg
        color: Theme.errorContainer

        RowLayout {
            id: rowContent

            anchors.left: parent.left
            anchors.right: parent.right
            anchors.verticalCenter: parent.verticalCenter
            anchors.leftMargin: 14
            anchors.rightMargin: 14
            spacing: 10

            NeriIcon {
                name: "error"
                iconSize: 20
                color: Theme.onErrorContainerColor
                Layout.alignment: Qt.AlignVCenter
            }

            Text {
                Layout.fillWidth: true
                text: root.message
                wrapMode: Text.Wrap
                color: Theme.onErrorContainerColor
                font.family: Theme.fontFamily
                font.pixelSize: Theme.textStyle("bodyMedium").size
            }

            Rectangle {
                visible: root.retryable
                Layout.alignment: Qt.AlignVCenter
                implicitWidth: retryText.implicitWidth + 20
                implicitHeight: retryText.implicitHeight + 12
                radius: Theme.radiusSm
                color: retryHover.hovered ? Theme.withAlpha(Theme.onErrorContainerColor, 0.12) : "transparent"

                Text {
                    id: retryText

                    anchors.centerIn: parent
                    text: "重试"
                    color: Theme.onErrorContainerColor
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.textStyle("labelLarge").size
                    font.weight: Font.Medium
                }

                HoverHandler {
                    id: retryHover
                    cursorShape: Qt.PointingHandCursor
                }

                MouseArea {
                    anchors.fill: parent
                    onClicked: {
                        root.retryClicked()
                        root.retry()
                    }
                }
            }
        }
    }
}
