package moe.ouom.neriplayer.desktop.net

import kotlinx.serialization.json.JsonObject
import moe.ouom.neriplayer.desktop.core.AccountInfo
import moe.ouom.neriplayer.desktop.core.MediaSource
import moe.ouom.neriplayer.desktop.core.displayName

sealed interface LoginPollResult {
    /** 二维码过期，需要重新获取 */
    data object Expired : LoginPollResult

    /** 等待扫码；scanned=true 表示已扫码、等待手机确认 */
    data class Waiting(val scanned: Boolean) : LoginPollResult

    data class Success(val account: AccountInfo) : LoginPollResult

    data class Failed(val message: String) : LoginPollResult
}

/** 网易云扫码登录（使用官方 App 可识别的 codekey 链接）。 */
class NeteaseLogin(private val http: HttpService) {

    private val headers = mapOf(
        "Referer" to "https://music.163.com/",
        "Cookie" to "appver=2.0.2; os=pc",
    )

    fun createQrCode(): Pair<String, String>? {
        val text = http.get("https://music.163.com/api/login/qrcode/unikey?type=1", headers) ?: return null
        val obj = NeriJsonParser.parse(text).asObject() ?: return null
        val key = obj.str("unikey")?.takeIf { it.isNotBlank() } ?: return null
        return key to "https://music.163.com/login?codekey=$key"
    }

    fun poll(key: String): LoginPollResult {
        val text = http.get("https://music.163.com/api/login/qrcode/client/login?key=$key&type=1", headers)
        val obj = text?.let { NeriJsonParser.parse(it).asObject() }
        return when (val code = obj?.int("code")) {
            800 -> LoginPollResult.Expired
            801 -> LoginPollResult.Waiting(scanned = false)
            802 -> LoginPollResult.Waiting(scanned = true)
            803 -> {
                val info = fetchProfile(obj)
                if (info == null) {
                    LoginPollResult.Failed("登录成功，但读取账号信息失败")
                } else {
                    LoginPollResult.Success(info)
                }
            }

            null -> LoginPollResult.Failed("网络异常，请稍后重试")
            else -> LoginPollResult.Failed("登录返回未知状态：$code")
        }
    }

    /** 读取账号信息（登录完成或需要补全昵称 / UID 时调用）。 */
    fun fetchProfile(pollObject: JsonObject? = null): AccountInfo? {
        val accountText = http.get("https://music.163.com/api/nuser/account/get", headers)
        val root = accountText?.let { NeriJsonParser.parse(it).asObject() }
        val profile = root?.obj("profile")
        val nickname = profile?.str("nickname")?.takeIf { it.isNotBlank() }
            ?: pollObject?.str("nickname").orEmpty()
        val userId = profile?.long("userId")?.toString()?.takeIf { it != "0" }
            ?: pollObject?.long("userId")?.toString().orEmpty()
        val avatar = profile?.str("avatarUrl") ?: pollObject?.str("avatarUrl")
        val vip = (root?.obj("account")?.long("vipType") ?: 0L) > 0L
        if (nickname.isNotBlank() || userId.isNotBlank()) {
            return AccountInfo(
                source = MediaSource.NETEASE.name,
                nickname = nickname,
                userId = userId,
                avatarUrl = avatar,
                vip = vip,
                loginAt = System.currentTimeMillis(),
            )
        }
        // 兜底：账号信息接口偶发失败时，只要登录 Cookie 已下发就认为登录成功
        return parseCookieFallbackAccount(
            source = MediaSource.NETEASE,
            cookies = http.cookieSnapshot("netease"),
            cookieName = "MUSIC_U",
            userId = "",
        )
    }
}

/** 哔哩哔哩扫码登录（passport 网页扫码接口）。 */
class BiliLogin(private val http: HttpService) {

    private val headers = mapOf("Referer" to "https://www.bilibili.com/")

    fun createQrCode(): Pair<String, String>? {
        val text = http.get("https://passport.bilibili.com/x/passport-login/web/qrcode/generate", headers)
            ?: return null
        val data = NeriJsonParser.parse(text).asObject()?.obj("data") ?: return null
        val key = data.str("qrcode_key")?.takeIf { it.isNotBlank() } ?: return null
        val url = data.str("url")?.takeIf { it.isNotBlank() } ?: return null
        return key to url
    }

    fun poll(key: String): LoginPollResult {
        val text = http.get(
            "https://passport.bilibili.com/x/passport-login/web/qrcode/poll?qrcode_key=$key",
            headers,
        )
        val root = text?.let { NeriJsonParser.parse(it).asObject() }
        val data = root?.obj("data")
        return when (val code = data?.int("code")) {
            86101 -> LoginPollResult.Waiting(scanned = false)
            86090 -> LoginPollResult.Waiting(scanned = true)
            86038 -> LoginPollResult.Expired
            0 -> {
                println("[login] bilibili 扫码确认，已获取 Cookie：${http.cookieSnapshot("bilibili").keys}")
                val info = fetchProfile()
                if (info == null) {
                    LoginPollResult.Failed("登录成功，但读取账号信息失败")
                } else {
                    LoginPollResult.Success(info)
                }
            }

            null -> LoginPollResult.Failed("网络异常，请稍后重试")
            else -> LoginPollResult.Failed(data?.str("message") ?: "登录返回未知状态：$code")
        }
    }

    /** 读取账号信息（登录完成或需要补全昵称 / UID 时调用）。 */
    fun fetchProfile(): AccountInfo? {
        val navText = http.get("https://api.bilibili.com/x/web-interface/nav", headers)
        val cookies = http.cookieSnapshot("bilibili")
        val navRoot = navText?.let { NeriJsonParser.parse(it).asObject() }
        val navData = navRoot?.obj("data")
        println(
            "[login] bilibili nav code=${navRoot?.int("code")} isLogin=${navData?.bool("isLogin")} " +
                "cookieKeys=${cookies.keys}"
        )
        if (navData?.bool("isLogin") == true) {
            return biliAccountFromNav(navData)
        }
        // 兜底一：成员信息接口
        val memberText = http.get("https://api.bilibili.com/x/member/web/account", headers)
        val memberData = memberText?.let { NeriJsonParser.parse(it).asObject()?.obj("data") }
        val fromMember = memberData?.let { biliAccountFromMember(it) }
        if (fromMember != null) {
            println("[login] bilibili 使用成员接口兜底：${fromMember.displayName()}")
            return fromMember
        }
        // 兜底二：只要 SESSDATA 已下发就认为登录成功（昵称留空，界面显示 UID）
        val fromCookie = parseCookieFallbackAccount(
            source = MediaSource.BILIBILI,
            cookies = cookies,
            cookieName = "SESSDATA",
            userId = cookies["DedeUserID"].orEmpty(),
        )
        println("[login] bilibili Cookie 兜底结果：${fromCookie?.displayName() ?: "未登录"}")
        return fromCookie
    }
}

/** 从 nav 接口的 data 构造账号信息（isLogin 为 JSON 布尔值）。 */
fun biliAccountFromNav(data: JsonObject): AccountInfo {
    val vipStatus = data.obj("vipStatus")?.int("status") ?: data.int("vipStatus") ?: 0
    return AccountInfo(
        source = MediaSource.BILIBILI.name,
        nickname = data.str("uname").orEmpty(),
        userId = data.long("mid")?.toString().orEmpty(),
        avatarUrl = data.str("face"),
        vip = vipStatus > 0,
        loginAt = System.currentTimeMillis(),
    )
}

/** 从成员信息接口的 data 构造账号信息。 */
fun biliAccountFromMember(data: JsonObject): AccountInfo? {
    val nickname = data.str("uname").orEmpty()
    val mid = data.long("mid")?.toString().orEmpty()
    if (nickname.isBlank() && mid.isBlank()) return null
    return AccountInfo(
        source = MediaSource.BILIBILI.name,
        nickname = nickname,
        userId = mid,
        avatarUrl = data.str("face"),
        vip = false,
        loginAt = System.currentTimeMillis(),
    )
}

/** 解析哔哩哔哩账号信息：nav → 成员接口 → Cookie 兜底。可离线单测。 */
fun parseBiliAccount(
    navText: String?,
    memberText: String?,
    cookies: Map<String, String>,
): AccountInfo? {
    val navData = navText?.let { NeriJsonParser.parse(it).asObject()?.obj("data") }
    if (navData?.bool("isLogin") == true) {
        return biliAccountFromNav(navData)
    }
    val memberData = memberText?.let { NeriJsonParser.parse(it).asObject()?.obj("data") }
    memberData?.let { data -> biliAccountFromMember(data)?.let { return it } }
    return parseCookieFallbackAccount(
        source = MediaSource.BILIBILI,
        cookies = cookies,
        cookieName = "SESSDATA",
        userId = cookies["DedeUserID"].orEmpty(),
    )
}

/** 账号信息接口不可用时的兜底：登录 Cookie 存在即视为已登录。 */
fun parseCookieFallbackAccount(
    source: MediaSource,
    cookies: Map<String, String>,
    cookieName: String,
    userId: String,
): AccountInfo? {
    if (cookies[cookieName].orEmpty().isBlank()) return null
    return AccountInfo(
        source = source.name,
        nickname = "",
        userId = userId,
        avatarUrl = null,
        vip = false,
        loginAt = System.currentTimeMillis(),
    )
}
