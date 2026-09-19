package moe.ouom.neriplayer.desktop.ui

/**
 * 跨窗口的应用级动作（托盘菜单、后台控制面板、MPRIS 都会用到）。
 * 由 NeriApp / Main 在各自作用域里注册实现。
 */
object AppIntents {
    var showMainWindow: (() -> Unit)? = null
    var openSettings: (() -> Unit)? = null
    var openDownloads: (() -> Unit)? = null
    var toggleTrayPanel: (() -> Unit)? = null
    var quit: (() -> Unit)? = null
}
