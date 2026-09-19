package moe.ouom.neriplayer.desktop.sync

import moe.ouom.neriplayer.desktop.core.MediaSource
import moe.ouom.neriplayer.desktop.core.Playlist
import moe.ouom.neriplayer.desktop.core.Song
import moe.ouom.neriplayer.desktop.core.biliSongKey
import moe.ouom.neriplayer.desktop.core.localSongKey
import moe.ouom.neriplayer.desktop.core.neteaseSongKey
import java.security.MessageDigest

/**
 * 本地数据与同步数据的互相映射。
 *
 * 身份规则与手机端 SongIdentity.stableKey() 一致：`id|album|mediaUri`，
 * 其中本地文件用 `file://` 形式表达 mediaUri，便于两端识别同一首歌。
 */
object SyncMapping {

    fun songSyncId(song: Song): Long = song.remoteId?.toLongOrNull() ?: 0L

    fun songMediaUri(song: Song): String? = song.filePath?.let { path ->
        if (path.startsWith("file://")) path else "file://$path"
    }

    fun identityKey(song: Song): String = identityKey(songSyncId(song), song.album, songMediaUri(song))

    fun identityKey(id: Long, album: String, mediaUri: String?): String = "$id|${album}|${mediaUri ?: ""}"

    /** 由本地字符串 ID 推导出稳定的正数同步 ID（同一账号下多次同步结果一致）。 */
    fun stableSyncId(localId: String): Long {
        localId.removePrefix("sync:").toLongOrNull()?.let { return it }
        val digest = MessageDigest.getInstance("SHA-256").digest(localId.toByteArray())
        var value = 0L
        for (index in 0 until 8) {
            value = (value shl 8) or (digest[index].toLong() and 0xFF)
        }
        return value and 0x7FFFFFFFFFFFFFFFL
    }

    fun playlistSyncId(playlist: Playlist): Long = stableSyncId(playlist.id)

    fun syncPlaylistId(syncId: Long): String = "sync:$syncId"

    /** 收藏歌单按平台拆分（与手机端一致，每个来源一个收藏夹）。 */
    fun favoriteSyncId(source: MediaSource): Long = stableSyncId("favorites:${source.name.lowercase()}")

    fun toSyncSong(song: Song): SyncSong = SyncSong(
        id = songSyncId(song),
        name = song.displayName(),
        artist = song.artist,
        album = song.album,
        albumId = song.albumId?.toLongOrNull() ?: 0L,
        durationMs = song.durationMs,
        coverUrl = song.artworkUrl,
        mediaUri = songMediaUri(song),
        addedAt = song.dateAdded,
        matchedLyric = song.lyrics,
        syncMetadataVersion = CURRENT_SYNC_METADATA_VERSION,
    )

    fun fromSyncSong(sync: SyncSong): Song {
        val mediaUri = sync.mediaUri
        val filePath = mediaUri
            ?.takeIf { it.startsWith("file://") }
            ?.removePrefix("file://")
        val biliHint = sync.channelId ?: sync.audioId ?: sync.subAudioId
        val source = when {
            filePath != null -> MediaSource.LOCAL
            biliHint != null -> MediaSource.BILIBILI
            sync.id != 0L -> MediaSource.NETEASE
            else -> MediaSource.LOCAL
        }
        val remoteId = when (source) {
            MediaSource.NETEASE -> sync.id.takeIf { it != 0L }?.toString()
            MediaSource.BILIBILI -> biliHint
            else -> null
        }
        val key = when {
            filePath != null -> localSongKey(filePath)
            source == MediaSource.NETEASE && remoteId != null -> neteaseSongKey(remoteId.toLong())
            source == MediaSource.BILIBILI && remoteId != null -> biliSongKey(remoteId)
            else -> "sync:${sync.id}|${sync.album}|${sync.name}"
        }
        return Song(
            key = key,
            source = source,
            title = sync.name,
            artist = sync.artist,
            album = sync.album,
            durationMs = sync.durationMs,
            filePath = filePath,
            remoteId = remoteId,
            artworkUrl = sync.coverUrl ?: sync.customCoverUrl,
            lyrics = sync.matchedLyric,
            dateAdded = sync.addedAt,
            albumId = sync.albumId.takeIf { it != 0L }?.toString(),
        )
    }

    fun toSyncPlaylist(playlist: Playlist): SyncPlaylist = SyncPlaylist(
        id = playlistSyncId(playlist),
        name = playlist.name,
        songs = playlist.songs.map(::toSyncSong),
        createdAt = playlist.createdAt,
        modifiedAt = playlist.updatedAt,
        isDeleted = false,
        songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION,
    )
}
