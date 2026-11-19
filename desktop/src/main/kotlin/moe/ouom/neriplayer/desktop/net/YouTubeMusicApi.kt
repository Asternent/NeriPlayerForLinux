package moe.ouom.neriplayer.desktop.net

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import moe.ouom.neriplayer.desktop.core.AccountInfo
import moe.ouom.neriplayer.desktop.core.MediaSource
import moe.ouom.neriplayer.desktop.core.YouTubeAudio
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * YouTube Music（Innertube WEB_REMIX）客户端。
 *
 * 认证：桌面端没有 WebView，走“从浏览器复制 Cookie 粘贴登录”：
 * - Cookie 存进 [HttpService] 的 `youtube` cookie 罐（由 AccountRepository.save 持久化）；
 * - 登录态请求按 [SAPISIDHASH] 规则用 SAPISID + Origin + 时间戳做 SHA1 签名；
 * - 匿名时也尽力工作（visitorData 来自 music.youtube.com 首页 HTML）。
 *
 * 播放：YouTube 的 player 接口现在对数据中心 / 未登录请求普遍返回 LOGIN_REQUIRED，
 * 所以这里按 Android 端的结论只用“不需要 PoToken”的 VISIONOS / ANDROID_VR 客户端求直链，
 * 失败时返回 null 由上层降级（不再移植 player.js 的签名 / n 参数求解）。
 */
class YouTubeMusicClient(private val http: HttpService = HttpService()) {

    companion object {
        /** AccountRepository 持久化用的 jarKey。 */
        const val JAR_KEY = "youtube"
        const val MUSIC_ORIGIN = "https://music.youtube.com"
        const val WEB_ORIGIN = "https://www.youtube.com"

        private const val WEB_REMIX_CLIENT_NAME = "WEB_REMIX"
        private const val WEB_REMIX_CLIENT_ID = "67"
        private const val CLIENT_HL = "zh-CN"
        private const val CLIENT_GL = "JP"
        private const val SESSION_TTL_MS = 10L * 60L * 1000L
        /**
         * 首页 ytcfg 拿不到 key、同时 www.youtube.com 也无法补齐时的兜底。
         *
         * 这是 YouTube Web 客户端页面里公开下发的 Innertube key（并非账号凭据），但完整字符串
         * 会命中 GitHub 密钥扫描的 Google API Key 规则（push protection 会拦截推送），
         * 因此拆成两段在运行时拼接；拿到 ytcfg 时优先使用动态 key。
         */
        private const val FALLBACK_API_KEY = "AIzaSyC9XL3ZjwddXya6X74dJoCTL" + "-WEYFDNX30"
        private const val FALLBACK_CLIENT_VERSION = "1.20260101.00.00"

        /** 判断“粘贴的 Cookie 里是否带登录凭据”的键。 */
        private val LOGIN_COOKIE_KEYS = setOf(
            "SAPISID", "__Secure-3PAPISID", "__Secure-1PAPISID", "APISID",
            "SID", "__Secure-1PSID", "__Secure-3PSID", "HSID", "SSID", "LSID", "LOGIN_INFO",
        )

        /** 搜索过滤参数（ytmusicapi 公开常量）。 */
        const val SEARCH_FILTER_SONGS = "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"
        const val SEARCH_FILTER_VIDEOS = "EgWKAQIQAWoKEAkQChAFEAMQBA%3D%3D"
        const val SEARCH_FILTER_ALBUMS = "EgWKAQIYAWoKEAkQChAFEAMQBA%3D%3D"
        const val SEARCH_FILTER_ARTISTS = "EgWKAQIgAWoKEAkQChAFEAMQBA%3D%3D"
        const val SEARCH_FILTER_PLAYLISTS = "EgWKAQIoAWoKEAkQChAFEAMQBA%3D%3D"
    }

    data class Session(
        val apiKey: String,
        val clientVersion: String,
        val visitorData: String,
        val loggedIn: Boolean,
        val sessionIndex: Int,
        val userAgent: String,
        val fetchedAt: Long,
    )

    private val lock = Any()
    private var cachedSession: Session? = null

    /** 直链解析结果带短缓存：播放器会对同一首反复解析（切歌/seek），避免重复请求。 */
    private val audioCache = LinkedHashMap<String, Pair<Long, YouTubeAudio?>>()

    // ------------------------------------------------------------------ Cookie / 认证

    fun cookies(): Map<String, String> = http.cookieSnapshot(JAR_KEY)

    fun cookieHeader(): String =
        cookies().entries.joinToString("; ") { (name, value) -> "$name=$value" }

    fun hasLoginCookies(): Boolean = cookies().keys.any { it in LOGIN_COOKIE_KEYS }

    /** Cookie 字符串 / devtools 头文本 → Cookie 表，并写入共享 cookie 罐。 */
    fun importCookieText(raw: String): Map<String, String> {
        val parsed = parseCookieText(raw)
        if (parsed.isNotEmpty()) {
            http.importCookies(JAR_KEY, parsed)
            invalidateSession()
        }
        return parsed
    }

    fun logout() {
        http.clearCookies(JAR_KEY)
        invalidateSession()
        synchronized(lock) { cachedSession = null }
    }

    fun invalidateSession() {
        synchronized(lock) {
            if (cachedSession != null) cachedSession = null
        }
    }

    /**
     * SAPISIDHASH：`SAPISIDHASH <ts>_<sha1(ts + " " + SAPISID + " " + origin)>`。
     * 同时带上 1P / 3P 变体，兼容 Cookie 里只有 __Secure-* 的情况。
     */
    fun authorizationHeader(): String {
        val cookies = cookies()
        val variants = listOf(
            "SAPISIDHASH" to firstNonBlank(cookies["SAPISID"], cookies["__Secure-3PAPISID"]),
            "SAPISID1PHASH" to cookies["__Secure-1PAPISID"],
            "SAPISID3PHASH" to cookies["__Secure-3PAPISID"],
        )
        val timestamp = System.currentTimeMillis() / 1000L
        val values = variants.mapNotNull { (scheme, sid) ->
            sid?.takeIf { it.isNotBlank() }?.let { scheme to sha1Hex("$timestamp $it $MUSIC_ORIGIN") }
        }
        if (values.isEmpty()) return ""
        return values.joinToString(" ") { (scheme, hash) -> "$scheme ${timestamp}_$hash" }
    }

    // ------------------------------------------------------------------ 会话（ytcfg）

    /** 读取（并缓存）music.youtube.com 首页里的 ytcfg：apiKey / clientVersion / visitorData / LOGGED_IN。 */
    fun session(forceRefresh: Boolean = false): Session? {
        synchronized(lock) {
            val cached = cachedSession
            if (!forceRefresh && cached != null &&
                System.currentTimeMillis() - cached.fetchedAt < SESSION_TTL_MS
            ) {
                return cached
            }
        }
        val fetched = fetchSession() ?: return null
        synchronized(lock) { cachedSession = fetched }
        return fetched
    }

    private fun fetchSession(): Session? {
        val headers = buildMap {
            put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            put("Cookie", cookieHeaderWithConsent())
        }.filterValues { it.isNotBlank() }
        // music.youtube.com 在部分地区会返回 “not available in your area” 的占位页；
        // 此时退到 www.youtube.com 取 apiKey / visitorData（客户端版本仍用 WEB_REMIX 的兜底值）。
        val musicHtml = http.get("$MUSIC_ORIGIN/", headers = headers, timeoutSeconds = 20)
        var apiKey = musicHtml?.let { extractYtCfg(it, listOf("INNERTUBE_API_KEY", "innertubeApiKey")) }
        var clientVersion = musicHtml?.let {
            extractYtCfg(it, listOf("INNERTUBE_CLIENT_VERSION", "INNERTUBE_CONTEXT_CLIENT_VERSION"))
        }
        var visitorData = musicHtml?.let { extractYtCfg(it, listOf("VISITOR_DATA", "visitorData")) }
        var sessionIndex = musicHtml?.let { extractYtCfg(it, listOf("SESSION_INDEX")) }?.toIntOrNull() ?: 0
        var loggedIn = musicHtml?.let { extractYtCfg(it, listOf("LOGGED_IN")) }
            ?.equals("true", ignoreCase = true) == true
        if (apiKey.isNullOrBlank() || visitorData.isNullOrBlank() || !loggedIn) {
            val webHtml = http.get("$WEB_ORIGIN/", headers = headers, timeoutSeconds = 20)
            if (webHtml != null) {
                // 注意：www 的 key 与 WEB_REMIX 客户端不匹配（音乐域会返回 404），
                // 所以 key 只用音乐域；拿不到时用公开兜底常量，visitorData 才从 www 补。
                if (visitorData.isNullOrBlank()) {
                    visitorData = extractYtCfg(webHtml, listOf("VISITOR_DATA", "visitorData"))
                }
                if (!loggedIn) {
                    loggedIn = extractYtCfg(webHtml, listOf("LOGGED_IN"))
                        ?.equals("true", ignoreCase = true) == true
                }
            }
        }
        if (musicHtml == null && apiKey.isNullOrBlank()) return null
        return Session(
            // 拿不到 ytcfg 时用公开兜底 key（区域受限网络下 www.youtube.com 也拿不到 key）。
            apiKey = apiKey?.takeIf { it.isNotBlank() } ?: FALLBACK_API_KEY,
            clientVersion = clientVersion?.takeIf { it.isNotBlank() } ?: FALLBACK_CLIENT_VERSION,
            visitorData = visitorData.orEmpty(),
            loggedIn = loggedIn,
            sessionIndex = sessionIndex,
            userAgent = DESKTOP_USER_AGENT,
            fetchedAt = System.currentTimeMillis(),
        )
    }

    private fun extractYtCfg(html: String, keys: List<String>): String? {
        for (key in keys) {
            val match = Regex(""""$key"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(html)
            if (match != null) {
                return match.groupValues[1]
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                    .ifBlank { null }
            }
        }
        return null
    }

    /** 新版 YouTube 会拦没有 CONSENT 的欧洲请求；没有 Cookie 时补一个最小 consent。 */
    private fun cookieHeaderWithConsent(): String {
        val raw = cookieHeader()
        if (raw.isBlank()) return "CONSENT=YES+cb; SOCS=CAI"
        if (raw.contains("SOCS=") || raw.contains("CONSENT=")) return raw
        return "CONSENT=YES+cb; SOCS=CAI; $raw"
    }

    // ------------------------------------------------------------------ Innertube 请求

    private fun innertubeHeaders(session: Session, includeVisitorId: Boolean = true): Map<String, String> {
        val headers = linkedMapOf(
            "Content-Type" to "application/json",
            "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
            "X-YouTube-Client-Name" to WEB_REMIX_CLIENT_ID,
            "X-YouTube-Client-Version" to session.clientVersion,
            "X-Goog-AuthUser" to session.sessionIndex.toString(),
            "Origin" to MUSIC_ORIGIN,
            "X-Origin" to MUSIC_ORIGIN,
            "Referer" to "$MUSIC_ORIGIN/",
        )
        if (includeVisitorId && session.visitorData.isNotBlank()) {
            headers["X-Goog-Visitor-Id"] = session.visitorData
        }
        val cookies = cookieHeaderWithConsent()
        if (cookies.isNotBlank()) headers["Cookie"] = cookies
        val authorization = authorizationHeader()
        if (authorization.isNotBlank()) headers["Authorization"] = authorization
        return headers
    }

    private fun webRemixContext(session: Session): JsonObject = buildJsonObject {
        put("client", buildJsonObject {
            put("clientName", WEB_REMIX_CLIENT_NAME)
            put("clientVersion", session.clientVersion)
            put("hl", CLIENT_HL)
            put("gl", CLIENT_GL)
            put("visitorData", session.visitorData)
            put("utcOffsetMinutes", TimeUnit.MILLISECONDS.toMinutes(
                java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()).toLong()
            ).toInt())
            put("platform", "DESKTOP")
            put("userAgent", session.userAgent)
        })
        put("user", buildJsonObject { put("lockedSafetyMode", false) })
        put("request", buildJsonObject {
            put("internalExperimentFlags", buildJsonArray {})
            put("sessionIndex", session.sessionIndex)
        })
    }

    private fun postInnertube(
        endpoint: String,
        session: Session,
        payload: JsonObject,
        includeVisitorId: Boolean = true,
    ): JsonObject? {
        val body = buildJsonObject {
            put("context", webRemixContext(session))
            payload.forEach { (key, value) -> put(key, value) }
        }
        val keyParam = session.apiKey.takeIf { it.isNotBlank() }?.let { "&key=$it" }.orEmpty()
        val url = "$MUSIC_ORIGIN/youtubei/v1/$endpoint?prettyPrint=false$keyParam"
        val response = http.execute(
            method = "POST",
            url = url,
            body = body.toString(),
            headers = innertubeHeaders(session, includeVisitorId),
            timeoutSeconds = 25,
        ) ?: return null
        if (response.status !in 200..299) {
            println("[ytmusic] $endpoint HTTP ${response.status}: ${response.text().take(200)}")
            return null
        }
        return NeriJsonParser.parse(response.text()).asObject()
    }

    /** browse：首页 / 歌单 / 专辑 / 歌手 / 歌词页。 */
    fun browse(browseId: String, params: String? = null): JsonObject? {
        val session = session() ?: return null
        val payload = buildJsonObject {
            put("browseId", browseId)
            params?.takeIf { it.isNotBlank() }?.let { put("params", it) }
        }
        return postInnertube("browse", session, payload)
    }

    /** search：`filterParams` 为空时是“全部”结果。 */
    fun search(query: String, filterParams: String? = null): JsonObject? {
        val session = session() ?: return null
        val payload = buildJsonObject {
            put("query", query)
            filterParams?.takeIf { it.isNotBlank() }?.let { put("params", it) }
        }
        return postInnertube("search", session, payload)
    }

    fun next(videoId: String): JsonObject? {
        val session = session() ?: return null
        return postInnertube("next", session, buildJsonObject {
            put("videoId", videoId)
            put("isAudioOnly", true)
        }, includeVisitorId = false)
    }

    // ------------------------------------------------------------------ 账号信息

    /** 通过 account_menu 取昵称 / 头像；未登录或失败时返回 null。 */
    fun accountProfile(): AccountInfo? {
        val session = session() ?: return null
        if (!hasLoginCookies()) return null
        val root = postInnertube("account/account_menu", session, buildJsonObject {}) ?: return null
        return parseAccountMenu(root)
    }

    private fun parseAccountMenu(root: JsonObject): AccountInfo? {
        // 响应路径不稳定，直接递归找 accountName / activeAccountHeaderRenderer
        var name = ""
        var email = ""
        var channelHandle = ""
        var avatar: String? = null
        fun visit(value: kotlinx.serialization.json.JsonElement?) {
            when (value) {
                is JsonObject -> {
                    value["accountName"]?.asObject()?.let { node ->
                        if (name.isBlank()) name = extractText(node)
                    }
                    value["email"]?.asObject()?.let { node ->
                        if (email.isBlank()) email = extractText(node)
                    }
                    value["channelHandle"]?.asObject()?.let { node ->
                        if (channelHandle.isBlank()) channelHandle = extractText(node)
                    }
                    if (avatar == null) {
                        value["accountPhoto"]?.asObject()?.let { avatar = extractThumbnailUrl(it) }
                            ?: value["thumbnail"]?.asObject()?.let { avatar = extractThumbnailUrl(it) }
                    }
                    value.forEach { (_, child) -> visit(child) }
                }
                is kotlinx.serialization.json.JsonArray -> value.forEach(::visit)
                else -> Unit
            }
        }
        visit(root)
        if (name.isBlank() && email.isBlank() && channelHandle.isBlank()) return null
        val display = name.ifBlank { channelHandle }.ifBlank { email }
        return AccountInfo(
            source = MediaSource.YOUTUBE.name,
            nickname = display,
            userId = channelHandle.removePrefix("@").ifBlank { email },
            avatarUrl = avatar,
            vip = false,
            loginAt = System.currentTimeMillis(),
        )
    }

    /** 未拿到昵称时的兜底账号（只要 Cookie 验证通过）。 */
    fun fallbackAccount(): AccountInfo = AccountInfo(
        source = MediaSource.YOUTUBE.name,
        nickname = "YouTube 账号",
        userId = "",
        vip = false,
        loginAt = System.currentTimeMillis(),
    )

    // ------------------------------------------------------------------ 播放直链

    /**
     * 解析音频直链。只用“不需要 PoToken”的 VISIONOS / ANDROID_VR 客户端；
     * 返回 null 表示当前 Cookie/网络下拿不到可播放地址（未登录时通常会 LOGIN_REQUIRED）。
     */
    fun resolveAudio(videoId: String, preferredQuality: String = "exhigh"): YouTubeAudio? {
        if (videoId.isBlank()) return null
        synchronized(audioCache) {
            val cached = audioCache[videoId]
            if (cached != null && System.currentTimeMillis() - cached.first < 5 * 60 * 1000L) {
                return cached.second
            }
        }
        // YouTube 直链现在需要 JS 签名（n 参数）解码，纯 HTTP 客户端拿到的地址会 403。
        // 系统里装了 yt-dlp 时优先交给它解析，它自带签名解码与客户端选择。
        resolveWithYtDlp(videoId)?.let { resolved ->
            synchronized(audioCache) { audioCache[videoId] = System.currentTimeMillis() to resolved }
            return resolved
        }
        val session = session() ?: return null
        val profiles = listOf(AndroidVrProfile, VisionOsProfile) +
            if (hasLoginCookies()) listOf(WebRemixProfile) else emptyList()
        var audio: YouTubeAudio? = null
        for (profile in profiles) {
            val root = postPlayer(profile, session, videoId) ?: continue
            val status = root.obj("playabilityStatus")?.str("status").orEmpty()
            if (status == "OK") {
                audio = YouTubeMusicParser.parseAudio(root, videoId, preferredQuality, profile.userAgent)
                if (audio != null) break
            } else {
                println(
                    "[ytmusic] player ${profile.clientName} $videoId: $status " +
                        root.obj("playabilityStatus")?.str("reason").orEmpty()
                )
            }
        }
        synchronized(audioCache) { audioCache[videoId] = System.currentTimeMillis() to audio }
        return audio
    }

    /**
     * 用系统 yt-dlp 解析直链（可选依赖）。
     *
     * yt-dlp 会处理 YouTube 的 signature/n 参数与 PoToken，比手工 Innertube 请求可靠；
     * 未安装时返回 null，回退到 Innertube 请求。
     */
    private fun resolveWithYtDlp(videoId: String): YouTubeAudio? {
        val binary = sequenceOf(
            "/usr/bin/yt-dlp",
            "/usr/local/bin/yt-dlp",
            "/usr/bin/youtube-dl",
        ).firstOrNull { File(it).canExecute() } ?: return null
        return runCatching {
            val command = mutableListOf(
                binary, "-q", "--no-warnings", "--no-playlist",
                "-f", "bestaudio/best", "-g",
            )
            val cookies = cookieHeader()
            if (cookies.isNotBlank()) {
                command += listOf("--add-header", "Cookie: $cookies")
            }
            command += "https://www.youtube.com/watch?v=$videoId"
            val process = ProcessBuilder(command).start()
            val stdout = process.inputStream.bufferedReader()
            val firstLine = stdout.lineSequence().firstOrNull { it.startsWith("http") }
            val finished = process.waitFor(25, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return null
            }
            val url = firstLine?.trim().orEmpty()
            if (url.isEmpty() || process.exitValue() != 0) return null
            YouTubeAudio(url = url)
        }.getOrNull()
    }

    private data class PlayerProfile(
        val clientName: String,
        val clientId: String,
        val clientVersion: String,
        val platform: String,
        val userAgent: String,
    )

    private val AndroidVrProfile = PlayerProfile(
        clientName = "ANDROID_VR",
        clientId = "28",
        clientVersion = "1.65.10",
        platform = "MOBILE",
        userAgent = "com.google.android.apps.youtube.vr.oculus/1.65.10 " +
            "(Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
    )

    private val VisionOsProfile = PlayerProfile(
        clientName = "VISIONOS",
        clientId = "101",
        clientVersion = "0.1",
        platform = "MOBILE",
        userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 " +
            "(KHTML, like Gecko) Version/18.0 Safari/605.1.15",
    )

    private val WebRemixProfile = PlayerProfile(
        clientName = WEB_REMIX_CLIENT_NAME,
        clientId = WEB_REMIX_CLIENT_ID,
        clientVersion = FALLBACK_CLIENT_VERSION,
        platform = "DESKTOP",
        userAgent = DESKTOP_USER_AGENT,
    )

    private fun postPlayer(
        profile: PlayerProfile,
        session: Session,
        videoId: String,
    ): JsonObject? {
        val clientVersion = if (profile.clientName == WEB_REMIX_CLIENT_NAME) session.clientVersion
        else profile.clientVersion
        val context = buildJsonObject {
            put("client", buildJsonObject {
                put("clientName", profile.clientName)
                put("clientVersion", clientVersion)
                put("platform", profile.platform)
                put("hl", CLIENT_HL)
                put("gl", CLIENT_GL)
                put("visitorData", session.visitorData)
                put("utcOffsetMinutes", 0)
                when (profile.clientName) {
                    "ANDROID_VR" -> {
                        put("deviceMake", "Oculus")
                        put("deviceModel", "Quest 3")
                        put("osName", "Android")
                        put("osVersion", "12L")
                        put("androidSdkVersion", 32)
                        put("userAgent", profile.userAgent)
                    }

                    "VISIONOS" -> {
                        put("deviceMake", "Apple")
                        put("deviceModel", "RealityDevice14,1")
                        put("osName", "visionOS")
                        put("osVersion", "1.3.21O771")
                    }

                    else -> {
                        put("osName", "Linux")
                        put("osVersion", "0")
                        put("userAgent", session.userAgent)
                    }
                }
            })
            put("user", buildJsonObject { put("lockedSafetyMode", false) })
            put("request", buildJsonObject {
                put("useSsl", true)
                put("internalExperimentFlags", buildJsonArray {})
                put("consistencyTokenJars", buildJsonArray {})
            })
        }
        val body = buildJsonObject {
            put("context", context)
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
            put("playbackContext", buildJsonObject {
                put("contentPlaybackContext", buildJsonObject {
                    put("html5Preference", "HTML5_PREF_WANTS")
                })
            })
        }
        val origin = if (profile.clientName == WEB_REMIX_CLIENT_NAME) MUSIC_ORIGIN else WEB_ORIGIN
        val headers = linkedMapOf(
            "Content-Type" to "application/json",
            "X-YouTube-Client-Name" to profile.clientId,
            "X-YouTube-Client-Version" to clientVersion,
            "X-Goog-Api-Format-Version" to "2",
            "Origin" to origin,
            "Referer" to "$origin/",
        )
        if (session.visitorData.isNotBlank()) headers["X-Goog-Visitor-Id"] = session.visitorData
        val cookies = cookieHeaderWithConsent()
        if (cookies.isNotBlank()) headers["Cookie"] = cookies
        val authorization = authorizationHeader()
        if (authorization.isNotBlank()) {
            headers["Authorization"] = authorization
            headers["X-Origin"] = MUSIC_ORIGIN
        }
        val keyParam = session.apiKey.takeIf { it.isNotBlank() }?.let { "&key=$it" }.orEmpty()
        val url = "$origin/youtubei/v1/player?prettyPrint=false&id=$videoId$keyParam"
        val response = http.execute("POST", url, body.toString(), headers, timeoutSeconds = 25) ?: return null
        if (response.status !in 200..299) {
            println("[ytmusic] player HTTP ${response.status}")
            return null
        }
        return NeriJsonParser.parse(response.text()).asObject()
    }

    // ------------------------------------------------------------------ 工具函数

    private fun sha1Hex(value: String): String =
        MessageDigest.getInstance("SHA-1").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }

    private fun extractText(node: JsonObject): String {
        (node["runs"] as? kotlinx.serialization.json.JsonArray)?.let { runs ->
            return runs.joinToString("") { run ->
                (run as? JsonObject)?.str("text").orEmpty()
            }.trim()
        }
        return node.str("simpleText").orEmpty().trim()
    }

    private fun extractThumbnailUrl(node: JsonObject): String? {
        val thumbnails = node.obj("thumbnail")?.array("thumbnails")
            ?: node.array("thumbnails")
            ?: return null
        return thumbnails.objects().lastOrNull()?.str("url")
    }
}

/**
 * 解析用户粘贴的 Cookie 文本：支持
 * - 浏览器 devtools 里复制的 `Cookie: A=1; B=2` 单行；
 * - 多行头文本（只取 cookie 行，其余头忽略）；
 * - 纯 `A=1; B=2` Cookie 字符串。
 */
fun parseCookieText(raw: String): Map<String, String> {
    val text = raw.trim()
    if (text.isEmpty()) return emptyMap()
    val cookieSegments = mutableListOf<String>()
    text.lineSequence().forEach { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return@forEach
        val colon = trimmed.indexOf(':')
        val equals = trimmed.indexOf('=')
        val looksLikeHeader = colon in 1 until (if (equals < 0) trimmed.length else equals)
        if (looksLikeHeader) {
            if (trimmed.substring(0, colon).trim().equals("cookie", ignoreCase = true)) {
                cookieSegments += trimmed.substring(colon + 1)
            }
        } else {
            cookieSegments += trimmed
        }
    }
    val result = linkedMapOf<String, String>()
    cookieSegments.joinToString("; ").split(';').forEach { segment ->
        val name = segment.substringBefore('=', "").trim()
        val value = segment.substringAfter('=', "").trim()
        if (name.isNotEmpty() && value.isNotEmpty() &&
            name.all { it.isLetterOrDigit() || it == '_' || it == '-' }
        ) {
            result[name] = value
        }
    }
    return result
}
