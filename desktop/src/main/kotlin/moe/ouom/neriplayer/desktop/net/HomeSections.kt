package moe.ouom.neriplayer.desktop.net

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import moe.ouom.neriplayer.desktop.core.AppContainer
import moe.ouom.neriplayer.desktop.core.MediaSource
import moe.ouom.neriplayer.desktop.core.OnlineCollection
import moe.ouom.neriplayer.desktop.core.Song
import java.util.Collections

/**
 * 首页栏目聚合。
 *
 * 把 Android 版首页的各个推荐类别（猜你喜欢 / 每日推荐 / 推荐新歌 / 排行榜 / 雷达歌单 /
 * 推荐歌单 / 私人 FM / B 站音乐）统一抓一遍，拼成前端按顺序渲染的 `sections` 列表。
 *
 * 设计要点：
 * - 所有请求并发发起（限流），单个栏目失败只记 `errors`，不影响整页；
 * - 未登录时登录专属栏目（每日推荐 / 私人 FM / 每日推荐歌单）直接不请求、不出现，只记一条提示；
 * - 空栏目整栏隐藏（由调用方只把非空栏目放进 sections）；
 * - 首页请求量大，用短 TTL 缓存兜住刷新/切页的重复调用。
 */
object HomeSections {

    /** 私有雷达（猜你喜欢）歌单 id，与 Android 的 NETEASE_PRIVATE_RADAR_PLAYLIST_ID 一致。 */
    private const val NETEASE_PRIVATE_RADAR_ID = "3136952023"

    /** 热歌榜 / 飙升榜 / 新歌榜，id 取自 Android NeteaseHomeRecommendations。 */
    private val RANK_PLAYLISTS = listOf(
        RankPlaylist("3778678", "热歌榜"),
        RankPlaylist("19723756", "飙升榜"),
        RankPlaylist("3779629", "新歌榜"),
    )

    /** 雷达歌单，id 取自 Android NeteaseRadarPlaylistDefinitions。 */
    private val RADAR_PLAYLISTS = listOf(
        NamedPlaylist("5320167908", "时光雷达"),
        NamedPlaylist("5362359247", "宝藏雷达"),
        NamedPlaylist("5300458264", "新歌雷达"),
        NamedPlaylist("5327906368", "乐迷雷达"),
        NamedPlaylist("5341776086", "神秘雷达"),
    )

    private const val SECTION_SONG_LIMIT = 18
    private const val PLAYLIST_LIMIT = 12
    private const val MAX_CONCURRENT_REQUESTS = 8
    private const val CACHE_TTL_MS = 120_000L

    private data class RankPlaylist(val id: String, val title: String)

    private data class NamedPlaylist(val id: String, val title: String)

    private data class PlaylistSource(val id: String, val title: String, val loginOnly: Boolean)

    private data class CacheEntry(val at: Long, val key: String, val payload: HomePayload)

    @Volatile
    private var cache: CacheEntry? = null

    /**
     * 拉取首页全量数据。
     * @param force 忽略短缓存（下拉刷新 / 登录态变化时用）
     */
    suspend fun load(container: AppContainer, force: Boolean = false): HomePayload =
        withContext(Dispatchers.IO) {
            val settings = container.settings.current
            val loggedIn = isNeteaseLoggedIn(container)
            val cacheKey = "netease=${settings.neteaseEnabled};" +
                "bili=${settings.bilibiliEnabled};login=$loggedIn"
            val now = System.currentTimeMillis()
            cache?.takeIf { !force && it.key == cacheKey && now - it.at <= CACHE_TTL_MS }
                ?.let { return@withContext it.payload }
            val payload = build(
                container = container,
                neteaseEnabled = settings.neteaseEnabled,
                bilibiliEnabled = settings.bilibiliEnabled,
                loggedIn = loggedIn,
            )
            cache = CacheEntry(now, cacheKey, payload)
            payload
        }

    /**
     * 首屏会并发十几条请求，偶发被风控/超时返回空；空结果重试一次，
     * 避免排行榜、推荐歌单这类 tab 列表直接少一项。
     */
    private suspend fun <T> orRetry(
        attempts: Int = 2,
        delayMs: Long = 350L,
        block: suspend () -> List<T>,
    ): List<T> {
        var last = emptyList<T>()
        repeat(attempts) { attempt ->
            last = block()
            if (last.isNotEmpty()) return last
            if (attempt < attempts - 1) delay(delayMs * (attempt + 1))
        }
        return last
    }

    /** 网易云登录态以持久化账号 + MUSIC_U Cookie 同时成立为准，避免发注定 301 的请求。 */
    private fun isNeteaseLoggedIn(container: AppContainer): Boolean {
        if (!container.accounts.isLoggedIn(MediaSource.NETEASE)) return false
        val cookie = container.online.httpService.cookieSnapshot("netease")["MUSIC_U"]
        return !cookie.isNullOrBlank()
    }

    private suspend fun build(
        container: AppContainer,
        neteaseEnabled: Boolean,
        bilibiliEnabled: Boolean,
        loggedIn: Boolean,
    ): HomePayload = coroutineScope {
        val errors = Collections.synchronizedList(mutableListOf<String>())
        val netease = container.online.netease
        val bilibili = container.online.bilibili
        val gate = Semaphore(MAX_CONCURRENT_REQUESTS)

        /** 并发跑一个栏目的网络请求；失败返回 null 并记录一条中文说明。 */
        fun <T> fetch(name: String, block: suspend () -> T): Deferred<T?> = async {
            runCatching { gate.withPermit { block() } }.fold(
                onSuccess = { it },
                onFailure = { error ->
                    println("[home] $name failed: ${error::class.simpleName}: ${error.message}")
                    errors += "$name 加载失败：${error.message ?: error.javaClass.simpleName}"
                    null
                },
            )
        }

        val continuePlaying = container.history.entries.value.take(12).map { it.song }

        val guessYouLikeDeferred = if (neteaseEnabled) fetch("猜你喜欢") {
            orRetry { netease.playlistPreview(NETEASE_PRIVATE_RADAR_ID, SECTION_SONG_LIMIT)?.second.orEmpty() }
        } else null

        val radarDeferred = if (neteaseEnabled) {
            RADAR_PLAYLISTS.map { playlist ->
                fetch(playlist.title) {
                    netease.playlistSummary(playlist.id)
                        ?: OnlineCollection(
                            id = playlist.id,
                            name = playlist.title,
                            source = MediaSource.NETEASE,
                        )
                }
            }
        } else {
            emptyList()
        }

        val dailySongsDeferred = if (neteaseEnabled && loggedIn) {
            fetch("每日推荐") { orRetry { netease.dailyRecommendedSongs() } }
        } else null

        val privateFmDeferred = if (neteaseEnabled && loggedIn) {
            fetch("私人 FM") { orRetry { netease.personalFmSongs(SECTION_SONG_LIMIT) } }
        } else null

        val newSongsDeferred = if (neteaseEnabled) {
            fetch("推荐新歌") { orRetry { netease.recommendedNewSongs(SECTION_SONG_LIMIT) } }
        } else null

        // 排行榜：三个榜单各一个 tab。
        val rankDeferred = if (neteaseEnabled) {
            RANK_PLAYLISTS.map { playlist ->
                playlist to fetch(playlist.title) {
                    orRetry { netease.playlistPreview(playlist.id, SECTION_SONG_LIMIT)?.second.orEmpty() }
                }
            }
        } else {
            emptyList()
        }

        // 推荐歌单：个性化 / 每日推荐歌单（登录）/ 精品 / 热门 / ACG。
        val playlistSources = listOf(
            PlaylistSource("personalized", "个性化推荐", false),
            PlaylistSource("daily", "每日推荐歌单", true),
            PlaylistSource("highQuality", "精品歌单", false),
            PlaylistSource("hot", "热门歌单", false),
            PlaylistSource("acg", "ACG 歌单", false),
        )
        val playlistDeferred = if (neteaseEnabled) {
            playlistSources
                .filter { !it.loginOnly || loggedIn }
                .map { source ->
                    source to fetch(source.title) {
                        orRetry {
                            when (source.id) {
                                "personalized" -> netease.recommendedPlaylists(PLAYLIST_LIMIT)
                                "daily" -> netease.dailyRecommendedPlaylists()
                                "highQuality" -> netease.highQualityPlaylists(PLAYLIST_LIMIT)
                                "hot" -> netease.playlistSquare("全部", PLAYLIST_LIMIT)
                                else -> netease.playlistSquare("ACG", PLAYLIST_LIMIT)
                            }
                        }
                    }
                }
        } else {
            emptyList()
        }

        // B 站音乐：热门榜 / 原创榜 / 音乐分区热门视频。
        val biliRankHotDeferred = if (bilibiliEnabled) {
            fetch("B站音乐热门榜") { bilibili.musicRankSongs(listType = 1, limit = SECTION_SONG_LIMIT) }
        } else null
        val biliRankOriginalDeferred = if (bilibiliEnabled) {
            fetch("B站音乐原创榜") { bilibili.musicRankSongs(listType = 2, limit = SECTION_SONG_LIMIT) }
        } else null
        val biliHotDeferred = if (bilibiliEnabled) {
            fetch("B站热门音乐") { bilibili.musicHotVideos(SECTION_SONG_LIMIT) }
        } else null

        // ---- 汇总 ----
        val guessYouLike = guessYouLikeDeferred?.await().orEmpty()
        val radarCollections = radarDeferred.mapNotNull { it.await() }
        val dailySongs = dailySongsDeferred?.await().orEmpty()
        val privateFmSongs = privateFmDeferred?.await().orEmpty()
        val newSongs = newSongsDeferred?.await().orEmpty()
        val rankSongs = rankDeferred.map { (playlist, deferred) ->
            playlist.title to deferred.await().orEmpty()
        }
        val playlists = playlistDeferred.map { (source, deferred) ->
            source.title to deferred.await().orEmpty()
        }

        val biliGroups = mutableListOf<HomeGroupItem>()
        biliRankHotDeferred?.await()?.takeIf { it.isNotEmpty() }?.let {
            biliGroups += HomeGroupItem("hotRank", "热门榜", "songs", total = it.size, songs = it)
        }
        biliRankOriginalDeferred?.await()?.takeIf { it.isNotEmpty() }?.let {
            biliGroups += HomeGroupItem("originalRank", "原创榜", "songs", total = it.size, songs = it)
        }
        biliHotDeferred?.await()?.takeIf { it.isNotEmpty() }?.let {
            biliGroups += HomeGroupItem("hotVideos", "热门音乐", "songs", total = it.size, songs = it)
        }

        if (!neteaseEnabled) {
            errors += "网易云音源已在设置中禁用"
        } else if (!loggedIn) {
            errors += "登录网易云后可解锁每日推荐、私人 FM 和每日推荐歌单"
        }

        val sections = mutableListOf<HomeSectionItem>()
        if (continuePlaying.isNotEmpty()) {
            sections += HomeSectionItem(
                id = "continuePlaying",
                title = "继续播放",
                kind = "songs",
                icon = "history",
                songs = continuePlaying,
            )
        }
        if (guessYouLike.isNotEmpty()) {
            sections += HomeSectionItem(
                id = "guessYouLike",
                title = "猜你喜欢",
                kind = "songs",
                icon = "auto_awesome",
                songs = guessYouLike,
            )
        }
        if (radarCollections.isNotEmpty()) {
            sections += HomeSectionItem(
                id = "radar",
                title = "雷达歌单",
                kind = "collections",
                icon = "radar",
                collections = radarCollections,
            )
        }
        if (dailySongs.isNotEmpty()) {
            sections += HomeSectionItem(
                id = "dailyRecommend",
                title = "每日推荐",
                kind = "songs",
                icon = "today",
                songs = dailySongs,
            )
        }
        if (privateFmSongs.isNotEmpty()) {
            sections += HomeSectionItem(
                id = "privateFm",
                title = "私人 FM",
                kind = "songs",
                icon = "headphones",
                songs = privateFmSongs,
            )
        }
        if (biliGroups.isNotEmpty()) {
            sections += HomeSectionItem(
                id = "biliMusic",
                title = "B站音乐",
                kind = "groups",
                icon = "smart_display",
                groups = biliGroups,
            )
        }
        val rankGroups = rankSongs
            .filter { (_, songs) -> songs.isNotEmpty() }
            .map { (title, songs) ->
                HomeGroupItem(
                    id = title,
                    title = title,
                    kind = "songs",
                    total = songs.size,
                    songs = songs,
                )
            }
        if (rankGroups.isNotEmpty()) {
            sections += HomeSectionItem(
                id = "ranks",
                title = "排行榜",
                kind = "groups",
                icon = "leaderboard",
                groups = rankGroups,
            )
        }
        if (newSongs.isNotEmpty()) {
            sections += HomeSectionItem(
                id = "newSongs",
                title = "推荐新歌",
                kind = "songs",
                icon = "new_releases",
                songs = newSongs,
            )
        }
        val playlistGroups = playlists
            .filter { (_, collections) -> collections.isNotEmpty() }
            .map { (title, collections) ->
                HomeGroupItem(
                    id = title,
                    title = title,
                    kind = "collections",
                    total = collections.size,
                    collections = collections,
                )
            }
        if (playlistGroups.isNotEmpty()) {
            sections += HomeSectionItem(
                id = "playlists",
                title = "推荐歌单",
                kind = "groups",
                icon = "playlist_play",
                groups = playlistGroups,
            )
        }

        // 兼容旧字段：热歌榜单曲、雷达歌单、热门歌单仍然单独下发一份。
        val hotTracks = rankSongs.firstOrNull { it.first == "热歌榜" }?.second.orEmpty()
        val hotLists = playlists.firstOrNull { it.first == "热门歌单" }?.second.orEmpty()

        HomePayload(
            continuePlaying = HomeSongsBox(continuePlaying),
            guessYouLike = HomeSongsBox(guessYouLike),
            hotTracks = HomeSongsBox(hotTracks),
            newSongs = HomeSongsBox(newSongs),
            radar = HomeCollectionsBox(radarCollections),
            hotLists = HomeCollectionsBox(hotLists),
            sections = sections,
            errors = errors.toList(),
            loggedIn = loggedIn,
        )
    }
}

/** 首页单栏目：`kind = songs | collections | groups`，`groups` 时前端渲染成可切换的 tab 组。 */
@Serializable
data class HomeSectionItem(
    val id: String,
    val title: String,
    val kind: String,
    val icon: String = "",
    val songs: List<Song> = emptyList(),
    val collections: List<OnlineCollection> = emptyList(),
    val groups: List<HomeGroupItem> = emptyList(),
)

/** tab 组里的一个子栏目（例如「热歌榜」或「精品歌单」）。 */
@Serializable
data class HomeGroupItem(
    val id: String,
    val title: String,
    val kind: String,
    val total: Int = 0,
    val songs: List<Song> = emptyList(),
    val collections: List<OnlineCollection> = emptyList(),
)

@Serializable
data class HomeSongsBox(
    val songs: List<Song> = emptyList(),
)

@Serializable
data class HomeCollectionsBox(
    val collections: List<OnlineCollection> = emptyList(),
)

/** `online.home` 的完整返回体；旧字段（guessYouLike/hotTracks/…）保持原形状。 */
@Serializable
data class HomePayload(
    val continuePlaying: HomeSongsBox = HomeSongsBox(),
    val guessYouLike: HomeSongsBox = HomeSongsBox(),
    val hotTracks: HomeSongsBox = HomeSongsBox(),
    val newSongs: HomeSongsBox = HomeSongsBox(),
    val radar: HomeCollectionsBox = HomeCollectionsBox(),
    val hotLists: HomeCollectionsBox = HomeCollectionsBox(),
    val sections: List<HomeSectionItem> = emptyList(),
    val errors: List<String> = emptyList(),
    val loggedIn: Boolean = false,
)
