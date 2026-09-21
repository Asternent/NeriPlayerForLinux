package moe.ouom.neriplayer.desktop.ui

import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * 系统托盘（后台常驻）。
 *
 * 这里刻意不使用 Compose Desktop 自带的 `Tray` 组合项：它的菜单是原生 AWT 弹出菜单，
 * 灰底、无图标、字号也无法跟随应用主题，观感与应用本体割裂。
 * 改为直接管理 `java.awt.TrayIcon` 并且不挂任何 popup menu，
 * 单击（左右键均可）统一交给应用内自绘的 [TrayControlPanel]。
 */
object DesktopTray {
    private var icon: TrayIcon? = null

    /** 当前桌面环境是否提供系统托盘（无托盘面板的极简环境会返回 false）。 */
    val available: Boolean
        get() = runCatching { SystemTray.isSupported() }.getOrDefault(false)

    /** 安装托盘图标；onActivate 在点击图标时调用。返回是否安装成功。 */
    fun install(onActivate: () -> Unit): Boolean {
        if (icon != null) return true
        val tray = runCatching { SystemTray.getSystemTray() }.getOrNull() ?: return false
        val size = runCatching {
            val hinted = tray.trayIconSize
            if (hinted == null) 24 else maxOf(hinted.width, hinted.height, 16)
        }.getOrDefault(24)
        val image = loadTrayImage(size) ?: return false
        val created = TrayIcon(image, "NeriPlayer").apply {
            isImageAutoSize = true
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(event: MouseEvent) {
                    println(
                        "[tray] 收到托盘图标点击 button=${event.button} screen=${event.locationOnScreen}"
                    )
                    when (event.button) {
                        MouseEvent.BUTTON1, MouseEvent.BUTTON3 -> onActivate()
                    }
                }
            })
        }
        val added = runCatching { tray.add(created) }.isSuccess
        if (added) {
            icon = created
            println("[tray] 已安装系统托盘图标（点击图标打开主题化控制面板）")
        } else {
            println("[tray] 系统托盘不可用，跳过托盘图标")
        }
        return added
    }

    /** 托盘悬停提示（一般显示当前播放的歌曲）。 */
    fun updateTooltip(text: String) {
        icon?.toolTip = text
    }

    /** 系统通知（走托盘气泡，由桌面环境转发到通知中心）。 */
    fun notify(title: String, message: String) {
        val target = icon ?: return
        runCatching { target.displayMessage(title, message, TrayIcon.MessageType.NONE) }
    }

    fun dispose() {
        val target = icon ?: return
        icon = null
        runCatching { SystemTray.getSystemTray()?.remove(target) }
    }

    /** 从 jar 资源里读取托盘图标，并按托盘需要的尺寸缩放。 */
    private fun loadTrayImage(size: Int): java.awt.Image? = runCatching {
        val stream = object {}.javaClass.getResourceAsStream("/neriplayer-tray.png") ?: return null
        val source = stream.use { ImageIO.read(it) } ?: return null
        val scaled = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val graphics = scaled.createGraphics()
        graphics.setRenderingHint(
            RenderingHints.KEY_INTERPOLATION,
            RenderingHints.VALUE_INTERPOLATION_BILINEAR,
        )
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        graphics.drawImage(source, 0, 0, size, size, null)
        graphics.dispose()
        scaled
    }.getOrNull()
}
