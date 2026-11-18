package moe.ouom.neriplayer.desktop.backend.api

import moe.ouom.neriplayer.desktop.backend.CoreServer
import moe.ouom.neriplayer.desktop.core.AppContainer

/** 封面解析与缓存。命令清单见 desktop/docs/protocol-artwork.md（待补）。 */
object ArtworkApi {
    fun register(server: CoreServer, container: AppContainer) {
        // 命令在此注册：server.on("xxx.yyy") { params -> ... }
    }
}
