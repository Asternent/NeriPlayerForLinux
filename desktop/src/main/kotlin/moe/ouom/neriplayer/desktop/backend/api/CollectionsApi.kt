package moe.ouom.neriplayer.desktop.backend.api

import moe.ouom.neriplayer.desktop.backend.CoreServer
import moe.ouom.neriplayer.desktop.core.AppContainer

/** 歌单/收藏/历史/统计。命令清单见 desktop/docs/protocol-collections.md（待补）。 */
object CollectionsApi {
    fun register(server: CoreServer, container: AppContainer) {
        // 命令在此注册：server.on("xxx.yyy") { params -> ... }
    }
}
