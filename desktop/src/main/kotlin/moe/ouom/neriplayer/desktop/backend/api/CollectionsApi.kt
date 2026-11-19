package moe.ouom.neriplayer.desktop.backend.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.builtins.ListSerializer
import moe.ouom.neriplayer.desktop.backend.CoreServer
import moe.ouom.neriplayer.desktop.backend.ProtocolException
import moe.ouom.neriplayer.desktop.backend.WireJson
import moe.ouom.neriplayer.desktop.backend.arrayOrNull
import moe.ouom.neriplayer.desktop.backend.badRequest
import moe.ouom.neriplayer.desktop.backend.encodeJson
import moe.ouom.neriplayer.desktop.backend.intOrNull
import moe.ouom.neriplayer.desktop.backend.objectOrNull
import moe.ouom.neriplayer.desktop.backend.stringOrNull
import moe.ouom.neriplayer.desktop.core.AppContainer
import moe.ouom.neriplayer.desktop.core.Playlist
import moe.ouom.neriplayer.desktop.core.Song
import moe.ouom.neriplayer.desktop.core.StatsPeriod
import moe.ouom.neriplayer.desktop.core.StatsSort
import java.util.Locale

/**
 * 歌单 / 收藏 / 播放历史 / 播放统计命令。
 *
 * 都是轻量读写（数据在内存 StateFlow 里，写操作会整表落盘），写命令统一切到 IO 线程执行；
 * 变化由 StatePublisher 通过 `collections.changed` / `history.changed` / `stats.changed` 推送。
 */
object CollectionsApi {

    private val PERIODS = listOf(
        StatsPeriod.DAY to "日",
        StatsPeriod.WEEK to "周",
        StatsPeriod.MONTH to "月",
        StatsPeriod.YEAR to "年",
        StatsPeriod.ALL to "总",
    )

    private val SORTS = listOf(
        StatsSort.PLAY_COUNT to "按播放次数",
        StatsSort.LISTEN_TIME to "按累计时长",
        StatsSort.RECENT to "按最近播放",
    )

    fun register(server: CoreServer, container: AppContainer) {
        val playlists = container.playlists
        val history = container.history
        val stats = container.stats

        fun ok() = buildJsonObject { put("ok", true) }

        // ---------------------------------------------------------------- 歌单 / 收藏
        server.on("collections.list") {
            buildJsonObject {
                // playlists 只含用户歌单；系统「我喜欢的音乐」单独放在 favorites
                put("playlists", encodeJson(playlists.userPlaylists()))
                put("favorites", encodeJson(playlists.favorites()))
            }
        }

        server.on("collections.create") { params ->
            val name = params.stringOrNull("name")?.trim().orEmpty()
            if (name.isBlank()) badRequest("缺少 name")
            val playlist = withContext(Dispatchers.IO) { playlists.createPlaylist(name) }
            buildJsonObject { put("playlist", encodeJson(playlist)) }
        }

        server.on("collections.rename") { params ->
            val playlist = playlistOf(container, params)
            if (playlist.system) badRequest("不能重命名系统歌单")
            val name = params.stringOrNull("name")?.trim().orEmpty()
            if (name.isBlank()) badRequest("缺少 name")
            withContext(Dispatchers.IO) { playlists.renamePlaylist(playlist.id, name) }
            ok()
        }

        server.on("collections.delete") { params ->
            val playlist = playlistOf(container, params)
            if (playlist.system) badRequest("不能删除系统歌单")
            withContext(Dispatchers.IO) { playlists.deletePlaylist(playlist.id) }
            ok()
        }

        server.on("collections.addSongs") { params ->
            val playlist = playlistOf(container, params)
            val songs = songsOf(params)
            withContext(Dispatchers.IO) { playlists.addSongs(playlist.id, songs) }
            ok()
        }

        server.on("collections.removeSong") { params ->
            val playlist = playlistOf(container, params)
            val songKey = params.stringOrNull("songKey")?.trim().orEmpty()
            if (songKey.isBlank()) badRequest("缺少 songKey")
            withContext(Dispatchers.IO) { playlists.removeSong(playlist.id, songKey) }
            ok()
        }

        server.on("collections.moveSong") { params ->
            val playlist = playlistOf(container, params)
            val from = params.intOrNull("from") ?: badRequest("缺少 from")
            val to = params.intOrNull("to") ?: badRequest("缺少 to")
            if (from !in playlist.songs.indices || to !in playlist.songs.indices) {
                badRequest("索引越界: from=$from, to=$to, size=${playlist.songs.size}")
            }
            withContext(Dispatchers.IO) { playlists.moveSong(playlist.id, from, to) }
            ok()
        }

        server.on("collections.toggleFavorite") { params ->
            val song = songOf(params)
            val favorite = withContext(Dispatchers.IO) { playlists.toggleFavorite(song) }
            buildJsonObject { put("favorite", favorite) }
        }

        server.on("collections.isFavorite") { params ->
            val songKey = params.stringOrNull("songKey")?.trim().orEmpty()
            if (songKey.isBlank()) badRequest("缺少 songKey")
            val favorite = playlists.favorites().songs.any { it.key == songKey }
            buildJsonObject { put("favorite", favorite) }
        }

        server.on("collections.replaceFavorites") { params ->
            val songs = songsOf(params)
            withContext(Dispatchers.IO) { playlists.replaceFavorites(songs) }
            ok()
        }

        // ---------------------------------------------------------------- 播放历史
        server.on("history.list") { params ->
            val limit = limitOf(params, default = 600)
            buildJsonObject { put("entries", encodeJson(history.entries.value.take(limit))) }
        }

        server.on("history.continuePlaying") { params ->
            val limit = limitOf(params, default = 12)
            val songs = history.continuePlaying(limit).map { it.song }
            buildJsonObject { put("songs", encodeJson(songs)) }
        }

        server.on("history.recentlyPlayed") { params ->
            val limit = limitOf(params, default = 60)
            buildJsonObject { put("songs", encodeJson(history.recentlyPlayedSongs(limit))) }
        }

        server.on("history.mostPlayed") { params ->
            val limit = limitOf(params, default = 12)
            buildJsonObject { put("songs", encodeJson(history.mostPlayedSongs(limit))) }
        }

        server.on("history.remove") { params ->
            val songKey = params.stringOrNull("songKey")?.trim().orEmpty()
            if (songKey.isBlank()) badRequest("缺少 songKey")
            withContext(Dispatchers.IO) { history.removeEntry(songKey) }
            ok()
        }

        server.on("history.clear") {
            withContext(Dispatchers.IO) { history.clear() }
            ok()
        }

        // ---------------------------------------------------------------- 播放统计
        server.on("stats.summary") { params ->
            val period = periodOf(params)
            buildJsonObject { put("summary", encodeJson(stats.summary(period))) }
        }

        server.on("stats.top") { params ->
            val period = periodOf(params)
            val sort = sortOf(params)
            val limit = limitOf(params, default = 50)
            buildJsonObject { put("songs", encodeJson(stats.topSongs(period, sort, limit))) }
        }

        server.on("stats.clear") {
            withContext(Dispatchers.IO) { stats.clear() }
            ok()
        }

        server.on("stats.periods") {
            buildJsonObject {
                put("periods", buildJsonArray {
                    PERIODS.forEach { (value, label) ->
                        add(buildJsonObject {
                            put("value", value.name)
                            put("label", label)
                        })
                    }
                })
                put("sorts", buildJsonArray {
                    SORTS.forEach { (value, label) ->
                        add(buildJsonObject {
                            put("value", value.name)
                            put("label", label)
                        })
                    }
                })
            }
        }
    }

    // ---------------------------------------------------------------- 参数解析

    private fun playlistOf(container: AppContainer, params: JsonObject): Playlist {
        val id = params.stringOrNull("id")?.trim().orEmpty()
        if (id.isBlank()) badRequest("缺少 id")
        return container.playlists.byId(id)
            ?: throw ProtocolException("not_found", "歌单不存在: $id")
    }

    private fun songsOf(params: JsonObject): List<Song> {
        val songs = params.arrayOrNull("songs") ?: badRequest("缺少 songs")
        return runCatching {
            WireJson.decodeFromJsonElement(ListSerializer(Song.serializer()), songs)
        }.getOrElse { badRequest("songs 格式非法: ${it.message}") }
    }

    private fun songOf(params: JsonObject): Song {
        val song = params.objectOrNull("song") ?: badRequest("缺少 song")
        return runCatching { WireJson.decodeFromJsonElement(Song.serializer(), song) }
            .getOrElse { badRequest("song 格式非法: ${it.message}") }
    }

    private fun limitOf(params: JsonObject, default: Int): Int {
        val limit = params.intOrNull("limit") ?: return default
        if (limit < 0) badRequest("limit 不能为负数: $limit")
        return limit
    }

    private fun periodOf(params: JsonObject): StatsPeriod {
        val raw = params.stringOrNull("period") ?: badRequest("缺少 period")
        return runCatching { StatsPeriod.valueOf(raw.trim().uppercase(Locale.ROOT)) }
            .getOrElse { badRequest("非法 period: $raw（可选 DAY/WEEK/MONTH/YEAR/ALL）") }
    }

    private fun sortOf(params: JsonObject): StatsSort {
        val raw = params.stringOrNull("sort") ?: badRequest("缺少 sort")
        return runCatching { StatsSort.valueOf(raw.trim().uppercase(Locale.ROOT)) }
            .getOrElse { badRequest("非法 sort: $raw（可选 PLAY_COUNT/LISTEN_TIME/RECENT）") }
    }
}
