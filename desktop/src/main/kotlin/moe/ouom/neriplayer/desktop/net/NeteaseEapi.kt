package moe.ouom.neriplayer.desktop.net

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * 网易云 eapi 请求封装。
 *
 * 明文接口（`/api/...`）只返回普通 LRC；逐词时间（yrc）只在 eapi 的
 * `/eapi/song/lyric/v1` 里，所以这里只实现这个接口需要的最小加密逻辑：
 * 摘要串做 MD5，再用 AES-128-ECB 加密成 hex，作为单个表单字段 `params` 发送。
 */
object NeteaseEapi {

    const val BASE = "https://interface.music.163.com"

    private const val AES_KEY = "e82ckenh8dichen8"
    private const val SEPARATOR = "36cd479b6b5"
    private const val SALT_FORMAT = "nobody%suse%smd5forencrypt"

    private val baseHeaders = mapOf(
        "Referer" to "https://music.163.com/",
        // 匿名也能取歌词；带 os/appver 让接口返回桌面版结构
        "Cookie" to "os=pc; appver=2.0.2",
    )

    /**
     * 以 eapi 形式 POST 一个接口。
     *
     * @param path eapi 路径，例如 `/eapi/song/lyric/v1`（签名时按对应的 `/api/...` 路径计算）
     * @param payload 明文参数，按插入顺序序列化成紧凑 JSON（顺序参与签名）
     * @return 原始响应文本；网络失败返回 null
     */
    fun postForm(http: HttpService, path: String, payload: Map<String, Any>): String? {
        val cipher = encrypt(path, payloadJson(payload)) ?: return null
        return http.postForm(
            url = BASE + path,
            form = mapOf("params" to cipher),
            headers = baseHeaders,
            timeoutSeconds = 20,
        )
    }

    /** 按 eapi 规则把明文参数加密成 hex（大写）。 */
    fun encrypt(path: String, payloadJson: String): String? = runCatching {
        val plainPath = path.replace("/eapi", "/api")
        val digest = md5Hex(SALT_FORMAT.format(plainPath, payloadJson))
        val message = "$plainPath-$SEPARATOR-$payloadJson-$SEPARATOR-$digest"
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(AES_KEY.toByteArray(Charsets.UTF_8), "AES"))
        cipher.doFinal(message.toByteArray(Charsets.UTF_8)).joinToString("") { "%02X".format(it) }
    }.getOrNull()

    /** Map 序列化成紧凑 JSON：数字保持数字，字符串保持字符串。 */
    private fun payloadJson(payload: Map<String, Any>): String = buildJsonObject {
        payload.forEach { (key, value) ->
            when (value) {
                is String -> put(key, value)
                is Number -> put(key, value)
                is Boolean -> put(key, value)
                else -> put(key, JsonPrimitive(value.toString()))
            }
        }
    }.toString()

    private fun md5Hex(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
