import QtQuick
import ".."

// 登录二维码卡片：固定白色底（LoginFlow.kt:75 的硬编码白），240dp 方形，圆角 16。
// 后端以 data:image/png;base64,... 下发二维码，直接作为 Image.source 显示。
Item {
    id: root

    property string imageSource: ""
    property bool loading: false
    /** 二维码过期时把图片压暗，提示需要刷新。 */
    property bool dimmed: false
    property string placeholderText: ""

    implicitWidth: 240
    implicitHeight: 240

    readonly property bool hasImage: imageSource !== ""

    Rectangle {
        id: plate
        anchors.fill: parent
        radius: 16
        color: "#FFFFFF"
        clip: true

        Image {
            id: qrImage
            anchors.fill: parent
            anchors.margins: 12
            source: root.imageSource
            fillMode: Image.PreserveAspectFit
            asynchronous: true
            cache: false
            smooth: false
            opacity: root.dimmed ? 0.22 : 1
            visible: root.hasImage && status === Image.Ready

            Behavior on opacity {
                NumberAnimation {
                    duration: Theme.motionShort
                }
            }
        }

        // 无二维码（生成中 / 失败 / 加载中）时的占位
        Column {
            anchors.centerIn: parent
            spacing: 10
            visible: !(root.hasImage && qrImage.status === Image.Ready)

            NeriIcon {
                anchors.horizontalCenter: parent.horizontalCenter
                name: root.loading ? "qr_code_2" : "error"
                iconSize: 64
                color: root.loading ? "#9E9E9E" : "#C62828"
            }

            Text {
                anchors.horizontalCenter: parent.horizontalCenter
                width: Math.min(200, plate.width - 24)
                horizontalAlignment: Text.AlignHCenter
                wrapMode: Text.Wrap
                text: root.placeholderText !== ""
                      ? root.placeholderText
                      : (root.loading ? "正在生成二维码…" : "二维码不可用")
                color: root.loading ? "#757575" : "#C62828"
                font.family: Theme.fontFamily
                font.pixelSize: 12
            }
        }

        // 过期时的提示叠层
        Column {
            anchors.centerIn: parent
            spacing: 6
            visible: root.dimmed

            NeriIcon {
                anchors.horizontalCenter: parent.horizontalCenter
                name: "refresh"
                iconSize: 34
                color: "#424242"
            }

            Text {
                anchors.horizontalCenter: parent.horizontalCenter
                text: "二维码已过期"
                color: "#424242"
                font.family: Theme.fontFamily
                font.pixelSize: 13
                font.weight: Font.Medium
            }
        }
    }
}
