package moe.ouom.neriplayer.desktop.backend.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import moe.ouom.neriplayer.desktop.backend.CoreServer
import moe.ouom.neriplayer.desktop.backend.WireJson
import moe.ouom.neriplayer.desktop.backend.badRequest
import moe.ouom.neriplayer.desktop.backend.boolOrNull
import moe.ouom.neriplayer.desktop.backend.encodeJson
import moe.ouom.neriplayer.desktop.backend.intOrNull
import moe.ouom.neriplayer.desktop.backend.log
import moe.ouom.neriplayer.desktop.backend.objectOrNull
import moe.ouom.neriplayer.desktop.backend.stringOrNull
import moe.ouom.neriplayer.desktop.core.AppContainer
import moe.ouom.neriplayer.desktop.core.CommentPage
import moe.ouom.neriplayer.desktop.core.CommentPlatform
import moe.ouom.neriplayer.desktop.core.CommentTarget
import moe.ouom.neriplayer.desktop.core.Song
import moe.ouom.neriplayer.desktop.core.commentLengthLimit
import moe.ouom.neriplayer.desktop.core.commentSortOf
import moe.ouom.neriplayer.desktop.core.resolveCommentTarget
import moe.ouom.neriplayer.desktop.net.BiliCommentApi
import moe.ouom.neriplayer.desktop.net.CommentApiException
import moe.ouom.neriplayer.desktop.net.CommentLoginRequiredException
import moe.ouom.neriplayer.desktop.net.NeteaseCommentApi

/**
 * 评论区（桌面版）。
 *
 * 参考 Android 版 `modules/platform/.../comments` 的分层：平台 JSON 只在 net/CommentApis.kt 解析，
 * 这里只做参数校验、平台分派与错误装箱。读评论走各平台公开接口（匿名可用）；
 * 点赞 / 发表 / 删除需要登录 Cookie，未登录时返回 `requiresLogin` 而不是抛协议错误，
 * 前端据此显示登录引导。
 *
 * 注册由集成负责人加到 BackendMain.kt（本文件自带 `register(server, container)`）。
 * 命令与错误码见 desktop/docs/protocol-comments.md。
 */
object CommentsApi {

    private const val DEFAULT_PAGE_SIZE = 20

    fun register(server: CoreServer, container: AppContainer) {
        val http = container.online.httpService
        val netease = NeteaseCommentApi(http)
        val bili = BiliCommentApi(http)

        // ---------------------------------------------------------------- 评论列表
        server.on("comments.list") { params ->
            val song = params.requireSong()
            val target = resolveCommentTarget(song)
            val sort = commentSortOf(params.stringOrNull("sort"))
            val page = (params.intOrNull("page") ?: 1).coerceAtLeast(1)
            val pageSize = (params.intOrNull("pageSize") ?: DEFAULT_PAGE_SIZE).coerceIn(1, 50)
            val cursor = params.stringOrNull("cursor")?.takeIf { it.isNotBlank() }
            if (target == null) {
                encodeJson(unsupportedPage(song, page, pageSize))
            } else {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        when (target.platform) {
                            CommentPlatform.NETEASE -> netease.comments(target, sort, page, pageSize, cursor)
                            CommentPlatform.BILIBILI -> bili.comments(target, sort, page, pageSize)
                        }
                    }.getOrElse { error ->
                        log("comments.list failed (${target.platform}): ${error.message}")
                        internalErrorPage(target, page, pageSize, error)
                    }
                }
                encodeJson(result)
            }
        }

        // ---------------------------------------------------------------- 楼中楼回复
        server.on("comments.replies") { params ->
            val song = params.requireSong()
            val commentId = params.stringOrNull("commentId")?.trim()?.takeIf { it.isNotEmpty() }
                ?: badRequest("缺少 commentId")
            val target = resolveCommentTarget(song)
            val page = (params.intOrNull("page") ?: 1).coerceAtLeast(1)
            val pageSize = (params.intOrNull("pageSize") ?: DEFAULT_PAGE_SIZE).coerceIn(1, 20)
            val cursor = params.stringOrNull("cursor")?.takeIf { it.isNotBlank() }
            if (target == null) {
                encodeJson(unsupportedPage(song, page, pageSize))
            } else {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        when (target.platform) {
                            CommentPlatform.NETEASE -> netease.replies(target, commentId, page, pageSize, cursor)
                            CommentPlatform.BILIBILI -> bili.replies(target, commentId, page, pageSize)
                        }
                    }.getOrElse { error ->
                        log("comments.replies failed (${target.platform}): ${error.message}")
                        internalErrorPage(target, page, pageSize, error)
                    }
                }
                encodeJson(result)
            }
        }

        // ---------------------------------------------------------------- 点赞 / 取消点赞
        server.on("comments.like") { params ->
            val song = params.requireSong()
            val target = resolveCommentTarget(song)
            val commentId = params.stringOrNull("commentId")?.trim()?.takeIf { it.isNotEmpty() }
                ?: badRequest("缺少 commentId")
            val liked = params.boolOrNull("like") ?: true
            if (target == null) {
                actionFailure(CommentApiException("unsupported", unsupportedSongMessage(song)))
            } else {
                val outcome = withContext(Dispatchers.IO) {
                    runCatching {
                        when (target.platform) {
                            CommentPlatform.NETEASE -> netease.setLiked(commentId, target.neteaseSongId, liked)
                            CommentPlatform.BILIBILI -> bili.setLiked(target, commentId, liked)
                        }
                    }
                }
                if (outcome.isSuccess) {
                    buildJsonObject {
                        put("ok", true)
                        put("liked", liked)
                    }
                } else {
                    log("comments.like failed (${target.platform}): ${outcome.exceptionOrNull()?.message}")
                    actionFailure(outcome.exceptionOrNull()!!, liked)
                }
            }
        }

        // ---------------------------------------------------------------- 发表评论（尽力）
        server.on("comments.post") { params ->
            val song = params.requireSong()
            val target = resolveCommentTarget(song)
            val content = params.stringOrNull("content")?.trim().orEmpty()
            if (content.isEmpty()) badRequest("评论内容不能为空")
            if (target == null) {
                actionFailure(CommentApiException("unsupported", unsupportedSongMessage(song)))
            } else if (content.length > commentLengthLimit(target.platform)) {
                badRequest("评论内容超出 ${commentLengthLimit(target.platform)} 字上限")
            } else {
                val outcome = withContext(Dispatchers.IO) {
                    runCatching {
                        when (target.platform) {
                            CommentPlatform.NETEASE -> netease.post(target.neteaseSongId, content)
                            CommentPlatform.BILIBILI -> bili.post(target, content)
                        }
                    }
                }
                val comment = outcome.getOrNull()
                if (outcome.isSuccess) {
                    buildJsonObject {
                        put("ok", true)
                        if (comment != null) put("comment", encodeJson(comment))
                    }
                } else {
                    log("comments.post failed (${target.platform}): ${outcome.exceptionOrNull()?.message}")
                    actionFailure(outcome.exceptionOrNull()!!)
                }
            }
        }

        // ---------------------------------------------------------------- 删除评论（可选能力）
        server.on("comments.delete") { params ->
            val song = params.requireSong()
            val target = resolveCommentTarget(song)
            val commentId = params.stringOrNull("commentId")?.trim()?.takeIf { it.isNotEmpty() }
                ?: badRequest("缺少 commentId")
            if (target == null) {
                actionFailure(CommentApiException("unsupported", unsupportedSongMessage(song)))
            } else if (target.platform == CommentPlatform.NETEASE) {
                // 网易云没有公开的 api 删除接口（weapi 删除需要额外加密与 risk control），桌面版先不暴露
                actionFailure(CommentApiException("unsupported", "桌面版暂不支持删除网易云评论"))
            } else {
                val outcome = withContext(Dispatchers.IO) {
                    runCatching { bili.delete(target, commentId) }
                }
                if (outcome.isSuccess) {
                    buildJsonObject { put("ok", true) }
                } else {
                    log("comments.delete failed: ${outcome.exceptionOrNull()?.message}")
                    actionFailure(outcome.exceptionOrNull()!!)
                }
            }
        }
    }

    // ------------------------------------------------------------------ 辅助

    /** 失败的「动作」结果（点赞/发表/删除）：统一带 ok=false，让前端只走 Toast 不崩。 */
    private fun actionFailure(error: Throwable, liked: Boolean = false): JsonObject = buildJsonObject {
        put("ok", false)
        put("liked", liked)
        when (error) {
            is CommentLoginRequiredException -> {
                put("requiresLogin", true)
                put("errorCode", "login_required")
                put("error", error.message.orEmpty())
            }

            is CommentApiException -> {
                put("errorCode", error.code)
                put("error", error.message)
            }

            else -> {
                put("errorCode", "internal_error")
                put("error", "操作失败：${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    private fun unsupportedPage(song: Song, page: Int, pageSize: Int): CommentPage = CommentPage(
        page = page,
        pageSize = pageSize,
        source = "UNSUPPORTED",
        error = unsupportedSongMessage(song),
        errorCode = "unsupported",
    )

    private fun unsupportedSongMessage(song: Song): String = when (song.source.name) {
        "LOCAL" -> "本地歌曲没有评论区"
        "YOUTUBE" -> "YouTube 音源暂不支持评论"
        else -> "该歌曲来源不支持评论（仅支持网易云音乐 / 哔哩哔哩）"
    }

    private fun internalErrorPage(
        target: CommentTarget,
        page: Int,
        pageSize: Int,
        error: Throwable,
    ): CommentPage = CommentPage(
        page = page,
        pageSize = pageSize,
        source = target.platform.name,
        error = "评论加载失败：${error.message ?: error.javaClass.simpleName}",
        errorCode = "internal_error",
    )

    private fun JsonObject.requireSong(): Song {
        val element = objectOrNull("song") ?: badRequest("缺少 song")
        return runCatching { WireJson.decodeFromJsonElement(Song.serializer(), element) }
            .getOrElse { badRequest("song 格式非法: ${it.message}") }
    }
}
