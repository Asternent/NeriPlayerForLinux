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

    readonly property bool playing: state === "PLAYING"
    readonly property bool idle: state === "IDLE"
    readonly property bool hasSong: song !== null && song !== undefined
    readonly property string title: song && song.title ? song.title : ""
    readonly property string artist: song && song.artist ? song.artist : ""
    readonly property string album: song && song.album ? song.album : ""
    readonly property real progress: durationMs > 0 ? Math.min(1, Math.max(0, positionMs / durationMs)) : 0

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
        positionMs = Math.max(0, ms)
        _send("player.seekTo", { positionMs: Math.max(0, Math.round(ms)) })
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
        let low = 0
        let high = lines.length - 1
        let result = -1
        while (low <= high) {
            const mid = (low + high) >> 1
            if (lines[mid].timeMs <= positionMs) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
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
        if (data.lyrics !== undefined && data.lyrics !== null) lyrics = data.lyrics
    }

    function applySnapshot(data) { _apply(data) }

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
            }
        }
    }
}
