package moe.ouom.neriplayer.desktop.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.desktop.core.AudioInput
import moe.ouom.neriplayer.desktop.core.MediaSource
import moe.ouom.neriplayer.desktop.core.OnlineCollection
import moe.ouom.neriplayer.desktop.core.SearchKind
import moe.ouom.neriplayer.desktop.core.SearchPayload
import moe.ouom.neriplayer.desktop.core.Song

data class NeteaseHomeData(
    val recommendedSongs: List<Song> = emptyList(),
    val topSongs: List<Song> = emptyList(),
    val newSongs: List<Song> = emptyList(),
    val radarPlaylists: List<OnlineCollection> = emptyList(),
    val hotPlaylists: List<OnlineCollection> = emptyList(),
    val error: String? = null,
)

/** 在线音源聚合：搜索 / 播放地址 / 歌词 / 首页推荐。 */
class OnlineRepository(private val http: HttpService = HttpService()) {

    /** 暴露给账号仓库复用同一份 Cookie 与连接设置。 */
    val httpService: HttpService get() = http

    val netease = NeteaseApi(http)
    val bilibili = BiliApi(http)

    suspend fun search(
        source: MediaSource,
        kind: SearchKind,
        query: String,
        page: Int = 1,
    ): SearchPayload = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext SearchPayload()
        when (source) {
            MediaSource.NETEASE -> when (kind) {
                SearchKind.SONG -> SearchPayload(songs = netease.searchSongs(query, offset = (page - 1) * 30))
                SearchKind.PLAYLIST -> SearchPayload(collections = netease.searchPlaylists(query))
                SearchKind.ARTIST -> SearchPayload(artists = netease.searchArtists(query))
                SearchKind.ALBUM -> SearchPayload(collections = netease.searchAlbums(query))
            }

            MediaSource.BILIBILI -> SearchPayload(songs = bilibili.searchVideos(query, page))
            MediaSource.YOUTUBE, MediaSource.LOCAL -> SearchPayload()
        }
    }

    suspend fun resolvePlayback(song: Song, quality: String): AudioInput? = withContext(Dispatchers.IO) {
        when (song.source) {
            MediaSource.LOCAL -> song.filePath?.let { AudioInput(path = it) }
            MediaSource.NETEASE -> {
                val id = song.remoteId ?: return@withContext null
                val resolved = netease.songUrl(id, quality) ?: return@withContext null
                AudioInput(
                    url = resolved.url,
                    headers = mapOf(
                        "Referer" to "https://music.163.com/",
                        "User-Agent" to DESKTOP_USER_AGENT,
                    ),
                )
            }

            MediaSource.BILIBILI -> {
                val bvid = song.remoteId ?: return@withContext null
                val cid = song.extraId?.takeIf { it.isNotBlank() }
                    ?: bilibili.videoPages(bvid).firstOrNull()?.first
                    ?: return@withContext null
                val url = bilibili.audioUrl(bvid, cid) ?: return@withContext null
                AudioInput(url = url, headers = bilibili.biliHeaders())
            }

            MediaSource.YOUTUBE -> null
        }
    }

    suspend fun lyrics(song: Song): Pair<String, String?>? = withContext(Dispatchers.IO) {
        when (song.source) {
            MediaSource.NETEASE -> song.remoteId?.let { netease.lyric(it) }
            MediaSource.BILIBILI -> null
            MediaSource.LOCAL -> localLyricFallback(song)
            MediaSource.YOUTUBE -> null
        }
    }

    /** 本地歌曲没有歌词文件时，用「标题 + 歌手」在线补全。 */
    private fun localLyricFallback(song: Song): Pair<String, String?>? {
        val title = song.title.ifBlank { return null }
        val query = if (song.artist.isBlank()) title else "${song.title} ${song.artist}"
        val candidate = netease.searchSongs(query, limit = 5).firstOrNull { match ->
            match.title.contains(title, ignoreCase = true) || title.contains(match.title, ignoreCase = true)
        } ?: return null
        val id = candidate.remoteId ?: return null
        return netease.lyric(id)
    }

    suspend fun neteaseHome(): NeteaseHomeData = withContext(Dispatchers.IO) {
        runCatching {
            val recommended = netease.searchSongs("热门歌曲", limit = 18)
            val topListId = netease.toplists().firstOrNull { it.name.contains("热歌") }?.id
                ?: netease.toplists().firstOrNull()?.id
            val topSongs = topListId?.let { netease.playlistDetail(it)?.second }?.take(18).orEmpty()
            val newSongs = netease.recommendedNewSongs(18)
            val radar = netease.recommendedPlaylists(12)
            val hot = netease.toplists().take(12)
            NeteaseHomeData(
                recommendedSongs = recommended,
                topSongs = topSongs,
                newSongs = newSongs,
                radarPlaylists = radar,
                hotPlaylists = hot,
            )
        }.getOrElse { error ->
            NeteaseHomeData(error = error.message ?: error.javaClass.simpleName)
        }
    }

    suspend fun playlistSongs(collection: OnlineCollection): List<Song> = withContext(Dispatchers.IO) {
        when (collection.source) {
            MediaSource.NETEASE -> netease.playlistDetail(collection.id)?.second.orEmpty()
            else -> emptyList()
        }
    }

    suspend fun albumSongs(collection: OnlineCollection): List<Song> = withContext(Dispatchers.IO) {
        when (collection.source) {
            MediaSource.NETEASE -> netease.albumDetail(collection.id)?.second.orEmpty()
            else -> emptyList()
        }
    }

    suspend fun artistSongs(artistId: String): List<Song> = withContext(Dispatchers.IO) {
        netease.artistTopSongs(artistId)
    }
}
