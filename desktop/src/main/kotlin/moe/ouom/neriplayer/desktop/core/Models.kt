package moe.ouom.neriplayer.desktop.core

import kotlinx.serialization.Serializable

/** 音频来源平台。 */
@Serializable
enum class MediaSource {
    LOCAL,
    NETEASE,
    BILIBILI,
    YOUTUBE;

    val displayName: String
        get() = when (this) {
            LOCAL -> "本地"
            NETEASE -> "网易云"
            BILIBILI -> "哔哩哔哩"
            YOUTUBE -> "YouTube"
        }
}

@Serializable
enum class RepeatMode { OFF, ALL, ONE }

@Serializable
enum class PlaybackState { IDLE, PREPARING, PLAYING, PAUSED, ERROR }

/** 播放器中的一首歌（本地或在线）。 */
@Serializable
data class Song(
    val key: String,
    val source: MediaSource = MediaSource.LOCAL,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0L,
    val filePath: String? = null,
    val remoteId: String? = null,
    val artworkPath: String? = null,
    val artworkUrl: String? = null,
    val lyrics: String? = null,
    val dateAdded: Long = 0L,
    val trackNumber: Int = 0,
    val artistId: String? = null,
    val albumId: String? = null,
    val extraId: String? = null,
) {
    fun displayName(): String =
        title.ifBlank { filePath?.substringAfterLast('/')?.substringBeforeLast('.') ?: "未知歌曲" }

    fun artistText(): String = artist.ifBlank { "未知艺术家" }

    fun albumText(): String = album.ifBlank { "未知专辑" }

    fun isLocalFile(): Boolean = source == MediaSource.LOCAL && !filePath.isNullOrBlank()
}

fun localSongKey(path: String): String = "local:$path"

fun neteaseSongKey(id: Long): String = "netease:$id"

fun biliSongKey(bvid: String): String = "bili:$bvid"

/** 用户歌单 / 我喜欢的音乐。 */
@Serializable
data class Playlist(
    val id: String,
    val name: String,
    val songs: List<Song> = emptyList(),
    val system: Boolean = false,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

/** 播放历史条目。 */
@Serializable
data class UsageEntry(
    val song: Song,
    val playedAt: Long,
    val playCount: Int = 1,
)

/** 播放统计（按天累计）。 */
@Serializable
data class PlayStat(
    val songKey: String,
    val day: String,
    val playCount: Int = 0,
    val listenMs: Long = 0L,
)

data class ArtistGroup(
    val name: String,
    val songs: List<Song>,
)

data class AlbumGroup(
    val name: String,
    val artist: String,
    val songs: List<Song>,
)

data class LyricLine(
    val timeMs: Long,
    val text: String,
    val translation: String? = null,
)

data class Lyrics(
    val raw: String = "",
    val lines: List<LyricLine> = emptyList(),
    val translated: List<LyricLine> = emptyList(),
    val source: String? = null,
) {
    val isEmpty: Boolean get() = lines.isEmpty() && raw.isBlank()
}

/** 在线歌单 / 专辑 摘要。 */
data class OnlineCollection(
    val id: String,
    val name: String,
    val creator: String = "",
    val coverUrl: String? = null,
    val trackCount: Int = 0,
    val playCount: Long = 0L,
    val source: MediaSource = MediaSource.NETEASE,
    val description: String = "",
)

data class OnlineArtist(
    val id: String,
    val name: String,
    val avatarUrl: String? = null,
    val songCount: Int = 0,
    val source: MediaSource = MediaSource.NETEASE,
)

data class OnlineTrack(
    val song: Song,
    val albumName: String = "",
    val coverUrl: String? = null,
)

enum class SearchKind { SONG, PLAYLIST, ARTIST, ALBUM }

data class SearchPayload(
    val songs: List<Song> = emptyList(),
    val collections: List<OnlineCollection> = emptyList(),
    val artists: List<OnlineArtist> = emptyList(),
)
