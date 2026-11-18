package moe.ouom.neriplayer.desktop.core

import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.DBusSigHandler
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import java.util.AbstractMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 通过 xdg-desktop-portal 的 GlobalShortcuts 注册全局快捷键。
 *
 * 流程：CreateSession → BindShortcuts（桌面弹授权/配置窗口）→ 监听 Activated。
 * Wayland 下这是唯一跨桌面可用的全局快捷键方式；桌面不支持时会失败并静默降级。
 *
 * 最终生效的按键由用户在系统对话框里确认，应用只能提供 preferred_trigger 建议值。
 */
class GlobalShortcutsPortal(
    private val onActivated: (String) -> Unit,
) {
    @Volatile private var connection: DBusConnection? = null
    @Volatile private var sessionHandle: String? = null
    @Volatile private var registered = false

    /** 常驻的 Response 监听（每连接注册一次），按 request handle 分发到等待方。 */
    private val responseListener = ConcurrentHashMap<String, ResponseWaiter>()

    private class ResponseWaiter {
        val latch = CountDownLatch(1)
        @Volatile var payload: Map<String, Variant<*>?>? = null
        @Volatile var done = false
    }

    val isActive: Boolean get() = registered

    /** 在连接上注册常驻 Response/Activated 监听，幂等。 */
    private fun ensurePortalListener(conn: DBusConnection) {
        conn.addSigHandler(PortalRequestSignals.Response::class.java) { signal ->
            val waiter = responseListener[signal.path] ?: return@addSigHandler
            if (!waiter.done) {
                waiter.done = true
                if (signal.code.toInt() == 0) waiter.payload = signal.results
                waiter.latch.countDown()
            }
        }
    }

    /** 把绑定转成 xdg shortcuts spec 的触发器字符串，例如 "CTRL+ALT+l"。 */
    private fun triggerOf(binding: ShortcutBinding): String? {
        if (!binding.isBound) return null
        val key = xdgKeyName(binding.key) ?: return null
        val mods = buildList {
            if (binding.ctrl) add("CTRL")
            if (binding.alt) add("ALT")
            if (binding.shift) add("SHIFT")
            if (binding.meta) add("LOGO")
        }
        return (mods + key).joinToString("+")
    }

    /**
     * 注册所有支持全局快捷键的动作。桌面端对已由用户配置过的键保留用户设置，
     * 未配置过的键按内置建议值直接生效；后续改键统一在系统设置里完成。
     * 没有内置建议值的动作也注册进系统（不带 preferred_trigger），
     * 这样它会出现在系统快捷键列表里，只是默认未绑定。
     */
    fun registerDefaults(): Boolean {
        val bindings = ShortcutAction.entries
            .filter { it.globalSupported }
            .map { it to it.defaultGlobalBinding }
        return register(bindings)
    }

    /** 打开桌面系统的全局快捷键配置界面（KDE 弹配置窗口；GNOME 未实现则返回 false）。 */
    fun openConfigure(): Boolean {
        val conn = connection ?: return false
        val session = sessionHandle ?: return false
        return runCatching {
            val portal = conn.getRemoteObject(
                PORTAL_BUS,
                PORTAL_PATH,
                GlobalShortcutsPortalInterface::class.java,
                false,
            )
            portal.ConfigureShortcuts(
                org.freedesktop.dbus.DBusPath(session),
                "",
                HashMap<String, Variant<*>>(),
            )
            println("[shortcut] 已打开系统全局快捷键配置")
            true
        }.getOrElse { error ->
            println("[shortcut] 打开系统配置失败：${error.message}")
            false
        }
    }

    /**
     * 注册绑定，返回是否成功。
     * [bindings] 只应包含 globalSupported 的动作；未绑定的动作仅注册条目，
     * 不携带 preferred_trigger，让它在系统快捷键列表可见但默认未绑定。
     */
    fun register(bindings: List<Pair<ShortcutAction, ShortcutBinding>>): Boolean {
        val entries = bindings.mapNotNull { (action, binding) ->
            val trigger = triggerOf(binding)
            action.id to trigger
        }
        println(
            "[shortcut] 待注册全局绑定：${entries.joinToString { (id, t) -> "$id=${t ?: "未绑定"}" }}",
        )
        if (entries.isEmpty()) {
            println("[shortcut] 无全局快捷键动作，跳过注册")
            return false
        }
        return runCatching {
            val conn = DBusConnectionBuilder.forSessionBus().build()
            connection = conn
            // 非沙箱进程需要先通过 Registry 声明 app id，portal（≥1.16）才允许后续调用；
            // 否则 CreateSession 报 "An app id is required"。.desktop 进标准目录后 portal
            // 会自动关联，此时 Register 会报 already associated，属正常，忽略即可。
            runCatching {
                val registry = conn.getRemoteObject(
                    PORTAL_BUS,
                    PORTAL_PATH,
                    PortalRegistryInterface::class.java,
                    false,
                )
                registry.Register(APP_ID, HashMap())
                println("[shortcut] Registry.Register 成功，app id=$APP_ID")
            }.onFailure { e ->
                println("[shortcut] Registry.Register：${e.message}")
            }
            val portal = conn.getRemoteObject(
                PORTAL_BUS,
                PORTAL_PATH,
                GlobalShortcutsPortalInterface::class.java,
                false,
            )

            // 先注册常驻 Response 监听再发起调用：portal 的方法返回路径后立刻异步发
            // Response 信号，若等调用返回再注册会因时序丢信号。
            ensurePortalListener(conn)
            val options = HashMap<String, Variant<*>>().apply {
                put("session_handle_token", Variant("neriplayer"))
                put("handle_token", Variant("neriplayer_create"))
            }
            val createHandle = portal.CreateSession(options)
            val created = awaitResponse(createHandle.path, SESSION_TIMEOUT_MS)
            if (created == null) {
                println("[shortcut] CreateSession 超时或被拒")
                return@runCatching false
            }
            val sessionPath = (created["session_handle"]?.value?.toString())
                ?: run {
                    println("[shortcut] CreateSession 结果缺 session_handle")
                    return@runCatching false
                }
            // BindShortcuts 的 session 参数直接传 DBusPath；ObjectPath(source,path) 的
            // 双参构造第一个是 source，别把顺序倒过来。
            val session = org.freedesktop.dbus.DBusPath(sessionPath)
            sessionHandle = sessionPath
            println("[shortcut] 会话已建立：$sessionPath")

            val shortcutArray: Array<ShortcutEntry> =
                entries.map { (id, trigger) ->
                    ShortcutEntry(
                        id,
                        HashMap<String, Variant<*>>().apply {
                            put("description", Variant(ShortcutAction.fromId(id)?.label ?: id))
                            if (trigger != null) put("preferred_trigger", Variant(trigger))
                        } as Map<String, Variant<*>>,
                    )
                }.toTypedArray()

            val bindHandle = portal.BindShortcuts(
                session,
                shortcutArray,
                "",
                HashMap<String, Variant<*>>().apply { put("handle_token", Variant("neriplayer_bind")) },
            )
            // 授权对话框可能等待用户操作，结果只用于判断成败
            val bound = awaitResponse(bindHandle.path, BIND_TIMEOUT_MS)
            if (bound == null) {
                println("[shortcut] 全局快捷键未获授权")
                stop()
                return@runCatching false
            }
            registered = true

            // 长按或自动重复会连发 Activated，这里去抖：同一动作 120ms 内只处理一次，
            // 避免 toggle 类动作（悬浮歌词、面板）出现「开了又关」。
            var lastAction = ""
            var lastAt = 0L
            val handler = DBusSigHandler<PortalSignals.Activated> { signal ->
                val now = System.nanoTime()
                val action = signal.shortcutId
                runCatching {
                    if (!(action == lastAction && now - lastAt < 120_000_000L)) {
                        lastAction = action
                        lastAt = now
                        onActivated(action)
                    }
                }
            }
            conn.addSigHandler(PortalSignals.Activated::class.java, handler)
            println("[shortcut] 全局快捷键已注册 ${entries.size} 个")
            true
        }.getOrElse { error ->
            println("[shortcut] 全局快捷键注册失败（降级为仅程序内）：${error.message}")
            stop()
            false
        }
    }

    fun stop() {
        runCatching {
            val conn = connection
            val session = sessionHandle
            if (conn != null && session != null) {
                conn.getRemoteObject(PORTAL_BUS, session, PortalSessionInterface::class.java, false).Close()
            }
        }
        registered = false
        sessionHandle = null
        responseListener.clear()
        runCatching { connection?.disconnect() }
        connection = null
    }

    /** 等待 portal Request 的 Response 信号；失败或超时返回 null。
     * 调用方应先经 ensurePortalListener 注册常驻监听再发起 portal 调用，
     * 避免响应先于临时 handler 到达而丢失。 */
    private fun awaitResponse(
        handle: String,
        timeoutMs: Long,
    ): Map<String, Variant<*>?>? {
        val waiter = ResponseWaiter()
        responseListener[handle] = waiter
        val ok = waiter.latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        responseListener.remove(handle)
        return if (ok) waiter.payload else null
    }

    companion object {
        /** 与 ~/.local/share/applications/ 下 neriplayer.desktop 的 basename 一致。 */
        private const val APP_ID = "neriplayer"
        private const val PORTAL_BUS = "org.freedesktop.portal.Desktop"
        private const val PORTAL_PATH = "/org/freedesktop/portal/desktop"
        private const val SESSION_TIMEOUT_MS = 15_000L

        /** 绑定可能等待用户点击授权，给足时间。 */
        private const val BIND_TIMEOUT_MS = 120_000L

        /** Compose keyCode 转 xdg shortcuts spec 的键名；无法表示时返回 null。
         * 入参做低 32 位归一化，与 Compose Key 的 (keyLocation<<32)|keyCode 打包格式对齐。 */
        /** xkb 键名 → portal 接受的触发器键名（单字母统一大写，其余按 xkb 名称）。 */
        private fun xdgKeyName(key: String): String? {
            if (key.isBlank()) return null
            return when (key) {
                "space" -> "space"
                "Escape" -> "Escape"
                "Return" -> "Return"
                "Tab" -> "Tab"
                "Up", "Down", "Left", "Right" -> key
                else -> if (key.length == 1) key.uppercase() else key
            }
        }
    }
}

@DBusInterfaceName("org.freedesktop.host.portal.Registry")
private interface PortalRegistryInterface : DBusInterface {
    fun Register(appId: String, options: Map<String, Variant<*>>)
}

@DBusInterfaceName("org.freedesktop.portal.GlobalShortcuts")
private interface GlobalShortcutsPortalInterface : DBusInterface {
    fun CreateSession(options: Map<String, Variant<*>>): org.freedesktop.dbus.DBusPath

    fun BindShortcuts(
        sessionHandle: org.freedesktop.dbus.DBusPath,
        shortcuts: Array<ShortcutEntry>,
        parentWindow: String,
        options: Map<String, Variant<*>>,
    ): org.freedesktop.dbus.DBusPath

    /** 打开系统的全局快捷键配置界面（portal ≥1.21；GNOME 桌面未实现时抛异常）。 */
    fun ConfigureShortcuts(
        sessionHandle: org.freedesktop.dbus.DBusPath,
        parentWindow: String,
        options: Map<String, Variant<*>>,
    )
}

@DBusInterfaceName("org.freedesktop.portal.Session")
private interface PortalSessionInterface : DBusInterface {
    fun Close()
}

/** global shortcuts 条目：(shortcut_id, options)，映射为 D-Bus struct (sa{sv})。
 * 必须用 dbus-java 的 Struct 子类 + @Position 字段才能在 marshall 时导出。 */
class ShortcutEntry(
    @field:org.freedesktop.dbus.annotations.Position(0) val shortcutId: String,
    @field:org.freedesktop.dbus.annotations.Position(1) val options: Map<String, Variant<*>>,
) : org.freedesktop.dbus.Struct()

@DBusInterfaceName("org.freedesktop.portal.GlobalShortcuts")
private interface PortalSignals : DBusInterface {
    /** org.freedesktop.portal.GlobalShortcuts.Activated 信号。
     * 必须声明为 DBusInterface 的成员类，dbus-java 才允许注册。
     * timestamp 是 t（UInt64），不是 u（UInt32），类型不符会导致信号被丢弃。 */
    class Activated(
        path: String,
        private val session: org.freedesktop.dbus.DBusPath,
        val shortcutId: String,
        private val timestamp: org.freedesktop.dbus.types.UInt64,
        private val options: Map<String, Variant<*>>,
    ) : DBusSignal(path, session, shortcutId, timestamp, options)
}

@DBusInterfaceName("org.freedesktop.portal.Request")
private interface PortalRequestSignals : DBusInterface {
    /** org.freedesktop.portal.Request.Response 信号。 */
    class Response(
        path: String,
        val code: UInt32,
        val results: Map<String, Variant<*>?>,
    ) : DBusSignal(path, code, results)
}
