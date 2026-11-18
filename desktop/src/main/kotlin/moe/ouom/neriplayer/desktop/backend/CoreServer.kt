package moe.ouom.neriplayer.desktop.backend

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong

/**
 * 无头后端的 IPC 服务器。
 *
 * 一条 Unix domain socket 上跑「按行分隔的 JSON」：
 *   请求 {"id":1,"method":"player.play","params":{}}
 *   响应 {"id":1,"result":{...}} / {"id":1,"error":{"code":"...","message":"..."}}
 *   事件 {"event":"player","data":{...}}
 *
 * 命令处理函数在 [CoroutineScope] 上执行，互不阻塞；写回客户端时加锁保证整行完整。
 */
class CoreServer(
    private val socketPath: Path,
    private val onFirstClient: (ClientSession) -> Unit = {},
) {

    /** 供 API 注册命令用。 */
    fun on(method: String, handler: suspend (JsonObject) -> JsonElement) {
        handlers[method] = handler
    }

    fun start() {
        Files.createDirectories(socketPath.parent)
        Files.deleteIfExists(socketPath)
        val channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        channel.bind(UnixDomainSocketAddress.of(socketPath))
        runCatching {
            Files.setPosixFilePermissions(socketPath, PosixFilePermissions.fromString("rw-------"))
        }
        serverChannel = channel
        Thread({ acceptLoop(channel) }, "neriplayer-core-accept").apply {
            isDaemon = true
            start()
        }
        log("listening on $socketPath")
    }

    fun awaitTermination() {
        stopLatch.await()
    }

    fun close() {
        running = false
        runCatching { serverChannel?.close() }
        clients.forEach { it.close() }
        clients.clear()
        runCatching { Files.deleteIfExists(socketPath) }
        stopLatch.countDown()
    }

    /** 向所有已连接的前端广播事件。 */
    fun broadcast(event: String, data: JsonElement) {
        if (clients.isEmpty()) return
        val line = buildJsonObject {
            put("event", event)
            put("data", data)
        }.toString()
        for (client in clients) {
            runCatching { client.send(line) }.onFailure { clients.remove(client) }
        }
    }

    fun broadcastMap(event: String, data: Map<String, Any?>) = broadcast(event, encodeAny(data))

    private fun acceptLoop(channel: ServerSocketChannel) {
        while (running) {
            val socket = runCatching { channel.accept() }.getOrNull() ?: break
            val session = ClientSession(socket)
            clients.add(session)
            log("client connected (${clients.size} total)")
            onFirstClient(session)
            session.startReader()
        }
    }

    private fun dispatch(session: ClientSession, request: JsonObject) {
        val id = (request["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: -1L
        val method = request.stringOrNull("method")
        if (method == null) {
            session.send(errorLine(id, "bad_request", "缺少 method"))
            return
        }
        val params = request.objectOrNull("params") ?: JsonObject(emptyMap())
        scope.launch {
            try {
                val handler = handlers[method]
                    ?: throw ProtocolException("unknown_method", "未知方法: $method")
                val result = handler(params)
                session.send(resultLine(id, result))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: ProtocolException) {
                session.send(errorLine(id, error.code, error.message))
            } catch (error: Throwable) {
                log("command $method failed: ${error::class.simpleName}: ${error.message}")
                session.send(errorLine(id, "internal_error", error.message ?: error.toString()))
            }
        }
    }

    private fun resultLine(id: Long, result: JsonElement): String = buildJsonObject {
        put("id", id)
        put("result", result)
    }.toString()

    private fun errorLine(id: Long, code: String, message: String): String = buildJsonObject {
        put("id", id)
        putJsonObject("error") {
            put("code", code)
            put("message", message)
        }
    }.toString()

    /** 单个前端连接。 */
    inner class ClientSession(private val channel: SocketChannel) {
        private val writeLock = Any()
        private val writer = BufferedWriter(
            OutputStreamWriter(Channels.newOutputStream(channel), StandardCharsets.UTF_8)
        )

        fun startReader() {
            Thread({
                try {
                    val reader = BufferedReader(InputStreamReader(Channels.newInputStream(channel), StandardCharsets.UTF_8))
                    while (running) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank()) continue
                        val request = runCatching { WireJson.parseToJsonElement(line).jsonObject }.getOrElse {
                            log("bad frame: ${line.take(160)}")
                            continue
                        }
                        dispatch(this, request)
                    }
                } catch (_: Throwable) {
                    // 客户端断开是正常现象
                } finally {
                    clients.remove(this)
                    runCatching { channel.close() }
                    log("client disconnected (${clients.size} left)")
                }
            }, "neriplayer-core-client").apply {
                isDaemon = true
                start()
            }
        }

        fun send(line: String) {
            synchronized(writeLock) {
                writer.write(line)
                writer.newLine()
                writer.flush()
            }
        }

        fun close() {
            runCatching { channel.close() }
        }
    }

    private val handlers = ConcurrentHashMap<String, suspend (JsonObject) -> JsonElement>()
    private val clients = CopyOnWriteArrayList<ClientSession>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stopLatch = CountDownLatch(1)
    private val idCounter = AtomicLong(0L)

    @Volatile
    private var running = true

    @Volatile
    private var serverChannel: ServerSocketChannel? = null
}

internal fun log(message: String) {
    System.err.println("[core] $message")
}
