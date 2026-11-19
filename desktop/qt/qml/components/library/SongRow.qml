import QtQuick
import QtQuick.Layouts
import ".."
import "Format.js" as Format

// 歌曲行（共享组件，媒体库 / 歌单 / 歌手 / 最近播放 / 统计等页面复用）。
//
// 属性：
//   song         Song 对象（必需）
//   index        在当前列表中的下标，用于序号列与 activated 信号（默认 -1）
//   playing      bool，是否为「当前正在播放的歌曲」——决定高亮与播放指示
//   showAlbum    bool，副标题是否包含专辑名（默认 true）
//   showIndex    bool，是否显示 30px 序号列（默认 true）
//   extraText    string，右侧附加信息（如最近播放时间 / 播放次数），空字符串隐藏
//   trailingMode "duration"（默认，显示时长）| "none"（不显示时长）
//   showMore     bool，是否显示右侧「更多」按钮（默认 true）
//
// 信号：
//   activated(index)    左键点击行 → 用当前列表作为队列播放
//   moreClicked(song)   点击「更多」按钮 → 打开 SongActionsMenu
//   rightClicked(song)  右键点击行 → 页面可打开同一菜单
//
// 视觉规格见 desktop/qt/docs/design-spec.md §3.5：行高 58、封面 46 圆角 9、
// 序号列 30、hover 底色 surfaceContainerHigh(α0.5)、当前行 primaryContainer(α0.3)。
Item {
    id: root

    property var song: null
    property int index: -1
    property bool playing: false
    property bool showAlbum: true
    property bool showIndex: true
    property string extraText: ""
    property string trailingMode: "duration"
    property bool showMore: true
    // 副标题里的歌手 / 专辑名是否可点（跳转统一歌手页 / 专辑页）。默认开启。
    property bool linksEnabled: true

    signal activated(int index)
    signal moreClicked(var song)
    signal rightClicked(var song)

    readonly property string songSource: root.song && root.song.source
                                        ? String(root.song.source).toUpperCase() : "LOCAL"
    readonly property string artistText: root.song && root.song.artist ? String(root.song.artist) : ""
    readonly property string albumText: root.song && root.song.album ? String(root.song.album) : ""
    // 与 Format.songSubtitle 的原行为一致：专辑名与歌手同名时不重复显示。
    readonly property bool showAlbumText: root.showAlbum !== false && root.albumText !== ""
                                         && root.albumText !== root.artistText
    // 本地库按名字过滤；网易云/B 站可用名字搜索兜底；YouTube 必须带 id。
    readonly property bool artistLinkAvailable: root.linksEnabled && root.artistText !== ""
                                                && (root.songSource !== "YOUTUBE" || !!(root.song && root.song.artistId))
    readonly property bool albumLinkAvailable: root.linksEnabled && root.albumText !== ""
                                               && (root.songSource !== "YOUTUBE" || !!(root.song && root.song.albumId))

    /** 多位歌手的 "A / B" 串取第一位的名字（在线音源歌手页只对应一位歌手）。 */
    function primaryArtist(text) {
        const slash = String(text || "").indexOf("/")
        return slash > 0 ? String(text).substring(0, slash).trim() : String(text || "")
    }

    /** 打开歌手页：在线音源带 artistId，本地音源按歌手名过滤本地库。 */
    function openArtist() {
        if (!root.artistLinkAvailable)
            return
        const name = root.songSource === "LOCAL" ? root.artistText : primaryArtist(root.artistText)
        Nav.push("remoteArtist", {
                     "source": root.songSource,
                     "id": root.song.artistId ? String(root.song.artistId) : name,
                     "name": name
                 })
    }

    /** 打开专辑页：在线音源带 albumId，本地音源按专辑名过滤本地库。 */
    function openAlbum() {
        if (!root.albumLinkAvailable)
            return
        Nav.push("albumDetail", {
                     "source": root.songSource,
                     "id": root.song.albumId ? String(root.song.albumId) : root.albumText,
                     "name": root.albumText,
                     "coverUrl": root.song && root.song.artworkUrl ? String(root.song.artworkUrl) : "",
                     "creator": root.artistText,
                     "trackCount": 0
                 })
    }

    implicitWidth: 360
    implicitHeight: 58

    Rectangle {
        anchors.fill: parent
        color: root.playing
               ? Theme.withAlpha(Theme.primaryContainer, 0.3)
               : (rowMouse.containsMouse ? Theme.withAlpha(Theme.surfaceContainerHigh, 0.5) : "transparent")

        Behavior on color {
            ColorAnimation {
                duration: Theme.motionShort
            }
        }
    }

    MouseArea {
        id: rowMouse

        anchors.fill: parent
        hoverEnabled: true
        acceptedButtons: Qt.LeftButton | Qt.RightButton

        onClicked: function(mouse) {
            if (mouse.button === Qt.RightButton)
                root.rightClicked(root.song)
            else
                root.activated(root.index)
        }
    }

    RowLayout {
        anchors.fill: parent
        anchors.leftMargin: Theme.spacingMd
        anchors.rightMargin: Theme.spacingXs
        spacing: Theme.spacingSm

        // 序号列：当前歌曲显示播放指示，hover 时显示播放图标，其余显示序号
        Item {
            Layout.preferredWidth: 30
            Layout.preferredHeight: 24
            Layout.alignment: Qt.AlignVCenter
            visible: root.showIndex

            PlayingIndicator {
                anchors.centerIn: parent
                visible: root.playing && Player.playing
                playing: true
                barHeight: 15
            }

            NeriIcon {
                anchors.centerIn: parent
                visible: root.playing && !Player.playing
                name: "pause"
                iconSize: 17
                color: Theme.primary
            }

            NeriIcon {
                anchors.centerIn: parent
                visible: !root.playing && rowMouse.containsMouse
                name: "play_arrow"
                iconSize: 17
                color: Theme.primary
            }

            Text {
                anchors.centerIn: parent
                visible: !root.playing && !rowMouse.containsMouse
                text: root.index >= 0 ? String(root.index + 1) : ""
                color: Theme.onSurfaceVariantColor
                font.family: Theme.fontFamily
                font.pixelSize: Theme.textStyle("bodySmall").size
            }
        }

        CoverArt {
            Layout.preferredWidth: 46
            Layout.preferredHeight: 46
            Layout.alignment: Qt.AlignVCenter
            radius: 9
            placeholderIconSize: 20
            path: root.song && root.song.artworkPath ? root.song.artworkPath : ""
            url: root.song && root.song.artworkUrl ? root.song.artworkUrl : ""
        }

        Column {
            Layout.fillWidth: true
            Layout.alignment: Qt.AlignVCenter
            spacing: 2

            Text {
                width: parent.width
                text: Format.songTitle(root.song)
                elide: Text.ElideRight
                maximumLineCount: 1
                color: root.playing ? Theme.primary : Theme.onSurfaceColor
                font.family: Theme.fontFamily
                font.pixelSize: Theme.textStyle("bodyLarge").size
                font.weight: Theme.textStyle("bodyLarge").weight
            }

            // 副标题：歌手 / 专辑名可点（跳转歌手页、专辑页）；点击空白仍走整行播放。
            Row {
                width: parent.width
                spacing: 0

                Text {
                    id: artistLabel

                    width: Math.min(implicitWidth, parent.width * (root.showAlbumText ? 0.62 : 1.0))
                    visible: root.artistText !== ""
                    text: root.artistText
                    elide: Text.ElideRight
                    maximumLineCount: 1
                    color: artistMouse.containsMouse ? Theme.primary : Theme.onSurfaceVariantColor
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.textStyle("bodySmall").size

                    MouseArea {
                        id: artistMouse

                        anchors.fill: parent
                        hoverEnabled: true
                        enabled: root.artistLinkAvailable
                        cursorShape: enabled ? Qt.PointingHandCursor : Qt.ArrowCursor
                        onClicked: root.openArtist()
                    }
                }

                Text {
                    id: subtitleSeparator

                    width: implicitWidth
                    visible: root.artistText !== "" && root.showAlbumText
                    text: " · "
                    color: Theme.onSurfaceVariantColor
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.textStyle("bodySmall").size
                }

                Text {
                    id: albumLabel

                    width: Math.max(0, Math.min(implicitWidth, parent.width - artistLabel.width - subtitleSeparator.width))
                    visible: root.showAlbumText
                    text: root.albumText
                    elide: Text.ElideRight
                    maximumLineCount: 1
                    color: albumMouse.containsMouse ? Theme.primary : Theme.onSurfaceVariantColor
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.textStyle("bodySmall").size

                    MouseArea {
                        id: albumMouse

                        anchors.fill: parent
                        hoverEnabled: true
                        enabled: root.albumLinkAvailable
                        cursorShape: enabled ? Qt.PointingHandCursor : Qt.ArrowCursor
                        onClicked: root.openAlbum()
                    }
                }

                Text {
                    visible: root.artistText === "" && !root.showAlbumText
                    text: "未知艺术家"
                    color: Theme.onSurfaceVariantColor
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.textStyle("bodySmall").size
                }
            }
        }

        Text {
            visible: root.extraText !== ""
            text: root.extraText
            Layout.alignment: Qt.AlignVCenter
            color: Theme.onSurfaceVariantColor
            font.family: Theme.fontFamily
            font.pixelSize: Theme.textStyle("labelMedium").size
        }

        Text {
            visible: root.trailingMode === "duration"
            text: Format.formatDuration(root.song ? root.song.durationMs : 0)
            Layout.alignment: Qt.AlignVCenter
            Layout.preferredWidth: 42
            horizontalAlignment: Text.AlignRight
            color: Theme.onSurfaceVariantColor
            font.family: Theme.fontFamily
            font.pixelSize: Theme.textStyle("labelMedium").size
        }

        IconButton {
            visible: root.showMore
            icon: "more_vert"
            iconSize: 18
            size: 34
            tooltip: "更多"
            onClicked: root.moreClicked(root.song)
        }
    }
}
