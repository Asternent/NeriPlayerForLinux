package moe.ouom.neriplayer.desktop.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

object NeriJsonParser {
    val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

    fun parse(text: String): JsonElement? = runCatching { json.parseToJsonElement(text) }.getOrNull()
}

fun JsonElement?.asObject(): JsonObject? = (this as? JsonObject)

fun JsonObject.str(key: String): String? {
    val value = this[key] ?: return null
    if (value is JsonNull) return null
    return (value as? JsonPrimitive)?.contentOrNullSafe()
}

private fun JsonPrimitive.contentOrNullSafe(): String? =
    if (this is JsonNull) null else runCatching { content }.getOrNull()

fun JsonObject.long(key: String): Long? = str(key)?.let { raw ->
    raw.toLongOrNull() ?: raw.toDoubleOrNull()?.toLong()
}

fun JsonObject.int(key: String): Int? = str(key)?.let { raw ->
    raw.toIntOrNull() ?: raw.toDoubleOrNull()?.toInt()
}

fun JsonObject.double(key: String): Double? = str(key)?.toDoubleOrNull()

fun JsonObject.obj(key: String): JsonObject? = this[key].asObject()

fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

fun JsonArray.objects(): List<JsonObject> = this.mapNotNull { it as? JsonObject }

/** 轻量 HTTP 客户端，带 Cookie 记忆。 */
class HttpService {

    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(12))
        .build()

    private val cookieJar = ConcurrentHashMap<String, MutableMap<String, String>>()

    /** Cookie 按平台分组，让登录态可以在同一平台的子域之间共享。 */
    fun jarKeyFor(host: String): String = when {
        host.endsWith("bilibili.com") -> "bilibili"
        host.endsWith("music.163.com") || host.endsWith("163.com") -> "netease"
        else -> host
    }

    fun cookiesFor(host: String): Map<String, String> = cookieJar[jarKeyFor(host)].orEmpty()

    fun cookieSnapshot(key: String): Map<String, String> = cookieJar[key].orEmpty().toMap()

    fun setCookie(host: String, name: String, value: String) {
        cookieJar.getOrPut(jarKeyFor(host)) { ConcurrentHashMap() }[name] = value
    }

    fun importCookies(key: String, cookies: Map<String, String>) {
        val jar = cookieJar.getOrPut(key) { ConcurrentHashMap() }
        cookies.forEach { (name, value) -> if (value.isNotBlank()) jar[name] = value }
    }

    fun clearCookies(key: String) {
        cookieJar.remove(key)
    }

    private fun storeCookies(host: String, response: HttpResponse<*>) {
        val values = response.headers().allValues("set-cookie")
        if (values.isEmpty()) return
        val jar = cookieJar.getOrPut(jarKeyFor(host)) { ConcurrentHashMap() }
        values.forEach { header ->
            val pair = header.substringBefore(';')
            val name = pair.substringBefore('=', "").trim()
            val value = pair.substringAfter('=', "").trim()
            if (name.isNotEmpty() && value.isNotEmpty()) jar[name] = value
        }
    }

    private fun cookieHeaderFor(host: String): String? =
        cookieJar[jarKeyFor(host)]?.takeIf { it.isNotEmpty() }
            ?.entries?.joinToString("; ") { "${it.key}=${it.value}" }

    fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutSeconds: Long = 15,
    ): String? = request("GET", url, null, headers, timeoutSeconds)

    fun postForm(
        url: String,
        form: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
        timeoutSeconds: Long = 15,
    ): String? {
        val body = form.entries.joinToString("&") { (key, value) ->
            "${urlEncode(key)}=${urlEncode(value)}"
        }
        return request("POST", url, body, headers + mapOf("Content-Type" to "application/x-www-form-urlencoded"), timeoutSeconds)
    }

    private fun request(
        method: String,
        url: String,
        body: String?,
        headers: Map<String, String>,
        timeoutSeconds: Long,
    ): String? {
        return runCatching {
            val host = runCatching { URI(url).host }.getOrNull().orEmpty()
            val builder = HttpRequest.newBuilder(URI(url))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("User-Agent", DESKTOP_USER_AGENT)
                .header("Accept", "*/*")
                .header("Accept-Encoding", "gzip")
            headers.forEach { (key, value) -> builder.header(key, value) }
            cookieHeaderFor(host)?.let { builder.header("Cookie", it) }
            if (method == "POST") {
                builder.POST(HttpRequest.BodyPublishers.ofString(body.orEmpty()))
            } else {
                builder.GET()
            }
            val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
            storeCookies(host, response)
            val bytes = response.body() ?: return@runCatching null
            val decoded = if (response.headers().firstValue("Content-Encoding").orElse("").contains("gzip")) {
                runCatching {
                    GZIPInputStream(bytes.inputStream()).use { stream ->
                        val out = ByteArrayOutputStream()
                        stream.copyTo(out)
                        out.toByteArray()
                    }
                }.getOrDefault(bytes)
            } else {
                bytes
            }
            decoded.decodeToString()
        }.getOrNull()
    }

    fun download(url: String, headers: Map<String, String> = emptyMap()): ByteArray? = runCatching {
        val host = runCatching { URI(url).host }.getOrNull().orEmpty()
        val builder = HttpRequest.newBuilder(URI(url))
            .timeout(Duration.ofSeconds(25))
            .header("User-Agent", DESKTOP_USER_AGENT)
        headers.forEach { (key, value) -> builder.header(key, value) }
        cookieHeaderFor(host)?.let { builder.header("Cookie", it) }
        val response = client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofByteArray())
        storeCookies(host, response)
        response.body()
    }.getOrNull()
}

fun urlEncode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)
