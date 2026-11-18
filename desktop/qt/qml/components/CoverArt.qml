import QtQuick

// 封面图：优先本地文件路径，其次远程 URL，都没有时显示占位图标。
Item {
    id: cover

    property string path: ""
    property string url: ""
    property int radius: 10
    property real placeholderIconSize: 22

    readonly property bool hasImage: (path !== "" && path !== undefined) || (url !== "" && url !== undefined)
    readonly property url source: {
        if (path !== "" && path !== undefined)
            return "file://" + path
        if (url !== "" && url !== undefined)
            return url
        return ""
    }

    Rectangle {
        anchors.fill: parent
        radius: cover.radius
        color: Theme.surfaceContainerHighest
        clip: true

        Image {
            id: image
            anchors.fill: parent
            source: cover.source
            fillMode: Image.PreserveAspectCrop
            asynchronous: true
            cache: true
            visible: status === Image.Ready
        }

        NeriIcon {
            anchors.centerIn: parent
            visible: image.status !== Image.Ready
            name: "music_note"
            iconSize: cover.placeholderIconSize
            color: Theme.onSurfaceVariantColor
        }
    }
}
