package moe.ouom.neriplayer.desktop.core

import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.DBusMemberName
import org.freedesktop.dbus.annotations.Position
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.Struct
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * 系统托盘：StatusNotifierItem（SNI）+ DBusMenu。
 *
 * 为什么不用 java.awt.TrayIcon：GNOME（Ubuntu 默认桌面）里 AWT 的托盘图标走的是 XEmbed 兼容层
 * （gnome-shell-extension-appindicator 的 legacy tray）。点图标时 GNOME 会用弹层把点击转发给
 * XEmbed 窗口，同时自己握住输入抓取（grab）；应用再弹出自己的窗口时，用户的真实点击会被
 * 这个抓取吃掉——表现就是「面板弹出来了，但按钮点不动」。
 *
 * 改用桌面环境原生的 SNI + DBusMenu 后，菜单由 GNOME 自己渲染，点菜单项通过 D-Bus 回调我们，
 * 不存在抓取冲突；面板想用自绘样式时，再通过菜单里的「打开控制面板」调用应用内面板。
 */

private const val SNI_BUS_PREFIX = "org.kde.StatusNotifierItem"
private const val SNI_OBJECT_PATH = "/StatusNotifierItem"
private const val SNI_IFACE = "org.kde.StatusNotifierItem"
private const val WATCHER_NAME = "org.kde.StatusNotifierWatcher"
private const val WATCHER_PATH = "/StatusNotifierWatcher"
private const val WATCHER_IFACE = "org.kde.StatusNotifierWatcher"
private const val MENU_PATH = "/MenuBar"
private const val MENU_IFACE = "com.canonical.dbusmenu"

/** 菜单项 id；0 是根节点。 */
private object MenuIds {
    const val ROOT = 0
    const val PLAY_PAUSE = 1
    const val PREVIOUS = 2
    const val NEXT = 3
    const val SHOW_WINDOW = 5
    const val FLOATING_LYRICS = 6
    const val DOWNLOADS = 7
    const val SETTINGS = 8
    const val OPEN_PANEL = 10
    const val QUIT = 12
}

@DBusInterfaceName(SNI_IFACE)
interface StatusNotifierItemInterface : DBusInterface {
    fun Activate(x: Int, y: Int)
    fun SecondaryActivate(x: Int, y: Int)
    fun ContextMenu(x: Int, y: Int)
    fun Scroll(delta: Int, orientation: String)
}

@DBusInterfaceName(WATCHER_IFACE)
interface StatusNotifierWatcherInterface : DBusInterface {
    fun RegisterStatusNotifierItem(service: String)
    fun RegisterStatusNotifierHost(service: String)
}

/** DBusMenu 的 `(ia{sv}av)` 节点。 */
class MenuLayoutNode(
    @field:Position(0) val id: Int,
    @field:Position(1) val properties: Map<String, Variant<*>>,
    @field:Position(2) val children: Array<Variant<*>>,
) : Struct() {
}

/** DBusMenu 的 `GetLayout` 返回值 `(u(ia{sv}av))`。 */
class MenuLayoutReply(
    @field:Position(0) val revision: UInt32,
    @field:Position(1) val layout: MenuLayoutNode,
) : Struct()

/** DBusMenu 的 `a(ia{sv})` 元素。 */
class MenuItemProperties(
    @field:Position(0) val id: Int,
    @field:Position(1) val properties: Map<String, Variant<*>>,
) : Struct()

/** DBusMenu 的 `a(isvu)` 元素。 */
class MenuEventEntry(
    @field:Position(0) val id: Int,
    @field:Position(1) val eventId: String,
    @field:Position(2) val data: Variant<*>,
    @field:Position(3) val timestamp: UInt32,
) : Struct()

/** DBusMenu 的 `AboutToShowGroup` 返回值 `(aiai)`。 */
class MenuGroupReply(
    @field:Position(0) val updatesNeeded: IntArray,
    @field:Position(1) val idErrors: IntArray,
) : Struct()

/** SNI 的 `a(iiay)` 元素：一条图标位图。 */
class IconPixmapEntry(
    @field:Position(0) val width: Int,
    @field:Position(1) val height: Int,
    @field:Position(2) val bytes: ByteArray,
) : Struct()

@DBusInterfaceName(MENU_IFACE)
interface DBusMenuInterface : DBusInterface {
    fun GetLayout(parentId: Int, recursionDepth: Int, propertyNames: Array<String>): MenuLayoutReply
    fun GetGroupProperties(ids: IntArray, propertyNames: Array<String>): Array<MenuItemProperties>
    fun GetProperty(id: Int, name: String): Variant<*>
    fun Event(id: Int, eventId: String, data: Variant<*>, timestamp: UInt32)
    fun EventGroup(events: Array<MenuEventEntry>): MenuGroupReply
    fun AboutToShow(id: Int): Boolean
    fun AboutToShowGroup(ids: IntArray): MenuGroupReply
}

/**
 * 托盘菜单服务。启动成功表示当前桌面环境支持 SNI（GNOME / KDE / XFCE 等），
 * 此时不要再安装 AWT 托盘图标，否则会出现两个图标。
 */
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
    private var busName: String? = null
    private var revision = 0L

    val running: Boolean get() = connection != null

    fun start(): Boolean {
        if (connection != null) return true
        return runCatching {
            val conn = DBusConnectionBuilder.forSessionBus().build()
            val name = "$SNI_BUS_PREFIX-${ProcessHandle.current().pid()}-1"
            conn.exportObject(SNI_OBJECT_PATH, ExportedItem())
            conn.exportObject(MENU_PATH, ExportedMenu())
            conn.requestBusName(name)

            val watcher = conn.getRemoteObject(
                WATCHER_NAME,
                WATCHER_PATH,
                StatusNotifierWatcherInterface::class.java,
                true,
            )
            watcher.RegisterStatusNotifierItem(name)

            connection = conn
            busName = name
            println("[sni] 已注册状态栏指示器 $name（系统托盘菜单由桌面渲染）")
            true
        }.getOrElse { error ->
            println("[sni] 注册失败（回退到 AWT 托盘）：${error.message}")
            runCatching { connection?.disconnect() }
            connection = null
            busName = null
            false
        }
    }

    fun stop() {
        runCatching { connection?.disconnect() }
        connection = null
        busName = null
    }

    /** 播放状态 / 歌曲变化后刷新菜单标签与提示。 */
    fun refresh() {
        val conn = connection ?: return
        revision += 1
        runCatching {
            conn.sendMessage(
                MenuLayoutUpdated(MENU_PATH, UInt32(revision), 0)
            )
            val items = mapOf(
                MenuIds.PLAY_PAUSE to mapOf<String, Variant<*>>("label" to Variant(labelFor(MenuIds.PLAY_PAUSE))),
                MenuIds.FLOATING_LYRICS to mapOf(
                    "label" to Variant(labelFor(MenuIds.FLOATING_LYRICS)),
                    "toggle-state" to Variant(if (floatingLyricsEnabled()) 1 else 0),
                ),
            )
            conn.sendMessage(MenuItemsUpdated(MENU_PATH, items))
        }
    }

    private fun labelFor(id: Int): String = when (id) {
        MenuIds.PLAY_PAUSE -> if (playing()) "暂停" else "播放"
        MenuIds.FLOATING_LYRICS -> if (floatingLyricsEnabled()) "悬浮歌词（已开启）" else "开启悬浮歌词"
        else -> ""
    }

    /** 菜单结构：id → (标签, 是否勾选项)。 */
    private fun menuEntries(): List<Triple<Int, String, Boolean>> = listOf(
        Triple(MenuIds.PLAY_PAUSE, labelFor(MenuIds.PLAY_PAUSE), false),
        Triple(MenuIds.PREVIOUS, "上一首", false),
        Triple(MenuIds.NEXT, "下一首", false),
        Triple(MenuIds.SHOW_WINDOW, "显示主窗口", false),
        Triple(MenuIds.FLOATING_LYRICS, labelFor(MenuIds.FLOATING_LYRICS), true),
        Triple(MenuIds.DOWNLOADS, "下载管理", false),
        Triple(MenuIds.SETTINGS, "设置", false),
        Triple(MenuIds.OPEN_PANEL, "打开控制面板", false),
        Triple(MenuIds.QUIT, "退出 NeriPlayer", false),
    )

    private fun separators(): List<Int> = listOf(4, 9, 11)

    private fun invoke(id: Int) {
        println("[sni] 菜单项点击 id=$id")
        when (id) {
            MenuIds.PLAY_PAUSE -> onTogglePlay()
            MenuIds.PREVIOUS -> onPrevious()
            MenuIds.NEXT -> onNext()
            MenuIds.SHOW_WINDOW -> onShowWindow()
            MenuIds.FLOATING_LYRICS -> onToggleFloatingLyrics()
            MenuIds.DOWNLOADS -> onOpenDownloads()
            MenuIds.SETTINGS -> onOpenSettings()
            MenuIds.OPEN_PANEL -> onOpenPanel()
            MenuIds.QUIT -> onQuit()
        }
        if (id != MenuIds.OPEN_PANEL) refresh()
    }

    private fun leafProperties(id: Int, label: String, toggle: Boolean): Map<String, Variant<*>> {
        val map = mutableMapOf<String, Variant<*>>(
            "label" to Variant(label),
            "enabled" to Variant(true),
            "visible" to Variant(true),
            "type" to Variant("standard"),
        )
        if (toggle) {
            map["toggle-type"] = Variant("checkmark")
            map["toggle-state"] = Variant(if (floatingLyricsEnabled()) 1 else 0)
        }
        return map
    }

    private fun separatorProperties(): Map<String, Variant<*>> = mapOf(
        "type" to Variant("separator"),
        "visible" to Variant(true),
    )

    private fun menuTree(): Array<Variant<*>> {
        val entries = menuEntries()
        val children = mutableListOf<Variant<*>>()
        entries.forEachIndexed { index, (id, label, toggle) ->
            // 在「下一首」后、「打开控制面板」前插入分隔线
            if (index == 3 || index == 7) {
                val separatorId = if (index == 3) 4 else 9
                children += Variant(
                    MenuLayoutNode(separatorId, separatorProperties(), emptyArray()),
                    MenuLayoutNode::class.java,
                )
            }
            children += Variant(
                MenuLayoutNode(id, leafProperties(id, label, toggle), emptyArray()),
                MenuLayoutNode::class.java,
            )
        }
        children += Variant(
            MenuLayoutNode(11, separatorProperties(), emptyArray()),
            MenuLayoutNode::class.java,
        )
        return children.toTypedArray()
    }

    private inner class ExportedItem : StatusNotifierItemInterface, Properties {
        override fun getObjectPath(): String = SNI_OBJECT_PATH

        override fun Activate(x: Int, y: Int) = onShowWindow()

        override fun SecondaryActivate(x: Int, y: Int) = onShowWindow()

        override fun ContextMenu(x: Int, y: Int) = Unit

        override fun Scroll(delta: Int, orientation: String) {
            if (delta > 0) onNext() else onPrevious()
        }

        // 直接复用 GetAll：属性值（尤其 IconPixmap）必须和 Get 返回同一种可编组类型，
        // 否则 dbus-java 在编组回包时会抛异常并拖垮整条连接（MPRIS 也会一起掉线）
        @Suppress("UNCHECKED_CAST")
        override fun <A : Any?> Get(interfaceName: String, propertyName: String): A =
            (GetAll(interfaceName)[propertyName]?.value ?: "") as A

        override fun <A : Any?> Set(interfaceName: String, propertyName: String, value: A) = Unit

        override fun GetAll(interfaceName: String): Map<String, Variant<*>> = mapOf(
            "Category" to Variant("ApplicationStatus"),
            "Id" to Variant("neriplayer"),
            "Title" to Variant(title()),
            "Status" to Variant("Active"),
            "IconPixmap" to Variant(trayIconPixmap(), "a(iiay)"),
            // 注意：dbus-java 的 ObjectPath 编组有缺陷（会让整条连接断开），
            // 这里用「字符串 + o 签名」发送，线上类型仍是 object path
            "Menu" to Variant(MENU_PATH, "o"),
            "ItemIsMenu" to Variant(true),
            "IconName" to Variant("neriplayer"),
        )
    }

    private inner class ExportedMenu : DBusMenuInterface {
        override fun getObjectPath(): String = MENU_PATH

        override fun GetLayout(parentId: Int, recursionDepth: Int, propertyNames: Array<String>): MenuLayoutReply {
            println("[sni] 桌面请求菜单布局 parent=$parentId depth=$recursionDepth")
            val root = MenuLayoutNode(MenuIds.ROOT, rootProperties(), menuTree())
            return MenuLayoutReply(UInt32(revision), root)
        }

        override fun GetGroupProperties(ids: IntArray, propertyNames: Array<String>): Array<MenuItemProperties> =
            menuEntries().filter { ids.isEmpty() || it.first in ids }
                .map { MenuItemProperties(it.first, leafProperties(it.first, it.second, it.third)) }
                .toTypedArray()

        override fun GetProperty(id: Int, name: String): Variant<*> {
            val entry = menuEntries().firstOrNull { it.first == id }
                ?: return Variant("")
            return leafProperties(id, entry.second, entry.third)[name] ?: Variant("")
        }

        override fun Event(id: Int, eventId: String, data: Variant<*>, timestamp: UInt32) {
            if (eventId == "clicked") invoke(id)
        }

        override fun EventGroup(events: Array<MenuEventEntry>): MenuGroupReply {
            val handled = mutableListOf<Int>()
            events.forEach { entry ->
                if (entry.eventId == "clicked") {
                    invoke(entry.id)
                    handled += entry.id
                }
            }
            return MenuGroupReply(handled.toIntArray(), IntArray(0))
        }

        override fun AboutToShow(id: Int): Boolean = false

        override fun AboutToShowGroup(ids: IntArray): MenuGroupReply = MenuGroupReply(IntArray(0), IntArray(0))
    }

    private fun rootProperties(): Map<String, Variant<*>> = mapOf(
        "children-display" to Variant("submenu"),
    )

    /** 托盘图标：32×32 ARGB，SNI 要求 `a(iiay)`（大端 A,R,G,B）。 */
    private fun trayIconPixmap(): Array<IconPixmapEntry> {
        val size = 32
        val image = runCatching {
            val stream = object {}.javaClass.getResourceAsStream("/neriplayer-tray.png")
                ?: return@runCatching null
            stream.use { ImageIO.read(it) }
        }.getOrNull() ?: BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val scaled = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        scaled.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(image, 0, 0, size, size, null)
            dispose()
        }
        val bytes = ByteArrayOutputStream(size * size * 4)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val argb = scaled.getRGB(x, y)
                bytes.write((argb shr 24) and 0xFF)
                bytes.write((argb shr 16) and 0xFF)
                bytes.write((argb shr 8) and 0xFF)
                bytes.write(argb and 0xFF)
            }
        }
        return arrayOf(IconPixmapEntry(size, size, bytes.toByteArray()))
    }
}

/** `com.canonical.dbusmenu.LayoutUpdated` 信号。 */
@DBusInterfaceName(MENU_IFACE)
@DBusMemberName("LayoutUpdated")
private class MenuLayoutUpdated(path: String, revision: UInt32, parent: Int) :
    org.freedesktop.dbus.messages.DBusSignal(path, revision, parent) {
}

/** `com.canonical.dbusmenu.ItemsPropertiesUpdated` 信号。 */
@DBusInterfaceName(MENU_IFACE)
@DBusMemberName("ItemsPropertiesUpdated")
private class MenuItemsUpdated(path: String, updated: Map<Int, Map<String, Variant<*>>>) :
    org.freedesktop.dbus.messages.DBusSignal(path, updated, emptyList<Any>()) {
}
