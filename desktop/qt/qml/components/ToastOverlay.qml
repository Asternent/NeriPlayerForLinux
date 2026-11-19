import QtQuick
import QtQuick.Layouts

// 轻提示（Snackbar）浮层，数据源是 Toast 全局对象。
Item {
    id: overlay

    Column {
        anchors.horizontalCenter: parent.horizontalCenter
        anchors.bottom: parent.bottom
        anchors.bottomMargin: 100
        spacing: 8

        Repeater {
            model: Toast.items
            delegate: Rectangle {
                id: toastItem
                required property var modelData
                readonly property bool isError: modelData.kind === "error"

                width: Math.min(560, toastText.implicitWidth + 32)
                height: toastText.implicitHeight + 20
                radius: 8
                color: isError ? Theme.errorContainer : Theme.inverseSurface
                opacity: 0

                Component.onCompleted: opacity = 1
                Behavior on opacity {
                    NumberAnimation {
                        duration: Theme.motionMedium
                        easing.type: Easing.OutCubic
                    }
                }

                Text {
                    id: toastText
                    anchors.centerIn: parent
                    width: Math.min(520, implicitWidth)
                    text: toastItem.modelData.text
                    wrapMode: Text.Wrap
                    horizontalAlignment: Text.AlignHCenter
                    color: toastItem.isError ? Theme.onErrorContainerColor : Theme.inverseOnSurface
                    font.family: Theme.fontFamily
                    font.pixelSize: 14
                }
            }
        }
    }
}
