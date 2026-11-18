package moe.ouom.neriplayer.desktop.backend.api

import moe.ouom.neriplayer.desktop.backend.CoreServer
import moe.ouom.neriplayer.desktop.core.AppContainer

/** 下载管理。命令清单见 desktop/docs/protocol-download.md（待补）。 */
object DownloadApi {
    fun register(server: CoreServer, container: AppContainer) {
        // 命令在此注册：server.on("xxx.yyy") { params -> ... }
    }
}
