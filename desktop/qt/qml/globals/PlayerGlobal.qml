import QtQuick

// 播放器全局状态与命令。所有状态由后端的 "player" / "player.queue" / "player.lyrics" 事件驱动。
QtObject {
    id: player

    // ---- 状态 ----
    property var song: null
    property string state: "IDLE"          // IDLE / PREPARING / PLAYING / PAUSED / ERROR
    property real positionMs: 0
    property real durationMs: 0
    property real volume: 0.85
    property bool shuffle: false
    property string repeatMode: "OFF"      // OFF / ALL / ONE
    property real speed: 1.0
    property real pitch: 0.0
    property bool loudness: false
    property bool equalizerEnabled: false
    property var equalizerBands: [0, 0, 0, 0, 0, 0, 0, 0, 0, 0]
    property bool buffering: false
    property var sleepTimer: ({ active: false, stopAfterCurrent: false, remainingSeconds: 0, totalSeconds: 0 })
    property bool supportsEffects: false
    property var queue: []
    property int queueIndex: -1
    property var lyrics: ({ lines: [], translated: [], source: null })
    property bool lyricsLoading: false
    property string coverSeed: ""
    property string coverPath: ""

    // ---- 收藏（播放页红心；由 collections.changed 事件保持同步） ----
    property bool favorite: false
    property string favoriteSongKey: ""

    readonly property bool playing: state === "PLAYING"
    readonly property bool idle: state === "IDLE"
    readonly property bool hasSong: song !== null && song !== undefined
    readonly property string title: song && song.title ? song.title : ""
    readonly property string artist: song && song.artist ? song.artist : ""
    readonly property string album: song && song.album ? song.album : ""
    readonly property real progress: durationMs > 0
                                      ? Math.min(1, Math.max(0, smoothPositionMs / durationMs)) : 0

    // ---- 平滑播放进度 ----
    // 后端 "player" 事件只有 4Hz，进度直接用来驱动进度条/逐字高亮会一跳一跳；
    // 这里用本地定时器在两次快照之间线性推进，快照到达时只做小幅纠偏。
    // seek / 暂停 / 切歌这些真实跳变直接吸附到真实值（见 _syncSmoothPosition）。
    property real smoothPositionMs: 0
    property real _smoothBaseMs: 0
    property real _smoothBaseStamp: 0
    /** seek 后短暂忽略后端旧快照，避免平滑值被上一帧的旧进度拉回去。 */
    property real _smoothIgnoreUntil: 0
    readonly property real _smoothSnapThresholdMs: 400

    property Timer smoothTimer: Timer {
        interval: 33
        repeat: true
        // 时长未知（直播/流）时也推进，只是不做上界夹取
        running: player.playing
        onTriggered: player._advanceSmoothPosition()
    }

    function _advanceSmoothPosition() {
        if (!player.playing)
            return
        const elapsed = Date.now() - player._smoothBaseStamp
        const next = player._smoothBaseMs + elapsed * (player.speed > 0 ? player.speed : 1)
        player.smoothPositionMs = Math.max(0, player.durationMs > 0
                                           ? Math.min(player.durationMs, next) : next)
    }

    /** 用一次真实进度校正平滑值：正常播放只纠偏一半，避免 4Hz 快照把动画拉回跳变。 */
    function _syncSmoothPosition(real) {
        if (Date.now() < player._smoothIgnoreUntil)
            return
        if (!player.playing
                || Math.abs(real - player.smoothPositionMs) > player._smoothSnapThresholdMs) {
            player.smoothPositionMs = real
            player._smoothBaseMs = real
            player._smoothBaseStamp = Date.now()
            return
        }
        player._smoothBaseMs = player.smoothPositionMs + (real - player.smoothPositionMs) * 0.5
        player._smoothBaseStamp = Date.now()
    }

    onSpeedChanged: {
        if (playing) {
            _smoothBaseMs = smoothPositionMs
            _smoothBaseStamp = Date.now()
        }
    }

    // 播放页跳转入口用：在线歌曲才带 artistId/albumId
    readonly property string songArtistId: song && song.artistId !== undefined && song.artistId !== null ? String(song.artistId) : ""
    readonly property string songAlbumId: song && song.albumId !== undefined && song.albumId !== null ? String(song.albumId) : ""
    readonly property string songSource: song && song.source ? String(song.source) : "LOCAL"

    function _send(method, params) {
        Api.get(method, params || {}, function(result, error) {
            if (error) {
                console.warn("[player]", method, "failed:", error.message)
                if (error.message)
                    Toast.show(error.message, "error")
            }
            return result
        })
    }

    // ---- 播放控制 ----
    function togglePlayPause() { _send("player.togglePlayPause") }
    function play() { _send("player.play") }
    function pause() { _send("player.pause") }
    function next() { _send("player.next") }
    function previous() { _send("player.previous") }
    function seekTo(ms) {
        const target = Math.max(0, ms)
        positionMs = target
        // 点击 seek 用真实值，但平滑进度要立刻跟上，否则会从旧位置慢慢追过去
        smoothPositionMs = target
        _smoothBaseMs = target
        _smoothBaseStamp = Date.now()
        _smoothIgnoreUntil = Date.now() + 400
        _send("player.seekTo", { positionMs: Math.round(target) })
    }
    function seekBy(deltaMs) { seekTo(positionMs + deltaMs) }

    function setVolume(value) {
        volume = Math.max(0, Math.min(1, value))
        _send("player.setVolume", { value: volume })
    }
    function setShuffle(enabled) {
        shuffle = enabled
        _send("player.setShuffle", { enabled: enabled })
    }
    function toggleShuffle() { setShuffle(!shuffle) }
    function setRepeatMode(mode) {
        repeatMode = mode
        _send("player.setRepeatMode", { mode: mode })
    }
    function cycleRepeatMode() {
        _send("player.cycleRepeatMode")
    }
    function setSpeed(value) {
        speed = value
        _send("player.setSpeed", { value: value })
    }
    function setPitch(value) {
        pitch = value
        _send("player.setPitch", { value: value })
    }
    function setLoudness(enabled) {
        loudness = enabled
        _send("player.setLoudness", { enabled: enabled })
    }
    function setEqualizerEnabled(enabled) {
        equalizerEnabled = enabled
        _send("player.setEqualizerEnabled", { enabled: enabled })
    }
    function setEqualizerPreset(name, bands) {
        equalizerBands = bands
        equalizerEnabled = true
        _send("player.setEqualizerPreset", { name: name, bands: bands })
    }
    function setEqualizerBand(index, gain) {
        const bands = equalizerBands.slice()
        bands[index] = gain
        equalizerBands = bands
        _send("player.setEqualizerBand", { index: index, gain: gain })
    }
    function resetEffects() { _send("player.resetEffects") }

    // ---- 队列 ----
    function setQueue(songs, startIndex, autoPlay) {
        _send("player.setQueue", {
                  songs: songs,
                  startIndex: startIndex || 0,
                  autoPlay: autoPlay === undefined ? true : autoPlay
              })
    }
    function playSongNow(song, queue) {
        _send("player.playSongNow", { song: song, queue: queue || null })
    }
    // 后端注册的方法是 player.playAt（见 backend/api/PlayerApi.kt）。
    function playQueueIndex(index) { _send("player.playAt", { index: index }) }
    function enqueue(songs) { _send("player.enqueue", { songs: songs }) }
    function enqueueNext(songs) { _send("player.enqueueNext", { songs: songs }) }
    function removeFromQueue(index) { _send("player.removeFromQueue", { index: index }) }
    function moveInQueue(from, to) { _send("player.moveInQueue", { from: from, to: to }) }
    function clearQueue() { _send("player.clearQueue") }

    // ---- 睡眠定时器 ----
    function startSleepTimer(minutes) { _send("player.startSleepTimer", { minutes: minutes }) }
    function stopAfterCurrentSong() { _send("player.stopAfterCurrentSong") }
    function cancelSleepTimer() { _send("player.cancelSleepTimer") }

    // ---- 歌词 ----
    function reloadLyrics() { _send("player.reloadLyrics") }

    /** 当前应该高亮的歌词行号（-1 表示还没到第一行）。 */
    function currentLyricIndex() {
        const lines = lyrics && lyrics.lines ? lyrics.lines : []
        // 行切换必须和逐字高亮进度用同一个时钟：进度由 smoothPositionMs 驱动，
        // 而 4Hz 快照的 positionMs 会滞后 100~450ms，用它切行会让上一行还没亮完就跳走。
        const clock = smoothPositionMs
        let low = 0
        let high = lines.length - 1
        let result = -1
        while (low <= high) {
            const mid = (low + high) >> 1
            if (lines[mid].timeMs <= clock) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }

    // ---- 收藏 ----

    /** 按当前歌曲重新查询是否已收藏。 */
    function refreshFavorite() {
        const current = song
        if (!current || !current.key) {
            favoriteSongKey = ""
            favorite = false
            return
        }
        const key = String(current.key)
        favoriteSongKey = key
        Api.call("collections.isFavorite", { songKey: key }).then(function(result) {
            if (player.favoriteSongKey !== key)
                return
            player.favorite = !!(result && result.favorite)
        }).catch(function() {
            // 收藏查询失败不影响播放，保持上一次状态即可
        })
    }

    /** 红心：乐观更新，失败时回滚并提示。 */
    function toggleFavorite() {
        const current = song
        if (!current)
            return
        const target = !favorite
        favorite = target
        Api.call("collections.toggleFavorite", { song: current }).then(function(result) {
            if (result && result.favorite !== undefined)
                player.favorite = !!result.favorite
            Toast.show(player.favorite ? "已加入我喜欢的音乐" : "已取消收藏", "info")
        }).catch(function(error) {
            player.favorite = !target
            Toast.show(error && error.message ? error.message : "收藏失败", "error")
        })
    }

    /** 应用一份播放器状态快照（事件与 app.bootstrap 共用）。 */
    function _apply(data) {
        if (!data)
            return
        if (data.song !== undefined) {
            song = data.song
            coverPath = data.song && data.song.artworkPath ? data.song.artworkPath : ""
        }
        if (data.state !== undefined) state = data.state
        if (data.positionMs !== undefined) positionMs = data.positionMs
        if (data.durationMs !== undefined) durationMs = data.durationMs
        if (data.volume !== undefined) volume = data.volume
        if (data.shuffle !== undefined) shuffle = data.shuffle
        if (data.repeatMode !== undefined) repeatMode = data.repeatMode
        if (data.speed !== undefined) speed = data.speed
        if (data.pitch !== undefined) pitch = data.pitch
        if (data.loudness !== undefined) loudness = data.loudness
        if (data.equalizerEnabled !== undefined) equalizerEnabled = data.equalizerEnabled
        if (data.equalizerBands !== undefined) equalizerBands = data.equalizerBands
        if (data.buffering !== undefined) buffering = data.buffering
        if (data.sleepTimer !== undefined) sleepTimer = data.sleepTimer
        if (data.supportsEffects !== undefined) supportsEffects = data.supportsEffects
        if (data.queueIndex !== undefined) queueIndex = data.queueIndex
        if (data.coverSeed !== undefined)
            coverSeed = data.coverSeed ? String(data.coverSeed) : ""
        if (data.lyrics !== undefined && data.lyrics !== null) lyrics = data.lyrics
        if (data.positionMs !== undefined)
            _syncSmoothPosition(positionMs)
    }

    function applySnapshot(data) { _apply(data) }

    // player 事件 4Hz 快照每次都带 song 对象（引用会变），只在 key 真正变化时查收藏，
    // 否则会每秒向后端发好几次 collections.isFavorite。
    onSongChanged: {
        const key = song && song.key ? String(song.key) : ""
        if (key === favoriteSongKey)
            return
        refreshFavorite()
    }

    // ---- 后端事件 ----
    property Connections backendConnection: Connections {
        target: Backend

        function onEvent(name, data) {
            if (name === "player") {
                player._apply(data)
            } else if (name === "player.queue") {
                player.queue = data.songs || []
                if (data.index !== undefined) player.queueIndex = data.index
            } else if (name === "player.lyrics") {
                player.lyrics = data
                player.lyricsLoading = false
            } else if (name === "player.lyricsLoading") {
                player.lyricsLoading = !!data.loading
            } else if (name === "player.coverSeed") {
                player.coverSeed = data.seed || ""
            } else if (name === "collections.changed") {
                if (player.hasSong)
                    player.refreshFavorite()
            }
        }
    }
}
