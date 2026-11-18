import QtQuick

// 歌手卡片（圆形头像 + 名称）。
Item {
    id: card

    property var data: null
    property string name: data && data.name !== undefined ? data.name : ""
    property string avatarUrl: data && data.avatarUrl !== undefined && data.avatarUrl !== null ? data.avatarUrl : ""
    property string subtitle: data && data.songCount !== undefined ? (data.songCount + " 首") : ""
    property real cardWidth: 136

    signal clicked()

    implicitWidth: cardWidth
    implicitHeight: avatar.height + 8 + nameText.implicitHeight + 16

    Rectangle {
        id: avatar
        anchors.horizontalCenter: parent.horizontalCenter
        width: card.cardWidth - 12
        height: width
        radius: width / 2
        color: Theme.surfaceContainerHighest

        CoverArt {
            anchors.fill: parent
            anchors.margins: 2
            radius: width / 2
            url: card.avatarUrl
            placeholderIconSize: 40
        }
    }

    Text {
        id: nameText
        anchors.top: avatar.bottom
        anchors.topMargin: 8
        width: card.cardWidth
        text: card.name
        elide: Text.ElideRight
        horizontalAlignment: Text.AlignHCenter
        color: Theme.onSurfaceColor
        font.family: Theme.fontFamily
        font.pixelSize: 13
        font.weight: Font.Medium
    }

    Text {
        anchors.top: nameText.bottom
        anchors.topMargin: 1
        width: card.cardWidth
        text: card.subtitle
        elide: Text.ElideRight
        horizontalAlignment: Text.AlignHCenter
        color: Theme.onSurfaceVariantColor
        font.family: Theme.fontFamily
        font.pixelSize: 11
        visible: text !== ""
    }

    HoverHandler {
        cursorShape: Qt.PointingHandCursor
    }

    MouseArea {
        anchors.fill: parent
        onClicked: card.clicked()
    }
}
