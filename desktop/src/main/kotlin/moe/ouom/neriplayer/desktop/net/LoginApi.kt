package moe.ouom.neriplayer.desktop.net

import kotlinx.serialization.json.JsonObject
import moe.ouom.neriplayer.desktop.core.AccountInfo
import moe.ouom.neriplayer.desktop.core.MediaSource

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

    private fun fetchProfile(pollObject: JsonObject?): AccountInfo? {
        val accountText = http.get("https://music.163.com/api/nuser/account/get", headers)
        val root = accountText?.let { NeriJsonParser.parse(it).asObject() }
        val profile = root?.obj("profile")
        val nickname = profile?.str("nickname")?.takeIf { it.isNotBlank() }
            ?: pollObject?.str("nickname").orEmpty()
        val userId = profile?.long("userId")?.toString()?.takeIf { it != "0" }
            ?: pollObject?.long("userId")?.toString().orEmpty()
        val avatar = profile?.str("avatarUrl") ?: pollObject?.str("avatarUrl")
        val vip = (root?.obj("account")?.long("vipType") ?: 0L) > 0L
        if (nickname.isBlank() && userId.isBlank()) return null
        return AccountInfo(
            source = MediaSource.NETEASE.name,
            nickname = nickname,
            userId = userId,
            avatarUrl = avatar,
            vip = vip,
            loginAt = System.currentTimeMillis(),
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

    private fun fetchProfile(): AccountInfo? {
        val text = http.get("https://api.bilibili.com/x/web-interface/nav", headers) ?: return null
        val data = NeriJsonParser.parse(text).asObject()?.obj("data") ?: return null
        if (data.int("isLogin") != 1) return null
        val vip = (data.obj("vipStatus")?.int("status") ?: data.int("vipStatus") ?: 0) > 0
        return AccountInfo(
            source = MediaSource.BILIBILI.name,
            nickname = data.str("uname").orEmpty(),
            userId = data.long("mid")?.toString().orEmpty(),
            avatarUrl = data.str("face"),
            vip = vip,
            loginAt = System.currentTimeMillis(),
        )
    }
}
