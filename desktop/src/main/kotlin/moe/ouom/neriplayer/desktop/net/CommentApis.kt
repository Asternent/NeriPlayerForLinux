package moe.ouom.neriplayer.desktop.net

import moe.ouom.neriplayer.desktop.core.CommentImage
import moe.ouom.neriplayer.desktop.core.CommentItem
import moe.ouom.neriplayer.desktop.core.CommentPage
import moe.ouom.neriplayer.desktop.core.CommentQuote
import moe.ouom.neriplayer.desktop.core.CommentSort
import moe.ouom.neriplayer.desktop.core.CommentTarget
import java.util.concurrent.ConcurrentHashMap

private const val NETEASE_BASE = "https://music.163.com"
/** 点赞等登录后才可用的 api 接口在 interface3 上不会被 418 风控拦截。 */
private const val NETEASE_INTERFACE_BASE = "https://interface3.music.163.com"
private const val BILI_BASE = "https://api.bilibili.com"
private const val NETEASE_REFERER = "https://music.163.com/"
private const val BILI_REFERER = "https://www.bilibili.com/"
private const val NETEASE_THREAD_PREFIX = "R_SO_4_"

/** 评论接口请求/解析失败。code 与协议错误码同名（network / permission / api / unsupported）。 */
class CommentApiException(val code: String, override val message: String) : RuntimeException(message)

/** 需要登录态才能执行（点赞 / 发表评论 / 删除），由协议层转成 requiresLogin 结果。 */
class CommentLoginRequiredException(message: String) : RuntimeException(message)

private fun normalizeImageUrl(url: String?): String? {
    val value = url?.trim().orEmpty()
    if (value.isEmpty()) return null
    return when {
        value.startsWith("//") -> "https:$value"
        value.startsWith("http://") -> "https://" + value.removePrefix("http://")
        else -> value
    }
}

private fun networkErrorPage(
    source: String,
    page: Int,
    pageSize: Int,
    message: String = "网络请求失败，请检查网络后重试",
): CommentPage = CommentPage(
    page = page,
    pageSize = pageSize,
    source = source,
    error = message,
    errorCode = "network",
)

private fun neteaseThreadId(songId: Long): String = "$NETEASE_THREAD_PREFIX$songId"

/**
 * 网易云评论接口。
 *
 * - 评论列表 `/api/v2/resource/comments`、楼中楼 `/api/resource/comment/floor/get` 都是公开 api 接口，
 *   匿名可读，不需要 weapi 加密（这也是桌面端能不做加密就实现「读评论」的原因）；
 * - 点赞 `/api/v1/comment/like`、发评论 `/api/resource/comments/add` 需要登录 Cookie，并且平台可能追加
 *   checkToken 校验；这里按登录态尽力实现，失败时把平台返回码暴露给前端；
 * - Cookie 复用 [HttpService] 的共享 jar（与扫码登录面板同一份登录态）。
 */
class NeteaseCommentApi(private val http: HttpService) {

    private val headers = mapOf("Referer" to NETEASE_REFERER)

    fun isLoggedIn(): Boolean = cookies()["MUSIC_U"].orEmpty().isNotBlank()

    private fun cookies(): Map<String, String> = http.cookieSnapshot("netease")

    fun comments(
        target: CommentTarget,
        sort: CommentSort,
        page: Int,
        pageSize: Int,
        cursor: String?,
    ): CommentPage {
        val songId = target.neteaseSongId
        if (songId <= 0L) {
            return CommentPage(source = "NETEASE", page = page, pageSize = pageSize, error = "网易云歌曲 id 无效")
        }
        val sortType = if (sort == CommentSort.HOT) 2 else 3
        val offset = (page - 1).toLong() * pageSize
        // 热门排序用 normalHot#offset 直接翻页；最新排序必须用上一页响应里的 cursor（时间边界），
        // 不能用 offset 代替，否则会漏评论/重复评论。
        val requestCursor = when {
            sortType == 2 -> "normalHot#$offset"
            page <= 1 -> "0"
            else -> cursor?.takeIf { it.isNotBlank() }
                ?: return CommentPage(
                    page = page,
                    pageSize = pageSize,
                    source = "NETEASE",
                    error = "加载最新评论需要携带上一页返回的 cursor",
                    errorCode = "cursor_required",
                )
        }

        val text = http.postForm(
            url = "$NETEASE_BASE/api/v2/resource/comments",
            form = mapOf(
                "threadId" to neteaseThreadId(songId),
                "pageNo" to page.toString(),
                "pageSize" to pageSize.toString(),
                "sortType" to sortType.toString(),
                "cursor" to requestCursor,
                "showInner" to "true",
            ),
            headers = headers,
        ) ?: return networkErrorPage("NETEASE", page, pageSize)

        val root = NeriJsonParser.parse(text).asObject()
            ?: return CommentPage(source = "NETEASE", page = page, pageSize = pageSize, error = "评论接口返回内容无法解析", errorCode = "api")
        val code = root.int("code") ?: -1
        if (code != 200) return neteaseErrorPage(code, page, pageSize)
        val data = root.obj("data")
            ?: return CommentPage(source = "NETEASE", page = page, pageSize = pageSize, error = "评论接口没有返回数据", errorCode = "api")

        val comments = data.array("comments")?.objects()?.map { parseComment(it) }.orEmpty()
        val total = data.long("totalCount")?.takeIf { it >= 0L }
        val hasMore = data.bool("hasMore")
            ?: total?.let { page.toLong() * pageSize < it }
            ?: (comments.size >= pageSize)
        // 翻页接口偶发返回空页但 hasMore=true，前端会误以为还有内容；这里统一按实际条数兜底
        if (hasMore && comments.isEmpty()) {
            return CommentPage(
                page = page,
                pageSize = pageSize,
                source = "NETEASE",
                total = total,
                hasMore = false,
                cursor = data.str("cursor"),
                error = "没有更多评论了",
                errorCode = "api",
            )
        }
        return CommentPage(
            comments = comments,
            page = page,
            pageSize = pageSize,
            hasMore = hasMore,
            total = total,
            source = "NETEASE",
            cursor = data.str("cursor"),
        )
    }

    fun replies(
        target: CommentTarget,
        rootId: String,
        page: Int,
        pageSize: Int,
        cursor: String?,
    ): CommentPage {
        val songId = target.neteaseSongId
        val rootCommentId = rootId.toLongOrNull()?.takeIf { it > 0L }
        if (songId <= 0L || rootCommentId == null) {
            return CommentPage(source = "NETEASE", page = page, pageSize = pageSize, error = "评论 id 无效")
        }
        // 楼中楼按 time 游标翻页：第一页传 -1，之后把响应里的 data.time 原样带回来
        val time = if (page <= 1) {
            "-1"
        } else {
            cursor?.takeIf { it.isNotBlank() }
                ?: return CommentPage(
                    page = page,
                    pageSize = pageSize,
                    source = "NETEASE",
                    error = "加载更多回复需要携带上一页返回的 cursor",
                    errorCode = "cursor_required",
                )
        }
        val text = http.postForm(
            url = "$NETEASE_BASE/api/resource/comment/floor/get",
            form = mapOf(
                "threadId" to neteaseThreadId(songId),
                "parentCommentId" to rootCommentId.toString(),
                "limit" to pageSize.toString(),
                "time" to time,
            ),
            headers = headers,
        ) ?: return networkErrorPage("NETEASE", page, pageSize)

        val root = NeriJsonParser.parse(text).asObject()
            ?: return CommentPage(source = "NETEASE", page = page, pageSize = pageSize, error = "回复接口返回内容无法解析", errorCode = "api")
        val code = root.int("code") ?: -1
        if (code != 200) return neteaseErrorPage(code, page, pageSize)
        val data = root.obj("data")
            ?: return CommentPage(source = "NETEASE", page = page, pageSize = pageSize, error = "回复接口没有返回数据", errorCode = "api")
        val comments = data.array("comments")?.objects()?.map { parseComment(it) }.orEmpty()
        val nextCursor = data.long("time")?.takeIf { it > 0L }?.toString()
            ?: comments.lastOrNull()?.timeMs?.takeIf { it > 0L }?.toString()
        val total = data.long("totalCount")?.takeIf { it >= 0L }
        val hasMore = (data.bool("hasMore") ?: false) && nextCursor != null && nextCursor != cursor
        return CommentPage(
            comments = comments,
            page = page,
            pageSize = pageSize,
            hasMore = hasMore,
            total = total,
            source = "NETEASE",
            cursor = nextCursor,
        )
    }

    fun setLiked(commentId: String, songId: Long, liked: Boolean) {
        if (songId <= 0L) throw CommentApiException("api", "网易云歌曲 id 无效")
        if (!isLoggedIn()) throw CommentLoginRequiredException("请先登录网易云音乐账号")
        val text = http.postForm(
            url = "$NETEASE_INTERFACE_BASE/api/v1/comment/like",
            form = mapOf(
                "threadId" to neteaseThreadId(songId),
                "commentId" to commentId,
                "like" to liked.toString(),
                "csrf_token" to cookies()["__csrf"].orEmpty(),
            ),
            headers = headers,
        ) ?: throw CommentApiException("network", "点赞请求失败，请检查网络")
        val code = NeriJsonParser.parse(text).asObject()?.int("code") ?: -1
        if (code == 200) return
        throw neteaseFailure(code, "点赞")
    }

    /** 发表一级评论；成功时尽量返回平台回传的新评论对象。 */
    fun post(songId: Long, content: String): CommentItem? {
        if (songId <= 0L) throw CommentApiException("api", "网易云歌曲 id 无效")
        if (!isLoggedIn()) throw CommentLoginRequiredException("请先登录网易云音乐账号")
        val text = http.postForm(
            url = "$NETEASE_BASE/api/resource/comments/add",
            form = mapOf(
                "threadId" to neteaseThreadId(songId),
                "content" to content,
                "csrf_token" to cookies()["__csrf"].orEmpty(),
            ),
            headers = headers,
        ) ?: throw CommentApiException("network", "发表评论失败，请检查网络")
        val root = NeriJsonParser.parse(text).asObject()
            ?: throw CommentApiException("api", "发表评论失败：返回内容无法解析")
        val code = root.int("code") ?: -1
        if (code != 200 && code != 0) throw neteaseFailure(code, "发表评论")
        return root.obj("data")?.obj("comment")?.let { parseComment(it) }
    }

    private fun neteaseFailure(code: Int, action: String): RuntimeException = when (code) {
        301, 302, 315, 401, 403, -460 -> CommentLoginRequiredException("登录态已失效，请重新登录网易云音乐账号")
        else -> CommentApiException("api", "${action}失败（网易云返回码 $code）")
    }

    private fun neteaseErrorPage(code: Int, page: Int, pageSize: Int): CommentPage {
        val (errorCode, message) = when (code) {
            301, 302, 315, 401, 403, -460 -> "permission" to "需要登录或登录态已失效（网易云返回码 $code）"
            404 -> "not_found" to "歌曲不存在或评论区已关闭"
            in 500..599 -> "server" to "网易云服务异常（$code），请稍后重试"
            else -> "api" to "评论接口返回错误（网易云返回码 $code）"
        }
        return CommentPage(
            page = page,
            pageSize = pageSize,
            source = "NETEASE",
            loginRequired = errorCode == "permission",
            error = message,
            errorCode = errorCode,
        )
    }

    private fun parseComment(raw: kotlinx.serialization.json.JsonObject, includePreview: Boolean = true): CommentItem {
        val user = raw.obj("user")
        val showFloor = raw.obj("showFloorComment")
        val replyCount = raw.long("replyCount")?.takeIf { it >= 0L }
            ?: showFloor?.long("replyCount")?.takeIf { it >= 0L }
        val quotes = raw.array("beReplied")?.objects()?.map { quote ->
            CommentQuote(
                nickname = quote.obj("user")?.str("nickname").orEmpty(),
                // status=-5 表示被引用的评论已删除，此时 content 为 null
                content = if (quote.int("status") == -5) null else quote.str("content"),
            )
        }.orEmpty()
        val previews = if (includePreview) {
            showFloor?.array("comments")?.objects()?.map { parseComment(it, includePreview = false) }.orEmpty()
        } else {
            emptyList()
        }
        return CommentItem(
            id = raw.str("commentId").orEmpty(),
            parentId = raw.long("parentCommentId")?.takeIf { it > 0L }?.toString(),
            userId = user?.long("userId")?.takeIf { it > 0L }?.toString(),
            nickname = user?.str("nickname").orEmpty(),
            avatarUrl = normalizeImageUrl(user?.str("avatarUrl")),
            content = raw.str("content").orEmpty(),
            timeMs = raw.long("time") ?: 0L,
            likedCount = raw.long("likedCount") ?: 0L,
            liked = raw.bool("liked") ?: false,
            replyCount = replyCount,
            location = raw.str("ipLocation")?.takeIf { it.isNotBlank() },
            replies = previews,
            quotes = quotes,
        )
    }
}

/**
 * 哔哩哔哩评论接口（尽力实现）。
 *
 * 主评论走 legacy `/x/v2/reply`、楼中楼走 `/x/v2/reply/reply`：匿名只能读到热门预览，
 * 且「最新」排序在未登录时会被接口截断（page.num=0），这里把平台的匿名限制翻译成明确的错误提示；
 * 登录后的点赞 / 发表 / 删除走 web 端接口（csrf = bili_jct）。aid 由 bvid 懒解析并缓存。
 */
class BiliCommentApi(private val http: HttpService) {

    private val headers = mapOf("Referer" to BILI_REFERER)
    private val aidCache = ConcurrentHashMap<String, Long>()
    private var fingerprintReady = false

    fun isLoggedIn(): Boolean {
        val cookies = http.cookieSnapshot("bilibili")
        return cookies["SESSDATA"].orEmpty().isNotBlank() && cookies["bili_jct"].orEmpty().isNotBlank()
    }

    /**
     * 先访问一次视频页拿到 buvid3 / b_nut，再补 buvid4 指纹 Cookie。
     * 没有这些 Cookie 时 B 站会把评论接口当匿名风控请求处理，热评预览都拿不到。
     */
    private fun ensureFingerprint() {
        if (fingerprintReady) return
        fingerprintReady = true
        runCatching {
            http.get("https://www.bilibili.com/", headers, timeoutSeconds = 12)
            val spi = http.get("$BILI_BASE/x/frontend/finger/spi", headers)
            val data = NeriJsonParser.parse(spi.orEmpty()).asObject()?.obj("data")
            data?.str("b_3")?.takeIf { it.isNotBlank() }?.let { http.setCookie("api.bilibili.com", "buvid3", it) }
            data?.str("b_4")?.takeIf { it.isNotBlank() }?.let { http.setCookie("api.bilibili.com", "buvid4", it) }
        }
    }

    /** bvid → aid；也接受纯数字 id（少数历史歌曲的 remoteId 就是 aid）。 */
    fun resolveAid(bvid: String): Long? {
        val key = bvid.trim()
        if (key.isEmpty()) return null
        aidCache[key]?.let { return it }
        key.toLongOrNull()?.takeIf { it > 0L }?.let {
            aidCache[key] = it
            return it
        }
        ensureFingerprint()
        val text = http.get("$BILI_BASE/x/web-interface/view?bvid=${urlEncode(key)}", headers) ?: return null
        val root = NeriJsonParser.parse(text).asObject() ?: return null
        if ((root.int("code") ?: -1) != 0) return null
        val aid = root.obj("data")?.long("aid")?.takeIf { it > 0L } ?: return null
        aidCache[key] = aid
        return aid
    }

    fun comments(target: CommentTarget, sort: CommentSort, page: Int, pageSize: Int): CommentPage {
        val aid = resolveAid(target.bvid)
            ?: return CommentPage(
                source = "BILIBILI",
                page = page,
                pageSize = pageSize,
                error = "无法解析 B 站视频信息（bvid 无效或接口不可用）",
                errorCode = "not_found",
            )
        ensureFingerprint()
        val sortValue = if (sort == CommentSort.HOT) 1 else 0
        val text = http.get(
            "$BILI_BASE/x/v2/reply?type=1&oid=$aid&pn=$page&ps=$pageSize&sort=$sortValue",
            headers,
        ) ?: return networkErrorPage("BILIBILI", page, pageSize)
        val root = NeriJsonParser.parse(text).asObject()
            ?: return CommentPage(source = "BILIBILI", page = page, pageSize = pageSize, error = "评论接口返回内容无法解析", errorCode = "api")
        val code = root.int("code") ?: -1
        if (code != 0) return biliErrorPage(code, page, pageSize)

        val data = root.obj("data")
            ?: return CommentPage(source = "BILIBILI", page = page, pageSize = pageSize, error = "评论接口没有返回数据", errorCode = "api")
        val pageObject = data.obj("page")
        // 匿名访问「最新」排序时接口会返回 num=0/size=0，不能把这种受限响应当成「评论已读完」
        if (pageObject == null || (pageObject.int("num") ?: 0) != page || (pageObject.int("size") ?: 0) <= 0) {
            return CommentPage(
                page = page,
                pageSize = pageSize,
                source = "BILIBILI",
                error = if (!isLoggedIn()) {
                    "B 站未登录时只能读取热门评论预览，登录后可查看更多"
                } else {
                    "B 站评论分页返回异常（请求第 $page 页）"
                },
                errorCode = if (!isLoggedIn()) "permission" else "api",
                loginRequired = !isLoggedIn(),
            )
        }
        val comments = data.array("replies")?.objects()?.map { parseComment(it) }.orEmpty()
        val total = pageObject.long("count")?.takeIf { it >= 0L }
        val loadedThrough = (page - 1).toLong() * pageSize + comments.size
        // 匿名接口只回 3 条热门预览却谎报 hasMore，这里直接按「没有更多」处理，避免前端反复请求失败
        val anonymousPreview = !isLoggedIn() && comments.isNotEmpty() && comments.size < pageSize
        val hasMore = if (anonymousPreview) false else total?.let { loadedThrough < it } ?: (comments.size >= pageSize)
        return CommentPage(
            comments = comments,
            page = page,
            pageSize = pageSize,
            hasMore = hasMore,
            total = total,
            source = "BILIBILI",
        )
    }

    fun replies(
        target: CommentTarget,
        rootId: String,
        page: Int,
        pageSize: Int,
    ): CommentPage {
        val aid = resolveAid(target.bvid)
            ?: return CommentPage(source = "BILIBILI", page = page, pageSize = pageSize, error = "无法解析 B 站视频信息", errorCode = "not_found")
        val rootCommentId = rootId.toLongOrNull()?.takeIf { it > 0L }
            ?: return CommentPage(source = "BILIBILI", page = page, pageSize = pageSize, error = "评论 id 无效")
        ensureFingerprint()
        val text = http.get(
            "$BILI_BASE/x/v2/reply/reply?type=1&oid=$aid&root=$rootCommentId&pn=$page&ps=$pageSize",
            headers,
        ) ?: return networkErrorPage("BILIBILI", page, pageSize)
        val root = NeriJsonParser.parse(text).asObject()
            ?: return CommentPage(source = "BILIBILI", page = page, pageSize = pageSize, error = "回复接口返回内容无法解析", errorCode = "api")
        val code = root.int("code") ?: -1
        if (code != 0) return biliErrorPage(code, page, pageSize)
        val data = root.obj("data")
            ?: return CommentPage(source = "BILIBILI", page = page, pageSize = pageSize, error = "回复接口没有返回数据", errorCode = "api")
        val pageObject = data.obj("page")
        if (pageObject == null || (pageObject.int("num") ?: 0) != page || (pageObject.int("size") ?: 0) <= 0) {
            return CommentPage(
                page = page,
                pageSize = pageSize,
                source = "BILIBILI",
                error = if (!isLoggedIn()) "B 站未登录时只能读取第一页回复，登录后可继续翻页" else "回复分页返回异常",
                errorCode = if (!isLoggedIn()) "permission" else "api",
                loginRequired = !isLoggedIn(),
            )
        }
        val comments = data.array("replies")?.objects()?.map { parseComment(it) }.orEmpty()
        val total = pageObject.long("count")?.takeIf { it >= 0L }
        val loadedThrough = (page - 1).toLong() * pageSize + comments.size
        val anonymousPreview = !isLoggedIn() && comments.isNotEmpty() && comments.size < pageSize
        val hasMore = if (anonymousPreview) false else total?.let { loadedThrough < it } ?: (comments.size >= pageSize)
        return CommentPage(
            comments = comments,
            page = page,
            pageSize = pageSize,
            hasMore = hasMore,
            total = total,
            source = "BILIBILI",
        )
    }

    fun setLiked(target: CommentTarget, commentId: String, liked: Boolean) {
        val aid = resolveAid(target.bvid) ?: throw CommentApiException("not_found", "无法解析 B 站视频信息")
        requireLogin()
        val csrf = http.cookieSnapshot("bilibili")["bili_jct"].orEmpty()
        val text = http.postForm(
            url = "$BILI_BASE/x/v2/reply/action",
            form = mapOf(
                "type" to "1",
                "oid" to aid.toString(),
                "rpid" to commentId,
                "action" to if (liked) "1" else "0",
                "csrf" to csrf,
            ),
            headers = headers,
        ) ?: throw CommentApiException("network", "点赞请求失败，请检查网络")
        val code = NeriJsonParser.parse(text).asObject()?.int("code") ?: -1
        if (code == 0) return
        throw biliFailure(code, "点赞")
    }

    fun post(target: CommentTarget, content: String): CommentItem? {
        val aid = resolveAid(target.bvid) ?: throw CommentApiException("not_found", "无法解析 B 站视频信息")
        requireLogin()
        val csrf = http.cookieSnapshot("bilibili")["bili_jct"].orEmpty()
        val text = http.postForm(
            url = "$BILI_BASE/x/v2/reply/add",
            form = mapOf(
                "type" to "1",
                "oid" to aid.toString(),
                "message" to content,
                "root" to "0",
                "parent" to "0",
                "plat" to "1",
                "statistics" to "{\"appId\":100,\"platform\":5}",
                "gaia_source" to "main_web",
                "csrf" to csrf,
            ),
            headers = headers,
        ) ?: throw CommentApiException("network", "发表评论失败，请检查网络")
        val root = NeriJsonParser.parse(text).asObject()
            ?: throw CommentApiException("api", "发表评论失败：返回内容无法解析")
        val code = root.int("code") ?: -1
        if (code != 0) throw biliFailure(code, "发表评论")
        return root.obj("data")?.obj("reply")?.let { parseComment(it) }
    }

    fun delete(target: CommentTarget, commentId: String) {
        val aid = resolveAid(target.bvid) ?: throw CommentApiException("not_found", "无法解析 B 站视频信息")
        requireLogin()
        val csrf = http.cookieSnapshot("bilibili")["bili_jct"].orEmpty()
        val text = http.postForm(
            url = "$BILI_BASE/x/v2/reply/del",
            form = mapOf(
                "type" to "1",
                "oid" to aid.toString(),
                "rpid" to commentId,
                "csrf" to csrf,
            ),
            headers = headers,
        ) ?: throw CommentApiException("network", "删除评论失败，请检查网络")
        val code = NeriJsonParser.parse(text).asObject()?.int("code") ?: -1
        if (code == 0) return
        throw biliFailure(code, "删除评论")
    }

    private fun requireLogin() {
        if (!isLoggedIn()) throw CommentLoginRequiredException("请先登录哔哩哔哩账号")
    }

    private fun biliFailure(code: Int, action: String): RuntimeException = when (code) {
        -101, -102, -111, -403, -412, 12004 -> CommentLoginRequiredException("登录态已失效，请重新登录哔哩哔哩账号")
        12061, 12002 -> CommentApiException("closed", "评论区已关闭")
        -404, 12006 -> CommentApiException("not_found", "评论或视频不存在")
        else -> CommentApiException("api", "${action}失败（B 站返回码 $code）")
    }

    private fun biliErrorPage(code: Int, page: Int, pageSize: Int): CommentPage {
        val (errorCode, message) = when (code) {
            -101, -102, -111, -403, -412, 12004 -> "permission" to "需要登录或登录态已失效（B 站返回码 $code）"
            12061, 12002 -> "closed" to "评论区已关闭"
            -404, 12006 -> "not_found" to "视频不存在或评论已删除"
            in 500..599 -> "server" to "哔哩哔哩服务异常（$code），请稍后重试"
            else -> "api" to "评论接口返回错误（B 站返回码 $code）"
        }
        return CommentPage(
            page = page,
            pageSize = pageSize,
            source = "BILIBILI",
            loginRequired = errorCode == "permission",
            error = message,
            errorCode = errorCode,
        )
    }

    private fun parseComment(raw: kotlinx.serialization.json.JsonObject, includePreview: Boolean = true): CommentItem {
        val member = raw.obj("member")
        val content = raw.obj("content")
        val levelInfo = member?.obj("level_info")
        val replyCount = raw.long("rcount")?.takeIf { it >= 0L }
            ?: raw.long("count")?.takeIf { it >= 0L }
        // B 站 ctime 是秒级时间戳，统一换算成毫秒
        val timeMs = raw.long("ctime")?.takeIf { it > 0L }?.let { it * 1000L } ?: 0L
        val previews = if (includePreview) {
            raw.array("replies")?.objects()?.map { parseComment(it, includePreview = false) }.orEmpty()
        } else {
            emptyList()
        }
        val images = content?.array("pictures")?.objects()?.mapNotNull { picture ->
            val url = normalizeImageUrl(picture.str("img_src")) ?: return@mapNotNull null
            CommentImage(
                url = url,
                width = picture.int("img_width") ?: 0,
                height = picture.int("img_height") ?: 0,
            )
        }.orEmpty()
        return CommentItem(
            id = raw.long("rpid")?.toString().orEmpty(),
            parentId = raw.long("parent")?.takeIf { it > 0L }?.toString(),
            rootId = raw.long("root")?.takeIf { it > 0L }?.toString(),
            userId = member?.long("mid")?.takeIf { it > 0L }?.toString()
                ?: member?.str("mid")?.takeIf { it.isNotBlank() },
            nickname = member?.str("uname").orEmpty(),
            avatarUrl = normalizeImageUrl(member?.str("avatar")),
            content = content?.str("message").orEmpty(),
            timeMs = timeMs,
            likedCount = raw.long("like") ?: 0L,
            liked = (raw.int("action") ?: 0) == 1,
            replyCount = replyCount,
            location = raw.obj("reply_control")?.str("location")?.takeIf { it.isNotBlank() },
            userLevel = levelInfo?.int("current_level")?.takeIf { it > 0 },
            replies = previews,
            images = images,
        )
    }
}
