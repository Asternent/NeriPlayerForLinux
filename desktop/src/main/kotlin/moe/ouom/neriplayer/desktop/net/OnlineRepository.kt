package moe.ouom.neriplayer.desktop.net

import kotlinx.serialization.Serializable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.desktop.core.AudioInput
import moe.ouom.neriplayer.desktop.core.Lyrics
import moe.ouom.neriplayer.desktop.core.MediaSource
import moe.ouom.neriplayer.desktop.core.OnlineCollection
import moe.ouom.neriplayer.desktop.core.SearchKind
import moe.ouom.neriplayer.desktop.core.SearchPayload
import moe.ouom.neriplayer.desktop.core.Song
import moe.ouom.neriplayer.desktop.core.buildLrcLyrics
import moe.ouom.neriplayer.desktop.core.buildYrcLyrics

@Serializable
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

    /** 收藏夹封面接口不返回封面，加载过内容后把首条封面缓存下来供列表复用。 */
    private val _collectionCovers = kotlinx.coroutines.flow.MutableStateFlow<Map<String, String>>(emptyMap())
    val collectionCovers: kotlinx.coroutines.flow.StateFlow<Map<String, String>> = _collectionCovers

    fun coverFor(collection: OnlineCollection): String? =
        collection.coverUrl ?: _collectionCovers.value[collection.id]

    val netease = NeteaseApi(http)
    val bilibili = BiliApi(http)

    /** YouTube Music（Cookie 存在共享的 [HttpService] `youtube` 罐里）。 */
    val youtube = YouTubeMusicClient(http)

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

            MediaSource.YOUTUBE -> when (kind) {
                SearchKind.SONG -> SearchPayload(
                    songs = youtube.search(query, YouTubeMusicClient.SEARCH_FILTER_SONGS)
                        ?.let { YouTubeMusicParser.parseSearchSongs(it) }.orEmpty(),
                )

                SearchKind.PLAYLIST -> SearchPayload(
                    collections = youtube.search(query, YouTubeMusicClient.SEARCH_FILTER_PLAYLISTS)
                        ?.let { YouTubeMusicParser.parseSearchPlaylists(it) }.orEmpty(),
                )

                SearchKind.ARTIST -> SearchPayload(
                    artists = youtube.search(query, YouTubeMusicClient.SEARCH_FILTER_ARTISTS)
                        ?.let { YouTubeMusicParser.parseSearchArtists(it) }.orEmpty(),
                )

                SearchKind.ALBUM -> SearchPayload(
                    collections = youtube.search(query, YouTubeMusicClient.SEARCH_FILTER_ALBUMS)
                        ?.let { YouTubeMusicParser.parseSearchAlbums(it) }.orEmpty(),
                )
            }

            MediaSource.LOCAL -> SearchPayload()
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

            MediaSource.YOUTUBE -> {
                val videoId = song.remoteId?.takeIf { it.isNotBlank() } ?: return@withContext null
                val audio = youtube.resolveAudio(videoId, quality) ?: return@withContext null
                AudioInput(
                    url = audio.url,
                    headers = mapOf(
                        "User-Agent" to audio.userAgent.ifBlank { DESKTOP_USER_AGENT },
                        "Referer" to YouTubeMusicClient.MUSIC_ORIGIN + "/",
                        "Origin" to YouTubeMusicClient.MUSIC_ORIGIN,
                    ),
                )
            }
        }
    }

    suspend fun lyrics(song: Song): Pair<String, String?>? = withContext(Dispatchers.IO) {
        when (song.source) {
            MediaSource.NETEASE -> song.remoteId?.let { netease.lyric(it) }
            MediaSource.BILIBILI -> null
            MediaSource.LOCAL -> localLyricFallback(song)
            MediaSource.YOUTUBE -> youtubeLyrics(song.remoteId.orEmpty())
        }
    }

    /**
     * 结构化歌词：网易云优先用 eapi 的逐词 yrc，取不到再回退普通 LRC。
     * 与 [lyrics] 的区别是这里直接产出可序列化的 [Lyrics]（带 words / endMs）。
     */
    suspend fun lyricsStructured(song: Song): Lyrics? = withContext(Dispatchers.IO) {
        when (song.source) {
            MediaSource.NETEASE -> neteaseStructuredLyrics(song.remoteId)
            MediaSource.BILIBILI -> null
            MediaSource.LOCAL -> localLyricFallbackStructured(song)
            MediaSource.YOUTUBE -> youtubeLyrics(song.remoteId.orEmpty())
                ?.let { (raw, translated) -> buildLrcLyrics(raw, translated, song.source.displayName) }
        }
    }

    /** 网易云结构化歌词：yrc → 普通 LRC 的完整回退，任何失败都不影响普通歌词。 */
    private fun neteaseStructuredLyrics(remoteId: String?): Lyrics? {
        val id = remoteId?.takeIf { it.isNotBlank() } ?: return null
        val source = MediaSource.NETEASE.displayName
        val payload = netease.lyricPayload(id)
        val yrc = payload?.yrc
        if (!yrc.isNullOrBlank()) {
            buildYrcLyrics(
                yrcRaw = yrc,
                lrcRaw = payload.lrc,
                translatedYrc = payload.yrcTranslated,
                translatedRaw = payload.translated,
                source = source,
            )?.let { return it }
        }
        val plain = netease.lyric(id) ?: return null
        return buildLrcLyrics(plain.first, plain.second, source)
    }

    /** 本地歌曲没有歌词文件时在线补全；有 yrc 的同样走逐词解析。 */
    private fun localLyricFallbackStructured(song: Song): Lyrics? {
        val candidate = neteaseLyricCandidate(song) ?: return null
        if (!candidate.remoteId.isNullOrBlank()) {
            neteaseStructuredLyrics(candidate.remoteId)?.let { return it }
        }
        return localLyricFallback(song)
            ?.let { (raw, translated) -> buildLrcLyrics(raw, translated, song.source.displayName) }
    }

    /** 「标题 + 歌手」搜索一首最匹配的网易云歌曲（本地歌词在线补全用）。 */
    private fun neteaseLyricCandidate(song: Song): Song? {
        val title = song.title.ifBlank { return null }
        val query = if (song.artist.isBlank()) title else "${song.title} ${song.artist}"
        return netease.searchSongs(query, limit = 5).firstOrNull { match ->
            match.title.contains(title, ignoreCase = true) || title.contains(match.title, ignoreCase = true)
        }
    }

    /** YouTube 歌词：next 接口拿到歌词页 browseId 再 browse（返回纯文本，无 LRC 时间轴）。 */
    private fun youtubeLyrics(videoId: String): Pair<String, String?>? {
        if (videoId.isBlank()) return null
        val next = youtube.next(videoId) ?: return null
        val browseId = YouTubeMusicParser.parseLyricsBrowseId(next) ?: return null
        val text = youtube.browse(browseId)?.let { YouTubeMusicParser.parseLyrics(it) } ?: return null
        return text.takeIf { it.isNotBlank() }?.let { it to "YouTube Music" }
    }

    /** 本地歌曲没有歌词文件时，用「标题 + 歌手」在线补全。 */
    private fun localLyricFallback(song: Song): Pair<String, String?>? {
        val candidate = neteaseLyricCandidate(song) ?: return null
        val id = candidate.remoteId ?: return null
        return netease.lyric(id)
    }

    suspend fun neteaseHome(): NeteaseHomeData = withContext(Dispatchers.IO) {
        runCatching {
            val recommended = netease.searchSongs("热门歌曲", limit = 18)
            val topListId = netease.toplists().firstOrNull { it.name.contains("热歌") }?.id
                ?: netease.toplists().firstOrNull()?.id
            // 首页只展示前几首：限制抓取数量，避免为榜单歌单拉取全部曲目
            val topSongs = topListId?.let { netease.playlistDetail(it, limit = 30)?.second }?.take(18).orEmpty()
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
            MediaSource.BILIBILI -> bilibili.favoriteFolderSongs(collection.id)
            MediaSource.YOUTUBE -> youtubeCollectionSongs(collection.id)
            else -> emptyList()
        }
    }

    /**
     * 按来源加载歌单 / 收藏夹内容；onPage 用于边加载边刷新界面。
     * 网易云可能是歌单也可能是专辑，因此歌单为空时回退到专辑接口。
     */
    suspend fun songsForCollection(
        collection: OnlineCollection,
        onPage: (List<Song>) -> Unit = {},
    ): List<Song> = withContext(Dispatchers.IO) {
        when (collection.source) {
            MediaSource.NETEASE -> {
                val detail = netease.playlistDetail(collection.id)
                if (!detail?.second.isNullOrEmpty()) {
                    detail!!.second
                } else {
                    netease.albumDetail(collection.id)?.second.orEmpty()
                }
            }

            MediaSource.BILIBILI -> bilibili.favoriteFolderSongs(collection.id, onPage = onPage)
            MediaSource.YOUTUBE -> youtubeCollectionSongs(collection.id)
            else -> emptyList()
        }
    }.also { loaded ->
        if (collection.coverUrl.isNullOrBlank()) {
            loaded.firstOrNull()?.artworkUrl?.let { cover ->
                _collectionCovers.value = _collectionCovers.value + (collection.id to cover)
            }
        }
    }

    suspend fun albumSongs(collection: OnlineCollection): List<Song> = withContext(Dispatchers.IO) {
        when (collection.source) {
            MediaSource.NETEASE -> netease.albumDetail(collection.id)?.second.orEmpty()
            MediaSource.YOUTUBE -> youtubeAlbumSongs(collection.id)
            else -> emptyList()
        }
    }

    suspend fun artistSongs(artistId: String): List<Song> = withContext(Dispatchers.IO) {
        netease.artistTopSongs(artistId)
    }

    /** 指定音源的歌手热门歌曲（目前只有 YouTube 需要区分来源）。 */
    suspend fun artistSongs(source: MediaSource, artistId: String): List<Song> = withContext(Dispatchers.IO) {
        when (source) {
            MediaSource.YOUTUBE -> youtubeArtist(artistId)?.songs.orEmpty()
            else -> artistSongs(artistId)
        }
    }

    /** YouTube 歌手详情（供在线音源聚合命令复用）。 */
    suspend fun youtubeArtist(artistId: String) = withContext(Dispatchers.IO) {
        val root = youtube.browse(artistId) ?: return@withContext null
        YouTubeMusicParser.parseArtist(root, artistId, fallbackName = "")
    }

    /** YouTube 歌单 / 专辑内容：browseId 前缀区分（MPRE=专辑，VL=歌单）。 */
    private fun youtubeCollectionSongs(id: String): List<Song> {
        if (id.isBlank()) return emptyList()
        return when {
            id.startsWith("MPRE") -> youtubeAlbumSongs(id)
            else -> {
                val browseId = if (id.startsWith("VL")) id else "VL$id"
                val root = youtube.browse(browseId) ?: return emptyList()
                YouTubeMusicParser.parsePlaylist(root, browseId, fallbackTitle = "").songs
            }
        }
    }

    private fun youtubeAlbumSongs(id: String): List<Song> {
        val browseId = if (id.startsWith("MPRE")) id else "MPRE$id"
        val root = youtube.browse(browseId) ?: return emptyList()
        val detail = YouTubeMusicParser.parseAlbum(root, browseId, fallbackTitle = "")
        if (detail.songs.isNotEmpty()) return detail.songs
        // 有些专辑 id 实际是音频播放列表，回退按歌单取
        val playlistBrowseId = if (id.startsWith("VL")) id else "VL$id"
        val fallbackRoot = youtube.browse(playlistBrowseId) ?: return emptyList()
        return YouTubeMusicParser.parsePlaylist(fallbackRoot, playlistBrowseId, fallbackTitle = "").songs
    }
}
