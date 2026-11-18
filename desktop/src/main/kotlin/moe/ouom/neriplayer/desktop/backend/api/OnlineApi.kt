package moe.ouom.neriplayer.desktop.backend.api

import moe.ouom.neriplayer.desktop.backend.CoreServer
import moe.ouom.neriplayer.desktop.core.AppContainer

/** 在线音源（探索/搜索/详情/歌词）。命令清单见 desktop/docs/protocol-online.md（待补）。 */
object OnlineApi {
    fun register(server: CoreServer, container: AppContainer) {
        // 命令在此注册：server.on("xxx.yyy") { params -> ... }
    }
}
