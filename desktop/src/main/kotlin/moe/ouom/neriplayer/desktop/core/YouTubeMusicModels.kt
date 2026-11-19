package moe.ouom.neriplayer.desktop.core

import kotlinx.serialization.Serializable

/**
 * YouTube Music 音源的模型。
 *
 * 播放/搜索复用的通用模型（[Song] / [OnlineCollection] / [OnlineArtist]）仍放在 Models.kt；
 * 这里只放 YouTube 专有的结构：状态、首页栏目、聚合搜索结果、详情页载荷。
 * `Song.remoteId` 存 videoId，`OnlineCollection.id` 存 browseId（专辑 `MPRE…` / 歌单 `VL…`）。
 */

/** `ytmusic.status` 的结果：登录态与匿名可用能力。 */
@Serializable
data class YouTubeStatus(
    val loggedIn: Boolean = false,
    /** Cookie 罐里是否有登录相关 Cookie（SAPISID / SID 等），不代表一定有效。 */
    val hasCookies: Boolean = false,
    /** 匿名可用（搜索/浏览公开内容），当前实现下始终尽力尝试。 */
    val anonymous: Boolean = true,
    /** 是否拿到了 visitorData（Innertube 请求需要）。 */
    val visitorDataAvailable: Boolean = false,
    /** 播放直链解析是否可用（严格来说需要登录 Cookie，未登录时为 false）。 */
    val playbackAvailable: Boolean = false,
    val account: AccountInfo? = null,
    /** 给 UI 用的一句状态说明；无异常时为 null。 */
    val message: String? = null,
)

/** 首页栏目里的一张卡：歌曲 / 专辑 / 歌手 / 歌单。 */
@Serializable
data class YouTubeHomeItem(
    /** `song` / `album` / `artist` / `playlist` */
    val kind: String,
    val song: Song? = null,
    val collection: OnlineCollection? = null,
    val artist: OnlineArtist? = null,
)

/** 首页横向栏目。 */
@Serializable
data class YouTubeHomeSection(
    val id: String = "",
    val title: String = "",
    val items: List<YouTubeHomeItem> = emptyList(),
)

/** `ytmusic.search` 的聚合结果。 */
@Serializable
data class YouTubeSearchResult(
    val songs: List<Song> = emptyList(),
    val albums: List<OnlineCollection> = emptyList(),
    val artists: List<OnlineArtist> = emptyList(),
    val playlists: List<OnlineCollection> = emptyList(),
)

/** `ytmusic.playlist` / `ytmusic.album` 的详情载荷。 */
@Serializable
data class YouTubeCollectionDetail(
    val title: String = "",
    val coverUrl: String? = null,
    val description: String = "",
    val creator: String = "",
    val trackCount: Int = 0,
    val songs: List<Song> = emptyList(),
    val collection: OnlineCollection? = null,
)

/** `ytmusic.artist` 的详情载荷。 */
@Serializable
data class YouTubeArtistDetail(
    val name: String = "",
    val avatarUrl: String? = null,
    val description: String = "",
    val songCount: Int = 0,
    val songs: List<Song> = emptyList(),
    val albums: List<OnlineCollection> = emptyList(),
    val artist: OnlineArtist? = null,
)

/** 解析出来的音频直链（临时地址，带过期时间）。 */
@Serializable
data class YouTubeAudio(
    val url: String,
    val mimeType: String? = null,
    val bitrateKbps: Int = 0,
    val durationMs: Long = 0L,
    val userAgent: String = "",
)
