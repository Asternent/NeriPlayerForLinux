package moe.ouom.neriplayer.desktop.core

import kotlinx.serialization.Serializable

/** 评论来源平台。只由歌曲的逻辑音源决定，与播放地址回退结果无关。 */
@Serializable
enum class CommentPlatform {
    NETEASE,
    BILIBILI;

    val displayName: String
        get() = when (this) {
            NETEASE -> "网易云音乐"
            BILIBILI -> "哔哩哔哩"
        }
}

/** 评论排序方式；网易云额外支持推荐排序，桌面端先用「热门 / 最新」两种。 */
@Serializable
enum class CommentSort {
    HOT,
    NEWEST;
}

/** 字符串参数 → 排序方式；未知值按热门处理，避免前端拼错参数就报错。 */
fun commentSortOf(raw: String?): CommentSort = when (raw?.trim()?.lowercase()) {
    "new", "newest", "time" -> CommentSort.NEWEST
    else -> CommentSort.HOT
}

/** 被回复的楼中楼引用（网易云 beReplied）。 */
@Serializable
data class CommentQuote(
    val nickname: String = "",
    /** 被引用的评论已删除时为 null。 */
    val content: String? = null,
)

/** 评论配图（Bilibili content.pictures）。 */
@Serializable
data class CommentImage(
    val url: String,
    val width: Int = 0,
    val height: Int = 0,
)

/**
 * 统一评论模型。各平台原始 JSON 只在 net/CommentApis.kt 里解析，不向 UI 泄漏平台字段。
 *
 * 时间戳已归一化为毫秒；`replyCount` 为 null 表示平台没给回复数，UI 不显示而不是显示 0。
 */
@Serializable
data class CommentItem(
    val id: String,
    /** 直接父评论 id（楼中楼回复才有）。 */
    val parentId: String? = null,
    /** 一级评论 id；楼中楼回复时等于所属根评论。 */
    val rootId: String? = null,
    val userId: String? = null,
    val nickname: String = "",
    val avatarUrl: String? = null,
    val content: String = "",
    val timeMs: Long = 0L,
    val likedCount: Long = 0L,
    val liked: Boolean = false,
    val replyCount: Long? = null,
    /** IP 属地（网易云 ipLocation / B 站 reply_control.location）。 */
    val location: String? = null,
    val userLevel: Int? = null,
    /** 楼中楼预览（列表接口随一级评论一起返回的前几条回复）。 */
    val replies: List<CommentItem> = emptyList(),
    val quotes: List<CommentQuote> = emptyList(),
    val images: List<CommentImage> = emptyList(),
)

/**
 * 一页评论。
 *
 * 网络/平台错误不抛异常，而是放进 [error] / [errorCode]，让前端能区分「暂无评论」与「加载失败」；
 * [source] 为 `"UNSUPPORTED"` 表示这首歌没有评论区（本地 / YouTube / 未知来源）。
 */
@Serializable
data class CommentPage(
    val comments: List<CommentItem> = emptyList(),
    val page: Int = 1,
    val pageSize: Int = 20,
    val hasMore: Boolean = false,
    val total: Long? = null,
    val source: String = "UNSUPPORTED",
    /** 下一页游标（网易云最新排序 / 楼中楼分页必须带回来，B 站按页码翻页时为 null）。 */
    val cursor: String? = null,
    val loginRequired: Boolean = false,
    val error: String? = null,
    val errorCode: String? = null,
)

/** 歌曲 → 评论逻辑音源；不支持评论的来源返回 null。 */
data class CommentTarget(
    val platform: CommentPlatform,
    /** 网易云歌曲 id。 */
    val neteaseSongId: Long = 0L,
    /** B 站 bvid（Song.remoteId），aid 由评论客户端懒解析。 */
    val bvid: String = "",
    val songTitle: String = "",
) {
    val isNetease: Boolean get() = platform == CommentPlatform.NETEASE
}

/** 按歌曲逻辑音源解析评论目标：网易云 / B 站以外的来源一律不支持。 */
fun resolveCommentTarget(song: Song?): CommentTarget? {
    if (song == null) return null
    return when (song.source) {
        MediaSource.NETEASE -> {
            val id = song.remoteId?.trim()?.toLongOrNull()?.takeIf { it > 0L } ?: return null
            CommentTarget(CommentPlatform.NETEASE, neteaseSongId = id, songTitle = song.title)
        }

        MediaSource.BILIBILI -> {
            val bvid = song.remoteId?.trim()?.takeIf { it.isNotBlank() } ?: return null
            CommentTarget(CommentPlatform.BILIBILI, bvid = bvid, songTitle = song.title)
        }

        MediaSource.LOCAL, MediaSource.YOUTUBE -> null
    }
}

/** 单条评论正文长度上限：网易云 140，B 站 1000。 */
fun commentLengthLimit(platform: CommentPlatform): Int = when (platform) {
    CommentPlatform.NETEASE -> 140
    CommentPlatform.BILIBILI -> 1000
}
