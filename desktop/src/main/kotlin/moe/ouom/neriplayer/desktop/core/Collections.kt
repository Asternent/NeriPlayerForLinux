package moe.ouom.neriplayer.desktop.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

const val FAVORITES_PLAYLIST_ID = "system:favorites"

/** 歌单仓库：用户歌单 + 「我喜欢的音乐」。 */
class PlaylistRepository {

    private val store = JsonFileStore(AppDirs.playlistFile, ListSerializer(Playlist.serializer())) { emptyList() }
    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    fun load() {
        val loaded = store.load().filter { it.id != FAVORITES_PLAYLIST_ID }
        val favorites = store.load().firstOrNull { it.id == FAVORITES_PLAYLIST_ID }
            ?: Playlist(
                id = FAVORITES_PLAYLIST_ID,
                name = "我喜欢的音乐",
                system = true,
                createdAt = System.currentTimeMillis(),
            )
        _playlists.value = listOf(favorites) + loaded.sortedBy { it.createdAt }
    }

    private fun persist() = store.save(_playlists.value)

    fun userPlaylists(): List<Playlist> = _playlists.value.filter { !it.system }

    fun favorites(): Playlist = _playlists.value.firstOrNull { it.id == FAVORITES_PLAYLIST_ID }
        ?: Playlist(FAVORITES_PLAYLIST_ID, "我喜欢的音乐", system = true)

    fun byId(id: String): Playlist? = _playlists.value.firstOrNull { it.id == id }

    fun isFavorite(song: Song): Boolean = favorites().songs.any { it.key == song.key }

    fun createPlaylist(name: String): Playlist {
        val now = System.currentTimeMillis()
        val playlist = Playlist(
            id = UUID.randomUUID().toString(),
            name = name,
            createdAt = now,
            updatedAt = now,
        )
        _playlists.value = _playlists.value + playlist
        persist()
        return playlist
    }

    fun renamePlaylist(id: String, name: String) {
        _playlists.value = _playlists.value.map {
            if (it.id == id) it.copy(name = name, updatedAt = System.currentTimeMillis()) else it
        }
        persist()
    }

    fun deletePlaylist(id: String) {
        if (id == FAVORITES_PLAYLIST_ID) return
        _playlists.value = _playlists.value.filterNot { it.id == id }
        persist()
    }

    fun addSongs(id: String, songs: List<Song>) {
        if (songs.isEmpty()) return
        _playlists.value = _playlists.value.map { playlist ->
            if (playlist.id != id) return@map playlist
            val existing = playlist.songs.mapTo(HashSet()) { it.key }
            val merged = playlist.songs + songs.filter { existing.add(it.key) }
            playlist.copy(songs = merged, updatedAt = System.currentTimeMillis())
        }
        persist()
    }

    fun removeSong(id: String, songKey: String) {
        _playlists.value = _playlists.value.map { playlist ->
            if (playlist.id != id) return@map playlist
            playlist.copy(
                songs = playlist.songs.filterNot { it.key == songKey },
                updatedAt = System.currentTimeMillis(),
            )
        }
        persist()
    }

    fun moveSong(id: String, from: Int, to: Int) {
        _playlists.value = _playlists.value.map { playlist ->
            if (playlist.id != id) return@map playlist
            val list = playlist.songs.toMutableList()
            if (from !in list.indices || to !in list.indices) return@map playlist
            val item = list.removeAt(from)
            list.add(to, item)
            playlist.copy(songs = list, updatedAt = System.currentTimeMillis())
        }
        persist()
    }

    fun toggleFavorite(song: Song): Boolean {
        val isFav = isFavorite(song)
        if (isFav) {
            removeSong(FAVORITES_PLAYLIST_ID, song.key)
        } else {
            addSongs(FAVORITES_PLAYLIST_ID, listOf(song))
        }
        return !isFav
    }

    /** 同步导入：按固定 ID 覆盖或创建歌单。 */
    fun upsertFromSync(
        playlistId: String,
        name: String,
        songs: List<Song>,
        createdAt: Long,
        updatedAt: Long,
    ): Boolean {
        val existing = _playlists.value.firstOrNull { it.id == playlistId }
        return if (existing == null) {
            _playlists.value = _playlists.value + Playlist(
                id = playlistId,
                name = name,
                songs = songs,
                system = false,
                createdAt = createdAt,
                updatedAt = updatedAt,
            )
            persist()
            true
        } else if (existing.songs != songs || existing.name != name) {
            _playlists.value = _playlists.value.map {
                if (it.id == playlistId) it.copy(name = name, songs = songs, updatedAt = updatedAt) else it
            }
            persist()
            true
        } else {
            false
        }
    }

    /** 同步导入：整体替换「我喜欢的音乐」。 */
    fun replaceFavorites(songs: List<Song>) {
        _playlists.value = _playlists.value.map { playlist ->
            if (playlist.id == FAVORITES_PLAYLIST_ID) {
                playlist.copy(songs = songs, updatedAt = System.currentTimeMillis())
            } else {
                playlist
            }
        }
        persist()
    }
}

/** 播放历史 / 继续播放。 */
class HistoryRepository {

    private val store = JsonFileStore(AppDirs.historyFile, ListSerializer(UsageEntry.serializer())) { emptyList() }
    private val _entries = MutableStateFlow<List<UsageEntry>>(emptyList())
    val entries: StateFlow<List<UsageEntry>> = _entries.asStateFlow()

    fun load() {
        _entries.value = store.load().sortedByDescending { it.playedAt }
    }

    private fun persist() = store.save(_entries.value)

    fun record(song: Song, playedMs: Long = 0L) {
        val now = System.currentTimeMillis()
        val list = _entries.value.toMutableList()
        val index = list.indexOfFirst { it.song.key == song.key }
        if (index >= 0) {
            val old = list.removeAt(index)
            list.add(0, old.copy(song = song, playedAt = now, playCount = old.playCount + 1))
        } else {
            list.add(0, UsageEntry(song = song, playedAt = now, playCount = 1))
        }
        _entries.value = list.take(600)
        persist()
    }

    fun clear() {
        _entries.value = emptyList()
        persist()
    }

    fun removeEntry(songKey: String) {
        _entries.value = _entries.value.filterNot { it.song.key == songKey }
        persist()
    }

    /** 同步导入：写入一条最近播放记录（保留原时间）。 */
    fun importEntry(song: Song, playedAt: Long, playCount: Int) {
        if (playedAt <= 0L) return
        val list = _entries.value.toMutableList()
        val index = list.indexOfFirst { it.song.key == song.key }
        if (index >= 0) {
            val old = list[index]
            if (playedAt >= old.playedAt) {
                list[index] = old.copy(song = song, playedAt = playedAt, playCount = maxOf(old.playCount, playCount))
            }
        } else {
            list.add(UsageEntry(song = song, playedAt = playedAt, playCount = playCount.coerceAtLeast(1)))
        }
        _entries.value = list.sortedByDescending { it.playedAt }.take(600)
        persist()
    }

    fun continuePlaying(limit: Int = 12): List<UsageEntry> =
        _entries.value.sortedByDescending { it.playedAt }.take(limit)

    fun recentlyPlayedSongs(limit: Int = 60): List<Song> =
        _entries.value.sortedByDescending { it.playedAt }.map { it.song }.take(limit)

    fun mostPlayedSongs(limit: Int = 12): List<Song> =
        _entries.value.sortedByDescending { it.playCount }.map { it.song }.take(limit)
}

enum class StatsPeriod { DAY, WEEK, MONTH, YEAR, ALL }

enum class StatsSort { PLAY_COUNT, LISTEN_TIME, RECENT }

data class StatsSummary(
    val plays: Int = 0,
    val listenMs: Long = 0L,
    val trackCount: Int = 0,
)

data class HotSong(
    val song: Song,
    val playCount: Int,
    val listenMs: Long,
    val lastPlayed: Long,
)

/** 播放统计仓库（按天分桶累计）。 */
class StatsRepository(private val history: HistoryRepository) {

    private val store = JsonFileStore(AppDirs.statsFile, ListSerializer(PlayStat.serializer())) { emptyList() }
    private val _stats = MutableStateFlow<List<PlayStat>>(emptyList())
    val stats: StateFlow<List<PlayStat>> = _stats.asStateFlow()

    fun load() {
        _stats.value = store.load()
    }

    private fun persist() = store.save(_stats.value)

    private fun mutate(song: Song, playIncrement: Int, listenDelta: Long) {
        val day = LocalDate.now().toString()
        val list = _stats.value.toMutableList()
        val index = list.indexOfFirst { it.songKey == song.key && it.day == day }
        if (index >= 0) {
            val old = list[index]
            list[index] = old.copy(
                playCount = old.playCount + playIncrement,
                listenMs = old.listenMs + listenDelta.coerceAtLeast(0),
            )
        } else {
            list.add(
                PlayStat(
                    song.key,
                    day,
                    playCount = playIncrement.coerceAtLeast(0),
                    listenMs = listenDelta.coerceAtLeast(0),
                )
            )
        }
        _stats.value = list
        persist()
    }

    /** 记录一次播放（歌曲开始播放时调用）。 */
    fun recordPlay(song: Song) = mutate(song, playIncrement = 1, listenDelta = 0L)

    /** 追加收听时长，用于边播放边累计统计。 */
    fun addListenTime(song: Song, deltaMs: Long) = mutate(song, playIncrement = 0, listenDelta = deltaMs)

    fun record(song: Song, listenMs: Long) = mutate(song, playIncrement = 1, listenDelta = listenMs)

    fun clear() {
        _stats.value = emptyList()
        persist()
    }

    /** 同步导入：按 (歌曲, 日期) 取较大值合并。 */
    fun upsertFromSync(songKey: String, day: String, playCount: Int, listenMs: Long) {
        if (day.isBlank()) return
        val list = _stats.value.toMutableList()
        val index = list.indexOfFirst { it.songKey == songKey && it.day == day }
        if (index >= 0) {
            val old = list[index]
            list[index] = old.copy(
                playCount = maxOf(old.playCount, playCount),
                listenMs = maxOf(old.listenMs, listenMs),
            )
        } else {
            list.add(PlayStat(songKey, day, playCount.coerceAtLeast(0), listenMs.coerceAtLeast(0)))
        }
        _stats.value = list
        persist()
    }

    private fun startOfPeriod(period: StatsPeriod): LocalDate? {
        val today = LocalDate.now()
        return when (period) {
            StatsPeriod.DAY -> today
            StatsPeriod.WEEK -> today.minusDays(6)
            StatsPeriod.MONTH -> today.minusDays(29)
            StatsPeriod.YEAR -> today.minusDays(364)
            StatsPeriod.ALL -> null
        }
    }

    private fun filtered(period: StatsPeriod): List<PlayStat> {
        val start = startOfPeriod(period) ?: return _stats.value
        return _stats.value.filter { stat ->
            runCatching { !LocalDate.parse(stat.day).isBefore(start) }.getOrDefault(true)
        }
    }

    fun summary(period: StatsPeriod): StatsSummary {
        val list = filtered(period)
        return StatsSummary(
            plays = list.sumOf { it.playCount },
            listenMs = list.sumOf { it.listenMs },
            trackCount = list.mapTo(HashSet()) { it.songKey }.size,
        )
    }

    fun topSongs(period: StatsPeriod, sort: StatsSort, limit: Int = 50): List<HotSong> {
        val songByKey = history.entries.value.associate { it.song.key to it.song }
        val grouped = filtered(period).groupBy { it.songKey }
        val result = grouped.mapNotNull { (key, stats) ->
            val song = songByKey[key] ?: return@mapNotNull null
            val lastPlayed = history.entries.value.firstOrNull { it.song.key == key }?.playedAt ?: 0L
            HotSong(
                song = song,
                playCount = stats.sumOf { it.playCount },
                listenMs = stats.sumOf { it.listenMs },
                lastPlayed = lastPlayed,
            )
        }
        val sorted = when (sort) {
            StatsSort.PLAY_COUNT -> result.sortedByDescending { it.playCount }
            StatsSort.LISTEN_TIME -> result.sortedByDescending { it.listenMs }
            StatsSort.RECENT -> result.sortedByDescending { it.lastPlayed }
        }
        return sorted.take(limit)
    }
}

fun dayKeyOf(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate().toString()

fun daysBetween(from: LocalDate, to: LocalDate): Long = ChronoUnit.DAYS.between(from, to)
