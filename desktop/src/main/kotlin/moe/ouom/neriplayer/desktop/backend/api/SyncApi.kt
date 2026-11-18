package moe.ouom.neriplayer.desktop.backend.api

import moe.ouom.neriplayer.desktop.backend.CoreServer
import moe.ouom.neriplayer.desktop.core.AppContainer

/** GitHub 跨设备同步。命令清单见 desktop/docs/protocol-sync.md（待补）。 */
object SyncApi {
    fun register(server: CoreServer, container: AppContainer) {
        // 命令在此注册：server.on("xxx.yyy") { params -> ... }
    }
}
