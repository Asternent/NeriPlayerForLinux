package moe.ouom.neriplayer.desktop.backend.api

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import moe.ouom.neriplayer.desktop.backend.CoreServer
import moe.ouom.neriplayer.desktop.backend.PlayerWire
import moe.ouom.neriplayer.desktop.backend.WireJson
import moe.ouom.neriplayer.desktop.backend.badRequest
import moe.ouom.neriplayer.desktop.backend.boolOrNull
import moe.ouom.neriplayer.desktop.backend.doubleOrNull
import moe.ouom.neriplayer.desktop.backend.encodeAny
import moe.ouom.neriplayer.desktop.backend.encodeJson
import moe.ouom.neriplayer.desktop.backend.intOrNull
import moe.ouom.neriplayer.desktop.backend.longOrNull
import moe.ouom.neriplayer.desktop.backend.objectOrNull
import moe.ouom.neriplayer.desktop.backend.arrayOrNull
import moe.ouom.neriplayer.desktop.backend.stringOrNull
import moe.ouom.neriplayer.desktop.core.AppContainer
import moe.ouom.neriplayer.desktop.core.RepeatMode
import moe.ouom.neriplayer.desktop.core.Song

/** 播放器命令。状态由 StatePublisher 通过 "player" / "player.queue" / "player.lyrics" 事件推送。 */
object PlayerApi {

    fun register(server: CoreServer, container: AppContainer) {
        val player = container.player

        fun ok() = buildJsonObject { put("ok", true) }

        server.on("player.togglePlayPause") { player.togglePlayPause(); ok() }
        server.on("player.play") { player.play(); ok() }
        server.on("player.pause") { player.pause(); ok() }
        server.on("player.next") { player.next(); ok() }
        server.on("player.previous") { player.previous(); ok() }

        server.on("player.seekTo") {
            player.seekTo(it.longOrNull("positionMs") ?: badRequest("缺少 positionMs")); ok()
        }
        server.on("player.setVolume") {
            player.setVolume((it.doubleOrNull("value") ?: badRequest("缺少 value")).toFloat()); ok()
        }
        server.on("player.setShuffle") {
            player.setShuffle(it.boolOrNull("enabled") ?: badRequest("缺少 enabled")); ok()
        }
        server.on("player.toggleShuffle") { player.toggleShuffle(); ok() }
        server.on("player.setRepeatMode") {
            val mode = it.stringOrNull("mode") ?: badRequest("缺少 mode")
            player.setRepeatMode(runCatching { RepeatMode.valueOf(mode) }.getOrElse {
                badRequest("非法 repeatMode: $mode")
            }); ok()
        }
        server.on("player.cycleRepeatMode") { player.cycleRepeatMode(); ok() }
        server.on("player.setSpeed") {
            player.setSpeed((it.doubleOrNull("value") ?: badRequest("缺少 value")).toFloat()); ok()
        }
        server.on("player.setPitch") {
            player.setPitch((it.doubleOrNull("value") ?: badRequest("缺少 value")).toFloat()); ok()
        }
        server.on("player.setLoudness") {
            player.setLoudness(it.boolOrNull("enabled") ?: badRequest("缺少 enabled")); ok()
        }
        server.on("player.setEqualizerEnabled") {
            player.setEqualizerEnabled(it.boolOrNull("enabled") ?: badRequest("缺少 enabled")); ok()
        }
        server.on("player.setEqualizerPreset") {
            val name = it.stringOrNull("name") ?: badRequest("缺少 name")
            val bands = it.arrayOrNull("bands")?.map { element ->
                (element as? JsonPrimitive)?.content?.toFloatOrNull() ?: 0f
            } ?: badRequest("缺少 bands")
            player.setEqualizerPreset(name, bands); ok()
        }
        server.on("player.setEqualizerBand") {
            val index = it.intOrNull("index") ?: badRequest("缺少 index")
            val gain = (it.doubleOrNull("gain") ?: badRequest("缺少 gain")).toFloat()
            player.setEqualizerBand(index, gain); ok()
        }
        server.on("player.resetEffects") { player.resetEffects(); ok() }

        server.on("player.setQueue") { params ->
            val songs = songsOf(params)
            val startIndex = params.intOrNull("startIndex") ?: 0
            val autoPlay = params.boolOrNull("autoPlay") ?: true
            player.setQueue(songs, startIndex, autoPlay)
            ok()
        }
        server.on("player.playSongNow") { params ->
            val song = songOf(params)
            val queue = params.arrayOrNull("queue")?.takeIf { it.isNotEmpty() }
                ?.let { array -> WireJson.decodeFromJsonElement(
                    kotlinx.serialization.builtins.ListSerializer(Song.serializer()), array) }
            player.playSongNow(song, queue)
            ok()
        }
        server.on("player.playAt") {
            player.playAt(it.intOrNull("index") ?: badRequest("缺少 index")); ok()
        }
        server.on("player.enqueue") { params ->
            player.enqueue(songsOf(params)); ok()
        }
        server.on("player.enqueueNext") { params ->
            player.enqueueNext(songsOf(params)); ok()
        }
        server.on("player.removeFromQueue") {
            player.removeFromQueue(it.intOrNull("index") ?: badRequest("缺少 index")); ok()
        }
        server.on("player.moveInQueue") {
            val from = it.intOrNull("from") ?: badRequest("缺少 from")
            val to = it.intOrNull("to") ?: badRequest("缺少 to")
            player.moveInQueue(from, to); ok()
        }
        server.on("player.clearQueue") { player.clearQueue(); ok() }

        server.on("player.startSleepTimer") {
            player.startSleepTimer(it.intOrNull("minutes") ?: badRequest("缺少 minutes")); ok()
        }
        server.on("player.stopAfterCurrentSong") { player.stopAfterCurrentSong(); ok() }
        server.on("player.cancelSleepTimer") { player.cancelSleepTimer(); ok() }
        server.on("player.reloadLyrics") { player.reloadLyrics(); ok() }

        server.on("player.state") { PlayerWire.state(player) }
        server.on("player.queue") { PlayerWire.queue(player) }
    }

    private fun songsOf(params: JsonObject): List<Song> {
        val songs = params.arrayOrNull("songs") ?: badRequest("缺少 songs")
        return runCatching {
            WireJson.decodeFromJsonElement(
                kotlinx.serialization.builtins.ListSerializer(Song.serializer()),
                songs,
            )
        }.getOrElse { badRequest("songs 格式非法: ${it.message}") }
    }

    private fun songOf(params: JsonObject): Song {
        val song = params.objectOrNull("song") ?: badRequest("缺少 song")
        return runCatching { WireJson.decodeFromJsonElement(Song.serializer(), song) }
            .getOrElse { badRequest("song 格式非法: ${it.message}") }
    }
}
