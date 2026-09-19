package moe.ouom.neriplayer.desktop.tools

import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.desktop.core.HistoryRepository
import moe.ouom.neriplayer.desktop.core.LibraryRepository
import moe.ouom.neriplayer.desktop.core.MediaSource
import moe.ouom.neriplayer.desktop.core.PlaylistRepository
import moe.ouom.neriplayer.desktop.core.SettingsRepository
import moe.ouom.neriplayer.desktop.core.Song
import moe.ouom.neriplayer.desktop.core.StatsRepository
import moe.ouom.neriplayer.desktop.core.neteaseSongKey
import moe.ouom.neriplayer.desktop.net.HttpService
import moe.ouom.neriplayer.desktop.sync.GitHubSyncManager
import moe.ouom.neriplayer.desktop.sync.GitHubSyncTransport
import moe.ouom.neriplayer.desktop.sync.SyncConfigStore
import moe.ouom.neriplayer.desktop.sync.SyncDataSerializer
import moe.ouom.neriplayer.desktop.sync.SyncFavoritePlaylist
import moe.ouom.neriplayer.desktop.sync.SyncPlaylist
import moe.ouom.neriplayer.desktop.sync.SyncSong

/**
 * 真实的 GitHub 端到端测试：
 * 建临时私有仓库 → 初始上传 → 本地新增数据后再同步 → 校验远端内容 →
 * 模拟另一台设备写入新歌单并拉回 → 校验冲突检测 → 删除临时仓库。
 *
 * 运行：GH_TOKEN=xxx ./gradlew syncE2E
 */
private var failures = 0

private fun expect(name: String, condition: Boolean, detail: String = "") {
    val suffix = if (detail.isBlank()) "" else " ($detail)"
    if (condition) {
        println("[sync-e2e] PASS $name$suffix")
    } else {
        failures += 1
        println("[sync-e2e] FAIL $name$suffix")
    }
}

fun main() = runBlocking {
    val mockBase = System.getenv("GH_MOCK_BASE").orEmpty()
    val token = System.getenv("GH_TOKEN").orEmpty().ifBlank { if (mockBase.isNotBlank()) "mock-token" else "" }
    if (token.isBlank()) {
        println("[sync-e2e] 缺少 GH_TOKEN（或设置 GH_MOCK_BASE 使用本地模拟服务），跳过")
        return@runBlocking
    }
    val apiBase = mockBase.ifBlank { "https://api.github.com" }
    println("[sync-e2e] API 地址：$apiBase")
    val http = HttpService()
    val transport = GitHubSyncTransport(http, token, apiBase)
    val login = transport.currentUser()
    println("[sync-e2e] 账号：$login")
    expect("token-valid", login != null)
    if (login == null) return@runBlocking

    val repoName = "neriplayer-sync-e2e-" + System.currentTimeMillis().toString().takeLast(6)
    val created = transport.createRepo(repoName, private = true)
    println("[sync-e2e] 创建临时私有仓库：$created")
    expect("create-temp-repo", created.isSuccess)
    if (created.isFailure) return@runBlocking

    try {
        val settings = SettingsRepository()
        val playlists = PlaylistRepository().apply { load() }
        val history = HistoryRepository().apply { load() }
        val stats = StatsRepository(history).apply { load() }
        val library = LibraryRepository(settings).apply { load() }
        val store = SyncConfigStore().apply {
            update {
                it.copy(
                    token = token,
                    owner = login,
                    repo = repoName,
                    autoSync = false,
                    useDataSaver = false,
                    apiBase = apiBase,
                )
            }
        }
        val manager = GitHubSyncManager(store, playlists, history, stats, library, http)

        val song = Song(
            key = neteaseSongKey(186016L),
            source = MediaSource.NETEASE,
            title = "同步测试歌曲",
            artist = "测试歌手",
            album = "测试专辑",
            durationMs = 240_000L,
            remoteId = "186016",
            dateAdded = System.currentTimeMillis(),
        )

        val firstSync = manager.performSync()
        println("[sync-e2e] 首次同步：${firstSync.message}")
        expect("initial-upload", firstSync.success, firstSync.message)

        val playlist = playlists.createPlaylist("桌面端同步歌单")
        playlists.addSongs(playlist.id, listOf(song))
        playlists.toggleFavorite(song)
        history.record(song)
        stats.recordPlay(song)
        stats.addListenTime(song, 30_000L)

        val secondSync = manager.performSync()
        println("[sync-e2e] 第二次同步：${secondSync.message}")
        expect("second-sync", secondSync.success, secondSync.message)

        val remoteFile = transport.readSyncFile(login, repoName, useDataSaver = false)
        expect("remote-file-exists", remoteFile != null && remoteFile.content.isNotEmpty())
        val remoteData = remoteFile?.let { SyncDataSerializer.deserialize(it.content) }
        expect(
            "remote-contains-playlist",
            remoteData?.playlists?.any { it.name == "桌面端同步歌单" } == true,
            "歌单=${remoteData?.playlists?.map { it.name }}",
        )
        expect(
            "remote-contains-favorites",
            remoteData?.favoritePlaylists?.any { it.source == "netease" } == true ||
                remoteData?.favoritePlaylists?.isNotEmpty() == true,
        )
        expect(
            "remote-contains-recent-and-stats",
            (remoteData?.recentPlays?.isNotEmpty() == true) && (remoteData?.playbackStatBuckets?.isNotEmpty() == true),
            "recent=${remoteData?.recentPlays?.size} buckets=${remoteData?.playbackStatBuckets?.size}",
        )

        // 模拟另一台设备（手机）写入：新增一个歌单 + 一个收藏夹，随后桌面端应能拉回
        val phoneSong = SyncSong(id = 190137164L, name = "手机端歌曲", artist = "手机端歌手", album = "手机端专辑")
        val phoneData = (remoteData ?: manager.localSnapshotForTest()).copy(
            deviceId = "phone-e2e",
            playlists = (remoteData?.playlists.orEmpty()) + SyncPlaylist(
                id = 999_001L,
                name = "来自手机的歌单",
                songs = listOf(phoneSong),
                createdAt = 1L,
                modifiedAt = System.currentTimeMillis(),
            ),
            favoritePlaylists = (remoteData?.favoritePlaylists.orEmpty()) + SyncFavoritePlaylist(
                id = 999_002L,
                name = "手机端收藏",
                source = "bilibili",
                songs = listOf(phoneSong.copy(id = 0L, channelId = "BV1E2E4y1E2E2")),
                addedTime = 1L,
                modifiedAt = System.currentTimeMillis(),
            ),
        )
        val phoneBytes = SyncDataSerializer.serialize(phoneData, useDataSaver = false)
        val putResult = transport.writeSyncFile(
            owner = login,
            repo = repoName,
            content = phoneBytes,
            expectedHead = remoteFile?.headSha,
            message = "e2e: 模拟手机端写入",
            useDataSaver = false,
        )
        expect("phone-write", putResult is moe.ouom.neriplayer.desktop.sync.SyncWriteResult.Success, "$putResult")

        val thirdSync = manager.performSync()
        println("[sync-e2e] 第三次同步：${thirdSync.message}")
        expect("third-sync", thirdSync.success, thirdSync.message)
        expect(
            "pulled-phone-playlist",
            playlists.playlists.value.any { it.name == "来自手机的歌单" },
            "本地歌单=${playlists.playlists.value.map { it.name }}",
        )
        expect(
            "pulled-phone-favorite",
            playlists.favorites().songs.any { it.artist == "手机端歌手" },
            "收藏=${playlists.favorites().songs.map { it.displayName() }}",
        )

        // 冲突检测：用过期 head 提交应被判定为冲突而不是覆盖
        val conflictResult = transport.writeSyncFile(
            owner = login,
            repo = repoName,
            content = phoneBytes,
            expectedHead = "0000000000000000000000000000000000000000",
            message = "e2e: 过期 head 应冲突",
            useDataSaver = false,
        )
        expect("stale-head-conflict", conflictResult is moe.ouom.neriplayer.desktop.sync.SyncWriteResult.Conflict)

        println("[sync-e2e] 同步统计：${manager.summarize(remoteData ?: manager.localSnapshotForTest())}")
        println("[sync-e2e] 失败项：$failures")
    } finally {
        val removed = transport.deleteRepo(login, repoName)
        println("[sync-e2e] 删除临时仓库 $repoName：$removed")
        expect("cleanup-temp-repo", removed)
    }
    if (failures > 0) {
        kotlin.system.exitProcess(1)
    }
}
