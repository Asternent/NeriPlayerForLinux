package moe.ouom.neriplayer.desktop.backend.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import moe.ouom.neriplayer.desktop.backend.CoreServer
import moe.ouom.neriplayer.desktop.backend.ProtocolException
import moe.ouom.neriplayer.desktop.backend.WireJson
import moe.ouom.neriplayer.desktop.backend.badRequest
import moe.ouom.neriplayer.desktop.backend.encodeAny
import moe.ouom.neriplayer.desktop.backend.encodeJson
import moe.ouom.neriplayer.desktop.backend.intOrNull
import moe.ouom.neriplayer.desktop.backend.log
import moe.ouom.neriplayer.desktop.backend.objectOrNull
import moe.ouom.neriplayer.desktop.backend.stringOrNull
import moe.ouom.neriplayer.desktop.core.AppContainer
import moe.ouom.neriplayer.desktop.core.FfmpegSupport
import moe.ouom.neriplayer.desktop.core.MediaSource
import moe.ouom.neriplayer.desktop.core.OnlineArtist
import moe.ouom.neriplayer.desktop.core.OnlineCollection
import moe.ouom.neriplayer.desktop.core.SearchKind
import moe.ouom.neriplayer.desktop.core.SearchPayload
import moe.ouom.neriplayer.desktop.core.Song
import moe.ouom.neriplayer.desktop.net.DESKTOP_USER_AGENT
import moe.ouom.neriplayer.desktop.net.NeriJsonParser
import moe.ouom.neriplayer.desktop.net.HomeSections
import moe.ouom.neriplayer.desktop.net.array
import moe.ouom.neriplayer.desktop.net.asObject
import moe.ouom.neriplayer.desktop.net.int
import moe.ouom.neriplayer.desktop.net.long
import moe.ouom.neriplayer.desktop.net.obj
import moe.ouom.neriplayer.desktop.net.objects
import moe.ouom.neriplayer.desktop.net.str
import moe.ouom.neriplayer.desktop.net.urlEncode

/**
 * 在线音源（探索 / 搜索 / 详情 / 歌词）。
 *
 * 网络请求尽量复用 [moe.ouom.neriplayer.desktop.net.OnlineRepository] 与
 * [moe.ouom.neriplayer.desktop.net.NeteaseApi]/[moe.ouom.neriplayer.desktop.net.BiliApi] 的现成方法，
 * 只有在旧核心没有对应方法（搜索建议 / 风格标签 / 标签歌单）时才直接用 [moe.ouom.neriplayer.desktop.net.HttpService]
 * 调同名公开接口。命令清单见 desktop/docs/protocol-online.md。
 */
object OnlineApi {

    private const val NETEASE_BASE = "https://music.163.com"

    /** 与 NeteaseApi 内部一致：公开接口需要一个像客户端的 Referer 与伪客户端 Cookie。 */
    private val neteaseHeaders = mapOf(
        "Referer" to "https://music.163.com/",
        "Cookie" to "appver=2.0.2; os=pc",
    )

    private val biliHeaders = mapOf(
        "Referer" to "https://www.bilibili.com/",
        "User-Agent" to DESKTOP_USER_AGENT,
    )

    fun register(server: CoreServer, container: AppContainer) {
        val online = container.online

        // ---------------------------------------------------------------- 首页聚合
        server.on("online.home") { params ->
            // 首页聚合请求量大，HomeSections 内部有短 TTL 缓存；下拉刷新用 refresh=true 强制重取。
            val force = (params["refresh"] as? kotlinx.serialization.json.JsonPrimitive)
                ?.content?.toBooleanStrictOrNull() ?: false
            val payload = withContext(Dispatchers.IO) {
                HomeSections.load(container, force = force)
            }
            encodeJson(payload)
        }

        // ---------------------------------------------------------------- 搜索
        server.on("online.search") { params ->
            val source = params.requireSource()
            val keyword = params.stringOrNull("keyword").orEmpty().trim()
            val page = (params.intOrNull("page") ?: 1).coerceAtLeast(1)
            val kind = params.searchKindOrNull()
            withContext(Dispatchers.IO) {
                if (keyword.isEmpty()) return@withContext searchPayloadJson(SearchPayload())
                if (source == MediaSource.BILIBILI) {
                    // B 站没有歌单/歌手/专辑搜索，统一按视频搜索
                    val payload = online.search(MediaSource.BILIBILI, SearchKind.SONG, keyword, page)
                    searchPayloadJson(SearchPayload(songs = payload.songs))
                } else if (kind != null) {
                    searchPayloadJson(online.search(source, kind, keyword, page))
                } else {
                    // 未指定 kind 时一次返回三类结果（与旧 UI 的“全部”语义一致）
                    val songs = online.search(source, SearchKind.SONG, keyword, page).songs
                    val collections = online.search(source, SearchKind.PLAYLIST, keyword, page).collections
                    val artists = online.search(source, SearchKind.ARTIST, keyword, page).artists
                    searchPayloadJson(SearchPayload(songs, collections, artists))
                }
            }
        }

        server.on("online.hotSearches") { params ->
            val source = params.requireSource()
            val keywords = withContext(Dispatchers.IO) {
                when (source) {
                    MediaSource.NETEASE -> neteaseHotKeywords(container)
                    MediaSource.BILIBILI -> biliHotKeywords(container)
                    else -> emptyList()
                }
            }
            buildJsonObject { put("keywords", encodeAny(keywords)) }
        }

        server.on("online.searchSuggest") { params ->
            val source = params.requireSource()
            val keyword = params.stringOrNull("keyword").orEmpty().trim()
            val keywords = if (keyword.isEmpty()) {
                emptyList()
            } else withContext(Dispatchers.IO) {
                when (source) {
                    MediaSource.NETEASE -> neteaseSuggest(container, keyword)
                    MediaSource.BILIBILI -> biliSuggest(container, keyword)
                    else -> emptyList()
                }
            }
            buildJsonObject { put("keywords", encodeAny(keywords)) }
        }

        // ---------------------------------------------------------------- 标签
        server.on("online.tags") { params ->
            val source = params.sourceOrDefault()
            val tags = if (source == MediaSource.NETEASE) {
                withContext(Dispatchers.IO) { neteaseTags(container) }
            } else {
                emptyList()
            }
            buildJsonObject {
                put("tags", buildJsonArray {
                    tags.forEach { tag ->
                        add(buildJsonObject {
                            put("name", tag.first)
                            tag.second?.let { put("category", it) }
                        })
                    }
                })
            }
        }

        server.on("online.tagPlaylists") { params ->
            val tag = params.stringOrNull("tag")?.trim()?.takeIf { it.isNotEmpty() }
                ?: badRequest("缺少 tag")
            val page = (params.intOrNull("page") ?: 1).coerceAtLeast(1)
            val source = params.sourceOrDefault()
            if (source != MediaSource.NETEASE) {
                throw ProtocolException("unsupported", "只有网易云支持按标签浏览歌单")
            }
            val collections = withContext(Dispatchers.IO) {
                val remote = neteaseTagPlaylists(container, tag, page)
                if (remote.isNotEmpty()) remote
                else online.netease.searchPlaylists(tag, limit = 30, offset = (page - 1) * 30)
            }
            buildJsonObject { put("collections", encodeJson(collections)) }
        }

        // ---------------------------------------------------------------- 详情
        server.on("online.playlistDetail") { params ->
            val source = params.requireSource()
            val id = params.requireId()
            withContext(Dispatchers.IO) {
                val detail = when (source) {
                    MediaSource.NETEASE -> neteaseCollectionDetail(container, id)
                    MediaSource.BILIBILI -> biliFavoriteDetail(container, id)
                    else -> null
                } ?: run {
                    log("online.playlistDetail not found: ${source.name}/$id")
                    throw ProtocolException("not_found", "歌单不存在或接口不可用: ${source.name}/$id")
                }
                buildJsonObject {
                    put("collection", encodeJson(detail.first))
                    put("songs", encodeJson(detail.second))
                }
            }
        }

        server.on("online.albumDetail") { params ->
            val source = params.requireSource()
            val id = params.requireId()
            if (source != MediaSource.NETEASE) {
                throw ProtocolException("unsupported", "只有网易云支持专辑详情")
            }
            withContext(Dispatchers.IO) {
                val detail = online.netease.albumDetail(id)
                    ?: run {
                        log("online.albumDetail not found: $id")
                        throw ProtocolException("not_found", "专辑不存在或接口不可用: $id")
                    }
                buildJsonObject {
                    put("collection", encodeJson(enrich(detail.first, detail.second)))
                    put("songs", encodeJson(detail.second))
                }
            }
        }

        server.on("online.artistDetail") { params ->
            val source = params.requireSource()
            val id = params.requireId()
            if (source != MediaSource.NETEASE) {
                throw ProtocolException("unsupported", "只有网易云支持歌手详情")
            }
            withContext(Dispatchers.IO) {
                val songs = online.artistSongs(id)
                val (remoteArtist, albums) = neteaseArtistOverview(container, id)
                val artist = remoteArtist ?: OnlineArtist(
                    id = id,
                    name = songs.firstOrNull()?.artist.orEmpty(),
                    songCount = songs.size,
                    source = MediaSource.NETEASE,
                )
                buildJsonObject {
                    put("artist", encodeJson(artist))
                    put("songs", encodeJson(songs))
                    put("albums", encodeJson(albums))
                }
            }
        }

        server.on("online.collectionDetail") { params ->
            val source = params.requireSource()
            val id = params.requireId()
            val kind = params.stringOrNull("kind")?.trim()?.lowercase() ?: "playlist"
            withContext(Dispatchers.IO) {
                val detail = when (kind) {
                    "playlist" -> when (source) {
                        MediaSource.NETEASE -> neteaseCollectionDetail(container, id)
                        MediaSource.BILIBILI -> biliFavoriteDetail(container, id)
                        else -> null
                    }

                    "album" -> {
                        if (source != MediaSource.NETEASE) {
                            throw ProtocolException("unsupported", "只有网易云支持专辑详情")
                        }
                        online.netease.albumDetail(id)
                    }

                    "favorites" -> when (source) {
                        MediaSource.NETEASE -> neteaseCollectionDetail(container, id)
                        MediaSource.BILIBILI -> biliFavoriteDetail(container, id)
                        else -> null
                    }

                    else -> throw ProtocolException(
                        "bad_request",
                        "非法 kind: $kind（可选 playlist/album/favorites）",
                    )
                } ?: run {
                    log("online.collectionDetail not found: ${source.name}/$id (kind=$kind)")
                    throw ProtocolException("not_found", "内容不存在或接口不可用: ${source.name}/$id")
                }
                collectionDetailJson(detail.first, detail.second)
            }
        }

        // ---------------------------------------------------------------- 登录态歌单
        server.on("online.myPlaylists") { params ->
            val source = params.requireSource()
            val account = container.accounts.accountOf(source)
            val userId = account?.userId.orEmpty().trim()
            if (!container.accounts.isLoggedIn(source) || userId.isEmpty()) {
                buildJsonObject {
                    put("collections", encodeJson(emptyList<OnlineCollection>()))
                    put("needLogin", true)
                }
            } else {
                val collections = withContext(Dispatchers.IO) {
                    when (source) {
                        MediaSource.NETEASE -> online.netease.userPlaylists(userId)
                        MediaSource.BILIBILI -> online.bilibili.favoriteFolders(userId)
                        else -> throw ProtocolException("unsupported", "该音源不支持「我的歌单」")
                    }
                }
                buildJsonObject {
                    put("collections", encodeJson(collections))
                    put("needLogin", false)
                }
            }
        }

        server.on("online.biliFavorites") { params ->
            val url = params.stringOrNull("url")?.trim().orEmpty()
            val account = container.accounts.accountOf(MediaSource.BILIBILI)
            var userId = account?.userId.orEmpty().trim()
            val folderId = if (url.isNotEmpty()) parseBiliFavFolderId(url) else null
            if (userId.isEmpty() && url.isNotEmpty()) {
                userId = Regex("space\\.bilibili\\.com/(\\d+)").find(url)?.groupValues?.get(1).orEmpty()
            }
            if (userId.isEmpty() && folderId == null) {
                buildJsonObject {
                    put("collections", encodeJson(emptyList<OnlineCollection>()))
                    put("needLogin", true)
                }
            } else {
                val collections = withContext(Dispatchers.IO) {
                    val folders = if (userId.isEmpty()) {
                        emptyList()
                    } else {
                        online.bilibili.favoriteFolders(userId)
                    }
                    if (folderId == null) {
                        folders
                    } else {
                        val matched = folders.firstOrNull { it.id == folderId }
                        listOf(
                            matched ?: OnlineCollection(
                                id = folderId,
                                name = "B站收藏夹",
                                creator = "哔哩哔哩收藏夹",
                                source = MediaSource.BILIBILI,
                            )
                        )
                    }
                }
                buildJsonObject {
                    put("collections", encodeJson(collections))
                    put("needLogin", false)
                }
            }
        }

        // ---------------------------------------------------------------- 歌词 / 播放地址 / 音源
        server.on("online.lyrics") { params ->
            val song = params.requireSong()
            val refresh = (params["refresh"] as? kotlinx.serialization.json.JsonPrimitive)
                ?.content?.toBooleanStrictOrNull() ?: false
            if (refresh) container.lyrics.invalidate(song)
            val lyrics = withContext(Dispatchers.IO) { container.lyrics.load(song) }
            buildJsonObject { put("lyrics", encodeJson(lyrics)) }
        }

        server.on("online.playbackUrl") { params ->
            val song = params.requireSong()
            val quality = container.settings.current.qualityPreference
            val input = withContext(Dispatchers.IO) { online.resolvePlayback(song, quality) }
            buildJsonObject {
                put("song", encodeJson(song))
                if (input == null) {
                    put("playable", false)
                    put("error", "无法解析播放地址（音源可能被限制或需要登录）")
                } else {
                    put("playable", true)
                    put("path", input.path)
                    put("url", input.url)
                    put("headers", encodeAny(input.headers))
                }
            }
        }

        server.on("online.sources") {
            val settings = container.settings.current
            buildJsonObject {
                put("sources", buildJsonArray {
                    add(sourceEntry(MediaSource.NETEASE, settings.neteaseEnabled))
                    add(sourceEntry(MediaSource.BILIBILI, settings.bilibiliEnabled))
                    add(sourceEntry(MediaSource.YOUTUBE, settings.youtubeEnabled))
                })
                put("quality", settings.qualityPreference)
                put("ffmpeg", FfmpegSupport.available)
            }
        }
    }

    private fun searchPayloadJson(payload: SearchPayload): JsonObject = buildJsonObject {
        put("songs", encodeJson(payload.songs))
        put("collections", encodeJson(payload.collections))
        put("artists", encodeJson(payload.artists))
    }

    private fun sourceEntry(source: MediaSource, enabled: Boolean): JsonObject = buildJsonObject {
        put("id", source.name)
        put("name", source.displayName)
        put("enabled", enabled)
    }

    private fun collectionDetailJson(
        collection: OnlineCollection,
        songs: List<Song>,
    ): JsonObject {
        val cover = collection.coverUrl ?: songs.firstOrNull()?.artworkUrl
        return buildJsonObject {
            put("title", collection.name.ifBlank { songs.firstOrNull()?.album.orEmpty() })
            put("coverUrl", cover)
            put("description", collection.description)
            put("creator", collection.creator)
            put("songs", encodeJson(songs))
            put("collection", encodeJson(collection.copy(coverUrl = cover)))
        }
    }

    // ------------------------------------------------------------------ 参数解析

    private fun JsonObject.requireSource(): MediaSource {
        val raw = stringOrNull("source")?.trim()?.takeIf { it.isNotEmpty() }
            ?: badRequest("缺少 source")
        val source = runCatching { MediaSource.valueOf(raw.uppercase()) }.getOrElse {
            badRequest("非法 source: $raw（可选 NETEASE / BILIBILI）")
        }
        return when (source) {
            MediaSource.NETEASE, MediaSource.BILIBILI -> source
            MediaSource.LOCAL -> badRequest("source 不能是 LOCAL（本地媒体库用 library.* 命令）")
            MediaSource.YOUTUBE -> throw ProtocolException("unsupported", "YouTube 音源尚未实现")
        }
    }

    /** 只有部分命令的 source 可省略（默认网易云）。 */
    private fun JsonObject.sourceOrDefault(): MediaSource =
        stringOrNull("source")?.trim()?.takeIf { it.isNotEmpty() }?.let {
            val parsed = runCatching { MediaSource.valueOf(it.uppercase()) }.getOrElse {
                badRequest("非法 source: $it")
            }
            when (parsed) {
                MediaSource.NETEASE, MediaSource.BILIBILI -> parsed
                else -> throw ProtocolException("unsupported", "该音源不支持此命令")
            }
        } ?: MediaSource.NETEASE

    private fun JsonObject.searchKindOrNull(): SearchKind? {
        val raw = stringOrNull("kind")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { SearchKind.valueOf(raw.uppercase()) }.getOrElse {
            badRequest("非法 kind: $raw（可选 SONG / PLAYLIST / ARTIST / ALBUM）")
        }
    }

    private fun JsonObject.requireId(): String =
        stringOrNull("id")?.trim()?.takeIf { it.isNotEmpty() } ?: badRequest("缺少 id")

    private fun JsonObject.requireSong(): Song {
        val element = objectOrNull("song") ?: badRequest("缺少 song")
        return runCatching { WireJson.decodeFromJsonElement(Song.serializer(), element) }
            .getOrElse { badRequest("song 格式非法: ${it.message}") }
    }

    // ------------------------------------------------------------------ 网易云补充接口

    private fun neteaseGet(container: AppContainer, path: String): JsonObject? {
        val text = container.online.httpService.get("$NETEASE_BASE$path", neteaseHeaders) ?: return null
        return NeriJsonParser.parse(text).asObject()
    }

    /** 歌单详情；网易云的专辑 id 与歌单 id 会撞车，歌单取不到时回退专辑（与原 songsForCollection 一致）。 */
    private fun neteaseCollectionDetail(
        container: AppContainer,
        id: String,
    ): Pair<OnlineCollection, List<Song>>? {
        val playlist = container.online.netease.playlistDetail(id)
        if (playlist != null && playlist.second.isNotEmpty()) {
            return enrich(playlist.first, playlist.second) to playlist.second
        }
        val album = container.online.netease.albumDetail(id) ?: return playlist
        return enrich(album.first, album.second) to album.second
    }

    /** 收藏夹类歌单没有封面时，用首条歌曲封面兜底（复用 repository 的封面缓存）。 */
    private fun enrich(collection: OnlineCollection, songs: List<Song>): OnlineCollection {
        val cover = collection.coverUrl ?: songs.firstOrNull()?.artworkUrl
        val trackCount = if (collection.trackCount > 0) collection.trackCount else songs.size
        return collection.copy(coverUrl = cover, trackCount = trackCount)
    }

    /**
     * 热门搜索：网易云现在要求 /api/search/hot 带 type 参数，旧核心方法（无参数）会返回 400，
     * 所以这里直接调带参数的接口，失败再退回旧方法。
     */
    private fun neteaseHotKeywords(container: AppContainer): List<String> {
        val root = neteaseGet(container, "/api/search/hot?type=1111")
        val keywords = root?.obj("result")?.array("hots")?.objects().orEmpty().mapNotNull { item ->
            item.str("first")?.takeIf { it.isNotBlank() }
                ?: item.obj("first")?.str("keyword")?.takeIf { it.isNotBlank() }
                ?: item.str("keyword")?.takeIf { it.isNotBlank() }
        }
        return (keywords.ifEmpty { container.online.netease.hotSearchKeywords() }).distinct()
    }

    private fun neteaseSuggest(container: AppContainer, keyword: String): List<String> {
        val root = neteaseGet(
            container,
            "/api/search/suggest/web?csrf_token=&s=${urlEncode(keyword)}&limit=10",
        ) ?: return emptyList()
        val result = root.obj("result") ?: return emptyList()
        val keywords = ArrayList<String>()
        result.array("songs")?.objects().orEmpty().take(4).forEach { item ->
            val name = item.str("name")?.takeIf { it.isNotBlank() } ?: return@forEach
            val artist = item.array("artists")?.objects()?.firstOrNull()?.str("name").orEmpty()
            keywords += if (artist.isBlank()) name else "$name $artist"
        }
        result.array("artists")?.objects().orEmpty().take(3).forEach { item ->
            item.str("name")?.takeIf { it.isNotBlank() }?.let { keywords += it }
        }
        result.array("albums")?.objects().orEmpty().take(2).forEach { item ->
            item.str("name")?.takeIf { it.isNotBlank() }?.let { keywords += it }
        }
        result.array("playlists")?.objects().orEmpty().take(1).forEach { item ->
            item.str("name")?.takeIf { it.isNotBlank() }?.let { keywords += it }
        }
        return keywords.distinct().take(10)
    }

    /** 风格标签：网易云歌单分类接口（真实数据，失败时返回空数组）。 */
    private fun neteaseTags(container: AppContainer): List<Pair<String, Int?>> {
        val root = neteaseGet(container, "/api/playlist/catalogue") ?: return emptyList()
        return root.array("sub")?.objects().orEmpty().mapNotNull { item ->
            item.str("name")?.takeIf { it.isNotBlank() }?.let { name -> name to item.int("category") }
        }.distinctBy { it.first }
    }

    /** 标签歌单：旧核心没有这个方法，直接调网易云歌单列表接口（非 WBI，无需签名）。 */
    private fun neteaseTagPlaylists(
        container: AppContainer,
        tag: String,
        page: Int,
        limit: Int = 30,
    ): List<OnlineCollection> {
        val offset = (page - 1) * limit
        val root = neteaseGet(
            container,
            "/api/playlist/list?cat=${urlEncode(tag)}&order=hot&offset=$offset&limit=$limit&total=true",
        ) ?: return emptyList()
        return root.array("playlists")?.objects().orEmpty().mapNotNull { item ->
            val id = item.long("id") ?: return@mapNotNull null
            OnlineCollection(
                id = id.toString(),
                name = item.str("name").orEmpty(),
                creator = item.obj("creator")?.str("nickname").orEmpty(),
                coverUrl = normalizeImageUrl(item.str("coverImgUrl")),
                trackCount = item.int("trackCount") ?: 0,
                playCount = item.long("playCount") ?: 0L,
                source = MediaSource.NETEASE,
                description = item.str("description").orEmpty(),
            )
        }
    }

    /** 歌手信息 + 热门专辑：/api/artist/albums 同时返回 artist 与 hotAlbums，省一次请求。 */
    private fun neteaseArtistOverview(
        container: AppContainer,
        id: String,
    ): Pair<OnlineArtist?, List<OnlineCollection>> {
        val root = neteaseGet(container, "/api/artist/albums/$id?limit=20") ?: return null to emptyList()
        val artistObject = root.obj("artist")
        val artist = artistObject?.let {
            OnlineArtist(
                id = it.long("id")?.toString() ?: id,
                name = it.str("name").orEmpty(),
                avatarUrl = normalizeImageUrl(it.str("picUrl")),
                songCount = it.int("musicSize") ?: it.int("albumSize") ?: 0,
                source = MediaSource.NETEASE,
            )
        }
        val albums = root.array("hotAlbums")?.objects().orEmpty().mapNotNull { item ->
            val albumId = item.long("id") ?: return@mapNotNull null
            OnlineCollection(
                id = albumId.toString(),
                name = item.str("name").orEmpty(),
                creator = artist?.name.orEmpty(),
                coverUrl = normalizeImageUrl(item.str("picUrl")),
                trackCount = item.int("size") ?: 0,
                source = MediaSource.NETEASE,
            )
        }
        return artist to albums
    }

    // ------------------------------------------------------------------ B 站补充接口

    private fun biliSuggest(container: AppContainer, keyword: String): List<String> {
        val text = container.online.httpService.get(
            "https://s.search.bilibili.com/main/suggest?term=${urlEncode(keyword)}&main_ver=v1",
            biliHeaders,
        ) ?: return emptyList()
        val root = NeriJsonParser.parse(text).asObject() ?: return emptyList()
        return root.obj("result")?.array("tag")?.objects().orEmpty().mapNotNull { item ->
            (item.str("value") ?: item.str("term") ?: item.str("name"))
                ?.replace(Regex("<[^>]+>"), "")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }.distinct().take(10)
    }

    private fun biliHotKeywords(container: AppContainer): List<String> {
        val text = container.online.httpService.get(
            "https://api.bilibili.com/x/web-interface/search/square?limit=20",
            biliHeaders,
        ) ?: return emptyList()
        val root = NeriJsonParser.parse(text).asObject() ?: return emptyList()
        if (root.int("code") != 0) return emptyList()
        return root.obj("data")?.obj("trending")?.array("list")?.objects().orEmpty().mapNotNull { item ->
            item.str("keyword")?.takeIf { it.isNotBlank() }
        }.distinct()
    }

    private fun biliFolders(container: AppContainer): List<OnlineCollection> {
        val account = container.accounts.accountOf(MediaSource.BILIBILI) ?: return emptyList()
        val mid = account.userId.trim().takeIf { it.isNotEmpty() } ?: return emptyList()
        return container.online.bilibili.favoriteFolders(mid)
    }

    private fun biliFavoriteDetail(
        container: AppContainer,
        id: String,
    ): Pair<OnlineCollection, List<Song>>? {
        val folder = biliFolders(container).firstOrNull { it.id == id }
        val songs = container.online.bilibili.favoriteFolderSongs(id)
        // 既不在收藏夹列表里、又读不到任何内容，多半是未登录或 id 非法
        if (folder == null && songs.isEmpty()) return null
        val collection = enrich(
            folder ?: OnlineCollection(id = id, name = "B站收藏夹", source = MediaSource.BILIBILI),
            songs,
        )
        return collection to songs
    }

    /** 从 B 站收藏夹页面 URL 里取 media_id：支持 fid=、/ml<id>、/favlist?fid= 等形态。 */
    private fun parseBiliFavFolderId(url: String): String? {
        Regex("[?&]fid=(\\d+)").find(url)?.groupValues?.get(1)?.let { return it }
        Regex("/ml(\\d+)").find(url)?.groupValues?.get(1)?.let { return it }
        Regex("/favlist/(\\d+)").find(url)?.groupValues?.get(1)?.let { return it }
        return null
    }

    /** 与 NeteaseApi 内部一致：把 // 与 http 图片地址统一转 https，避免 QML 加载被拦。 */
    private fun normalizeImageUrl(url: String?): String? {
        val value = url?.trim().orEmpty()
        if (value.isEmpty()) return null
        return when {
            value.startsWith("//") -> "https:$value"
            value.startsWith("http://") -> "https://" + value.removePrefix("http://")
            else -> value
        }
    }
}
