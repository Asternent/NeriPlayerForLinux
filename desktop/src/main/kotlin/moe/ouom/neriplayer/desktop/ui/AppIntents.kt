package moe.ouom.neriplayer.desktop.ui

/**
 * 跨窗口的应用级动作（托盘菜单、后台控制面板、MPRIS 都会用到）。
 * 由 NeriApp / Main 在各自作用域里注册实现。
 */
object AppIntents {
    var showMainWindow: (() -> Unit)? = null
    /** 隐藏主窗口（收进托盘），用于「关闭窗口后后台播放」的自动化回归测试。 */
    var hideMainWindow: (() -> Unit)? = null
    var openSettings: (() -> Unit)? = null
    var openDownloads: (() -> Unit)? = null
    var toggleTrayPanel: (() -> Unit)? = null
    /** 打开桌面系统的全局快捷键配置页；返回是否成功打开。由 Main 注册。 */
    var openGlobalShortcutSettings: (() -> Boolean)? = null
    var quit: (() -> Unit)? = null
}
