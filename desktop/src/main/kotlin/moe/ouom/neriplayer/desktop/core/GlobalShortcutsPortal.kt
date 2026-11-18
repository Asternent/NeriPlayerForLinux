package moe.ouom.neriplayer.desktop.core

import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.DBusSigHandler
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
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

    val isActive: Boolean get() = registered

    /** 把 Compose 绑定转成 xdg shortcuts spec 的触发器字符串。 */
    private fun triggerOf(binding: ShortcutBinding): String? {
        if (!binding.isBound) return null
        val key = xdgKeyName(binding.keyCode) ?: return null
        val mods = buildList {
            if (binding.ctrl) add("CTRL")
            if (binding.alt) add("ALT")
            if (binding.shift) add("SHIFT")
            if (binding.meta) add("LOGO")
        }
        return (mods + key).joinToString("+")
    }

    /**
     * 注册绑定，返回是否成功。
     * [bindings] 只应包含 globalSupported 的动作。
     */
    fun register(bindings: List<Pair<ShortcutAction, ShortcutBinding>>): Boolean {
        val entries = bindings.mapNotNull { (action, binding) ->
            val trigger = triggerOf(binding) ?: return@mapNotNull null
            action.id to trigger
        }
        if (entries.isEmpty()) return false
        return runCatching {
            val conn = DBusConnectionBuilder.forSessionBus().build()
            connection = conn
            val portal = conn.getRemoteObject(
                PORTAL_BUS,
                PORTAL_PATH,
                GlobalShortcutsPortalInterface::class.java,
                false,
            )

            val options = HashMap<String, Variant<*>>().apply {
                put("session_handle_token", Variant("neriplayer"))
                put("handle_token", Variant("neriplayer_create"))
            }
            val createHandle = portal.CreateSession(options)
            val created = awaitResponse(conn, createHandle, SESSION_TIMEOUT_MS) ?: return@runCatching false
            val session = (created["session_handle"]?.value as? String) ?: return@runCatching false
            sessionHandle = session

            val shortcutArray: Array<Pair<String, Map<String, Variant<*>>>> = entries.map { (id, trigger) ->
                id to (HashMap<String, Variant<*>>().apply {
                    put("description", Variant(ShortcutAction.fromId(id)?.label ?: id))
                    put("preferred_trigger", Variant(trigger))
                } as Map<String, Variant<*>>)
            }.toTypedArray()

            val bindHandle = portal.BindShortcuts(
                session,
                shortcutArray,
                "",
                HashMap<String, Variant<*>>().apply { put("handle_token", Variant("neriplayer_bind")) },
            )
            // 授权对话框可能等待用户操作，结果只用于判断成败
            val bound = awaitResponse(conn, bindHandle, BIND_TIMEOUT_MS)
            if (bound == null) {
                println("[shortcut] 全局快捷键未获授权")
                stop()
                return@runCatching false
            }
            registered = true

            val handler = DBusSigHandler<ActivatedSignal> { signal ->
                runCatching { onActivated(signal.shortcutId) }
            }
            conn.addSigHandler(ActivatedSignal::class.java, handler)
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
                conn.getRemoteObject(session, PORTAL_PATH, PortalSessionInterface::class.java, false).Close()
            }
        }
        registered = false
        sessionHandle = null
        runCatching { connection?.disconnect() }
        connection = null
    }

    /** 等待 portal Request 的 Response 信号；失败或超时返回 null。 */
    private fun awaitResponse(
        conn: DBusConnection,
        handle: String,
        timeoutMs: Long,
    ): Map<String, Variant<*>?>? {
        val latch = CountDownLatch(1)
        var payload: Map<String, Variant<*>?>? = null
        val handler = DBusSigHandler<ResponseSignal> { signal ->
            if (signal.path == handle) {
                if (signal.code.toInt() == 0) payload = signal.results
                latch.countDown()
            }
        }
        runCatching { conn.addSigHandler(ResponseSignal::class.java, handler) }
            .onFailure { return null }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        runCatching { conn.removeSigHandler(ResponseSignal::class.java, handler) }
        return payload
    }

    companion object {
        private const val PORTAL_BUS = "org.freedesktop.portal.Desktop"
        private const val PORTAL_PATH = "/org/freedesktop/portal/desktop"
        private const val SESSION_TIMEOUT_MS = 15_000L

        /** 绑定可能等待用户点击授权，给足时间。 */
        private const val BIND_TIMEOUT_MS = 120_000L

        /** Compose keyCode 转 xdg shortcuts spec 的键名；无法表示时返回 null。 */
        private fun xdgKeyName(keyCode: Long): String? {
            val key = androidx.compose.ui.input.key.Key
            return when (keyCode) {
                key.Spacebar.keyCode -> "space"
                key.Enter.keyCode -> "Return"
                key.Escape.keyCode -> "Escape"
                key.Tab.keyCode -> "Tab"
                key.DirectionUp.keyCode -> "Up"
                key.DirectionDown.keyCode -> "Down"
                key.DirectionLeft.keyCode -> "Left"
                key.DirectionRight.keyCode -> "Right"
                key.MediaPlayPause.keyCode -> "XF86AudioPlay"
                key.MediaNext.keyCode -> "XF86AudioNext"
                key.MediaPrevious.keyCode -> "XF86AudioPrev"
                key.MediaStop.keyCode -> "XF86AudioStop"
                else -> {
                    val text = runCatching { androidx.compose.ui.input.key.Key(keyCode).toString() }.getOrNull().orEmpty()
                    when {
                        text.length == 4 && text.startsWith("Key") -> text.substring(3)
                        text.isEmpty() -> null
                        else -> null
                    }
                }
            }
        }
    }
}

@DBusInterfaceName("org.freedesktop.portal.GlobalShortcuts")
private interface GlobalShortcutsPortalInterface : DBusInterface {
    fun CreateSession(options: Map<String, Variant<*>>): String

    fun BindShortcuts(
        sessionHandle: String,
        shortcuts: Array<Pair<String, Map<String, Variant<*>>>>,
        parentWindow: String,
        options: Map<String, Variant<*>>,
    ): String
}

@DBusInterfaceName("org.freedesktop.portal.Session")
private interface PortalSessionInterface : DBusInterface {
    fun Close()
}

/** org.freedesktop.portal.GlobalShortcuts.Activated 信号。 */
class ActivatedSignal(
    path: String,
    private val session: String,
    val shortcutId: String,
    private val timestamp: Long,
    private val options: Map<String, Variant<*>>,
) : DBusSignal(path, session, shortcutId, timestamp, options)

/** org.freedesktop.portal.Request.Response 信号。 */
class ResponseSignal(
    path: String,
    val code: UInt32,
    val results: Map<String, Variant<*>?>,
) : DBusSignal(path, code, results)
