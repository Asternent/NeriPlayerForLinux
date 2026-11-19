.pragma library

// 媒体库相关页面共用的格式化工具（纯函数，可在任何 QML 里 import "Format.js" as Format 使用）。

/** 毫秒 → "m:ss"；无效/0 返回 "--:--"（与旧 Compose 的 formatDuration 一致）。 */
function formatDuration(ms) {
    if (!ms || ms <= 0)
        return "--:--"
    var totalSeconds = Math.floor(ms / 1000)
    var minutes = Math.floor(totalSeconds / 60)
    var seconds = totalSeconds % 60
    return minutes + ":" + (seconds < 10 ? "0" + seconds : seconds)
}

/** 毫秒 → "x 小时 y 分钟" / "y 分钟" / "z 秒"（与旧 Compose 的 formatLongDuration 一致）。 */
function formatLongDuration(ms) {
    if (!ms || ms <= 0)
        return "0 分钟"
    var totalSeconds = Math.floor(ms / 1000)
    if (totalSeconds < 60)
        return totalSeconds + " 秒"
    var totalMinutes = Math.floor(totalSeconds / 60)
    var hours = Math.floor(totalMinutes / 60)
    var minutes = totalMinutes % 60
    return hours > 0 ? (hours + " 小时 " + minutes + " 分钟") : (minutes + " 分钟")
}

/** 毫秒时间戳 → "MM-dd HH:mm"（最近播放用）。 */
function formatDateTime(ms) {
    if (!ms || ms <= 0)
        return ""
    var date = new Date(ms)
    function pad(value) { return value < 10 ? "0" + value : "" + value }
    return pad(date.getMonth() + 1) + "-" + pad(date.getDate()) + " "
            + pad(date.getHours()) + ":" + pad(date.getMinutes())
}

/** 播放次数 → "1.2 万" / "1.2 亿"。 */
function formatPlayCount(count) {
    if (!count)
        return "0"
    if (count >= 100000000)
        return (count / 100000000).toFixed(1) + " 亿"
    if (count >= 10000)
        return (count / 10000).toFixed(1) + " 万"
    return String(count)
}

function songTitle(song) {
    if (!song)
        return "未知歌曲"
    if (song.title)
        return String(song.title)
    if (song.filePath) {
        var name = String(song.filePath).split("/").pop()
        var dot = name.lastIndexOf(".")
        return dot > 0 ? name.substring(0, dot) : name
    }
    return "未知歌曲"
}

function songArtist(song) {
    return song && song.artist ? String(song.artist) : "未知艺术家"
}

function songAlbum(song) {
    return song && song.album ? String(song.album) : "未知专辑"
}

/** 歌曲副标题：默认 "艺术家 · 专辑"；showAlbum 为 false 时只有艺术家。 */
function songSubtitle(song, showAlbum) {
    if (!song)
        return ""
    var parts = []
    if (song.artist)
        parts.push(song.artist)
    if (showAlbum !== false && song.album && song.album !== song.artist)
        parts.push(song.album)
    return parts.length > 0 ? parts.join(" · ") : "未知艺术家"
}

function sameKey(left, right) {
    return !!(left && right && left.key === right.key)
}

/** 列表总时长（毫秒）。 */
function totalDuration(songs) {
    if (!songs)
        return 0
    var sum = 0
    for (var i = 0; i < songs.length; i++) {
        if (songs[i] && songs[i].durationMs)
            sum += songs[i].durationMs
    }
    return sum
}

/** "N 首 · x 小时 y 分钟"（歌曲列表工具条标题）。 */
function songsSummary(songs) {
    var count = songs ? songs.length : 0
    return count + " 首 · " + formatLongDuration(totalDuration(songs))
}
