package moe.ouom.neriplayer.desktop.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import moe.ouom.neriplayer.desktop.net.OnlineRepository
import moe.ouom.neriplayer.desktop.sync.GitHubSyncManager
import moe.ouom.neriplayer.desktop.sync.SyncConfigStore

/** 全局依赖容器，替代 Android 端的 Application 级单例。 */
class AppContainer {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = SettingsRepository()
    val library = LibraryRepository(settings)
    val playlists = PlaylistRepository()
    val history = HistoryRepository()
    val stats = StatsRepository(history)
    val online = OnlineRepository()
    val accounts = AccountRepository(online.httpService)
    val syncConfig = SyncConfigStore()
    val sync = GitHubSyncManager(
        configStore = syncConfig,
        playlists = playlists,
        history = history,
        stats = stats,
        library = library,
        http = online.httpService,
    )
    val neteaseLogin = moe.ouom.neriplayer.desktop.net.NeteaseLogin(online.httpService)
    val biliLogin = moe.ouom.neriplayer.desktop.net.BiliLogin(online.httpService)
    val lyrics = LyricsRepository { song -> online.lyrics(song) }
    val player = PlayerManager(
        settings = settings,
        history = history,
        stats = stats,
        lyricsRepository = lyrics,
        online = online,
        scope = scope,
    )

    fun bootstrap(scanLibrary: Boolean = true) {
        library.load()
        accounts.load()
        playlists.load()
        history.load()
        stats.load()
        player.attachSnapshotFlow()
        player.restoreLastQueue()
        scope.launch(Dispatchers.IO) { refreshAccountProfiles() }
        observeLocalChangesForAutoSync()
        if (scanLibrary) {
            scope.launch { library.scan() }
        }
    }

    private var localRevision = 0L

    /** 本地歌单 / 历史 / 统计变化后，若开启了自动同步则静默同步一次。 */
    private fun observeLocalChangesForAutoSync() {
        scope.launch {
            combine(playlists.playlists, history.entries, stats.stats) { p, h, s ->
                Triple(p.size, h.size, s.size)
            }.collect { localRevision += 1 }
        }
        scope.launch {
            var lastSyncedRevision = -1L
            while (true) {
                delay(15_000)
                val config = syncConfig.current
                if (!config.autoSync || !config.configured) continue
                if (lastSyncedRevision < 0L) {
                    // 启动后的第一次循环只记录基线，避免刚打开就上传
                    lastSyncedRevision = localRevision
                    continue
                }
                if (localRevision == lastSyncedRevision) continue
                sync.performSync()
                lastSyncedRevision = localRevision
            }
        }
    }

    /**
     * 登录时若账号信息接口临时失败，会以「只有 Cookie」的形式保存登录态；
     * 这里在启动时补拉一次昵称与 UID，让界面与「我的歌单」恢复正常。
     */
    private fun refreshAccountProfiles() {
        accounts.accountOf(MediaSource.NETEASE)
            ?.takeIf { it.nickname.isBlank() || it.userId.isBlank() }
            ?.let { runCatching { neteaseLogin.fetchProfile() }.getOrNull() }
            ?.let { accounts.save(MediaSource.NETEASE, it, "netease") }
        accounts.accountOf(MediaSource.BILIBILI)
            ?.takeIf { it.nickname.isBlank() || it.userId.isBlank() }
            ?.let { runCatching { biliLogin.fetchProfile() }.getOrNull() }
            ?.let { accounts.save(MediaSource.BILIBILI, it, "bilibili") }
    }
}
