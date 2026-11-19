package moe.ouom.neriplayer.desktop.backend.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import moe.ouom.neriplayer.desktop.backend.CoreServer
import moe.ouom.neriplayer.desktop.backend.ProtocolException
import moe.ouom.neriplayer.desktop.backend.badRequest
import moe.ouom.neriplayer.desktop.backend.encodeJson
import moe.ouom.neriplayer.desktop.backend.intOrNull
import moe.ouom.neriplayer.desktop.backend.log
import moe.ouom.neriplayer.desktop.backend.stringOrNull
import moe.ouom.neriplayer.desktop.core.AppContainer
import moe.ouom.neriplayer.desktop.core.LrcParser
import moe.ouom.neriplayer.desktop.core.Lyrics
import moe.ouom.neriplayer.desktop.core.MediaSource
import moe.ouom.neriplayer.desktop.core.SearchKind
import moe.ouom.neriplayer.desktop.core.Song
import moe.ouom.neriplayer.desktop.core.YouTubeSearchResult
import moe.ouom.neriplayer.desktop.core.YouTubeStatus
import moe.ouom.neriplayer.desktop.net.YouTubeMusicClient
import moe.ouom.neriplayer.desktop.net.YouTubeMusicParser

/**
 * YouTube Music 音源命令（协议见 desktop/docs/protocol-youtube.md）。
 *
 * 设计：
 * - Cookie 登录（桌面没有 WebView，只能从浏览器复制 Cookie 粘贴）；
 * - 匿名尽量可用：搜索 / 首页 / 详情都能匿名浏览，播放与个性化需要登录；
 * - 失败要优雅降级：列表类命令返回空数组 + `message`，只有参数非法 / id 完全取不到才抛协议错误。
 */
object YouTubeMusicApi {

    fun register(server: CoreServer, container: AppContainer) {
        val client = container.online.youtube

        // ---------------------------------------------------------------- 登录态

        server.on("ytmusic.status") {
            val account = container.accounts.accountOf(MediaSource.YOUTUBE)
            val storedLogin = container.accounts.isLoggedIn(MediaSource.YOUTUBE)
            val hasCookies = client.hasLoginCookies()
            val session = withContext(Dispatchers.IO) { client.session() }
            val loggedIn = storedLogin && hasCookies
            val message = when {
                loggedIn -> null
                hasCookies -> "已保存 Cookie，但未能确认登录态，请重新粘贴或检查 Cookie 是否过期"
                else -> "未登录：可匿名搜索与浏览公开内容；播放直连与个性化首页需要粘贴浏览器 Cookie 登录"
            }
            buildJsonObject {
                put(
                    "status",
                    encodeJson(
                        YouTubeStatus(
                            loggedIn = loggedIn,
                            hasCookies = hasCookies,
                            anonymous = true,
                            visitorDataAvailable = !session?.visitorData.isNullOrBlank(),
                            // 未登录时播放一般会被 YouTube 要求登录，如实报告
                            playbackAvailable = loggedIn || hasCookies,
                            account = account,
                            message = message,
                        ),
                    ),
                )
            }
        }

        server.on("ytmusic.setCookies") { params ->
            val raw = params.stringOrNull("cookies")?.takeIf { it.isNotBlank() }
                ?: params.stringOrNull("cookie")?.takeIf { it.isNotBlank() }
                ?: badRequest("缺少 cookies")
            val parsed = client.importCookieText(raw)
            if (parsed.isEmpty()) {
                badRequest(
                    "没有解析到 Cookie。请从浏览器开发者工具复制完整的 Cookie 请求头，" +
                        "例如 SAPISID=…; SID=…; HSID=…",
                )
            }
            val verified = withContext(Dispatchers.IO) { verifyLogin(client) }
            if (!verified) {
                client.logout()
                throw ProtocolException(
                    "bad_request",
                    "Cookie 中未找到有效的 YouTube 登录凭据（需要 SAPISID / SID 等）；" +
                        "请确认复制的是已登录 youtube.com / music.youtube.com 的 Cookie",
                )
            }
            val profile = withContext(Dispatchers.IO) { client.accountProfile() } ?: client.fallbackAccount()
            container.accounts.save(MediaSource.YOUTUBE, profile, YouTubeMusicClient.JAR_KEY)
            log("ytmusic: login saved for ${profile.nickname}")
            buildJsonObject {
                put("ok", true)
                put("account", encodeJson(profile))
                put("loggedIn", true)
            }
        }

        server.on("ytmusic.logout") {
            client.logout()
            withContext(Dispatchers.IO) {
                container.accounts.logout(MediaSource.YOUTUBE, YouTubeMusicClient.JAR_KEY)
            }
            buildJsonObject { put("ok", true) }
        }

        // ---------------------------------------------------------------- 首页 / 搜索

        server.on("ytmusic.home") { params ->
            val limit = (params.intOrNull("limit") ?: 8).coerceIn(1, 12)
            val loggedIn = container.accounts.isLoggedIn(MediaSource.YOUTUBE) && client.hasLoginCookies()
            val sections = withContext(Dispatchers.IO) {
                val root = client.browse("FEmusic_home") ?: return@withContext null
                YouTubeMusicParser.parseHome(root).take(limit)
            }
            buildJsonObject {
                put("sections", encodeJson(sections.orEmpty()))
                put("loggedIn", loggedIn)
                if (sections == null) {
                    put("message", "YouTube 首页加载失败：网络异常或接口不可用")
                } else if (sections.isEmpty()) {
                    put("message", "YouTube 暂无推荐内容（未登录时可能为空）")
                }
            }
        }

        server.on("ytmusic.search") { params ->
            val query = params.stringOrNull("query")?.trim()?.takeIf { it.isNotEmpty() }
                ?: badRequest("缺少 query")
            val kind = params.stringOrNull("kind")?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
            if (kind != null && runCatching { SearchKind.valueOf(kind) }.isFailure) {
                badRequest("非法 kind: $kind（可选 SONG / ALBUM / ARTIST / PLAYLIST）")
            }
            val result = withContext(Dispatchers.IO) { search(client, query, kind) }
            buildJsonObject {
                put("query", query)
                put("songs", encodeJson(result.songs))
                put("albums", encodeJson(result.albums))
                put("artists", encodeJson(result.artists))
                put("playlists", encodeJson(result.playlists))
            }
        }

        server.on("ytmusic.hotSearches") {
            // YouTube Music 没有公开热词接口；返回空让前端回退到搜索历史
            buildJsonObject { put("keywords", buildJsonArray {}) }
        }

        // ---------------------------------------------------------------- 详情

        server.on("ytmusic.playlist") { params ->
            val id = requireId(params)
            val detail = withContext(Dispatchers.IO) {
                val browseId = normalizePlaylistBrowseId(id)
                val root = client.browse(browseId) ?: return@withContext null
                YouTubeMusicParser.parsePlaylist(root, browseId, fallbackTitle = "")
            } ?: run {
                log("ytmusic.playlist not found: $id")
                throw ProtocolException("not_found", "YouTube 歌单不存在或接口不可用: $id")
            }
            if (detail.songs.isEmpty() && detail.title.isBlank()) {
                throw ProtocolException("not_found", "YouTube 歌单为空或 id 非法: $id")
            }
            buildJsonObject {
                put("title", detail.title)
                put("coverUrl", detail.coverUrl)
                put("description", detail.description)
                put("creator", detail.creator)
                put("trackCount", detail.trackCount)
                put("songs", encodeJson(detail.songs))
                detail.collection?.let { put("collection", encodeJson(it)) }
            }
        }

        server.on("ytmusic.album") { params ->
            val id = requireId(params)
            val detail = withContext(Dispatchers.IO) {
                val albumBrowseId = if (id.startsWith("MPRE")) id else "MPRE$id"
                var root = client.browse(albumBrowseId)
                var detail = root?.let { YouTubeMusicParser.parseAlbum(it, albumBrowseId, fallbackTitle = "") }
                // id 可能被用户填成了音频播放列表（OLAK5uy / VL…），回退按歌单解析
                if (detail == null || detail.songs.isEmpty()) {
                    val playlistBrowseId = normalizePlaylistBrowseId(id)
                    root = client.browse(playlistBrowseId) ?: root
                    detail = root?.let {
                        YouTubeMusicParser.parsePlaylist(it, playlistBrowseId, fallbackTitle = "")
                    } ?: detail
                }
                detail
            } ?: run {
                log("ytmusic.album not found: $id")
                throw ProtocolException("not_found", "YouTube 专辑不存在或接口不可用: $id")
            }
            if (detail.songs.isEmpty() && detail.title.isBlank()) {
                throw ProtocolException("not_found", "YouTube 专辑为空或 id 非法: $id")
            }
            buildJsonObject {
                put("title", detail.title)
                put("coverUrl", detail.coverUrl)
                put("description", detail.description)
                put("artist", detail.creator)
                put("creator", detail.creator)
                put("trackCount", detail.trackCount)
                put("songs", encodeJson(detail.songs))
                detail.collection?.let { put("collection", encodeJson(it)) }
            }
        }

        server.on("ytmusic.artist") { params ->
            val id = requireId(params)
            val detail = withContext(Dispatchers.IO) {
                val root = client.browse(id) ?: return@withContext null
                YouTubeMusicParser.parseArtist(root, id, fallbackName = "")
            } ?: run {
                log("ytmusic.artist not found: $id")
                throw ProtocolException("not_found", "YouTube 歌手不存在或接口不可用: $id")
            }
            if (detail.name.isBlank() && detail.songs.isEmpty() && detail.albums.isEmpty()) {
                throw ProtocolException("not_found", "YouTube 歌手为空或 id 非法: $id")
            }
            buildJsonObject {
                put("name", detail.name)
                put("avatarUrl", detail.avatarUrl)
                put("description", detail.description)
                put("songCount", detail.songCount)
                put("songs", encodeJson(detail.songs))
                put("albums", encodeJson(detail.albums))
                detail.artist?.let { put("artist", encodeJson(it)) }
            }
        }

        server.on("ytmusic.lyrics") { params ->
            val id = requireId(params)
            val lyrics = withContext(Dispatchers.IO) { loadLyrics(client, id) }
            buildJsonObject { put("lyrics", encodeJson(lyrics)) }
        }

        server.on("ytmusic.myPlaylists") {
            val loggedIn = container.accounts.isLoggedIn(MediaSource.YOUTUBE) && client.hasLoginCookies()
            if (!loggedIn) {
                buildJsonObject {
                    put("collections", buildJsonArray {})
                    put("needLogin", true)
                }
            } else {
                val collections = withContext(Dispatchers.IO) {
                    val root = client.browse("FEmusic_liked_playlists") ?: return@withContext null
                    YouTubeMusicParser.parseLibraryPlaylists(root)
                }
                buildJsonObject {
                    put("collections", encodeJson(collections.orEmpty()))
                    put("needLogin", false)
                    if (collections == null) put("message", "YouTube 歌单库加载失败，请检查网络后重试")
                }
            }
        }
    }

    // ------------------------------------------------------------------ 搜索

    private suspend fun search(
        client: YouTubeMusicClient,
        query: String,
        kind: String?,
    ): YouTubeSearchResult = coroutineScope {
        // 过滤参数是 YouTube 的内部常量，偶尔会变；过滤搜索为空时回退一次“全部”搜索再按类型筛。
        suspend fun songs(): List<Song> {
            val filtered = client.search(query, YouTubeMusicClient.SEARCH_FILTER_SONGS)
                ?.let { YouTubeMusicParser.parseSearchSongs(it) }.orEmpty()
            if (filtered.isNotEmpty()) return filtered
            return client.search(query, null)?.let { YouTubeMusicParser.parseSearchSongs(it) }.orEmpty()
        }

        suspend fun albums() = client.search(query, YouTubeMusicClient.SEARCH_FILTER_ALBUMS)
            ?.let { YouTubeMusicParser.parseSearchAlbums(it) }.orEmpty().ifEmpty {
                client.search(query, null)?.let { YouTubeMusicParser.parseSearchAlbums(it) }.orEmpty()
            }

        suspend fun artists() = client.search(query, YouTubeMusicClient.SEARCH_FILTER_ARTISTS)
            ?.let { YouTubeMusicParser.parseSearchArtists(it) }.orEmpty().ifEmpty {
                client.search(query, null)?.let { YouTubeMusicParser.parseSearchArtists(it) }.orEmpty()
            }

        suspend fun playlists() = client.search(query, YouTubeMusicClient.SEARCH_FILTER_PLAYLISTS)
            ?.let { YouTubeMusicParser.parseSearchPlaylists(it) }.orEmpty().ifEmpty {
                client.search(query, null)?.let { YouTubeMusicParser.parseSearchPlaylists(it) }.orEmpty()
            }

        when (kind) {
            "SONG" -> YouTubeSearchResult(songs = songs())
            "ALBUM" -> YouTubeSearchResult(albums = albums())
            "ARTIST" -> YouTubeSearchResult(artists = artists())
            "PLAYLIST" -> YouTubeSearchResult(playlists = playlists())
            // 未指定 kind：四类并行拉取（每类一次请求，避免前端串行等待）
            else -> {
                val songsDeferred = async { songs() }
                val albumsDeferred = async { albums() }
                val artistsDeferred = async { artists() }
                val playlistsDeferred = async { playlists() }
                YouTubeSearchResult(
                    songs = songsDeferred.await(),
                    albums = albumsDeferred.await(),
                    artists = artistsDeferred.await(),
                    playlists = playlistsDeferred.await(),
                )
            }
        }
    }

    // ------------------------------------------------------------------ 歌词 / 登录校验

    private fun loadLyrics(client: YouTubeMusicClient, id: String): Lyrics {
        val text = if (id.startsWith("MPLYt")) {
            client.browse(id)?.let { YouTubeMusicParser.parseLyrics(it) }
        } else {
            val next = client.next(id)
            val lyricsBrowseId = next?.let { YouTubeMusicParser.parseLyricsBrowseId(it) }
            lyricsBrowseId?.let { browseId ->
                client.browse(browseId)?.let { YouTubeMusicParser.parseLyrics(it) }
            }
        }
        if (text.isNullOrBlank()) {
            return Lyrics(raw = "", lines = emptyList(), source = "YouTube Music")
        }
        return Lyrics(
            raw = text,
            lines = LrcParser.parse(text),
            translated = emptyList(),
            source = "YouTube Music",
        )
    }

    /**
     * 校验粘贴的 Cookie 是否真的登录：先看首页 LOGGED_IN，再试 account_menu。
     * 两者都拿不到时只要 Cookie 里含 SAPISID/SID 也放行（网络抖动时不阻塞用户）。
     */
    private fun verifyLogin(client: YouTubeMusicClient): Boolean {
        if (!client.hasLoginCookies()) return false
        client.invalidateSession()
        val session = client.session(forceRefresh = true)
        if (session?.loggedIn == true) return true
        if (client.accountProfile() != null) return true
        return false
    }

    private fun requireId(params: JsonObject): String =
        params.stringOrNull("id")?.trim()?.takeIf { it.isNotEmpty() } ?: badRequest("缺少 id")

    /** 歌单 browseId 归一化：`VL…` 直接用，`PL…` / `OLAK5uy_…` 补 `VL` 前缀。 */
    private fun normalizePlaylistBrowseId(id: String): String = when {
        id.startsWith("VL") -> id
        id.startsWith("MPRE") -> id
        else -> "VL$id"
    }
}
