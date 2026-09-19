package moe.ouom.neriplayer.desktop.net

import moe.ouom.neriplayer.desktop.core.MediaSource
import moe.ouom.neriplayer.desktop.core.OnlineArtist
import moe.ouom.neriplayer.desktop.core.OnlineCollection
import moe.ouom.neriplayer.desktop.core.Song
import moe.ouom.neriplayer.desktop.core.biliSongKey
import moe.ouom.neriplayer.desktop.core.neteaseSongKey
import java.security.MessageDigest

private const val NETEASE_BASE = "https://music.163.com"

private fun md5Hex(text: String): String =
    MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

private fun cleanBiliText(raw: String?): String =
    raw.orEmpty().replace(Regex("<[^>]+>"), "").replace("&quot;", "\"").replace("&amp;", "&").trim()

private fun biliDurationMs(duration: String?): Long {
    if (duration.isNullOrBlank()) return 0L
    val parts = duration.trim().split(':')
    if (parts.size == 1) {
        val seconds = parts[0].toLongOrNull() ?: return 0L
        return seconds * 1000L
    }
    var totalSeconds = 0L
    parts.forEach { part -> totalSeconds = totalSeconds * 60 + (part.toLongOrNull() ?: 0L) }
    return totalSeconds * 1000L
}

private fun normalizeImageUrl(url: String?): String? {
    val value = url?.trim().orEmpty()
    if (value.isEmpty()) return null
    return when {
        value.startsWith("//") -> "https:$value"
        value.startsWith("http://") -> "https://" + value.removePrefix("http://")
        else -> value
    }
}

/** 网易云音乐公开接口客户端（无需登录即可搜索、取歌词与试听地址）。 */
class NeteaseApi(private val http: HttpService) {

    private val baseHeaders = mapOf(
        "Referer" to "https://music.163.com/",
        "Cookie" to "appver=2.0.2; os=pc",
    )

    private fun getJson(path: String): JsonObjectSelf? {
        val text = http.get("$NETEASE_BASE$path", baseHeaders) ?: return null
        return NeriJsonParser.parse(text).asObject()?.let { JsonObjectSelf(it) }
    }

    private fun songFromJson(obj: JsonObjectSelf): Song? {
        val id = obj.long("id") ?: return null
        val artists = obj.array("artists")?.objects()?.mapNotNull { it.str("name") }.orEmpty()
            .ifEmpty { obj.array("ar")?.objects()?.mapNotNull { it.str("name") }.orEmpty() }
        val albumObj = obj.obj("album") ?: obj.obj("al")
        val albumName = albumObj?.str("name").orEmpty()
        val cover = normalizeImageUrl(albumObj?.str("picUrl") ?: obj.str("picUrl"))
        val duration = obj.long("duration") ?: obj.long("dt") ?: 0L
        val albumId = (albumObj?.long("id"))?.toString()
        return Song(
            key = neteaseSongKey(id),
            source = MediaSource.NETEASE,
            title = obj.str("name").orEmpty().ifBlank { "未知歌曲" },
            artist = artists.joinToString(" / "),
            album = albumName,
            durationMs = duration,
            remoteId = id.toString(),
            artworkUrl = cover,
            albumId = albumId,
            artistId = obj.array("artists")?.objects()?.firstOrNull()?.long("id")?.toString(),
        )
    }

    fun searchSongs(query: String, limit: Int = 30, offset: Int = 0): List<Song> {
        val path = "/api/search/get?s=${urlEncode(query)}&type=1&limit=$limit&offset=$offset"
        val root = getJson(path) ?: return emptyList()
        val songs = root.obj("result")?.array("songs") ?: return emptyList()
        val parsed = songs.objects().mapNotNull { songFromJson(JsonObjectSelf(it)) }
        // 搜索接口不返回封面，缺少封面的歌曲用详情接口补齐
        val missingCoverIds = parsed.filter { it.artworkUrl.isNullOrBlank() }
            .mapNotNull { it.remoteId?.toLongOrNull() }
        if (missingCoverIds.isEmpty()) return parsed
        val covers = songCovers(missingCoverIds.take(50))
        return parsed.map { song ->
            if (song.artworkUrl.isNullOrBlank()) {
                val cover = covers[song.remoteId?.toLongOrNull()]
                if (cover != null) song.copy(artworkUrl = cover) else song
            } else {
                song
            }
        }
    }

    /** 批量补齐歌曲封面。 */
    private fun songCovers(ids: List<Long>): Map<Long, String> {
        if (ids.isEmpty()) return emptyMap()
        val root = getJson("/api/song/detail?ids=%5B${ids.joinToString(",")}%5D") ?: return emptyMap()
        val list = root.array("songs") ?: return emptyMap()
        return list.objects().mapNotNull { item ->
            val id = item.long("id") ?: return@mapNotNull null
            val cover = normalizeImageUrl(item.obj("album")?.str("picUrl")) ?: return@mapNotNull null
            id to cover
        }.toMap()
    }

    fun searchPlaylists(query: String, limit: Int = 30, offset: Int = 0): List<OnlineCollection> {
        val path = "/api/search/get?s=${urlEncode(query)}&type=1000&limit=$limit&offset=$offset"
        val root = getJson(path) ?: return emptyList()
        val list = root.obj("result")?.array("playlists") ?: return emptyList()
        return list.objects().mapNotNull { item ->
            val id = item.long("id") ?: return@mapNotNull null
            OnlineCollection(
                id = id.toString(),
                name = cleanBiliText(item.str("name")),
                creator = item.obj("creator")?.str("nickname").orEmpty(),
                coverUrl = normalizeImageUrl(item.str("coverImgUrl")),
                trackCount = item.int("trackCount") ?: 0,
                playCount = item.long("playCount") ?: 0L,
                source = MediaSource.NETEASE,
                description = item.str("description").orEmpty(),
            )
        }
    }

    fun searchArtists(query: String, limit: Int = 30, offset: Int = 0): List<OnlineArtist> {
        val path = "/api/search/get?s=${urlEncode(query)}&type=100&limit=$limit&offset=$offset"
        val root = getJson(path) ?: return emptyList()
        val list = root.obj("result")?.array("artists") ?: return emptyList()
        return list.objects().mapNotNull { item ->
            val id = item.long("id") ?: return@mapNotNull null
            OnlineArtist(
                id = id.toString(),
                name = cleanBiliText(item.str("name")),
                avatarUrl = normalizeImageUrl(item.str("picUrl")),
                songCount = item.int("musicSize") ?: item.int("albumSize") ?: 0,
                source = MediaSource.NETEASE,
            )
        }
    }

    fun searchAlbums(query: String, limit: Int = 30, offset: Int = 0): List<OnlineCollection> {
        val path = "/api/search/get?s=${urlEncode(query)}&type=10&limit=$limit&offset=$offset"
        val root = getJson(path) ?: return emptyList()
        val list = root.obj("result")?.array("albums") ?: return emptyList()
        return list.objects().mapNotNull { item ->
            val id = item.long("id") ?: return@mapNotNull null
            OnlineCollection(
                id = id.toString(),
                name = cleanBiliText(item.str("name")),
                creator = item.obj("artist")?.str("name").orEmpty(),
                coverUrl = normalizeImageUrl(item.str("picUrl")),
                trackCount = item.int("size") ?: 0,
                source = MediaSource.NETEASE,
            )
        }
    }

    fun hotSearchKeywords(): List<String> {
        val root = getJson("/api/search/hot") ?: return emptyList()
        val hots = root.obj("result")?.array("hots") ?: return emptyList()
        return hots.objects().mapNotNull { it.obj("first")?.str("keyword") ?: it.str("keyword") }.distinct()
    }

    /** 返回 (播放地址, 音质标签, 文件大小)。 */
    fun songUrl(id: String, level: String = "exhigh"): SongUrl? {
        val bitrate = when (level) {
            "standard" -> 128000
            "higher" -> 192000
            "exhigh", "lossless", "hires" -> 320000
            else -> 320000
        }
        val root = getJson("/api/song/enhance/player/url?ids=%5B$id%5D&br=$bitrate") ?: return null
        val data = root.array("data")?.objects()?.firstOrNull() ?: return null
        val url = data.str("url") ?: return null
        return SongUrl(
            url = url,
            level = data.str("level") ?: level,
            type = data.str("type").orEmpty(),
            size = data.long("size") ?: 0L,
        )
    }

    fun lyric(id: String): Pair<String, String?>? {
        val root = getJson("/api/song/lyric?id=$id&lv=1&kv=1&tv=-1") ?: return null
        val lyric = root.obj("lrc")?.str("lyric") ?: return null
        val translated = root.obj("tlyric")?.str("lyric")
        return lyric to translated?.takeIf { it.isNotBlank() }
    }

    fun playlistDetail(id: String): Pair<OnlineCollection, List<Song>>? {
        val root = getJson("/api/v6/playlist/detail?id=$id") ?: return null
        val playlist = root.obj("playlist") ?: return null
        val collection = OnlineCollection(
            id = id,
            name = cleanBiliText(playlist.str("name")),
            creator = playlist.obj("creator")?.str("nickname").orEmpty(),
            coverUrl = normalizeImageUrl(playlist.str("coverImgUrl")),
            trackCount = playlist.int("trackCount") ?: 0,
            playCount = playlist.long("playCount") ?: 0L,
            source = MediaSource.NETEASE,
            description = playlist.str("description").orEmpty(),
        )
        val tracks = playlist.array("tracks")?.objects().orEmpty().mapNotNull { songFromJson(JsonObjectSelf(it)) }
        return collection to tracks
    }

    fun albumDetail(id: String): Pair<OnlineCollection, List<Song>>? {
        val root = getJson("/api/v1/album/$id") ?: return null
        val album = root.obj("album") ?: return null
        val collection = OnlineCollection(
            id = id,
            name = cleanBiliText(album.str("name")),
            creator = album.obj("artist")?.str("name").orEmpty(),
            coverUrl = normalizeImageUrl(album.str("picUrl")),
            trackCount = album.int("size") ?: 0,
            source = MediaSource.NETEASE,
            description = album.str("description").orEmpty(),
        )
        val songs = root.array("songs")?.objects().orEmpty().mapNotNull { songFromJson(JsonObjectSelf(it)) }
        return collection to songs
    }

    fun artistTopSongs(id: String): List<Song> {
        val root = getJson("/api/v1/artist/$id") ?: return emptyList()
        val hot = root.array("hotSongs") ?: return emptyList()
        return hot.objects().mapNotNull { songFromJson(JsonObjectSelf(it)) }
    }

    fun recommendedNewSongs(limit: Int = 12): List<Song> {
        val root = getJson("/api/personalized/newsong?limit=$limit") ?: return emptyList()
        val list = root.array("result") ?: return emptyList()
        return list.objects().mapNotNull { item ->
            val songObj = item.obj("song") ?: item
            songFromJson(JsonObjectSelf(songObj))?.let { song ->
                if (song.artworkUrl.isNullOrBlank()) song.copy(artworkUrl = normalizeImageUrl(item.str("picUrl"))) else song
            }
        }
    }

    fun recommendedPlaylists(limit: Int = 12): List<OnlineCollection> {
        val root = getJson("/api/personalized/playlist?limit=$limit") ?: return emptyList()
        val list = root.array("result") ?: return emptyList()
        return list.objects().mapNotNull { item ->
            val id = item.long("id") ?: return@mapNotNull null
            OnlineCollection(
                id = id.toString(),
                name = cleanBiliText(item.str("name")),
                creator = item.obj("creator")?.str("nickname").orEmpty(),
                coverUrl = normalizeImageUrl(item.str("picUrl")),
                trackCount = item.int("trackCount") ?: 0,
                playCount = item.long("playCount") ?: 0L,
                source = MediaSource.NETEASE,
            )
        }
    }

    fun toplists(): List<OnlineCollection> {
        val root = getJson("/api/toplist/detail") ?: return emptyList()
        val list = root.array("list") ?: return emptyList()
        return list.objects().mapNotNull { item ->
            val id = item.long("id") ?: return@mapNotNull null
            OnlineCollection(
                id = id.toString(),
                name = cleanBiliText(item.str("name")),
                coverUrl = normalizeImageUrl(item.str("coverImgUrl")),
                trackCount = item.int("trackCount") ?: 0,
                source = MediaSource.NETEASE,
                description = item.str("updateFrequency").orEmpty(),
            )
        }
    }

    /** 登录后读取用户创建与收藏的歌单。 */
    fun userPlaylists(uid: String): List<OnlineCollection> {
        if (uid.isBlank()) return emptyList()
        val root = getJson("/api/user/playlist?uid=$uid&limit=100&offset=0") ?: return emptyList()
        val list = root.array("playlist") ?: return emptyList()
        return list.objects().mapNotNull { item ->
            val id = item.long("id") ?: return@mapNotNull null
            OnlineCollection(
                id = id.toString(),
                name = cleanBiliText(item.str("name")),
                creator = item.obj("creator")?.str("nickname").orEmpty(),
                coverUrl = normalizeImageUrl(item.str("coverImgUrl")),
                trackCount = item.int("trackCount") ?: 0,
                playCount = item.long("playCount") ?: 0L,
                source = MediaSource.NETEASE,
                description = item.str("description").orEmpty(),
            )
        }
    }
}

/** 简单的 JSON 对象包装，方便统一调用解析扩展。 */
@JvmInline
value class JsonObjectSelf(private val raw: kotlinx.serialization.json.JsonObject) {
    fun str(key: String) = raw.str(key)
    fun long(key: String) = raw.long(key)
    fun int(key: String) = raw.int(key)
    fun obj(key: String) = raw.obj(key)?.let(::JsonObjectSelf)
    fun array(key: String) = raw.array(key)
}

data class SongUrl(
    val url: String,
    val level: String,
    val type: String,
    val size: Long,
)

/** 哔哩哔哩客户端：WBI 签名搜索 + 音频流解析。 */
class BiliApi(private val http: HttpService) {

    private val headers = mapOf("Referer" to "https://www.bilibili.com/")

    private var wbiKeys: Pair<String, String>? = null
    private var cookieReady = false

    private fun ensureCookies() {
        if (cookieReady) return
        cookieReady = true
        runCatching {
            // 先访问主页拿到 buvid3 / b_nut，再补一份指纹 Cookie，降低接口风控概率
            http.get("https://www.bilibili.com/", mapOf("Referer" to "https://www.bilibili.com/"), timeoutSeconds = 12)
            val spi = http.get("https://api.bilibili.com/x/frontend/finger/spi", headers)
            NeriJsonParser.parse(spi.orEmpty()).asObject()?.obj("data")?.let { data ->
                data.str("b_3")?.let { http.setCookie("api.bilibili.com", "buvid3", it) }
                data.str("b_4")?.let { http.setCookie("api.bilibili.com", "buvid4", it) }
            }
        }
    }

    /** 风控/验证响应：data 里只有 v_voucher，或返回码非 0。 */
    private fun isBlockedResponse(root: kotlinx.serialization.json.JsonObject?): Boolean {
        if (root == null) return true
        if (root.int("code") != 0) return true
        val data = root.obj("data") ?: return true
        if (data["v_voucher"] != null) return true
        return data.array("result") == null
    }

    private val mixinKeyEncTab = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
        33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40,
        61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11,
        36, 20, 34, 44, 52
    )

    private fun loadWbiKeys(): Pair<String, String>? {
        wbiKeys?.let { return it }
        ensureCookies()
        val text = http.get("https://api.bilibili.com/x/web-interface/nav", headers) ?: return null
        val wbi = NeriJsonParser.parse(text).asObject()?.obj("data")?.obj("wbi_img") ?: return null
        val imgKey = wbi.str("img_url").orEmpty().substringAfterLast('/').substringBefore('.')
        val subKey = wbi.str("sub_url").orEmpty().substringAfterLast('/').substringBefore('.')
        if (imgKey.isEmpty() || subKey.isEmpty()) return null
        val keys = imgKey to subKey
        wbiKeys = keys
        return keys
    }

    private fun mixinKey(): String? {
        val (imgKey, subKey) = loadWbiKeys() ?: return null
        val raw = imgKey + subKey
        val builder = StringBuilder()
        mixinKeyEncTab.forEach { index -> if (index < raw.length) builder.append(raw[index]) }
        return builder.toString().take(32)
    }

    private fun refreshWbiKeys() {
        wbiKeys = null
    }

    private fun signedQuery(params: Map<String, String>): String? {
        val mixin = mixinKey() ?: return null
        val withTime = params.toMutableMap()
        withTime["wts"] = (System.currentTimeMillis() / 1000).toString()
        val query = withTime.entries
            .sortedBy { it.key }
            .joinToString("&") { (key, value) ->
                val cleaned = value.filterNot { it in "!'()*" }
                "${urlEncode(key)}=${urlEncode(cleaned)}"
            }
        val wRid = md5Hex(query + mixin)
        return "$query&w_rid=$wRid"
    }

    fun searchVideos(keyword: String, page: Int = 1): List<Song> {
        ensureCookies()
        val params = mapOf(
            "search_type" to "video",
            "keyword" to keyword,
            "page" to page.toString(),
            "page_size" to "30",
        )
        var lastRoot: kotlinx.serialization.json.JsonObject? = null
        repeat(3) { attempt ->
            if (attempt > 0) {
                Thread.sleep(600L * attempt)
                refreshWbiKeys()
            }
            lastRoot = wbiRequest("https://api.bilibili.com/x/web-interface/wbi/search/type", params)
            if (isBlockedResponse(lastRoot)) {
                val fallback = "https://api.bilibili.com/x/web-interface/search/type?search_type=video" +
                    "&keyword=${urlEncode(keyword)}&page=$page&page_size=30"
                lastRoot = NeriJsonParser.parse(http.get(fallback, headers).orEmpty()).asObject()
            }
            val results = lastRoot?.obj("data")?.array("result")
            if (results != null && results.isNotEmpty()) {
                return mapSearchResults(results)
            }
        }
        return emptyList()
    }

    private fun mapSearchResults(results: kotlinx.serialization.json.JsonArray): List<Song> =
        results.objects().mapNotNull { item ->
            val bvid = item.str("bvid") ?: item.str("aid") ?: return@mapNotNull null
            Song(
                key = biliSongKey(bvid),
                source = MediaSource.BILIBILI,
                title = cleanBiliText(item.str("title")).ifBlank { "未知视频" },
                artist = cleanBiliText(item.str("author")),
                album = "哔哩哔哩",
                durationMs = biliDurationMs(item.str("duration")),
                remoteId = bvid,
                artworkUrl = normalizeImageUrl(item.str("pic")),
            )
        }

    private fun wbiRequest(baseUrl: String, params: Map<String, String>): kotlinx.serialization.json.JsonObject? {
        val query = signedQuery(params) ?: return null
        val text = http.get("$baseUrl?$query", headers) ?: return null
        return NeriJsonParser.parse(text).asObject()
    }

    /** 调试用：返回搜索接口的原始 code 与 message。 */
    fun debugSearch(keyword: String): String {
        ensureCookies()
        val params = mapOf(
            "search_type" to "video",
            "keyword" to keyword,
            "page" to "1",
            "page_size" to "30",
        )
        val query = signedQuery(params) ?: return "mixin key unavailable"
        val text = http.get("https://api.bilibili.com/x/web-interface/wbi/search/type?$query", headers)
            ?: return "empty response"
        val root = NeriJsonParser.parse(text).asObject()
        val data = root?.obj("data")
        val results = data?.array("result")
        val first = results?.objects()?.firstOrNull()
        return "code=${root?.int("code")} message=${root?.str("message")} length=${text.length} " +
            "dataKeys=${data?.keys} resultSize=${results?.size} firstKeys=${first?.keys} " +
            "firstTitle=${first?.str("title")} firstBvid=${first?.str("bvid")}"
    }

    fun videoPages(bvid: String): List<Pair<String, String>> {
        val text = http.get("https://api.bilibili.com/x/player/pagelist?bvid=$bvid", headers) ?: return emptyList()
        val root = NeriJsonParser.parse(text).asObject() ?: return emptyList()
        val data = root.array("data") ?: return emptyList()
        return data.objects().mapNotNull { item ->
            val cid = item.long("cid")?.toString() ?: return@mapNotNull null
            cid to cleanBiliText(item.str("part"))
        }
    }

    fun audioUrl(bvid: String, cid: String): String? {
        ensureCookies()
        val params = mapOf(
            "bvid" to bvid,
            "cid" to cid,
            "fnval" to "16",
            "fourk" to "1",
        )
        var dash: kotlinx.serialization.json.JsonObject? = null
        for (attempt in 0 until 3) {
            if (attempt > 0) {
                Thread.sleep(500L * attempt)
                refreshWbiKeys()
            }
            var root = wbiRequest("https://api.bilibili.com/x/player/wbi/playurl", params)
            if (root == null || root.int("code") != 0 || root.obj("data")?.obj("dash") == null) {
                val fallback = "https://api.bilibili.com/x/player/playurl?bvid=$bvid&cid=$cid&fnval=16&fourk=1"
                root = NeriJsonParser.parse(http.get(fallback, headers).orEmpty()).asObject()
            }
            dash = root?.obj("data")?.obj("dash")
            if (dash != null) break
        }
        val resolvedDash = dash ?: return null
        val audios = resolvedDash.array("audio")?.objects().orEmpty()
        val best = audios.maxByOrNull { it.long("bandwidth") ?: 0L } ?: return null
        val direct = best.str("baseUrl") ?: best.str("base_url") ?: return null
        return normalizeImageUrl(direct)
    }

    fun biliHeaders(): Map<String, String> = mapOf(
        "Referer" to "https://www.bilibili.com/",
        "User-Agent" to DESKTOP_USER_AGENT,
        "Origin" to "https://www.bilibili.com",
    )

    /** 登录后读取用户创建的收藏夹。 */
    fun favoriteFolders(mid: String): List<OnlineCollection> {
        if (mid.isBlank()) return emptyList()
        val text = http.get(
            "https://api.bilibili.com/x/v3/fav/folder/created/list-all?up_mid=$mid",
            headers,
        ) ?: return emptyList()
        val root = NeriJsonParser.parse(text).asObject() ?: return emptyList()
        if (root.int("code") != 0) return emptyList()
        val list = root.obj("data")?.array("list") ?: return emptyList()
        return list.objects().mapNotNull { item ->
            val id = item.long("id") ?: return@mapNotNull null
            OnlineCollection(
                id = id.toString(),
                name = cleanBiliText(item.str("title")),
                creator = "哔哩哔哩收藏夹",
                coverUrl = normalizeImageUrl(item.str("cover")),
                trackCount = item.int("media_count") ?: 0,
                source = MediaSource.BILIBILI,
            )
        }
    }
}
