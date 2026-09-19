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
    log("累计失败项：$checksFailed")
    log("DONE")
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
