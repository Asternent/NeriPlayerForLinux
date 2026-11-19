import QtQuick
import "."
import ".."
import "../library"

// 首页 YouTube Music 推荐区块。
//
// 集成方在 HomePage 里用 Loader 挂载：
//   Loader {
//       active: Settings.get("youtubeEnabled", false)
//       visible: active && item && item.height > 0
//       source: "components/youtube/YouTubeHomeSection.qml"
//   }
// 未登录 / 无数据 / 请求失败时自身 height = 0 并隐藏，不占位。
//
// 数据：ytmusic.status + ytmusic.home（见 desktop/docs/protocol-youtube.md）；
// 点击歌曲直接播放，专辑 / 歌手 / 歌单按路由跳转（source = "YOUTUBE"）。
Item {
    id: root

    /** true：未登录时整块隐藏（首页默认行为）；探索页会设为 false 以展示匿名推荐。 */
    property bool requireLogin: true
    property bool youtubeEnabled: false
    property var sections: []
    property bool loggedIn: false
    property bool loading: false
    property string message: ""

    readonly property bool hasData: root.youtubeEnabled && root.sections.length > 0
                                       && (!root.requireLogin || root.loggedIn)

    visible: hasData
    height: hasData ? column.implicitHeight : 0
    implicitHeight: height

    property int _seq: 0

    // ---------------------------------------------------------------- 数据

    function reload() {
        if (!root.youtubeEnabled)
            return
        const seq = ++root._seq
        root.loading = true
        root.message = ""
        Api.call("ytmusic.status").then(function(result) {
            if (seq !== root._seq)
                return null
            const status = result && result.status ? result.status : null
            root.loggedIn = !!(status && status.loggedIn)
            return Api.call("ytmusic.home", { "limit": 8 })
        }).then(function(result) {
            if (seq !== root._seq)
                return
            root.loading = false
            root.sections = result && result.sections ? result.sections : []
            root.message = result && result.message ? String(result.message) : ""
        }).catch(function(error) {
            if (seq !== root._seq)
                return
            root.loading = false
            root.sections = []
            root.message = error && error.message ? String(error.message) : "YouTube 推荐加载失败"
        })
    }

    function maybeReload() {
        if (root.youtubeEnabled && Api.connected)
            root.reload()
    }

    function syncEnabledFromSettings() {
        const enabled = Settings.get("youtubeEnabled", false) === true
        if (root.youtubeEnabled === enabled)
            return
        root.youtubeEnabled = enabled
        root.sections = []
        if (enabled) {
            root.maybeReload()
        } else {
            root._seq += 1
            root.loading = false
        }
    }

    Component.onCompleted: {
        syncEnabledFromSettings()
        maybeReload()
    }

    Connections {
        target: Backend

        function onConnectedChanged() {
            if (Backend.connected)
                root.maybeReload()
        }
    }

    // 设置里的 youtubeEnabled 开关变化后自动显隐 / 重新加载
    Connections {
        target: Settings

        function onValuesChanged() {
            root.syncEnabledFromSettings()
        }
    }

    // ---------------------------------------------------------------- 跳转 / 播放

    function isAlbum(collection) {
        return collection && String(collection.id).indexOf("MPRE") === 0
    }

    function openCollection(collection) {
        if (!collection)
            return
        if (isAlbum(collection)) {
            Nav.push("albumDetail", {
                          "id": String(collection.id),
                          "name": collection.name || "",
                          "source": "YOUTUBE",
                          "coverUrl": collection.coverUrl || "",
                          "creator": collection.creator || "",
                          "trackCount": collection.trackCount || 0
                      })
        } else {
            Nav.push("onlineCollection", {
                          "id": String(collection.id),
                          "name": collection.name || "",
                          "source": "YOUTUBE",
                          "coverUrl": collection.coverUrl || "",
                          "creator": collection.creator || "",
                          "trackCount": collection.trackCount || 0
                      })
        }
    }

    function openArtist(artist) {
        if (!artist)
            return
        Nav.push("remoteArtist", {
                      "id": String(artist.id),
                      "name": artist.name || "",
                      "source": "YOUTUBE",
                      "avatarUrl": artist.avatarUrl || "",
                      "songCount": artist.songCount || 0
                  })
    }

    function playSong(song, songs, index) {
        if (!song)
            return
        const list = songs && songs.length > 0 ? songs : [song]
        try {
            Player.setQueue(list, index < 0 ? 0 : index, true)
        } catch (error) {
            Api.call("player.setQueue", {
                         "songs": list,
                         "startIndex": index < 0 ? 0 : index,
                         "autoPlay": true
                     }).catch(function(e) {
                Toast.show(e && e.message ? e.message : "播放失败", "error")
            })
        }
    }

    // ---------------------------------------------------------------- 界面

    Column {
        id: column

        width: root.width
        spacing: Theme.spacingSm

        Row {
            width: parent.width
            height: 40
            spacing: 8

            NeriIcon {
                anchors.verticalCenter: parent.verticalCenter
                name: "smart_display"
                iconSize: 20
                color: Theme.error
            }

            Text {
                anchors.verticalCenter: parent.verticalCenter
                width: parent.width - 40
                text: "YouTube Music 推荐"
                color: Theme.onSurfaceColor
                font.family: Theme.fontFamily
                font.pixelSize: Theme.textStyle("titleMedium").size
                font.weight: Font.DemiBold
            }
        }

        Repeater {
            model: root.sections

            delegate: YouTubeShelfRow {
                width: column.width
                title: modelData && modelData.title ? String(modelData.title) : ""
                items: modelData && modelData.items ? modelData.items : []
                onPlaySong: function(song, songs, index) { root.playSong(song, songs, index) }
                onOpenCollection: function(collection) { root.openCollection(collection) }
                onOpenArtist: function(artist) { root.openArtist(artist) }
            }
        }
    }
}
