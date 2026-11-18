package moe.ouom.neriplayer.desktop.core

import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBus
import java.util.concurrent.TimeUnit

/**
 * 单实例保证：启动时若已有 NeriPlayer 在跑，唤起它的窗口并退出当前进程。
 *
 * 用 MPRIS 的 well-known name 作为实例锁：它在 `AppContainer.bootstrap()` 里注册，
 * 早于托盘，且标准 MPRIS 接口的 Raise() 正好就是「显示主窗口」。
 * 第二个实例不加载音乐库、不连在线服务，直接通过 Raise() 唤起已有实例后退出。
 */
object SingleInstance {

    /** @return true 表示本进程是唯一实例、可以正常启动；false 表示已唤起旧实例、本进程应退出。 */
    fun acquire(): Boolean {
        val conn = runCatching { DBusConnectionBuilder.forSessionBus().build() }.getOrElse {
            // 没有会话总线（极简桌面/CI）时无法检测，放行，交给各模块自行降级
            return true
        }
        return try {
            val dbus = conn.getRemoteObject(
                "org.freedesktop.DBus",
                "/org/freedesktop/DBus",
                DBus::class.java,
                false,
            )
            if (!dbus.NameHasOwner(MPRIS_BUS_NAME)) {
                true
            } else {
                println("[single-instance] 已有实例在运行，唤起其窗口")
                runCatching {
                    val remote = conn.getRemoteObject(
                        MPRIS_BUS_NAME,
                        MPRIS_OBJECT_PATH,
                        MprisRootInterface::class.java,
                        false,
                    )
                    remote.Raise()
                    // 给对端一点时间处理 Raise，避免连接一断消息没送达
                    TimeUnit.MILLISECONDS.sleep(200)
                }.onFailure { println("[single-instance] 唤起已有实例失败：${it.message}") }
                false
            }
        } finally {
            runCatching { conn.disconnect() }
        }
    }
}
