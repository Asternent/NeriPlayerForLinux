package moe.ouom.neriplayer.desktop.core

import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 系统托盘（桌面原生菜单）。
 *
 * 桌面侧用 **StatusNotifierItem + DBusMenu** 显示图标与菜单，菜单由桌面自己渲染，
 * 点菜单项通过 D-Bus 回调本程序，因此不存在「弹出的窗口收不到点击」的问题。
 *
 * 实现方式：主程序导出 [TrayAppInterface]（只含字符串 / 整数这类简单类型），
 * 由 [neriplayer-tray-bridge.py]（随包发布，用系统 python3 + GIO）负责注册指示器、
 * 组装 DBusMenu 并把点击转回来。
 *
 * 为什么不直接在 JVM 里注册：dbus-java 编组「结构体返回值」时会多包一层括号
 * （GetLayout 需要 `(u(ia{sv}av))`，它发出的是 `((u(ia{sv}av)))`），GNOME 会据此
 * 判定菜单为空——表现就是「点了图标没有菜单」。
 */

const val TRAY_APP_BUS_NAME = "org.neriplayer.Tray"
const val TRAY_APP_OBJECT_PATH = "/org/neriplayer/Tray"
private const val TRAY_APP_IFACE = "org.neriplayer.Tray"

/** 托盘桥接进程回调主程序的接口（只用简单类型，避免 dbus-java 编组问题）。 */
@DBusInterfaceName(TRAY_APP_IFACE)
interface TrayAppInterface : DBusInterface {
    fun Title(): String
    fun MenuModel(): String
    fun MenuEvent(id: Int, eventId: String)
    fun Activate()
    fun SecondaryActivate()
    fun Scroll(delta: Int, orientation: String)
}

/** 托盘菜单项（序列化成 JSON 交给桥接进程）。 */
private data class TrayMenuItem(
    val id: Int,
    val label: String,
    val type: String = "standard",
    val enabled: Boolean = true,
    val visible: Boolean = true,
    val toggle: String? = null,
    val checked: Boolean = false,
)

private object TrayMenuIds {
    const val PLAY_PAUSE = 1
    const val PREVIOUS = 2
    const val NEXT = 3
    const val SEPARATOR_TOP = 4
    const val SHOW_WINDOW = 5
    const val FLOATING_LYRICS = 6
    const val DOWNLOADS = 7
    const val SETTINGS = 8
    const val SEPARATOR_BOTTOM = 9
    const val OPEN_PANEL = 10
    const val QUIT = 12
}

class StatusNotifierService(
    private val title: () -> String,
    private val playing: () -> Boolean,
    private val floatingLyricsEnabled: () -> Boolean,
    private val onTogglePlay: () -> Unit,
    private val onPrevious: () -> Unit,
    private val onNext: () -> Unit,
    private val onShowWindow: () -> Unit,
    private val onToggleFloatingLyrics: () -> Unit,
    private val onOpenDownloads: () -> Unit,
    private val onOpenSettings: () -> Unit,
    private val onOpenPanel: () -> Unit,
    private val onQuit: () -> Unit,
) {
    private var connection: DBusConnection? = null
    private var exported: ExportedTrayApp? = null
    private var bridge: Process? = null
    private var bridgeLog: File? = null

    val running: Boolean get() = bridge?.isAlive == true

    /** 启动托盘桥接；桌面不支持或缺少 python3-gi 时返回 false（调用方回退到 AWT 托盘）。 */
    fun start(): Boolean {
        if (running) return true
        return runCatching {
            if (!pythonBridgeAvailable()) {
                println("[tray] 未找到 python3-gi，回退到 AWT 托盘图标")
                return@runCatching false
            }
            val conn = DBusConnectionBuilder.forSessionBus().build()
            val app = ExportedTrayApp()
            conn.exportObject(TRAY_APP_OBJECT_PATH, app)
            conn.requestBusName(TRAY_APP_BUS_NAME)
            connection = conn
            exported = app
            if (!spawnBridge()) {
                stop()
                return@runCatching false
            }
            true
        }.getOrElse { error ->
            println("[tray] 托盘桥接启动失败（回退到 AWT 托盘）：${error.message}")
            stop()
            false
        }
    }

    fun stop() {
        runCatching { bridge?.destroy() }
        bridge = null
        runCatching { connection?.disconnect() }
        connection = null
        exported = null
    }

    /** 菜单文案变化后调用；桥接进程也会定期拉取，这里只是让变化更快生效。 */
    fun refresh() = Unit

    private fun menuModelJson(): String {
        val items = buildList {
            add(TrayMenuItem(TrayMenuIds.PLAY_PAUSE, if (playing()) "暂停" else "播放"))
            add(TrayMenuItem(TrayMenuIds.PREVIOUS, "上一首"))
            add(TrayMenuItem(TrayMenuIds.NEXT, "下一首"))
            add(TrayMenuItem(TrayMenuIds.SEPARATOR_TOP, "", type = "separator"))
            add(TrayMenuItem(TrayMenuIds.SHOW_WINDOW, "显示主窗口"))
            add(
                TrayMenuItem(
                    TrayMenuIds.FLOATING_LYRICS,
                    if (floatingLyricsEnabled()) "悬浮歌词（已开启）" else "开启悬浮歌词",
                    toggle = "checkmark",
                    checked = floatingLyricsEnabled(),
                ),
            )
            add(TrayMenuItem(TrayMenuIds.DOWNLOADS, "下载管理"))
            add(TrayMenuItem(TrayMenuIds.SETTINGS, "设置"))
            add(TrayMenuItem(TrayMenuIds.SEPARATOR_BOTTOM, "", type = "separator"))
            add(TrayMenuItem(TrayMenuIds.OPEN_PANEL, "打开控制面板"))
            add(TrayMenuItem(TrayMenuIds.QUIT, "退出 NeriPlayer"))
        }
        return items.joinToString(prefix = "[", postfix = "]") { item ->
            val parts = mutableListOf(
                "\"id\":${item.id}",
                "\"label\":${jsonString(item.label)}",
                "\"type\":${jsonString(item.type)}",
                "\"enabled\":${item.enabled}",
                "\"visible\":${item.visible}",
            )
            item.toggle?.let {
                parts += "\"toggle\":${jsonString(it)}"
                parts += "\"checked\":${item.checked}"
            }
            "{${parts.joinToString(",")}}"
        }
    }

    private fun jsonString(value: String): String {
        val builder = StringBuilder("\"")
        value.forEach { ch ->
            when (ch) {
                '\\' -> builder.append("\\\\")
                '"' -> builder.append("\\\"")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                else -> if (ch < ' ') builder.append("\\u%04x".format(ch.code)) else builder.append(ch)
            }
        }
        return builder.append('"').toString()
    }

    private fun handleMenuEvent(id: Int) {
        println("[tray] 菜单项点击 id=$id")
        when (id) {
            TrayMenuIds.PLAY_PAUSE -> onTogglePlay()
            TrayMenuIds.PREVIOUS -> onPrevious()
            TrayMenuIds.NEXT -> onNext()
            TrayMenuIds.SHOW_WINDOW -> onShowWindow()
            TrayMenuIds.FLOATING_LYRICS -> onToggleFloatingLyrics()
            TrayMenuIds.DOWNLOADS -> onOpenDownloads()
            TrayMenuIds.SETTINGS -> onOpenSettings()
            TrayMenuIds.OPEN_PANEL -> onOpenPanel()
            TrayMenuIds.QUIT -> onQuit()
        }
    }

    private fun pythonBridgeAvailable(): Boolean = runCatching {
        val process = ProcessBuilder("python3", "-c", "import gi; gi.require_version('Gio','2.0')")
            .redirectErrorStream(true)
            .start()
        process.waitFor(4, TimeUnit.SECONDS) && process.exitValue() == 0
    }.getOrDefault(false)

    /** 把打包在 jar 里的桥接脚本与托盘图标释放到临时目录，然后启动桥接进程。 */
    private fun spawnBridge(): Boolean {
        val runtimeDir = File(System.getProperty("java.io.tmpdir"), "neriplayer-tray")
        runtimeDir.mkdirs()
        val script = File(runtimeDir, "neriplayer-tray-bridge.py")
        val icon = File(runtimeDir, "neriplayer-tray.png")
        val scriptStream = javaClass.getResourceAsStream("/neriplayer-tray-bridge.py") ?: return false
        scriptStream.use { input -> script.outputStream().use { output -> input.copyTo(output) } }
        runCatching {
            javaClass.getResourceAsStream("/neriplayer-tray.png")?.use { input ->
                icon.outputStream().use { output -> input.copyTo(output) }
            }
        }
        runCatching { script.setExecutable(true) }

        val logFile = File(runtimeDir, "bridge.log")
        bridgeLog = logFile
        val process = ProcessBuilder(
            "python3", script.absolutePath,
            "--app", TRAY_APP_BUS_NAME,
            "--service", "org.kde.StatusNotifierItem-${ProcessHandle.current().pid()}-1",
            "--icon", icon.absolutePath,
        ).redirectErrorStream(true).redirectOutput(logFile).start()
        bridge = process

        // 等桥接进程注册成功（日志里出现「已注册托盘指示器」）
        val deadline = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive) break
            val text = runCatching { logFile.readText() }.getOrDefault("")
            if (text.contains("已注册托盘指示器")) {
                println("[tray] 已启用桌面原生托盘菜单（StatusNotifierItem + DBusMenu）")
                return true
            }
            Thread.sleep(200)
        }
        val text = runCatching { logFile.readText().trim() }.getOrDefault("")
        println("[tray] 托盘桥接未注册成功${if (text.isEmpty()) "" else "：$text"}")
        return false
    }

    private inner class ExportedTrayApp : TrayAppInterface {
        override fun getObjectPath(): String = TRAY_APP_OBJECT_PATH

        override fun Title(): String = title()

        override fun MenuModel(): String = menuModelJson()

        override fun MenuEvent(id: Int, eventId: String) {
            if (eventId == "clicked") handleMenuEvent(id)
        }

        override fun Activate() = onShowWindow()

        override fun SecondaryActivate() = onShowWindow()

        override fun Scroll(delta: Int, orientation: String) {
            if (delta > 0) onNext() else onPrevious()
        }
    }
}
