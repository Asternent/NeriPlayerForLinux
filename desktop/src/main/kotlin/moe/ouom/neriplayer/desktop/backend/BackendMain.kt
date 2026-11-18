package moe.ouom.neriplayer.desktop.backend

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.awaitCancellation
import moe.ouom.neriplayer.desktop.backend.api.AppApi
import moe.ouom.neriplayer.desktop.backend.api.ArtworkApi
import moe.ouom.neriplayer.desktop.backend.api.CollectionsApi
import moe.ouom.neriplayer.desktop.backend.api.DownloadApi
import moe.ouom.neriplayer.desktop.backend.api.LibraryApi
import moe.ouom.neriplayer.desktop.backend.api.LoginApi
import moe.ouom.neriplayer.desktop.backend.api.OnlineApi
import moe.ouom.neriplayer.desktop.backend.api.PlayerApi
import moe.ouom.neriplayer.desktop.backend.api.SyncApi
import moe.ouom.neriplayer.desktop.core.AppContainer
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 无头后端入口：Qt 前端启动时用 `java -jar neriplayer-core.jar` 拉起本进程，
 * 然后通过 Unix socket 上的 JSON 协议通信。
 *
 * 参数：
 *   --socket <path>   指定 socket 路径（默认 $XDG_RUNTIME_DIR/neriplayer/core.sock）
 *   --no-scan         启动时不扫描本地媒体库（调试/截图用）
 */
fun main(args: Array<String>) {
    val socketPath = resolveSocketPath(args)
    val noScan = args.contains("--no-scan") || System.getenv("NERIPLAYER_NO_SCAN") == "1"

    if (existingInstanceRunning(socketPath)) {
        log("another instance is already running at $socketPath, exiting")
        return
    }

    val container = AppContainer()
    lateinit var server: CoreServer
    server = CoreServer(socketPath) { AppApi.pushInitialState(server, container) }
    AppApi.register(server, container)
    PlayerApi.register(server, container)
    LibraryApi.register(server, container)
    CollectionsApi.register(server, container)
    OnlineApi.register(server, container)
    LoginApi.register(server, container)
    DownloadApi.register(server, container)
    SyncApi.register(server, container)
    ArtworkApi.register(server, container)
    val publisher = StatePublisher(server, container)

    // 先装好命令与事件，再启动核心（bootstrap 期间产生的状态会由 StatePublisher 推出去）
    container.bootstrap(scanLibrary = !noScan)
    val runtime = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default
    )
    val tray = TrayService(container, server)
    tray.start(runtime)
    AppApi.trayAvailable = tray.running
    val shortcuts = GlobalShortcutService(container, server)
    shortcuts.start(runtime)
    AppApi.openGlobalShortcutSettings = { shortcuts.openConfigure() }
    server.start()
    publisher.start()

    Runtime.getRuntime().addShutdownHook(Thread {
        log("shutting down")
        runCatching { container.player.persistQueueState() }
        runCatching { tray.stop() }
        runCatching { shortcuts.stop() }
        server.close()
    })

    log("neriplayer core ready")
    server.awaitTermination()
    runBlocking { awaitCancellation() }
}

private fun resolveSocketPath(args: Array<String>): Path {
    val index = args.indexOf("--socket")
    if (index >= 0 && index + 1 < args.size) return Paths.get(args[index + 1])
    System.getenv("NERIPLAYER_SOCKET")?.takeIf { it.isNotBlank() }?.let { return Paths.get(it) }
    val runtimeDir = System.getenv("XDG_RUNTIME_DIR")?.takeIf { it.isNotBlank() }
        ?: System.getProperty("java.io.tmpdir")
    return Paths.get(runtimeDir, "neriplayer", "core.sock")
}

private fun existingInstanceRunning(socketPath: Path): Boolean {
    if (!Files.exists(socketPath)) return false
    return runCatching {
        val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
        channel.connect(UnixDomainSocketAddress.of(socketPath))
        channel.close()
        true
    }.getOrDefault(false)
}
