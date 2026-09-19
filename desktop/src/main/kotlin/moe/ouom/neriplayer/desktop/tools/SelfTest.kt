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
    log("DONE")
}
