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
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch

/**
 * 无头后端的 IPC 服务器。
 *
 * 一条 Unix domain socket 上跑「按行分隔的 JSON」：
 *   请求 {"id":1,"method":"player.play","params":{}}
 *   响应 {"id":1,"result":{...}} / {"id":1,"error":{"code":"...","message":"..."}}
 *   事件 {"event":"player","data":{...}}
 *
 * 命令处理函数在 [scope] 上并发执行；每个连接的读线程只负责拆包，写用同一把锁保证整行完整。
 * 直接操作 SocketChannel + ByteBuffer（不用 java.io 流适配器，避免半关闭状态下的异常行为）。
 */
class CoreServer(
    private val socketPath: Path,
    private val onClientConnected: (ClientSession) -> Unit = {},
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
            if (!client.send(line))
                clients.remove(client)
        }
    }

    fun broadcastMap(event: String, data: Map<String, Any?>) = broadcast(event, encodeAny(data))

    private fun acceptLoop(channel: ServerSocketChannel) {
        while (running) {
            val socket = runCatching { channel.accept() }.getOrNull() ?: break
            val session = ClientSession(socket)
            clients.add(session)
            log("client connected (${clients.size} total)")
            session.startReader()
            runCatching { onClientConnected(session) }
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
                if (!session.send(resultLine(id, result)))
                    log("failed to reply $method (client gone)")
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

    /** 单个前端连接：一个读线程 + 加锁写。 */
    inner class ClientSession(private val channel: SocketChannel) {
        private val writeLock = Any()

        fun startReader() {
            Thread({ readLoop() }, "neriplayer-core-client").apply {
                isDaemon = true
                start()
            }
        }

        private fun readLoop() {
            val buffer = ByteBuffer.allocate(64 * 1024)
            val pending = ByteArrayOutputStream()
            try {
                while (running && channel.isOpen) {
                    buffer.clear()
                    val read = channel.read(buffer)
                    if (read < 0)
                        break
                    if (read == 0)
                        continue
                    buffer.flip()
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    pending.write(bytes)
                    // 逐行拆包
                    while (true) {
                        val data = pending.toByteArray()
                        val newline = data.indexOf('\n'.code.toByte())
                        if (newline < 0)
                            break
                        val line = String(data, 0, newline, StandardCharsets.UTF_8).trim()
                        val rest = data.copyOfRange(newline + 1, data.size)
                        pending.reset()
                        pending.write(rest)
                        if (line.isEmpty())
                            continue
                        val request = runCatching { WireJson.parseToJsonElement(line).jsonObject }
                            .getOrElse {
                                log("bad frame: ${line.take(160)}")
                                continue
                            }
                        dispatch(this, request)
                    }
                }
            } catch (error: Throwable) {
                if (running && channel.isOpen)
                    log("client reader stopped: ${error::class.simpleName}: ${error.message}")
            } finally {
                runCatching { channel.close() }
                clients.remove(this)
                log("client disconnected (${clients.size} left)")
            }
        }

        /** 写一行；连接已断开时返回 false。 */
        fun send(line: String): Boolean {
            val payload = (line + "\n").toByteArray(StandardCharsets.UTF_8)
            synchronized(writeLock) {
                if (!channel.isOpen)
                    return false
                val buffer = ByteBuffer.wrap(payload)
                return try {
                    while (buffer.hasRemaining())
                        channel.write(buffer)
                    true
                } catch (_: Throwable) {
                    close()
                    false
                }
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

    @Volatile
    private var running = true

    @Volatile
    private var serverChannel: ServerSocketChannel? = null
}

internal fun log(message: String) {
    System.err.println("[core] $message")
}
