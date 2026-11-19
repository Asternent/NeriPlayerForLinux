package moe.ouom.neriplayer.desktop.net

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import moe.ouom.neriplayer.desktop.core.MediaSource
import moe.ouom.neriplayer.desktop.core.OnlineArtist
import moe.ouom.neriplayer.desktop.core.OnlineCollection
import moe.ouom.neriplayer.desktop.core.Song
import moe.ouom.neriplayer.desktop.core.YouTubeArtistDetail
import moe.ouom.neriplayer.desktop.core.YouTubeAudio
import moe.ouom.neriplayer.desktop.core.YouTubeCollectionDetail
import moe.ouom.neriplayer.desktop.core.YouTubeHomeItem
import moe.ouom.neriplayer.desktop.core.YouTubeHomeSection
import java.util.Locale

/**
 * YouTube Music Innertube 响应解析。
 *
 * 结构参考 Android 端 `YouTubeMusicResponseParser`，但只保留桌面愿景用得到的字段：
 * 首页 carousel、搜索（歌曲/专辑/歌手/歌单）、歌单/专辑详情、歌手页、歌词、播放直链。
 * 所有渲染器都按“路径找不到就返回 null”的兜底写，接口改版时退化为空数据而不是抛异常。
 */
object YouTubeMusicParser {

    // ------------------------------------------------------------------ 首页

    fun parseHome(root: JsonObject): List<YouTubeHomeSection> {
        val sectionList = root.path("contents", "singleColumnBrowseResultsRenderer")
            ?.array("tabs")?.objects()?.firstOrNull()
            ?.path("tabRenderer", "content", "sectionListRenderer")
            ?: root.path("continuationContents", "sectionListContinuation")
        val contents = sectionList?.array("contents") ?: return emptyList()
        return contents.objects().mapIndexedNotNull { index, section ->
            val carousel = section.obj("musicCarouselShelfRenderer") ?: return@mapIndexedNotNull null
            val header = carousel.path("header", "musicCarouselShelfBasicHeaderRenderer")
            val title = extractText(header?.obj("title"))
            val items = carousel.array("contents").objects().mapNotNull(::parseHomeItem)
            if (items.isEmpty()) null
            else YouTubeHomeSection(id = "yt-$index", title = title, items = items)
        }
    }

    private fun parseHomeItem(item: JsonObject): YouTubeHomeItem? {
        item.obj("musicTwoRowItemRenderer")?.let { renderer ->
            val title = extractText(renderer.obj("title"))
            if (title.isBlank()) return null
            val subtitle = extractText(renderer.obj("subtitle"))
            val cover = thumbnailUrl(renderer.obj("thumbnailRenderer"))
            val navigation = renderer.obj("navigationEndpoint")
            val browse = navigation?.obj("browseEndpoint")
            val browseId = browse?.str("browseId").orEmpty()
            val pageType = browse?.pageType().orEmpty()
            val videoId = navigation?.obj("watchEndpoint")?.str("videoId").orEmpty()
            val durationText = extractDurationText(renderer.obj("subtitle"))
            if (videoId.isNotBlank()) {
                return YouTubeHomeItem(
                    kind = "song",
                    song = song(
                        videoId = videoId,
                        title = title,
                        artist = subtitle,
                        album = "",
                        durationMs = parseDurationMs(durationText),
                        coverUrl = cover,
                    ),
                )
            }
            return when {
                browseId.startsWith("MPRE") || pageType.contains("ALBUM") -> YouTubeHomeItem(
                    kind = "album",
                    collection = collection(
                        browseId = browseId,
                        name = title,
                        creator = subtitle,
                        coverUrl = cover,
                        description = "",
                        trackCount = parseTrackCount(subtitle),
                    ),
                )

                browseId.startsWith("VL") || pageType.contains("PLAYLIST") -> YouTubeHomeItem(
                    kind = "playlist",
                    collection = collection(
                        browseId = browseId,
                        name = title,
                        creator = subtitle,
                        coverUrl = cover,
                        trackCount = parseTrackCount(subtitle),
                    ),
                )

                browseId.startsWith("UC") || pageType.contains("ARTIST") -> YouTubeHomeItem(
                    kind = "artist",
                    artist = artist(
                        browseId = browseId,
                        name = title,
                        avatarUrl = cover,
                        songCount = 0,
                    ),
                )

                else -> null
            }
        }
        item.obj("musicResponsiveListItemRenderer")?.let { renderer ->
            val title = columnText(renderer, 0)
            val videoId = trackVideoId(renderer)
            if (title.isBlank() || videoId.isBlank()) return null
            val metadata = rowMetadata(renderer, 1)
            val durationText = fixedDurationText(renderer).ifBlank { metadata.durationText }
            return YouTubeHomeItem(
                kind = "song",
                song = song(
                    videoId = videoId,
                    title = title,
                    artist = metadata.artist,
                    album = metadata.album,
                    durationMs = parseDurationMs(durationText),
                    coverUrl = thumbnailUrl(renderer.obj("thumbnail")),
                ),
            )
        }
        return null
    }

    // ------------------------------------------------------------------ 搜索

    fun parseSearchSongs(root: JsonObject, limit: Int = 30): List<Song> =
        searchRenderers(root).mapNotNull(::songFromSearchRenderer).distinctBy { it.remoteId }.take(limit)

    fun parseSearchAlbums(root: JsonObject, limit: Int = 30): List<OnlineCollection> =
        searchRenderers(root).mapNotNull { renderer ->
            val browse = renderer.obj("navigationEndpoint")?.obj("browseEndpoint") ?: return@mapNotNull null
            val browseId = browse.str("browseId").orEmpty()
            val pageType = browse.pageType()
            if (browseId.isBlank()) return@mapNotNull null
            if (!browseId.startsWith("MPRE") && !pageType.contains("ALBUM")) return@mapNotNull null
            val name = columnText(renderer, 0)
            if (name.isBlank()) return@mapNotNull null
            val metadata = rowMetadata(renderer, 1)
            collection(
                browseId = browseId,
                name = name,
                creator = metadata.artist,
                coverUrl = thumbnailUrl(renderer.obj("thumbnail")),
                description = "",
                trackCount = 0,
            )
        }.distinctBy { it.id }.take(limit)

    fun parseSearchArtists(root: JsonObject, limit: Int = 30): List<OnlineArtist> =
        searchRenderers(root).mapNotNull { renderer ->
            val browse = renderer.obj("navigationEndpoint")?.obj("browseEndpoint") ?: return@mapNotNull null
            val browseId = browse.str("browseId").orEmpty()
            val pageType = browse.pageType()
            val name = columnText(renderer, 0)
            if (browseId.isBlank() || name.isBlank()) return@mapNotNull null
            if (!browseId.startsWith("UC") && !pageType.contains("ARTIST")) return@mapNotNull null
            artist(
                browseId = browseId,
                name = name,
                avatarUrl = thumbnailUrl(renderer.obj("thumbnail")),
                songCount = parseTrackCount(columnText(renderer, 1)),
            )
        }.distinctBy { it.id }.take(limit)

    fun parseSearchPlaylists(root: JsonObject, limit: Int = 30): List<OnlineCollection> =
        searchRenderers(root).mapNotNull { renderer ->
            val browse = renderer.obj("navigationEndpoint")?.obj("browseEndpoint") ?: return@mapNotNull null
            val browseId = browse.str("browseId").orEmpty()
            val pageType = browse.pageType()
            if (browseId.isBlank()) return@mapNotNull null
            if (!browseId.startsWith("VL") && !pageType.contains("PLAYLIST")) return@mapNotNull null
            val name = columnText(renderer, 0)
            if (name.isBlank()) return@mapNotNull null
            val metadata = rowMetadata(renderer, 1)
            collection(
                browseId = browseId,
                name = name,
                creator = metadata.artist,
                coverUrl = thumbnailUrl(renderer.obj("thumbnail")),
                description = "",
                trackCount = 0,
            )
        }.distinctBy { it.id }.take(limit)

    private fun searchRenderers(root: JsonObject): List<JsonObject> {
        val tab = root.path("contents", "tabbedSearchResultsRenderer")
            ?.array("tabs")?.objects()?.firstOrNull()
            ?.path("tabRenderer", "content", "sectionListRenderer")
        val sections = tab?.array("contents") ?: return emptyList()
        val renderers = mutableListOf<JsonObject>()
        sections.objects().forEach { section ->
            section.obj("musicShelfRenderer")?.array("contents")?.objects()?.let { items ->
                items.mapNotNullTo(renderers) { it.obj("musicResponsiveListItemRenderer") }
            }
            section.path("itemSectionRenderer")?.array("contents")?.objects()?.forEach { item ->
                item.obj("musicResponsiveListItemRenderer")?.let(renderers::add)
                item.obj("musicTwoRowItemRenderer")?.let(renderers::add)
                item.obj("musicShelfRenderer")?.array("contents")?.objects()?.let { items ->
                    items.mapNotNullTo(renderers) { it.obj("musicResponsiveListItemRenderer") }
                }
            }
        }
        return renderers
    }

    private fun songFromSearchRenderer(renderer: JsonObject): Song? {
        val videoId = trackVideoId(renderer)
        val title = columnText(renderer, 0)
        if (videoId.isBlank() || title.isBlank()) return null
        val metadata = rowMetadata(renderer, 1)
        val durationText = fixedDurationText(renderer).ifBlank { metadata.durationText }
        return song(
            videoId = videoId,
            title = title,
            artist = metadata.artist,
            album = metadata.album,
            durationMs = parseDurationMs(durationText),
            coverUrl = thumbnailUrl(renderer.obj("thumbnail")),
        )
    }

    // ------------------------------------------------------------------ 歌单 / 专辑详情

    fun parsePlaylist(root: JsonObject, browseId: String, fallbackTitle: String): YouTubeCollectionDetail {
        val header = detailHeader(root)
        val title = extractText(header?.obj("title")).ifBlank { fallbackTitle }
        val subtitle = extractText(header?.obj("subtitle"))
        val secondSubtitle = extractText(header?.obj("secondSubtitle"))
        val cover = thumbnailUrl(header?.obj("thumbnail"))
        val creator = extractText(header?.obj("straplineTextOne")).ifBlank {
            creatorFromSubtitle(subtitle.ifBlank { secondSubtitle })
        }
        val songs = playlistTracks(root, fallbackArtist = creator, fallbackAlbum = title)
        return YouTubeCollectionDetail(
            title = title,
            coverUrl = cover,
            description = "",
            creator = creator,
            trackCount = parseTrackCount(secondSubtitle).takeIf { it > 0 } ?: songs.size,
            songs = songs,
            collection = collection(
                browseId = browseId,
                name = title,
                creator = creator,
                coverUrl = cover,
                trackCount = parseTrackCount(secondSubtitle).takeIf { it > 0 } ?: songs.size,
            ),
        )
    }

    fun parseAlbum(root: JsonObject, browseId: String, fallbackTitle: String): YouTubeCollectionDetail {
        val header = detailHeader(root)
        val title = extractText(header?.obj("title")).ifBlank { fallbackTitle }
        val secondSubtitle = extractText(header?.obj("secondSubtitle"))
        val cover = thumbnailUrl(header?.obj("thumbnail"))
        val artistName = extractText(header?.obj("straplineTextOne"))
        val description = root.path("microformat", "microformatDataRenderer")?.str("description").orEmpty()
        val tracks = root.path("contents", "twoColumnBrowseResultsRenderer", "secondaryContents", "sectionListRenderer")
            ?.array("contents")?.objects().orEmpty()
            .mapNotNull { section ->
                section.obj("musicShelfRenderer")?.array("contents")
                    ?: section.obj("musicPlaylistShelfRenderer")?.array("contents")
            }
            .flatMap { it.objects() }
            .mapNotNull { it.obj("musicResponsiveListItemRenderer") }
            .mapNotNull { parseTrack(it, fallbackArtist = artistName, fallbackAlbum = title) }
        return YouTubeCollectionDetail(
            title = title,
            coverUrl = cover,
            description = description,
            creator = artistName,
            trackCount = parseTrackCount(secondSubtitle).takeIf { it > 0 } ?: tracks.size,
            songs = tracks,
            collection = collection(
                browseId = browseId,
                name = title,
                creator = artistName,
                coverUrl = cover,
                trackCount = parseTrackCount(secondSubtitle).takeIf { it > 0 } ?: tracks.size,
            ),
        )
    }

    private fun playlistTracks(root: JsonObject, fallbackArtist: String, fallbackAlbum: String): List<Song> {
        val sections = root.path("contents", "twoColumnBrowseResultsRenderer", "secondaryContents", "sectionListRenderer")
            ?.array("contents")?.objects().orEmpty()
        val renderers = sections.mapNotNull { section ->
            section.obj("musicPlaylistShelfRenderer")?.array("contents")
                ?: section.obj("musicShelfRenderer")?.array("contents")
        }.flatMap { it.objects() }
            .mapNotNull { it.obj("musicResponsiveListItemRenderer") }
        return renderers.mapNotNull { parseTrack(it, fallbackArtist, fallbackAlbum) }
    }

    private fun parseTrack(renderer: JsonObject, fallbackArtist: String, fallbackAlbum: String): Song? {
        val videoId = trackVideoId(renderer)
        val title = columnText(renderer, 0)
        if (videoId.isBlank() || title.isBlank()) return null
        val artist = columnText(renderer, 1).ifBlank { fallbackArtist }
        // 专辑页的 flex 第三列是「播放次数」之类的统计，不是专辑名
        val trackAlbum = columnText(renderer, 2)
            .takeIf { it.isNotBlank() && it.length < 80 && !looksLikeStatText(it) && !looksLikeYear(it) }
            .orEmpty()
        val album = trackAlbum.ifBlank { fallbackAlbum }
        val durationText = fixedDurationText(renderer)
        return song(
            videoId = videoId,
            title = title,
            artist = artist,
            album = album,
            durationMs = parseDurationMs(durationText),
            coverUrl = thumbnailUrl(renderer.obj("thumbnail")),
        )
    }

    /** 歌单/专辑头：两个 tab 的 sectionList 里找 musicResponsiveHeaderRenderer。 */
    private fun detailHeader(root: JsonObject): JsonObject? {
        val columns = listOf("twoColumnBrowseResultsRenderer", "singleColumnBrowseResultsRenderer")
        for (column in columns) {
            val tabs = root.path("contents", column)?.array("tabs") ?: continue
            for (tab in tabs.objects()) {
                val contents = tab.path("tabRenderer", "content", "sectionListRenderer")
                    ?.array("contents") ?: continue
                for (section in contents.objects()) {
                    section.obj("musicResponsiveHeaderRenderer")?.let { return it }
                    section.path("musicEditablePlaylistDetailHeaderRenderer", "header")
                        ?.obj("musicResponsiveHeaderRenderer")?.let { return it }
                }
            }
        }
        return null
    }

    // ------------------------------------------------------------------ 歌手页

    fun parseArtist(root: JsonObject, browseId: String, fallbackName: String): YouTubeArtistDetail {
        val header = root.obj("header")?.obj("musicImmersiveHeaderRenderer")
            ?: root.obj("header")?.obj("musicVisualHeaderRenderer")
        val name = extractText(header?.obj("title")).ifBlank { fallbackName }
        val description = extractText(header?.obj("description"))
        val avatar = thumbnailUrl(header?.obj("thumbnail"))
        val sections = root.path("contents", "singleColumnBrowseResultsRenderer")
            ?.array("tabs")?.objects()?.firstOrNull()
            ?.path("tabRenderer", "content", "sectionListRenderer")
            ?.array("contents")?.objects().orEmpty()

        val songs = mutableListOf<Song>()
        val albums = mutableListOf<OnlineCollection>()
        sections.forEach { section ->
            section.obj("musicShelfRenderer")?.let { shelf ->
                shelf.array("contents")?.objects()?.forEach { item ->
                    item.obj("musicResponsiveListItemRenderer")?.let { renderer ->
                        parseTrack(renderer, fallbackArtist = name, fallbackAlbum = "")
                            ?.let { songs += it }
                    }
                }
            }
            section.obj("musicCarouselShelfRenderer")?.let { carousel ->
                val title = extractText(carousel.path("header", "musicCarouselShelfBasicHeaderRenderer")?.obj("title"))
                carousel.array("contents")?.objects()?.forEach { item ->
                    item.obj("musicTwoRowItemRenderer")?.let { renderer ->
                        val browse = renderer.obj("navigationEndpoint")?.obj("browseEndpoint")
                        val albumId = browse?.str("browseId").orEmpty()
                        val pageType = browse?.pageType().orEmpty()
                        val itemTitle = extractText(renderer.obj("title"))
                        if (albumId.isBlank() || itemTitle.isBlank()) return@forEach
                        if (!albumId.startsWith("MPRE") && !pageType.contains("ALBUM")) return@forEach
                        val subtitle = extractText(renderer.obj("subtitle"))
                        albums += collection(
                            browseId = albumId,
                            name = itemTitle,
                            creator = creatorFromSubtitle(subtitle).ifBlank { name },
                            coverUrl = thumbnailUrl(renderer.obj("thumbnailRenderer")),
                            description = "",
                            trackCount = parseTrackCount(subtitle),
                        )
                    }
                }
            }
        }
        val distinctSongs = songs.distinctBy { it.remoteId }
        val distinctAlbums = albums.distinctBy { it.id }
        return YouTubeArtistDetail(
            name = name,
            avatarUrl = avatar,
            description = description,
            songCount = distinctSongs.size,
            songs = distinctSongs,
            albums = distinctAlbums,
            artist = artist(
                browseId = browseId,
                name = name,
                avatarUrl = avatar,
                songCount = distinctSongs.size,
            ),
        )
    }

    // ------------------------------------------------------------------ 歌单库 / 歌词

    /** 我的歌单（FEmusic_liked_playlists）：递归找带 playlist 页面的 twoRow 卡。 */
    fun parseLibraryPlaylists(root: JsonObject): List<OnlineCollection> {
        val result = mutableListOf<OnlineCollection>()
        fun visit(value: JsonElement?) {
            when (value) {
                is JsonObject -> {
                    value.obj("musicTwoRowItemRenderer")?.let { renderer ->
                        val browse = renderer.obj("navigationEndpoint")?.obj("browseEndpoint")
                        val browseId = browse?.str("browseId").orEmpty()
                        val pageType = browse?.pageType().orEmpty()
                        val title = extractText(renderer.obj("title"))
                        if (title.isNotBlank() &&
                            (browseId.startsWith("VL") || pageType.contains("PLAYLIST"))
                        ) {
                            val subtitle = extractText(renderer.obj("subtitle"))
                            result += collection(
                                browseId = browseId,
                                name = title,
                                creator = subtitle,
                                coverUrl = thumbnailUrl(renderer.obj("thumbnailRenderer")),
                                trackCount = parseTrackCount(subtitle),
                            )
                        }
                    }
                    value.forEach { (_, child) -> visit(child) }
                }

                is JsonArray -> value.forEach(::visit)
                else -> Unit
            }
        }
        visit(root)
        return result.distinctBy { it.id }
    }

    fun parseLyricsBrowseId(root: JsonObject): String? {
        val tabs = root.path(
            "contents", "singleColumnMusicWatchNextResultsRenderer", "tabbedRenderer",
            "watchNextTabbedResultsRenderer",
        )?.array("tabs") ?: return null
        return tabs.objects().firstNotNullOfOrNull { tab ->
            tab.path("tabRenderer", "endpoint", "browseEndpoint")?.str("browseId")
                ?.takeIf { it.startsWith("MPLYt") }
        }
    }

    fun parseLyrics(root: JsonObject): String? {
        val sections = root.path("contents", "sectionListRenderer")?.array("contents") ?: return null
        return sections.objects().firstNotNullOfOrNull { section ->
            section.obj("musicDescriptionShelfRenderer")?.let { renderer ->
                extractText(renderer.obj("description")).takeIf { it.isNotBlank() }
            }
        }
    }

    // ------------------------------------------------------------------ 播放直链

    fun parseAudio(
        root: JsonObject,
        videoId: String,
        preferredQuality: String,
        userAgent: String,
    ): YouTubeAudio? {
        val streaming = root.obj("streamingData") ?: return null
        val formats = listOfNotNull(streaming.array("adaptiveFormats"), streaming.array("formats"))
            .flatMap { it.objects() }
            .filter { format ->
                val mime = format.str("mimeType").orEmpty().substringBefore(';').trim()
                mime.startsWith("audio/") && !format.str("url").isNullOrBlank()
            }
        if (formats.isEmpty()) return null
        val targetBitrate = when (preferredQuality.lowercase(Locale.US)) {
            "standard", "low" -> 96_000
            "higher", "medium" -> 160_000
            "exhigh", "high" -> 192_000
            "lossless", "very_high" -> Int.MAX_VALUE
            else -> 192_000
        }
        // itag 140(128k m4a)/141(256k m4a)/251(opus) 优先；bitrate 接近目标挡位更合适
        val score: (JsonObject) -> Int = { format ->
            val itag = format.int("itag")
            val bitrate = format.int("bitrate") ?: 0
            val mime = format.str("mimeType").orEmpty()
            var value = 0
            when (itag) {
                140 -> value += 40
                141 -> value += 36
                251 -> value += 34
                250 -> value += 28
                249 -> value += 20
                else -> value += 10
            }
            if (mime.contains("mp4")) value += 6
            val distance = kotlin.math.abs(bitrate.toLong() - targetBitrate.toLong())
                .coerceAtMost(1_000_000L) / 100_000L
            value - distance.toInt()
        }
        val selected = formats.maxByOrNull(score) ?: return null
        val durationMs = root.obj("videoDetails")?.str("lengthSeconds")?.toLongOrNull()?.times(1000)
            ?: selected.str("approxDurationMs")?.toLongOrNull()
            ?: 0L
        return YouTubeAudio(
            url = selected.str("url").orEmpty(),
            mimeType = selected.str("mimeType")?.substringBefore(';')?.trim(),
            bitrateKbps = ((selected.int("bitrate") ?: 0) + 500) / 1000,
            durationMs = durationMs,
            userAgent = userAgent,
        )
    }

    // ------------------------------------------------------------------ 通用解析工具

    private fun JsonObject?.path(vararg keys: String): JsonObject? {
        var current = this
        for (key in keys) current = current?.obj(key) ?: return null
        return current
    }

    private fun JsonObject.pageType(): String =
        path(
            "browseEndpointContextSupportedConfigs", "browseEndpointContextMusicConfig",
        )?.str("pageType").orEmpty().uppercase(Locale.US)

    private fun JsonArray?.objects(): List<JsonObject> = this?.mapNotNull { it as? JsonObject }.orEmpty()

    private fun extractText(node: JsonObject?): String {
        if (node == null) return ""
        (node["runs"] as? JsonArray)?.let { runs ->
            return runs.joinToString("") { run -> (run as? JsonObject)?.str("text").orEmpty() }.trim()
        }
        return node.str("simpleText").orEmpty().trim()
    }

    private data class RowMetadata(
        val artist: String = "",
        val album: String = "",
        val durationText: String = "",
    )

    private fun rowMetadata(renderer: JsonObject, columnIndex: Int): RowMetadata {
        val node = renderer.array("flexColumns")?.objects()?.getOrNull(columnIndex)
            ?.obj("musicResponsiveListItemFlexColumnRenderer")?.obj("text") ?: return RowMetadata()
        val runs = node.array("runs")?.objects().orEmpty()
        var artist = ""
        var album = ""
        var duration = ""
        runs.forEach { run ->
            val text = run.str("text")?.trim().orEmpty()
            if (text.isBlank() || text in setOf("•", "·", "|")) return@forEach
            val pageType = run.obj("navigationEndpoint")?.obj("browseEndpoint")?.pageType().orEmpty()
            when {
                pageType.contains("ARTIST") || pageType.contains("USER_CHANNEL") -> {
                    if (artist.isBlank()) artist = text
                }

                pageType.contains("ALBUM") -> if (album.isBlank()) album = text
                looksLikeDuration(text) -> duration = text
                pageType.isNotBlank() -> Unit
                isTypeLabel(text) || looksLikeYear(text) || looksLikeStatText(text) -> Unit
                artist.isBlank() -> artist = text
                album.isBlank() && text.length < 80 -> album = text
            }
        }
        if (artist.isBlank() && album.isBlank() && duration.isBlank()) {
            duration = extractText(node)
        }
        return RowMetadata(artist = artist, album = album, durationText = duration)
    }

    private val TYPE_LABELS = setOf(
        "专辑", "单曲", "歌曲", "音乐人", "歌手", "播放列表", "歌单", "视频", "合辑", "EP",
        "Album", "Single", "Song", "Artist", "Playlist", "Video",
    )

    private fun isTypeLabel(text: String): Boolean = text.trim() in TYPE_LABELS

    private fun looksLikeYear(text: String): Boolean = Regex("^\\d{4}年?$").matches(text.trim())

    private fun looksLikeStatText(text: String): Boolean {
        val lower = text.lowercase(Locale.US)
        return listOf("播放", "观看", "views", "view", "listeners", "monthly", "听众", "订阅").any { lower.contains(it) }
    }

    /** 歌单/专辑副标题里挑一个能当 creator 的片段（跳过类型标签、年份、播放量）。 */
    private fun creatorFromSubtitle(subtitle: String): String =
        subtitle.split('•', '·', '|')
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() && !isTypeLabel(it) && !looksLikeYear(it) && !looksLikeStatText(it) }
            .orEmpty()

    private fun columnText(renderer: JsonObject, index: Int): String =
        extractText(
            renderer.array("flexColumns")?.objects()?.getOrNull(index)
                ?.obj("musicResponsiveListItemFlexColumnRenderer")?.obj("text"),
        )

    private fun fixedDurationText(renderer: JsonObject): String =
        extractText(
            renderer.array("fixedColumns")?.objects()?.firstOrNull()
                ?.obj("musicResponsiveListItemFixedColumnRenderer")?.obj("text"),
        )

    private fun extractDurationText(node: JsonObject?): String {
        val text = extractText(node)
        if (looksLikeDuration(text)) return text
        return (node?.array("runs")?.objects().orEmpty())
            .mapNotNull { it.str("text")?.trim() }
            .firstOrNull(::looksLikeDuration)
            .orEmpty()
    }

    private fun looksLikeDuration(text: String): Boolean {
        val trimmed = text.trim()
        if (!trimmed.contains(':')) return false
        return trimmed.split(':').all { it.trim().toLongOrNull() != null }
    }

    private fun parseDurationMs(text: String): Long {
        val parts = text.trim().split(':').mapNotNull { it.toLongOrNull() }
        val seconds = when (parts.size) {
            2 -> parts[0] * 60L + parts[1]
            3 -> parts[0] * 3600L + parts[1] * 60L + parts[2]
            else -> return 0L
        }
        return seconds * 1000L
    }

    private fun parseTrackCount(text: String): Int {
        if (text.isBlank()) return 0
        val match = Regex(
            "([0-9][0-9,]*)\\s*(?:首歌|首歌曲?|首|曲目|曲|集|songs?|tracks?|videos?|episodes?)",
            RegexOption.IGNORE_CASE,
        ).find(text) ?: return 0
        return match.groupValues[1].replace(",", "").toIntOrNull() ?: 0
    }

    private fun trackVideoId(renderer: JsonObject): String {
        renderer.path(
            "overlay", "musicItemThumbnailOverlayRenderer", "content", "musicPlayButtonRenderer",
            "playNavigationEndpoint", "watchEndpoint",
        )?.str("videoId")?.takeIf { it.isNotBlank() }?.let { return it }
        renderer.obj("playlistItemData")?.str("videoId")?.takeIf { it.isNotBlank() }?.let { return it }
        renderer.array("flexColumns")?.objects()?.firstOrNull()
            ?.obj("musicResponsiveListItemFlexColumnRenderer")?.obj("text")?.array("runs")?.objects()
            ?.firstNotNullOfOrNull { run ->
                run.obj("navigationEndpoint")?.obj("watchEndpoint")?.str("videoId")
                    ?.takeIf { it.isNotBlank() }
            }?.let { return it }
        return ""
    }

    private fun thumbnailUrl(node: JsonObject?): String? {
        if (node == null) return null
        val container = node.obj("musicThumbnailRenderer")
            ?: node.obj("croppedSquareThumbnailRenderer")
            ?: node
        val thumbnails = container.obj("thumbnail")?.array("thumbnails")
            ?: container.array("thumbnails")
            ?: return null
        val url = thumbnails.objects().lastOrNull()?.str("url") ?: return null
        return upgradeThumbnailUrl(url)
    }

    /** 把 `=w60-h60-...` 尺寸后缀升级成高清；非 Google 图床原样返回。 */
    private fun upgradeThumbnailUrl(url: String): String {
        if (url.isBlank()) return url
        val sizeRegex = Regex("=w\\d+(-h\\d+)?(-[a-zA-Z0-9-]+)*$")
        return when {
            sizeRegex.containsMatchIn(url) -> url.replace(sizeRegex, "=w1200-h1200")
            url.contains("lh3.googleusercontent.com") || url.contains("yt3.ggpht.com") ->
                if (url.contains('=')) url else "$url=w1200-h1200"

            else -> url
        }
    }

    private fun song(
        videoId: String,
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        coverUrl: String?,
    ): Song = Song(
        key = "youtube:$videoId",
        source = MediaSource.YOUTUBE,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        remoteId = videoId,
        artworkUrl = coverUrl,
    )

    private fun collection(
        browseId: String,
        name: String,
        creator: String = "",
        coverUrl: String? = null,
        description: String = "",
        trackCount: Int = 0,
    ): OnlineCollection = OnlineCollection(
        id = browseId,
        name = name,
        creator = creator,
        coverUrl = coverUrl,
        trackCount = trackCount,
        source = MediaSource.YOUTUBE,
        description = description,
    )

    private fun artist(
        browseId: String,
        name: String,
        avatarUrl: String? = null,
        songCount: Int = 0,
    ): OnlineArtist = OnlineArtist(
        id = browseId,
        name = name,
        avatarUrl = avatarUrl,
        songCount = songCount,
        source = MediaSource.YOUTUBE,
    )
}
