package moe.ouom.neriplayer.desktop.tools

import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.desktop.core.AppDirs
import moe.ouom.neriplayer.desktop.core.AudioInput
import moe.ouom.neriplayer.desktop.core.FfmpegSupport
import moe.ouom.neriplayer.desktop.core.LrcParser
import moe.ouom.neriplayer.desktop.core.MediaSource
import moe.ouom.neriplayer.desktop.core.Song
import moe.ouom.neriplayer.desktop.core.createAudioEngine
import moe.ouom.neriplayer.desktop.core.displayName
import moe.ouom.neriplayer.desktop.net.OnlineRepository
import moe.ouom.neriplayer.desktop.net.asObject
import kotlinx.coroutines.cancelChildren
import java.io.File

private fun log(message: String) = println("[selftest] $message")

private var checksFailed = 0

private fun check(name: String, condition: Boolean, detail: String = "") {
    if (condition) {
        log("PASS $name${if (detail.isEmpty()) "" else " ($detail)"}")
    } else {
        checksFailed += 1
        log("FAIL $name${if (detail.isEmpty()) "" else " ($detail)"}")
    }
}

/** 校验哔哩哔哩账号解析：nav(isLogin 为布尔) → 成员接口 → Cookie 兜底。 */
private fun checkAccountParsing() {
    val loggedInNav = """
        {"code":0,"message":"0","data":{"isLogin":true,"uname":"测试用户","mid":123456,
        "face":"https://i0.hdslb.com/bfs/face/x.jpg","vipStatus":{"status":1,"type":2}}}
    """.trimIndent()
    val account = moe.ouom.neriplayer.desktop.net.parseBiliAccount(loggedInNav, null, emptyMap())
    check(
        "bili-nav-logged-in",
        account != null && account.nickname == "测试用户" && account.userId == "123456" && account.vip,
        "nickname=${account?.nickname} mid=${account?.userId} vip=${account?.vip}",
    )

    val loggedOutNav = """{"code":-101,"message":"账号未登录","data":{"isLogin":false}}"""
    val numericNav = """{"code":0,"data":{"isLogin":1,"uname":"数字标记用户","mid":42}}"""
    check(
        "bili-nav-isLogin-number",
        moe.ouom.neriplayer.desktop.net.parseBiliAccount(numericNav, null, emptyMap())?.nickname == "数字标记用户",
    )

    val memberJson = """{"code":0,"data":{"mid":999,"uname":"成员接口用户","face":"https://x/y.png"}}"""
    val fromMember = moe.ouom.neriplayer.desktop.net.parseBiliAccount(loggedOutNav, memberJson, emptyMap())
    check(
        "bili-member-fallback",
        fromMember != null && fromMember.nickname == "成员接口用户" && fromMember.userId == "999",
        "nickname=${fromMember?.nickname}",
    )

    val fromCookie = moe.ouom.neriplayer.desktop.net.parseBiliAccount(
        loggedOutNav,
        null,
        mapOf("SESSDATA" to "abc%2Cdef", "DedeUserID" to "777"),
    )
    check(
        "bili-cookie-fallback",
        fromCookie != null && fromCookie.userId == "777" && fromCookie.displayName() == "UID 777",
        "displayName=${fromCookie?.displayName()}",
    )

    check(
        "bili-not-logged-in",
        moe.ouom.neriplayer.desktop.net.parseBiliAccount(loggedOutNav, null, emptyMap()) == null,
    )
    check(
        "netease-cookie-fallback",
        moe.ouom.neriplayer.desktop.net.parseCookieFallbackAccount(
            source = moe.ouom.neriplayer.desktop.core.MediaSource.NETEASE,
            cookies = mapOf("MUSIC_U" to "token"),
            cookieName = "MUSIC_U",
            userId = "",
        )?.displayName() == "已登录",
    )
}

fun main() = runBlocking {
    log("data dir: ${AppDirs.dataDir}")
    log("ffmpeg available: ${FfmpegSupport.available} (${FfmpegSupport.version})")

    // 1. LRC 解析
    val lrc = """
        [ti:测试]
        [offset:0]
        [00:01.00]第一句
        [00:03.50][00:05.50]重复句
        [00:07.25]最后一句
    """.trimIndent()
    val parsed = LrcParser.parse(lrc)
    log("lrc lines=${parsed.size} first=${parsed.firstOrNull()?.timeMs} last=${parsed.lastOrNull()?.timeMs}")

    // 2. 网络接口
    val online = OnlineRepository()
    val songs = online.search(MediaSource.NETEASE, moe.ouom.neriplayer.desktop.core.SearchKind.SONG, "周杰伦", 1)
    log("netease search result=${songs.songs.size} first=${songs.songs.firstOrNull()?.displayName()} / ${songs.songs.firstOrNull()?.artistText()}")
    val first = songs.songs.firstOrNull()
    if (first != null) {
        val urlResult = online.netease.songUrl(first.remoteId ?: "", "exhigh")
        log("netease song url=${urlResult?.url?.take(60)} level=${urlResult?.level}")
        val lyric = online.netease.lyric(first.remoteId ?: "")
        log("netease lyric length=${lyric?.first?.length}")
        val bili = online.search(MediaSource.BILIBILI, moe.ouom.neriplayer.desktop.core.SearchKind.SONG, "周杰伦 晴天", 1)
        log("bili search result=${bili.songs.size} first=${bili.songs.firstOrNull()?.displayName()}")
        log("bili debug: ${online.bilibili.debugSearch("周杰伦 晴天")}")
        val biliSong = bili.songs.firstOrNull()
        if (biliSong != null) {
            val audio = online.resolvePlayback(biliSong, "exhigh")
            log("bili audio url=${audio?.url?.take(60)}")
        }
    }

    // 5. 在线歌单完整性：详情接口只返回前若干首，必须按 trackIds 补齐
    val hotList = online.netease.playlistDetail("3778678")
    val hotCount = hotList?.second?.size ?: 0
    val declared = hotList?.first?.trackCount ?: 0
    check(
        "netease-playlist-full",
        hotCount >= 100,
        "热歌榜声明 $declared 首，实际取回 $hotCount 首",
    )
    check(
        "netease-playlist-limited",
        (online.netease.playlistDetail("3778678", limit = 5)?.second?.size ?: 0) == 5,
    )

    // 6. 必站收藏夹条目映射（时长单位是秒、封面 http 需要转 https、标题要去掉高亮标签）
    val favoriteItem = moe.ouom.neriplayer.desktop.net.NeriJsonParser.parse(
        """{"bvid":"BV1test","title":"【<em class=\"keyword\">音乐</em>】收藏夹测试视频","duration":206,"cover":"http://i1.hdslb.com/bfs/archive/x.jpg","upper":{"name":"测试UP主"}}"""
    ).asObject()
    val mapped = favoriteItem?.let {
        moe.ouom.neriplayer.desktop.net.favoriteMediaToSong(moe.ouom.neriplayer.desktop.net.JsonObjectSelf(it))
    }
    check(
        "bili-favorite-mapping",
        mapped != null &&
            mapped.title == "【音乐】收藏夹测试视频" &&
            mapped.durationMs == 206_000L &&
            mapped.artist == "测试UP主" &&
            mapped.remoteId == "BV1test" &&
            mapped.artworkUrl?.startsWith("https://") == true,
        "title=${mapped?.title} duration=${mapped?.durationMs} cover=${mapped?.artworkUrl}",
    )
    val emptyItem = moe.ouom.neriplayer.desktop.net.NeriJsonParser.parse("{}").asObject()
    check(
        "bili-favorite-missing-bvid",
        emptyItem == null ||
            moe.ouom.neriplayer.desktop.net.favoriteMediaToSong(
                moe.ouom.neriplayer.desktop.net.JsonObjectSelf(emptyItem)
            ) == null,
    )

    // 3. 生成测试音频并播放
    val tone = File(AppDirs.cacheDir, "selftest-tone.wav")
    val ffmpeg = ProcessBuilder(
        "ffmpeg", "-y", "-f", "lavfi", "-i", "sine=frequency=440:duration=3",
        "-ac", "2", "-ar", "48000", tone.absolutePath
    ).redirectErrorStream(true).start()
    ffmpeg.inputStream.readBytes()
    ffmpeg.waitFor()
    log("test tone generated: ${tone.isFile} size=${tone.length()}")

    val engine = createAudioEngine()
    log("engine=${engine.javaClass.simpleName} supportsEffects=${engine.supportsEffects}")
    var completed = false
    engine.onCompleted = { completed = true; log("engine completed callback") }
    engine.onError = { log("engine error: $it") }
    engine.open(AudioInput(path = tone.absolutePath), 0L, 3000L)
    engine.play()
    repeat(8) {
        kotlinx.coroutines.delay(400)
        val snap = engine.snapshot.value
        log("position=${snap.positionMs}ms playing=${snap.playing} buffering=${snap.buffering} error=${snap.error}")
    }
    engine.setSpeed(1.5f)
    kotlinx.coroutines.delay(800)
    log("after speed change position=${engine.snapshot.value.positionMs}ms")
    engine.seekTo(0)
    engine.play()
    kotlinx.coroutines.delay(1200)
    log("after seek position=${engine.snapshot.value.positionMs}ms playing=${engine.snapshot.value.playing}")
    kotlinx.coroutines.delay(2500)
    log("completed=$completed final=${engine.snapshot.value.positionMs}ms")
    engine.release()
    log("--- 账号解析自检 ---")
    checkAccountParsing()
    log("账号解析失败项：$checksFailed")
    log("--- GitHub 同步自检 ---")
    checkSyncSerializerAndMerge()
    log("--- 悬浮歌词自检 ---")
    log("--- 定位到正在播放自检 ---")
    log("--- 下载自检 ---")
    checkDownloads(online)
    log("--- 后台与系统控制自检 ---")
    checkBackground()
    log("累计失败项：$checksFailed")
    log("DONE")
}

/** 后台播放信息快照（MPRIS / 托盘 / 通知共用）。 */
private fun checkBackground() {
    val song = moe.ouom.neriplayer.desktop.core.Song(
        key = "netease:123",
        source = moe.ouom.neriplayer.desktop.core.MediaSource.NETEASE,
        title = "测试歌曲",
        artist = "测试歌手",
        album = "测试专辑",
        durationMs = 210_000L,
        artworkUrl = "https://p1.music.126.net/x.jpg",
    )
    val snapshot = moe.ouom.neriplayer.desktop.core.buildNowPlayingSnapshot(
        song = song,
        durationMs = 210_000L,
        positionMs = 12_000L,
        playing = true,
    )
    check(
        "background-snapshot-fields",
        snapshot != null &&
            snapshot.title == "测试歌曲" &&
            snapshot.artist == "测试歌手" &&
            snapshot.album == "测试专辑" &&
            snapshot.durationMs == 210_000L &&
            snapshot.positionMs == 12_000L &&
            snapshot.playing &&
            snapshot.artUrl == "https://p1.music.126.net/x.jpg" &&
            snapshot.source == "netease",
        "snapshot=$snapshot",
    )
    check(
        "background-snapshot-empty",
        moe.ouom.neriplayer.desktop.core.buildNowPlayingSnapshot(null, 0L, 0L, false) == null,
    )
}

/** 下载：文件名清理、记录读写，以及一次真实的在线歌曲下载。 */
private suspend fun checkDownloads(online: moe.ouom.neriplayer.desktop.net.OnlineRepository) {
    check(
        "download-sanitize-illegal",
        moe.ouom.neriplayer.desktop.core.sanitizeFileName("a/b:c*d?e\"f<g>h|i") == "a_b_c_d_e_f_g_h_i",
        moe.ouom.neriplayer.desktop.core.sanitizeFileName("a/b:c*d?e\"f<g>h|i"),
    )
    check(
        "download-sanitize-blank",
        moe.ouom.neriplayer.desktop.core.sanitizeFileName("   ") == "unknown",
    )
    check(
        "download-sanitize-length",
        moe.ouom.neriplayer.desktop.core.sanitizeFileName("x".repeat(300)).length == 120,
    )
    check(
        "download-sanitize-spaces",
        moe.ouom.neriplayer.desktop.core.sanitizeFileName("周杰伦   晴天 ") == "周杰伦 晴天",
    )

    // 真实下载：搜索一首在线歌曲 → 下载到临时目录 → 校验文件与记录
    val tempDir = kotlin.io.path.createTempDirectory("neri-download-test").toFile()
    val catalogFile = java.io.File(tempDir, "downloads.json")
    val catalog = moe.ouom.neriplayer.desktop.core.DownloadCatalog(catalogFile)
    val settingsRepo = moe.ouom.neriplayer.desktop.core.SettingsRepository()
    val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
    val manager = moe.ouom.neriplayer.desktop.core.DownloadManager(
        online = online,
        settings = settingsRepo,
        catalog = catalog,
        scope = scope,
        directoryOverride = tempDir,
        lyricsProvider = { song -> online.lyrics(song)?.first },
    )
    val candidates = online.search(
        moe.ouom.neriplayer.desktop.core.MediaSource.NETEASE,
        moe.ouom.neriplayer.desktop.core.SearchKind.SONG,
        "晴天 周杰伦",
    ).songs
    val target = candidates.firstOrNull()
    if (target == null) {
        check("download-real-file", false, "搜索不到可用歌曲")
    } else {
        val queued = manager.enqueue(listOf(target))
        check("download-enqueue", queued == 1, "queued=$queued")
        val deadline = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < deadline && catalog.items.value[target.key] == null) {
            kotlinx.coroutines.delay(500)
        }
        val entry = catalog.items.value[target.key]
        val file = entry?.let { java.io.File(it.filePath) }
        check(
            "download-real-file",
            file != null && file.isFile && file.length() > 0L,
            "file=${file?.absolutePath} size=${file?.length()}",
        )
        check(
            "download-file-name",
            file != null && file.name.contains("晴天") && file.extension == "mp3",
            "name=${file?.name}",
        )
        check(
            "download-catalog-persist",
            runCatching {
                val reloaded = moe.ouom.neriplayer.desktop.core.DownloadCatalog(catalogFile)
                reloaded.load()
                reloaded.contains(target.key)
            }.getOrDefault(false),
        )
        check(
            "download-enqueue-dedupe",
            manager.enqueue(listOf(target)) == 0,
            "重复入队应被跳过",
        )

        // 元数据：内嵌标签、内嵌封面、封面 sidecar、歌词 sidecar
        val downloadedFile = entry?.let { java.io.File(it.filePath) }
        if (downloadedFile != null && downloadedFile.isFile) {
            val audio = runCatching { org.jaudiotagger.audio.AudioFileIO.read(downloadedFile) }.getOrNull()
            check(
                "download-tag-title",
                audio?.tag?.getFirst(org.jaudiotagger.tag.FieldKey.TITLE) == target.displayName(),
                "title=${audio?.tag?.getFirst(org.jaudiotagger.tag.FieldKey.TITLE)}",
            )
            check(
                "download-tag-artist",
                audio?.tag?.getFirst(org.jaudiotagger.tag.FieldKey.ARTIST) == target.artist,
                "artist=${audio?.tag?.getFirst(org.jaudiotagger.tag.FieldKey.ARTIST)}",
            )
            val embedded = runCatching { audio?.tag?.firstArtwork?.binaryData }.getOrNull()
            check(
                "download-embedded-cover",
                embedded != null && embedded.isNotEmpty(),
                "bytes=${embedded?.size ?: 0}",
            )
            val coverFile = entry.artworkPath?.let { java.io.File(it) }
            check(
                "download-cover-sidecar",
                coverFile != null && coverFile.isFile && coverFile.length() > 1024,
                "file=${coverFile?.name} size=${coverFile?.length()}",
            )
            val lyricFile = entry.lyricsPath?.let { java.io.File(it) }
            check(
                "download-lyrics-sidecar",
                lyricFile != null && lyricFile.isFile && lyricFile.readText().contains("["),
                "file=${lyricFile?.name} size=${lyricFile?.length()}",
            )

            // 模拟「旧版本下载的文件」：清空标签与内嵌封面后，用「补齐标签」恢复
            val stripped = runCatching {
                val audio = org.jaudiotagger.audio.AudioFileIO.read(downloadedFile)
                val tag = audio.tag
                tag?.deleteField(org.jaudiotagger.tag.FieldKey.TITLE)
                tag?.deleteField(org.jaudiotagger.tag.FieldKey.ARTIST)
                tag?.deleteArtworkField()
                audio.commit()
                org.jaudiotagger.audio.AudioFileIO.read(downloadedFile)
                    .tag?.getFirst(org.jaudiotagger.tag.FieldKey.TITLE)
                    .isNullOrBlank()
            }.getOrDefault(false)
            check("download-repair-stripped", stripped, "清空标签后应为空")
            val repaired = kotlinx.coroutines.runBlocking { manager.repairMetadata() }
            val afterRepair = runCatching {
                org.jaudiotagger.audio.AudioFileIO.read(downloadedFile)
            }.getOrNull()
            check("download-repair-count", repaired >= 1, "repaired=$repaired")
            check(
                "download-repair-tag",
                afterRepair?.tag?.getFirst(org.jaudiotagger.tag.FieldKey.TITLE) == target.displayName(),
                "title=${afterRepair?.tag?.getFirst(org.jaudiotagger.tag.FieldKey.TITLE)}",
            )
            check(
                "download-repair-cover",
                afterRepair?.tag?.firstArtwork?.binaryData?.isNotEmpty() == true,
                "bytes=${afterRepair?.tag?.firstArtwork?.binaryData?.size ?: 0}",
            )
        }
    }
    scope.coroutineContext.cancelChildren()
    tempDir.deleteRecursively()
}

/** 同步通道序列化与合并策略的离线自检。 */
private fun checkSyncSerializerAndMerge() {
    val songA = moe.ouom.neriplayer.desktop.sync.SyncSong(
        id = 123L,
        name = "测试歌曲A",
        artist = "歌手A",
        album = "专辑A",
        durationMs = 210_000L,
        addedAt = 1_000L,
    )
    val songB = songA.copy(id = 456L, name = "测试歌曲B", album = "专辑B", addedAt = 2_000L)
    val payload = moe.ouom.neriplayer.desktop.sync.SyncData(
        deviceId = "desktop-test",
        deviceName = "self-test",
        playlists = listOf(
            moe.ouom.neriplayer.desktop.sync.SyncPlaylist(
                id = 1L,
                name = "歌单A",
                songs = listOf(songA),
                createdAt = 10L,
                modifiedAt = 20L,
            )
        ),
        favoritePlaylists = listOf(
            moe.ouom.neriplayer.desktop.sync.SyncFavoritePlaylist(
                id = 7L,
                name = "网易云收藏",
                source = "netease",
                songs = listOf(songA),
                addedTime = 5L,
                modifiedAt = 6L,
            )
        ),
        recentPlays = listOf(
            moe.ouom.neriplayer.desktop.sync.SyncRecentPlay(songId = 123L, song = songA, playedAt = 999L)
        ),
        playbackStatBuckets = listOf(
            moe.ouom.neriplayer.desktop.sync.SyncPlaybackStatBucket(
                dayStartAt = 1_700_000_000_000L,
                identityKey = "123|专辑A|",
                playCount = 3,
                totalListenMs = 12_000L,
            )
        ),
    )

    val jsonBytes = moe.ouom.neriplayer.desktop.sync.SyncDataSerializer.serialize(payload, useDataSaver = false)
    check("sync-json-text", jsonBytes.decodeToString().trimStart().startsWith("{"))
    check(
        "sync-json-roundtrip",
        moe.ouom.neriplayer.desktop.sync.SyncDataSerializer.deserialize(jsonBytes) == payload,
    )

    val rawBytes = moe.ouom.neriplayer.desktop.sync.SyncDataSerializer.serialize(payload, useDataSaver = true)
    check(
        "sync-raw-gzip-magic",
        rawBytes.size > 2 && rawBytes[0] == 0x1F.toByte() && rawBytes[1] == 0x8B.toByte(),
        "size=${rawBytes.size} json=${jsonBytes.size}",
    )
    check(
        "sync-raw-roundtrip",
        moe.ouom.neriplayer.desktop.sync.SyncDataSerializer.deserialize(rawBytes) == payload,
    )
    val legacy = java.util.Base64.getEncoder().encodeToString(rawBytes)
    check(
        "sync-legacy-base64-roundtrip",
        moe.ouom.neriplayer.desktop.sync.SyncDataSerializer.deserialize(legacy.toByteArray()) == payload,
    )

    // 合并：歌单按 modifiedAt 取新 + 歌曲取并集
    val local = moe.ouom.neriplayer.desktop.sync.SyncData(
        playlists = listOf(
            moe.ouom.neriplayer.desktop.sync.SyncPlaylist(id = 1L, name = "本地旧名", songs = listOf(songA), modifiedAt = 10L)
        ),
    )
    val remote = moe.ouom.neriplayer.desktop.sync.SyncData(
        deviceId = "phone",
        playlists = listOf(
            moe.ouom.neriplayer.desktop.sync.SyncPlaylist(id = 1L, name = "远端新名", songs = listOf(songB), modifiedAt = 20L)
        ),
    )
    val merged = moe.ouom.neriplayer.desktop.sync.SyncMerger.merge(local, remote)
    check(
        "sync-merge-playlist-newer-wins",
        merged.playlists.size == 1 && merged.playlists.first().name == "远端新名",
        "name=${merged.playlists.firstOrNull()?.name}",
    )
    check(
        "sync-merge-song-union",
        merged.playlists.first().songs.size == 2,
        "songs=${merged.playlists.first().songs.size}",
    )
    check(
        "sync-merge-keeps-remote-fields",
        merged.deviceId == "phone",
        "deviceId=${merged.deviceId}",
    )

    // 统计合并取 max，避免重复累加
    val statLocal = moe.ouom.neriplayer.desktop.sync.SyncTrackStat(
        identityKey = "123|专辑A|",
        totalListenMs = 5_000L,
        playCount = 2,
    )
    val statRemote = statLocal.copy(totalListenMs = 9_000L, playCount = 4)
    val mergedStats = moe.ouom.neriplayer.desktop.sync.SyncMerger.merge(
        moe.ouom.neriplayer.desktop.sync.SyncData(playbackStats = listOf(statLocal)),
        moe.ouom.neriplayer.desktop.sync.SyncData(playbackStats = listOf(statRemote)),
    ).playbackStats
    check(
        "sync-merge-stats-max",
        mergedStats.size == 1 && mergedStats.first().totalListenMs == 9_000L && mergedStats.first().playCount == 4,
    )

    // 最近播放：按身份去重并保留最新时间
    val recentMerged = moe.ouom.neriplayer.desktop.sync.SyncMerger.merge(
        moe.ouom.neriplayer.desktop.sync.SyncData(
            recentPlays = listOf(
                moe.ouom.neriplayer.desktop.sync.SyncRecentPlay(song = songA, playedAt = 100L)
            )
        ),
        moe.ouom.neriplayer.desktop.sync.SyncData(
            recentPlays = listOf(
                moe.ouom.neriplayer.desktop.sync.SyncRecentPlay(song = songA, playedAt = 500L)
            )
        ),
    ).recentPlays
    check("sync-merge-recent-latest", recentMerged.size == 1 && recentMerged.first().playedAt == 500L)

    // 删除墓碑：比 addedAt 更新的删除记录应移除歌曲
    val tombstone = moe.ouom.neriplayer.desktop.sync.SyncPlaylistSongDeletion(
        playlistId = 1L,
        songId = songB.id,
        album = songB.album,
        deletedAt = 5_000L,
    )
    val afterDeletion = moe.ouom.neriplayer.desktop.sync.SyncMerger.merge(
        moe.ouom.neriplayer.desktop.sync.SyncData(
            playlists = listOf(
                moe.ouom.neriplayer.desktop.sync.SyncPlaylist(id = 1L, name = "列表", songs = listOf(songA), modifiedAt = 1L)
            )
        ),
        moe.ouom.neriplayer.desktop.sync.SyncData(
            playlists = listOf(
                moe.ouom.neriplayer.desktop.sync.SyncPlaylist(id = 1L, name = "列表", songs = listOf(songB), modifiedAt = 2L)
            ),
            playlistSongDeletions = listOf(tombstone),
        ),
    ).playlists.first().songs
    check("sync-merge-deletion-tombstone", afterDeletion.size == 1 && afterDeletion.first().id == songA.id)

}

