package moe.ouom.neriplayer.desktop.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.desktop.core.HistoryRepository
import moe.ouom.neriplayer.desktop.core.LibraryRepository
import moe.ouom.neriplayer.desktop.core.PlayStat
import moe.ouom.neriplayer.desktop.core.PlaylistRepository
import moe.ouom.neriplayer.desktop.core.Song
import moe.ouom.neriplayer.desktop.core.StatsRepository
import moe.ouom.neriplayer.desktop.net.HttpService
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

data class SyncState(
    val running: Boolean = false,
    val configured: Boolean = false,
    val lastSyncAt: Long = 0L,
    val message: String = "",
    val success: Boolean = true,
)

/**
 * GitHub 同步：与手机端共用同一个仓库与同步文件。
 *
 * 流程：本地快照 → 拉取远端 → 合并（歌单/收藏/最近播放/播放统计）→ 写回本地 → 上传合并结果；
 * 上传使用 Git 数据接口并带上读取时的 HEAD，若期间有其他设备提交则判定冲突并自动重试一次。
 */
class GitHubSyncManager(
    private val configStore: SyncConfigStore,
    private val playlists: PlaylistRepository,
    private val history: HistoryRepository,
    private val stats: StatsRepository,
    private val library: LibraryRepository,
    private val http: HttpService,
) {

    private val _state = MutableStateFlow(
        SyncState(
            configured = configStore.current.configured,
            lastSyncAt = configStore.current.lastSyncAt,
            message = configStore.current.lastStatus,
        )
    )
    val state: StateFlow<SyncState> = _state.asStateFlow()

    fun refreshConfiguredState() {
        val cfg = configStore.current
        _state.value = _state.value.copy(
            configured = cfg.configured,
            lastSyncAt = cfg.lastSyncAt,
            message = cfg.lastStatus,
        )
    }

    private fun transportFor(token: String = configStore.current.token): GitHubSyncTransport =
        GitHubSyncTransport(http, token, configStore.current.apiBase)

    suspend fun verifyToken(token: String): Result<String> = withContext(Dispatchers.IO) {
        val transport = transportFor(token)
        val login = transport.currentUser()
        if (login == null) {
            Result.failure(IllegalStateException("Token 无效或网络不可用"))
        } else {
            val scopes = transport.tokenScopes()
            if (scopes.isNotEmpty() && scopes.none { it == "repo" || it == "public_repo" }) {
                Result.failure(IllegalStateException("Token 缺少 repo 权限（当前：${scopes.joinToString(",")}）"))
            } else {
                Result.success(login)
            }
        }
    }

    suspend fun listRepos(token: String): List<String> = withContext(Dispatchers.IO) {
        transportFor(token).listRepos()
    }

    suspend fun createRepo(token: String, name: String): Result<String> = withContext(Dispatchers.IO) {
        transportFor(token).createRepo(name.trim(), private = true)
    }

    suspend fun performSync(): SyncState = withContext(Dispatchers.IO) {
        val cfg = ensureDeviceId()
        if (!cfg.configured) {
            return@withContext finish(false, "尚未配置 GitHub 同步")
        }
        _state.value = _state.value.copy(running = true, message = "正在同步…", configured = true)
        val transport = transportFor(cfg.token)

        var lastError = ""
        for (attempt in 1..2) {
            val local = buildLocalData(cfg)
            val remoteFile = transport.readSyncFile(cfg.owner, cfg.repo, cfg.useDataSaver)
            val remote = remoteFile?.let { file ->
                runCatching { SyncDataSerializer.deserialize(file.content) }.getOrElse { SyncData() }
            }
            if (remote == null || isEmptyData(remote)) {
                val payload = prepareUpload(local, cfg)
                when (val result = transport.writeSyncFile(
                    owner = cfg.owner,
                    repo = cfg.repo,
                    content = payload,
                    expectedHead = remoteFile?.headSha,
                    message = "NeriPlayer 桌面端初始同步",
                    useDataSaver = cfg.useDataSaver,
                )) {
                    is SyncWriteResult.Success -> {
                        applyToLocal(local)
                        return@withContext finish(true, "初始数据已上传到 ${cfg.repoFullName}")
                    }

                    SyncWriteResult.Conflict -> {
                        lastError = "远端已更新，正在重试"
                        continue
                    }

                    is SyncWriteResult.Failed -> return@withContext finish(false, result.message)
                }
            }

            val merged = SyncMerger.merge(local, remote)
            applyToLocal(merged)
            if (SyncMerger.equivalent(merged, remote)) {
                return@withContext finish(true, "已是最新（远端数据已合并）")
            }
            val payload = prepareUpload(merged, cfg)
            when (val result = transport.writeSyncFile(
                owner = cfg.owner,
                repo = cfg.repo,
                content = payload,
                expectedHead = remoteFile.headSha,
                message = "NeriPlayer 桌面端同步 ${LocalDate.now()}",
                useDataSaver = cfg.useDataSaver,
            )) {
                is SyncWriteResult.Success -> return@withContext finish(true, describeMerge(merged))
                SyncWriteResult.Conflict -> {
                    lastError = "远端已更新，正在重试"
                    continue
                }

                is SyncWriteResult.Failed -> return@withContext finish(false, result.message)
            }
        }
        finish(false, lastError.ifBlank { "同步失败，请稍后重试" })
    }

    private fun ensureDeviceId(): SyncConfig {
        val cfg = configStore.current
        if (cfg.deviceId.isNotBlank()) return cfg
        val updated = cfg.copy(deviceId = "desktop-" + UUID.randomUUID().toString().take(8))
        configStore.update { updated }
        return updated
    }

    private fun finish(success: Boolean, message: String): SyncState {
        val now = System.currentTimeMillis()
        configStore.update {
            it.copy(lastSyncAt = if (success) now else it.lastSyncAt, lastStatus = message)
        }
        val state = SyncState(
            running = false,
            configured = true,
            lastSyncAt = if (success) now else configStore.current.lastSyncAt,
            message = message,
            success = success,
        )
        _state.value = state
        println("[sync] ${if (success) "成功" else "失败"}：$message")
        return state
    }

    // ------------------------------------------------------------------ 本地快照

    private fun buildLocalData(cfg: SyncConfig): SyncData {
        val songLookup = buildSongLookup()
        val favoriteSongs = playlists.favorites().songs
        val favoritePlaylists = favoriteSongs.groupBy { it.source }.map { (source, songs) ->
            val addedTime = songs.minOfOrNull { it.dateAdded } ?: 0L
            val modifiedAt = songs.maxOfOrNull { it.dateAdded } ?: addedTime
            SyncFavoritePlaylist(
                id = SyncMapping.favoriteSyncId(source),
                name = "${source.displayName}收藏",
                coverUrl = songs.firstNotNullOfOrNull { it.artworkUrl },
                trackCount = songs.size,
                source = source.name.lowercase(),
                songs = songs.map(SyncMapping::toSyncSong),
                addedTime = addedTime,
                modifiedAt = modifiedAt,
                sortOrder = addedTime,
            )
        }

        val statsBySong = stats.stats.value.groupBy { it.songKey }
        val buckets = mutableListOf<SyncPlaybackStatBucket>()
        val trackStats = mutableListOf<SyncTrackStat>()
        statsBySong.forEach { (songKey, entries) ->
            val song = songLookup[songKey] ?: return@forEach
            val identityKey = SyncMapping.identityKey(song)
            entries.forEach { entry ->
                val dayStart = dayStartAt(entry.day) ?: return@forEach
                buckets += SyncPlaybackStatBucket(
                    dayStartAt = dayStart,
                    identityKey = identityKey,
                    name = song.displayName(),
                    artist = song.artist,
                    album = song.album,
                    totalListenMs = entry.listenMs,
                    playCount = entry.playCount,
                    lastPlayedAt = dayStart,
                    firstPlayedAt = dayStart,
                    coverUrl = song.artworkUrl,
                    durationMs = song.durationMs,
                    mediaUri = SyncMapping.songMediaUri(song),
                    id = SyncMapping.songSyncId(song),
                )
            }
            trackStats += SyncTrackStat(
                identityKey = identityKey,
                name = song.displayName(),
                artist = song.artist,
                album = song.album,
                totalListenMs = entries.sumOf { it.listenMs },
                playCount = entries.sumOf { it.playCount }.coerceAtMost(Int.MAX_VALUE),
                lastPlayedAt = entries.maxOfOrNull { dayStartAt(it.day) ?: 0L } ?: 0L,
                firstPlayedAt = entries.minOfOrNull { dayStartAt(it.day) ?: Long.MAX_VALUE } ?: 0L,
                coverUrl = song.artworkUrl,
                durationMs = song.durationMs,
                mediaUri = SyncMapping.songMediaUri(song),
                id = SyncMapping.songSyncId(song),
            )
        }

        return SyncData(
            version = SYNC_DATA_VERSION,
            deviceId = cfg.deviceId,
            deviceName = "NeriPlayer Desktop",
            playlists = playlists.userPlaylists().map(SyncMapping::toSyncPlaylist),
            favoritePlaylists = favoritePlaylists,
            recentPlays = history.entries.value.take(300).map { entry ->
                SyncRecentPlay(
                    songId = SyncMapping.songSyncId(entry.song),
                    song = SyncMapping.toSyncSong(entry.song),
                    playedAt = entry.playedAt,
                    deviceId = cfg.deviceId,
                )
            },
            playbackStats = trackStats,
            playbackStatBuckets = buckets,
        )
    }

    private fun buildSongLookup(): Map<String, Song> = buildMap {
        library.songs.value.forEach { put(it.key, it) }
        history.entries.value.forEach { put(it.song.key, it.song) }
        playlists.playlists.value.forEach { playlist ->
            playlist.songs.forEach { put(it.key, it) }
        }
    }

    private fun dayStartAt(day: String): Long? = runCatching {
        LocalDate.parse(day).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }.getOrNull()

    private fun isEmptyData(data: SyncData): Boolean =
        data.playlists.isEmpty() && data.favoritePlaylists.isEmpty() &&
            data.recentPlays.isEmpty() && data.playbackStats.isEmpty() &&
            data.playbackStatBuckets.isEmpty()

    private fun prepareUpload(data: SyncData, cfg: SyncConfig): ByteArray {
        val payload = data.copy(
            deviceId = cfg.deviceId,
            deviceName = "NeriPlayer Desktop",
            lastModified = System.currentTimeMillis(),
        )
        return SyncDataSerializer.serialize(payload, cfg.useDataSaver)
    }

    private fun describeMerge(merged: SyncData): String =
        "同步完成：歌单 ${merged.playlists.count { !it.isDeleted }} 个、" +
            "收藏 ${merged.favoritePlaylists.sumOf { it.songs.size }} 首、" +
            "最近播放 ${merged.recentPlays.size} 条"

    // ------------------------------------------------------------------ 写回本地

    private fun applyToLocal(merged: SyncData) {
        val deletedIds = merged.playlists.filter { it.isDeleted }.map { it.id }.toSet()
        merged.playlists.filter { !it.isDeleted }.forEach { syncPlaylist ->
            val songs = syncPlaylist.songs.map(SyncMapping::fromSyncSong)
            // 本地已有的歌单用其自身同步 ID 匹配，避免同一歌单被导入成第二份
            val existingLocalId = playlists.userPlaylists()
                .firstOrNull { SyncMapping.playlistSyncId(it) == syncPlaylist.id }
                ?.id
            playlists.upsertFromSync(
                playlistId = existingLocalId ?: SyncMapping.syncPlaylistId(syncPlaylist.id),
                name = syncPlaylist.name.ifBlank { "同步歌单" },
                songs = songs,
                createdAt = syncPlaylist.createdAt,
                updatedAt = syncPlaylist.modifiedAt,
            )
        }
        deletedIds.forEach { id ->
            val localId = playlists.userPlaylists()
                .firstOrNull { SyncMapping.playlistSyncId(it) == id }
                ?.id
                ?: SyncMapping.syncPlaylistId(id)
            if (playlists.byId(localId) != null) playlists.deletePlaylist(localId)
        }

        val favoriteSongs = merged.favoritePlaylists
            .filter { !it.isDeleted }
            .flatMap { it.songs }
            .sortedBy { it.addedAt }
            .map(SyncMapping::fromSyncSong)
        if (favoriteSongs.isNotEmpty()) {
            val existing = playlists.favorites().songs.associateBy { it.key }
            val mergedFavorites = favoriteSongs.map { existing[it.key] ?: it }
            playlists.replaceFavorites(mergedFavorites)
        }

        val songKeyLookup = buildSongKeyLookup(merged)
        merged.recentPlays.take(300).forEach { play ->
            val song = SyncMapping.fromSyncSong(play.song)
            history.importEntry(song, play.playedAt, playCount = 1)
        }
        merged.recentPlayDeletions.forEach { deletion ->
            val key = songKeyLookup[SyncMapping.identityKey(deletion.songId, deletion.album, deletion.mediaUri)]
            if (key != null) {
                val entry = history.entries.value.firstOrNull { it.song.key == key }
                if (entry != null && deletion.deletedAt >= entry.playedAt) {
                    history.removeEntry(key)
                }
            }
        }

        merged.playbackStatBuckets.forEach { bucket ->
            val songKey = songKeyLookup[bucket.identityKey] ?: return@forEach
            val day = runCatching {
                java.time.Instant.ofEpochMilli(bucket.dayStartAt)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
                    .toString()
            }.getOrNull() ?: return@forEach
            stats.upsertFromSync(
                songKey = songKey,
                day = day,
                playCount = bucket.playCount,
                listenMs = bucket.totalListenMs,
            )
        }
    }

    /** 把同步身份映射回本地歌曲 key（本地库、历史、歌单里的歌曲都参与匹配）。 */
    private fun buildSongKeyLookup(merged: SyncData): Map<String, String> = buildMap {
        (library.songs.value + history.entries.value.map { it.song } +
            playlists.playlists.value.flatMap { it.songs }).forEach { song ->
            put(SyncMapping.identityKey(song), song.key)
        }
        (merged.playlists.flatMap { it.songs } +
            merged.favoritePlaylists.flatMap { it.songs } +
            merged.recentPlays.map { it.song }).forEach { syncSong ->
            putIfAbsent(
                SyncMapping.identityKey(syncSong.id, syncSong.album, syncSong.mediaUri),
                SyncMapping.fromSyncSong(syncSong).key,
            )
        }
    }

    /** 供测试与调试：把远端数据解码后的统计信息列出来。 */
    fun summarize(data: SyncData): String =
        "v${data.version} device=${data.deviceId} 歌单=${data.playlists.size} " +
            "收藏夹=${data.favoritePlaylists.size} 最近播放=${data.recentPlays.size} " +
            "统计=${data.playbackStats.size} 分桶=${data.playbackStatBuckets.size}"

    /** 供测试使用：直接合并两份数据。 */
    fun mergeForTest(local: SyncData, remote: SyncData): SyncData = SyncMerger.merge(local, remote)

    /** 供测试使用：读取本地快照。 */
    fun localSnapshotForTest(): SyncData = buildLocalData(ensureDeviceId())

    /** 供测试使用：统计条目数量，用于断言导入结果。 */
    fun importedStatCount(songKey: String): List<PlayStat> =
        stats.stats.value.filter { it.songKey == songKey }
}
