package moe.ouom.neriplayer.desktop.core

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

/**
 * 一起听协议与桌面播放器之间的映射：
 *  - Song（桌面播放器）⇄ ListenTogetherTrack（LTW 线协议）
 *  - 服务端地址归一化、邀请链接解析/生成
 *  - 播放模式（RepeatMode ⇄ worker.js 的 0/1/2）与播放状态
 */

// ---------------------------------------------------------------- 服务端地址

/** 与 Android normaliseBaseUrl 一致的归一化：只接受 http/https，去掉尾部斜杠、query 与 fragment。 */
fun normalizeListenTogetherBaseUrlOrNull(raw: String?): String? {
    val text = raw?.trim()?.trimEnd('/') ?: return null
    if (text.isEmpty()) return null
    val uri = runCatching { URI(text) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
    if (scheme != "http" && scheme != "https") return null
    val authority = uri.rawAuthority?.takeIf { it.isNotBlank() } ?: return null
    if (uri.rawQuery != null || uri.rawFragment != null) return null
    val path = uri.normalize().rawPath.orEmpty().trimEnd('/')
    return "$scheme://$authority$path"
}

/** 形如 `neriplayer://listen-together/join?roomId=...&secret=...` 的邀请信息。 */
data class ListenTogetherInvite(
    val roomId: String,
    val inviterNickname: String? = null,
    val baseUrl: String? = null,
    val joinSecret: String,
    /** 邀请里的 baseUrl 不是 https 时置位；此时回退到本机已配置的服务端。 */
    val hasInvalidBaseUrl: Boolean = false,
)

private const val INVITE_SCHEME = "neriplayer"
private const val INVITE_DEBUG_SCHEME = "neriplayer-debug"
private const val INVITE_HOST = "listen-together"
private const val INVITE_JOIN_PATH = "join"
private val INVITE_REGEX = Regex(
    pattern = """(?<![a-z0-9+.-])neriplayer(?:-debug)?://listen-together/join\?[^\s]+""",
    option = RegexOption.IGNORE_CASE,
)
private val ROOM_ID_REGEX = Regex("^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{6}$")
private val NICKNAME_REGEX = Regex("^[\\p{IsHan}A-Za-z0-9]{1,24}$")

val LISTEN_TOGETHER_ROOM_ID_PATTERN = ROOM_ID_REGEX

fun normalizeListenTogetherRoomId(raw: String?): String = raw?.trim()?.uppercase(Locale.ROOT).orEmpty()

fun validateListenTogetherRoomId(roomId: String): String? =
    if (ROOM_ID_REGEX.matches(roomId)) null else "房间号应为 6 位大写字母/数字"

fun validateListenTogetherNickname(nickname: String): String? = when {
    nickname.length !in 1..24 -> "昵称长度应为 1-24"
    !NICKNAME_REGEX.matches(nickname) -> "昵称只能包含中文、英文字母和数字"
    else -> null
}

/** 从粘贴的文本中解析邀请链接；允许链接前后带其他文字（与 Android 一致）。 */
fun parseListenTogetherInvite(rawText: String?): ListenTogetherInvite? {
    val text = rawText?.trim().orEmpty()
    if (text.isEmpty()) return null
    parseListenTogetherInviteInternal(text)?.let { return it }
    val match = INVITE_REGEX.find(text)?.value ?: return null
    return parseListenTogetherInviteInternal(match)
}

private fun parseListenTogetherInviteInternal(text: String): ListenTogetherInvite? {
    val uri = runCatching { URI(text) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
    if (scheme != INVITE_SCHEME && scheme != INVITE_DEBUG_SCHEME) return null
    if (!uri.host.equals(INVITE_HOST, ignoreCase = true)) return null
    val firstSegment = uri.path.orEmpty().split('/').firstOrNull { it.isNotBlank() }
    if (firstSegment != INVITE_JOIN_PATH) return null
    val query = decodeQuery(uri.rawQuery)
    val roomId = normalizeListenTogetherRoomId(query["roomId"])
    if (validateListenTogetherRoomId(roomId) != null) return null
    val joinSecret = query["secret"]?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val rawBaseUrl = query["baseUrl"]?.trim().orEmpty()
    // 邀请可能来自外部不可信来源：和 Android 一样只接受 https 的 baseUrl，其余回退到本机配置
    val normalizedBaseUrl = normalizeListenTogetherBaseUrlOrNull(rawBaseUrl)
        ?.takeIf { it.startsWith("https://", ignoreCase = true) }
    val inviter = query["inviter"]?.trim()
        ?.takeIf { it.isNotEmpty() && validateListenTogetherNickname(it) == null }
    return ListenTogetherInvite(
        roomId = roomId,
        inviterNickname = inviter,
        baseUrl = normalizedBaseUrl,
        joinSecret = joinSecret,
        hasInvalidBaseUrl = rawBaseUrl.isNotBlank() && normalizedBaseUrl == null,
    )
}

/** 生成与 Android 端兼容的邀请链接；默认服务端不必写入 baseUrl。 */
fun buildListenTogetherInvite(
    roomId: String,
    nickname: String?,
    baseUrl: String,
    joinSecret: String,
): String {
    val params = buildList {
        add("roomId" to roomId)
        nickname?.trim()?.takeIf { it.isNotEmpty() }?.let { add("inviter" to it) }
        val normalized = normalizeListenTogetherBaseUrlOrNull(baseUrl)
        val default = normalizeListenTogetherBaseUrlOrNull(LISTEN_TOGETHER_DEFAULT_SERVER_URL)
        if (normalized != null && normalized != default) add("baseUrl" to normalized)
        add("secret" to joinSecret)
    }
    return "neriplayer://listen-together/join?" + params.joinToString("&") { (key, value) ->
        urlEncode(key) + "=" + urlEncode(value)
    }
}

private fun decodeQuery(rawQuery: String?): Map<String, String> {
    if (rawQuery.isNullOrBlank()) return emptyMap()
    return rawQuery.split('&').mapNotNull { pair ->
        val rawKey = pair.substringBefore('=')
        val rawValue = pair.substringAfter('=', "")
        val key = runCatching { URLDecoder.decode(rawKey, Charsets.UTF_8) }.getOrNull()?.trim()
        val value = runCatching { URLDecoder.decode(rawValue, Charsets.UTF_8) }.getOrNull()
        if (key.isNullOrEmpty() || value == null) null else key to value
    }.toMap()
}

private fun urlEncode(value: String): String =
    URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

// ---------------------------------------------------------------- Song ⇄ Track

/** Android ListenTogetherTrackIdentity.buildStableTrackKey 的等价实现。 */
fun buildListenTogetherStableKey(
    channelId: String,
    audioId: String,
    subAudioId: String? = null,
    playlistContextId: String? = null,
): String = when (channelId) {
    LISTEN_TOGETHER_CHANNEL_BILIBILI ->
        listOf(channelId, audioId, subAudioId).filterNot { it.isNullOrBlank() }.joinToString(":")

    LISTEN_TOGETHER_CHANNEL_YOUTUBE_MUSIC ->
        listOf(channelId, audioId, playlistContextId).filterNot { it.isNullOrBlank() }.joinToString(":")

    else -> "$channelId:$audioId"
}

private fun listenTogetherChannelOf(song: Song): String? = when (song.source) {
    MediaSource.NETEASE -> LISTEN_TOGETHER_CHANNEL_NETEASE
    MediaSource.BILIBILI -> LISTEN_TOGETHER_CHANNEL_BILIBILI
    // 桌面版没有 YouTube 播放能力：既不分享也不会把这种来源送进房间
    MediaSource.YOUTUBE -> LISTEN_TOGETHER_CHANNEL_YOUTUBE_MUSIC
    MediaSource.LOCAL -> LISTEN_TOGETHER_CHANNEL_LOCAL
}

/**
 * 桌面播放器里的歌曲 → 房间曲目。
 * 返回 null 表示这首歌不能进入房间：本地文件、缺少远端 id 或桌面版不支持的来源。
 * 与 worker.js 的 sanitizeTrack 要求一致（stableKey/channelId/audioId/name/artist 非空）。
 */
fun Song.toListenTogetherTrackOrNull(): ListenTogetherTrack? {
    val channel = listenTogetherChannelOf(this) ?: return null
    if (channel == LISTEN_TOGETHER_CHANNEL_LOCAL) return null
    val audioId = remoteId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val stableKey = buildListenTogetherStableKey(
        channelId = channel,
        audioId = audioId,
        subAudioId = null,
        playlistContextId = null,
    )
    return ListenTogetherTrack(
        stableKey = stableKey,
        channelId = channel,
        audioId = audioId,
        subAudioId = null,
        playlistContextId = null,
        mediaUri = null,
        streamUrl = null,
        streamUrls = emptyList(),
        name = title.trim().ifEmpty { "未知歌曲" },
        artist = artist.trim().ifEmpty { "未知艺术家" },
        album = album.trim().ifEmpty { null },
        durationMs = durationMs.coerceAtLeast(0L),
        coverUrl = artworkUrl?.trim()?.takeIf { it.isNotEmpty() },
    )
}

/** 房间曲目 → 桌面播放器歌曲；不认识的渠道返回 null（避免把脏数据塞进队列）。 */
fun ListenTogetherTrack.toSongOrNull(): Song? {
    val channel = channelId.trim()
    val audio = audioId.trim().ifEmpty { null } ?: return null
    val name = name.trim().ifEmpty { "未知歌曲" }
    val artist = artist.trim().ifEmpty { "未知艺术家" }
    return when (channel) {
        LISTEN_TOGETHER_CHANNEL_NETEASE -> {
            val numericId = audio.toLongOrNull()
            Song(
                key = neteaseSongKey(numericId ?: stableKey.hashCode().toLong()),
                source = MediaSource.NETEASE,
                title = name,
                artist = artist,
                album = album.orEmpty(),
                durationMs = durationMs.coerceAtLeast(0L),
                remoteId = audio,
                artworkUrl = coverUrl,
            )
        }

        LISTEN_TOGETHER_CHANNEL_BILIBILI -> Song(
            key = biliSongKey(audio),
            source = MediaSource.BILIBILI,
            title = name,
            artist = artist,
            album = album.orEmpty(),
            durationMs = durationMs.coerceAtLeast(0L),
            remoteId = audio,
            // Android 端把分 P 的 cid 放在 subAudioId；桌面版用它跳过视频分 P 查询
            extraId = subAudioId?.trim()?.takeIf { it.isNotEmpty() },
            artworkUrl = coverUrl,
        )

        // Android 端支持 YouTube Music。桌面版没有对应播放实现，仍保留歌曲信息用于展示，
        // 播放时会由在线解析失败给出错误提示（见报告中的已知限制）。
        LISTEN_TOGETHER_CHANNEL_YOUTUBE_MUSIC -> Song(
            key = "youtubeMusic:$audio",
            source = MediaSource.YOUTUBE,
            title = name,
            artist = artist,
            album = album.orEmpty(),
            durationMs = durationMs.coerceAtLeast(0L),
            remoteId = audio,
            artworkUrl = coverUrl,
        )

        else -> null
    }
}

// ---------------------------------------------------------------- 播放模式

/** RepeatMode → worker.js 的 repeatMode（0 关闭 / 1 单曲 / 2 列表）。 */
fun RepeatMode.toListenTogetherRepeatMode(): Int = when (this) {
    RepeatMode.OFF -> 0
    RepeatMode.ALL -> 2
    RepeatMode.ONE -> 1
}

fun listenTogetherRepeatModeToRepeatMode(value: Int?): RepeatMode = when (value) {
    1 -> RepeatMode.ONE
    2 -> RepeatMode.ALL
    else -> RepeatMode.OFF
}

/** 桌面播放状态 → 房间 playback.state（"playing" / "paused"）。 */
fun PlaybackState.toListenTogetherPlaybackStateName(): String =
    if (this == PlaybackState.PLAYING) "playing" else "paused"

// ---------------------------------------------------------------- 错误文案

/** 服务端错误是英文短码；面板展示中文提示，未知错误保留原文方便排查。 */
fun describeListenTogetherError(raw: String?): String {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return "未知错误"
    val lower = text.lowercase(Locale.ROOT)
    return when {
        "join_secret_required" in lower -> "首次加入需要邀请链接（其中带有加入密钥），只填房间号仅适用于重连"
        "member_secret_required" in lower -> "该昵称/身份已在房间中，需要用原身份重连"
        "room not initialized" in lower -> "房间不存在或已过期"
        "room already initialized" in lower -> "房间已存在"
        "room closed" in lower -> "房间已关闭"
        "controller offline" in lower -> "控制者不在线"
        "member control disabled" in lower -> "控制者关闭了听众控制"
        "nickname contains invalid" in lower || "nickname length" in lower -> "昵称不合法（1-24 位中文/字母/数字）"
        "useruuid format is invalid" in lower -> "用户标识不合法"
        "only controller" in lower -> "只有控制者可以执行该操作"
        "unauthorized" in lower -> "登录凭证已失效，请重新加入房间"
        "audio link sharing disabled" in lower -> "房间未开启音频直链共享"
        "local tracks cannot be shared" in lower -> "本地歌曲不能加入一起听"
        "member control target mismatch" in lower -> "请求控制的目标歌曲已变化，请重试"
        "member control target unavailable" in lower -> "请求的歌曲不在当前房间队列中"
        "link request throttled" in lower -> "请求过于频繁，请稍后再试"
        "initial snapshot requires" in lower -> "创建房间需要当前有可分享的在线歌曲"
        "queue update" in lower -> "房间队列更新被拒绝，请稍后重试"
        else -> text
    }
}
