package moe.ouom.neriplayer.desktop.sync

/**
 * 同步数据合并策略（与手机端语义一致）：
 * - 歌单：按 modifiedAt 取新，歌曲取并集并尊重删除墓碑
 * - 收藏：按来源合并，取新
 * - 最近播放：按歌曲身份去重，保留最新播放时间
 * - 播放统计：按 identityKey（+ 日期分桶）取 max，避免两端相加造成重复计数
 */
object SyncMerger {

    fun merge(local: SyncData, remote: SyncData): SyncData {
        val deletedSongs = local.playlistSongDeletions + remote.playlistSongDeletions
        val recentDeleted = local.recentPlayDeletions + remote.recentPlayDeletions
        return remote.copy(
            playlists = mergePlaylists(local.playlists, remote.playlists, deletedSongs),
            favoritePlaylists = mergeFavorites(local.favoritePlaylists, remote.favoritePlaylists, deletedSongs),
            recentPlays = mergeRecentPlays(local.recentPlays, remote.recentPlays, recentDeleted),
            playbackStats = mergeTrackStats(local.playbackStats, remote.playbackStats),
            playbackStatBuckets = mergeStatBuckets(local.playbackStatBuckets, remote.playbackStatBuckets),
            recentPlayDeletions = mergeRecentDeletions(local.recentPlayDeletions, remote.recentPlayDeletions),
            playlistSongDeletions = mergeSongDeletions(local.playlistSongDeletions, remote.playlistSongDeletions),
            syncLog = (remote.syncLog + local.syncLog).takeLast(200),
            biliVideoSkipRules = mergeSkipRules(local.biliVideoSkipRules, remote.biliVideoSkipRules),
            playlistUsageStats = mergeUsageStats(local.playlistUsageStats, remote.playlistUsageStats),
        )
    }

    /** 合并结果与远端是否等价（用于判断是否需要上传）。 */
    fun equivalent(a: SyncData, b: SyncData): Boolean =
        a.playlists == b.playlists &&
            a.favoritePlaylists == b.favoritePlaylists &&
            a.recentPlays == b.recentPlays &&
            a.playbackStats == b.playbackStats &&
            a.playbackStatBuckets == b.playbackStatBuckets &&
            a.playlistSongDeletions == b.playlistSongDeletions &&
            a.recentPlayDeletions == b.recentPlayDeletions &&
            a.biliVideoSkipRules == b.biliVideoSkipRules

    private fun mergePlaylists(
        local: List<SyncPlaylist>,
        remote: List<SyncPlaylist>,
        deletions: List<SyncPlaylistSongDeletion>,
    ): List<SyncPlaylist> {
        val byId = linkedMapOf<Long, SyncPlaylist>()
        val localMap = local.associateBy { it.id }
        val remoteMap = remote.associateBy { it.id }
        (localMap.keys + remoteMap.keys).forEach { id ->
            val localItem = localMap[id]
            val remoteItem = remoteMap[id]
            byId[id] = when {
                localItem == null -> remoteItem!!
                remoteItem == null -> localItem
                else -> {
                    val newer = if (localItem.modifiedAt >= remoteItem.modifiedAt) localItem else remoteItem
                    val older = if (newer === localItem) remoteItem else localItem
                    val tombstoned = deletions.filter { it.playlistId == id }
                    newer.copy(songs = mergeSongLists(newer.songs, older.songs, tombstoned))
                }
            }
        }
        return byId.values.sortedWith(compareByDescending { it.modifiedAt })
    }

    private fun mergeFavorites(
        local: List<SyncFavoritePlaylist>,
        remote: List<SyncFavoritePlaylist>,
        deletions: List<SyncPlaylistSongDeletion>,
    ): List<SyncFavoritePlaylist> {
        val byKey = linkedMapOf<String, SyncFavoritePlaylist>()
        (local + remote).forEach { item ->
            val key = if (item.source.isNotBlank()) item.source.lowercase() else item.id.toString()
            val existing = byKey[key]
            byKey[key] = when {
                existing == null -> item
                item.modifiedAt > existing.modifiedAt ->
                    item.copy(songs = mergeSongLists(item.songs, existing.songs, deletions))

                else -> existing.copy(songs = mergeSongLists(existing.songs, item.songs, deletions))
            }
        }
        return byKey.values.filter { !it.isDeleted }.map { it.copy(trackCount = it.songs.size) }
    }

    /**
     * 歌曲并集：以 newer 的顺序为主，补上 older 中的歌曲；
     * 最后一律套用删除墓碑——删除时间不早于歌曲加入时间即视为已删除（重新加入的歌曲会被保留）。
     */
    private fun mergeSongLists(
        newer: List<SyncSong>,
        older: List<SyncSong>,
        deletions: List<SyncPlaylistSongDeletion>,
    ): List<SyncSong> {
        val result = newer.toMutableList()
        val existingKeys = newer.mapTo(HashSet()) { songIdentityKey(it) }
        older.forEach { candidate ->
            val key = songIdentityKey(candidate)
            if (key in existingKeys) return@forEach
            existingKeys += key
            result += candidate
        }
        if (deletions.isEmpty()) return result
        val deletedAtByIdentity = deletions.groupBy { identityKeyOf(it.songId, it.album, it.mediaUri) }
            .mapValues { (_, list) -> list.maxOf { it.deletedAt } }
        return result.filter { song ->
            val deletedAt = deletedAtByIdentity[songIdentityKey(song)] ?: 0L
            !(deletedAt > 0L && deletedAt >= song.addedAt)
        }
    }

    private fun mergeRecentPlays(
        local: List<SyncRecentPlay>,
        remote: List<SyncRecentPlay>,
        deletions: List<SyncRecentPlayDeletion>,
    ): List<SyncRecentPlay> {
        val byIdentity = linkedMapOf<String, SyncRecentPlay>()
        (local + remote).forEach { play ->
            val key = songIdentityKey(play.song)
            val deletedAt = deletions
                .filter { identityKeyOf(it.songId, it.album, it.mediaUri) == key }
                .maxOfOrNull { it.deletedAt } ?: 0L
            if (deletedAt > 0L && deletedAt >= play.playedAt) return@forEach
            val existing = byIdentity[key]
            if (existing == null || play.playedAt > existing.playedAt) {
                byIdentity[key] = play
            }
        }
        return byIdentity.values.sortedByDescending { it.playedAt }.take(300)
    }

    private fun mergeTrackStats(local: List<SyncTrackStat>, remote: List<SyncTrackStat>): List<SyncTrackStat> {
        val byKey = linkedMapOf<String, SyncTrackStat>()
        (local + remote).forEach { stat ->
            val existing = byKey[stat.identityKey]
            byKey[stat.identityKey] = if (existing == null) {
                stat
            } else {
                existing.copy(
                    totalListenMs = maxOf(existing.totalListenMs, stat.totalListenMs),
                    playCount = maxOf(existing.playCount, stat.playCount),
                    lastPlayedAt = maxOf(existing.lastPlayedAt, stat.lastPlayedAt),
                    firstPlayedAt = minOfNonZero(existing.firstPlayedAt, stat.firstPlayedAt),
                    coverUrl = existing.coverUrl ?: stat.coverUrl,
                    durationMs = maxOf(existing.durationMs, stat.durationMs),
                )
            }
        }
        return byKey.values.toList()
    }

    private fun mergeStatBuckets(
        local: List<SyncPlaybackStatBucket>,
        remote: List<SyncPlaybackStatBucket>,
    ): List<SyncPlaybackStatBucket> {
        val byKey = linkedMapOf<String, SyncPlaybackStatBucket>()
        (local + remote).forEach { bucket ->
            val key = "${bucket.identityKey}|${bucket.dayStartAt}"
            val existing = byKey[key]
            byKey[key] = if (existing == null) {
                bucket
            } else {
                existing.copy(
                    totalListenMs = maxOf(existing.totalListenMs, bucket.totalListenMs),
                    playCount = maxOf(existing.playCount, bucket.playCount),
                    lastPlayedAt = maxOf(existing.lastPlayedAt, bucket.lastPlayedAt),
                    firstPlayedAt = minOfNonZero(existing.firstPlayedAt, bucket.firstPlayedAt),
                )
            }
        }
        return byKey.values.sortedWith(compareBy({ it.identityKey }, { it.dayStartAt }))
    }

    private fun mergeRecentDeletions(
        local: List<SyncRecentPlayDeletion>,
        remote: List<SyncRecentPlayDeletion>,
    ): List<SyncRecentPlayDeletion> = (local + remote)
        .groupBy { identityKeyOf(it.songId, it.album, it.mediaUri) }
        .map { (_, list) -> list.maxBy { it.deletedAt } }
        .sortedByDescending { it.deletedAt }
        .take(400)

    private fun mergeSongDeletions(
        local: List<SyncPlaylistSongDeletion>,
        remote: List<SyncPlaylistSongDeletion>,
    ): List<SyncPlaylistSongDeletion> = (local + remote)
        .groupBy { "${it.playlistId}|${identityKeyOf(it.songId, it.album, it.mediaUri)}" }
        .map { (_, list) -> list.maxBy { it.deletedAt } }
        .sortedByDescending { it.deletedAt }
        .take(800)

    private fun mergeUsageStats(
        local: List<SyncPlaylistUsageStat>,
        remote: List<SyncPlaylistUsageStat>,
    ): List<SyncPlaylistUsageStat> = (local + remote)
        .groupBy { it.playlistKey }
        .map { (_, list) ->
            val newest = list.maxBy { it.lastOpenedAt }
            newest.copy(
                openCount = list.maxOf { it.openCount },
                lastOpenedAt = list.maxOf { it.lastOpenedAt },
                firstOpenedAt = minOfNonZero(list.map { it.firstOpenedAt }),
            )
        }
        .sortedByDescending { it.lastOpenedAt }
        .take(200)

    private fun mergeSkipRules(
        local: List<SyncBiliVideoSkipRule>,
        remote: List<SyncBiliVideoSkipRule>,
    ): List<SyncBiliVideoSkipRule> = (local + remote)
        .groupBy { "${it.bvid}|${it.cid}" }
        .map { (_, list) -> list.maxBy { it.modifiedAt } }
        .sortedWith(compareBy({ it.bvid }, { it.cid }))
        .take(500)

    private fun songIdentityKey(song: SyncSong): String = identityKeyOf(song.id, song.album, song.mediaUri)

    private fun identityKeyOf(id: Long, album: String, mediaUri: String?): String =
        SyncMapping.identityKey(id, album, mediaUri)

    private fun minOfNonZero(first: Long, second: Long): Long =
        listOf(first, second).filter { it > 0L }.minOrNull() ?: 0L

    private fun minOfNonZero(values: Collection<Long>): Long =
        values.filter { it > 0L }.minOrNull() ?: 0L
}
