package moe.ouom.neriplayer.desktop.net

import moe.ouom.neriplayer.desktop.core.ListenTogetherControlEvent
import moe.ouom.neriplayer.desktop.core.ListenTogetherControlResponse
import moe.ouom.neriplayer.desktop.core.ListenTogetherCreateRoomRequest
import moe.ouom.neriplayer.desktop.core.ListenTogetherJoinRoomRequest
import moe.ouom.neriplayer.desktop.core.ListenTogetherJson
import moe.ouom.neriplayer.desktop.core.ListenTogetherLeaveRoomResponse
import moe.ouom.neriplayer.desktop.core.ListenTogetherRoomResponse
import moe.ouom.neriplayer.desktop.core.ListenTogetherStateResponse
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/**
 * 一起听服务端 HTTP 客户端。
 *
 * 复用 [HttpService] 的 java.net.http.HttpClient（连接复用、超时、UA）；错误响应里
 * worker.js 会返回 `{ok:false,error:"..."}`，所以即使非 2xx 也按协议模型解析。
 */
class ListenTogetherHttpClient(private val http: HttpService) {

    data class Result<T>(val value: T?, val status: Int, val error: String?) {
        val ok: Boolean get() = error == null && value != null && status in 200..299
    }

    suspend fun createRoom(
        baseUrl: String,
        request: ListenTogetherCreateRoomRequest,
    ): Result<ListenTogetherRoomResponse> = post(
        url = "$baseUrl/api/rooms",
        body = ListenTogetherJson.encodeToString(request),
    )

    suspend fun joinRoom(
        baseUrl: String,
        roomId: String,
        request: ListenTogetherJoinRoomRequest,
        bearerToken: String? = null,
    ): Result<ListenTogetherRoomResponse> = post(
        url = "$baseUrl/api/rooms/$roomId/join",
        body = ListenTogetherJson.encodeToString(request),
        bearerToken = bearerToken,
    )

    suspend fun getState(
        baseUrl: String,
        roomId: String,
        token: String,
    ): Result<ListenTogetherStateResponse> = get(
        url = "$baseUrl/api/rooms/$roomId/state",
        bearerToken = token,
    )

    suspend fun leaveRoom(
        baseUrl: String,
        roomId: String,
        token: String,
    ): Result<ListenTogetherLeaveRoomResponse> = post(
        url = "$baseUrl/api/rooms/$roomId/leave",
        body = "{}",
        bearerToken = token,
    )

    suspend fun sendControlEvent(
        baseUrl: String,
        roomId: String,
        token: String,
        event: ListenTogetherControlEvent,
    ): Result<ListenTogetherControlResponse> = post(
        url = "$baseUrl/api/rooms/$roomId/control",
        body = ListenTogetherJson.encodeToString(event),
        bearerToken = token,
    )

    /** GET /healthz：用于「服务端可用性」提示；不要求一定是官方 worker 实现。 */
    suspend fun healthz(baseUrl: String): Boolean {
        val response = runCatching { http.execute("GET", "$baseUrl/healthz", timeoutSeconds = 6) }
            .getOrNull() ?: return false
        if (response.status !in 200..299) return false
        val text = response.text()
        return text.contains("neriplayer-listen-together-worker", ignoreCase = true) ||
            (text.contains("\"ok\"") && text.contains("true"))
    }

    private inline fun <reified T> get(url: String, bearerToken: String? = null): Result<T> {
        val response = runCatching {
            http.execute("GET", url, headers = authHeaders(bearerToken), timeoutSeconds = 15)
        }.getOrElse { return networkFailure(it) }
            ?: return Result(null, 0, "无法连接一起听服务端")
        return decode(url, response.status, response.text())
    }

    private inline fun <reified T> post(
        url: String,
        body: String,
        bearerToken: String? = null,
    ): Result<T> {
        val response = runCatching {
            http.execute(
                method = "POST",
                url = url,
                body = body,
                headers = authHeaders(bearerToken) + mapOf("Content-Type" to "application/json; charset=utf-8"),
                timeoutSeconds = 20,
            )
        }.getOrElse { return networkFailure(it) }
            ?: return Result(null, 0, "无法连接一起听服务端")
        return decode(url, response.status, response.text())
    }

    private inline fun <reified T> decode(url: String, status: Int, body: String): Result<T> {
        val text = body.trim()
        val parsed = runCatching { ListenTogetherJson.decodeFromString<T>(text) }.getOrNull()
        if (parsed != null) return Result(parsed, status, null)
        val hint = when {
            text.isEmpty() -> "空响应"
            else -> text.take(180)
        }
        return Result(null, status, "服务端返回异常（HTTP $status）：$hint")
    }

    private fun authHeaders(token: String?): Map<String, String> =
        if (token.isNullOrBlank()) emptyMap() else mapOf("Authorization" to "Bearer $token")

    private fun <T> networkFailure(error: Throwable): Result<T> {
        if (error is CancellationException) throw error
        val hint = error.message ?: error::class.simpleName.orEmpty()
        return Result(null, 0, "无法连接一起听服务端：$hint")
    }
}

/**
 * 一起听 WebSocket 客户端，基于 java.net.http.WebSocket。
 *
 * 回调都在 HttpClient 的线程上触发，调用方必须自行切回协程/线程再处理。
 */
class ListenTogetherWebSocketClient(
    private val onOpen: () -> Unit,
    private val onText: (String) -> Unit,
    private val onClosed: () -> Unit,
    private val onFailure: (String) -> Unit,
) {
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    private val lock = Any()
    private val finished = AtomicBoolean(false)

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var manuallyClosed = false

    @Volatile
    private var opened = false

    val isOpen: Boolean get() = opened && !manuallyClosed && socket != null

    fun connect(wsUrl: String) {
        closeInternal(notify = false)
        manuallyClosed = false
        opened = false
        finished.set(false)

        val listener = object : WebSocket.Listener {
            private val buffer = StringBuilder()

            override fun onOpen(webSocket: WebSocket) {
                opened = true
                webSocket.request(1)
                onOpen()
            }

            override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                if (buffer.length + data.length > MAX_WS_TEXT_CHARS) {
                    buffer.setLength(0)
                    fail("WebSocket 消息过大")
                    webSocket.abort()
                    return null
                }
                buffer.append(data)
                if (last) {
                    val text = buffer.toString()
                    buffer.setLength(0)
                    onText(text)
                }
                webSocket.request(1)
                return null
            }

            override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
                opened = false
                socket = null
                if (!manuallyClosed) onClosed()
                return null
            }

            override fun onError(webSocket: WebSocket, error: Throwable) {
                opened = false
                socket = null
                if (!manuallyClosed) fail(error.message ?: error::class.simpleName.orEmpty())
            }
        }

        val future = runCatching {
            client.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(12))
                .buildAsync(URI.create(wsUrl), listener)
        }.getOrElse {
            fail("无法建立 WebSocket 连接：${it.message}")
            return
        }
        future.whenComplete { webSocket, error ->
            if (error != null) {
                if (!manuallyClosed) fail(error.cause?.message ?: error.message ?: "WebSocket 连接失败")
            } else if (webSocket != null) {
                synchronized(lock) { socket = webSocket }
            }
        }
    }

    /** 同步发送一帧文本；调用线程会短暂阻塞（仅在连接异常时）。 */
    fun send(text: String): Boolean {
        if (!isOpen) return false
        val webSocket = synchronized(lock) { socket } ?: return false
        return runCatching {
            synchronized(lock) {
                webSocket.sendText(text, true).get(5, TimeUnit.SECONDS)
            }
            true
        }.getOrElse { false }
    }

    fun close() = closeInternal(notify = false)

    private fun closeInternal(notify: Boolean) {
        manuallyClosed = true
        opened = false
        val webSocket = synchronized(lock) {
            val current = socket
            socket = null
            current
        }
        runCatching { webSocket?.sendClose(WebSocket.NORMAL_CLOSURE, "client_closed") }
        if (notify) onClosed()
    }

    private fun fail(message: String) {
        if (finished.compareAndSet(false, true)) onFailure(message)
    }

    private companion object {
        const val MAX_WS_TEXT_CHARS = 2 * 1024 * 1024
    }
}
