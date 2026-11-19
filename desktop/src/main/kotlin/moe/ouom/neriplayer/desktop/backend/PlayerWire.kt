package moe.ouom.neriplayer.desktop.backend

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import moe.ouom.neriplayer.desktop.core.LyricLine
import moe.ouom.neriplayer.desktop.core.Lyrics
import moe.ouom.neriplayer.desktop.core.PlayerManager
import moe.ouom.neriplayer.desktop.core.Song
import moe.ouom.neriplayer.desktop.core.SleepTimerState

/** 播放器相关的 JSON 编码（事件、快照共用一个形状）。 */
object PlayerWire {

    fun songOrNull(song: Song?): JsonElement =
        if (song == null) JsonNull else encodeJson(song)

    fun sleepTimer(state: SleepTimerState): JsonObject = buildJsonObject {
        put("active", state.active)
        put("stopAfterCurrent", state.stopAfterCurrent)
        put("remainingSeconds", state.remainingSeconds)
        put("totalSeconds", state.totalSeconds)
    }

    fun lyrics(lyrics: Lyrics): JsonObject = buildJsonObject {
        put("source", lyrics.source)
        put("raw", lyrics.raw)
        put("lines", buildJsonArray {
            for (line in lyrics.lines) add(lineWire(line))
        })
        put("translated", buildJsonArray {
            for (line in lyrics.translated) add(lineWire(line))
        })
    }

    /** 歌词行（含逐词时间）；字段都是增量，旧消费方读不到时可忽略。 */
    private fun lineWire(line: LyricLine): JsonObject = buildJsonObject {
        put("timeMs", line.timeMs)
        put("text", line.text)
        put("translation", line.translation)
        put("endMs", line.endMs)
        put("words", buildJsonArray {
            for (word in line.words) add(buildJsonObject {
                put("startMs", word.startMs)
                put("durationMs", word.durationMs)
                put("text", word.text)
            })
        })
    }

    /** 播放器当前状态快照（不含队列与歌词，这两者有独立事件）。 */
    fun state(player: PlayerManager): JsonObject = buildJsonObject {
        put("song", songOrNull(player.currentSong.value))
        put("state", player.state.value.name)
        put("positionMs", player.positionMs.value)
        put("durationMs", player.durationMs.value)
        put("volume", player.volume.value)
        put("shuffle", player.shuffle.value)
        put("repeatMode", player.repeatMode.value.name)
        put("speed", player.speed.value)
        put("pitch", player.pitch.value)
        put("loudness", player.loudness.value)
        put("equalizerEnabled", player.equalizerEnabled.value)
        put("equalizerBands", encodeAny(player.equalizerBands.value))
        put("buffering", player.buffering.value)
        put("sleepTimer", sleepTimer(player.sleepTimer.value))
        put("supportsEffects", player.supportsEffects)
        put("queueIndex", player.currentIndex.value)
        put("coverSeed", player.coverSeedColor.value)
    }

    fun queue(player: PlayerManager): JsonObject = buildJsonObject {
        put("songs", encodeJson(player.queue.value))
        put("index", player.currentIndex.value)
    }
}
