package moe.ouom.neriplayer.desktop.tools

import kotlinx.coroutines.delay
import moe.ouom.neriplayer.desktop.core.AppContainer

/**
 * 界面自动化脚本：用于在没有输入合成能力的环境中驱动界面完成运行测试。
 *
 * 通过环境变量 `NERIPLAYER_UI_TEST` 传入，例如：
 * `tab:library;sleep:800;play:0;sleep:1500;screen:nowplaying;overlay:lyrics`
 */
data class UiScriptCommand(
    val name: String,
    val argument: String,
)

fun parseUiScript(script: String): List<UiScriptCommand> =
    script.split(';')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { part ->
            val index = part.indexOf(':')
            if (index < 0) {
                UiScriptCommand(part.lowercase(), "")
            } else {
                UiScriptCommand(part.take(index).trim().lowercase(), part.substring(index + 1).trim())
            }
        }

/** 脚本运行时对界面状态的操控入口。 */
interface UiScriptHost {
    fun selectTab(tab: String)
    fun openScreen(screen: String, argument: String)
    fun goBack()
    fun setOverlay(name: String?)
    fun showMessage(message: String)
    fun log(message: String)
}

/**
 * 执行脚本。返回是否全部执行成功。
 */
suspend fun runUiScript(
    script: String,
    container: AppContainer,
    host: UiScriptHost,
): Boolean {
    var ok = true
    var markedSongKey: String? = null
    var markedQueueIndex: Int = -1
    for (command in parseUiScript(script)) {
        when (command.name) {
            "sleep" -> delay(command.argument.toLongOrNull() ?: 500L)
            "tab" -> host.selectTab(command.argument)
            "screen" -> host.openScreen(command.argument, "")
            "play" -> {
                val songs = container.library.songs.value
                val index = command.argument.toIntOrNull() ?: 0
                if (songs.isEmpty()) {
                    host.log("FAIL play: 本地媒体库为空")
                    ok = false
                } else {
                    container.player.setQueue(songs, index.coerceIn(0, songs.lastIndex), autoPlay = true)
                    host.log("play index=$index song=${songs.getOrNull(index)?.displayName()}")
                }
            }

            "play-online" -> {
                val songs = container.player.queue.value
                if (songs.isEmpty()) {
                    host.log("FAIL play-online: 队列为空")
                    ok = false
                } else {
                    container.player.setQueue(songs, 0, autoPlay = true)
                }
            }

            "online" -> {
                val query = command.argument.ifBlank { "周杰伦" }
                val payload = kotlinx.coroutines.runBlocking {
                    runCatching {
                        container.online.search(
                            moe.ouom.neriplayer.desktop.core.MediaSource.NETEASE,
                            moe.ouom.neriplayer.desktop.core.SearchKind.SONG,
                            query,
                        )
                    }.getOrNull()
                }
                val songs = payload?.songs.orEmpty()
                host.log("online search '$query' -> ${songs.size} results")
                if (songs.isEmpty()) {
                    host.log("FAIL online: 网易云搜索无结果")
                    ok = false
                } else {
                    container.player.setQueue(songs, 0, autoPlay = true)
                    host.log("online playing: ${songs.first().displayName()} - ${songs.first().artistText()}")
                }
            }

            "bili" -> {
                val query = command.argument.ifBlank { "音乐" }
                val songs = kotlinx.coroutines.runBlocking {
                    runCatching { container.online.bilibili.searchVideos(query) }.getOrDefault(emptyList())
                }
                host.log("bili search '$query' -> ${songs.size} results")
                if (songs.isEmpty()) {
                    host.log("FAIL bili: 哔哩哔哩搜索无结果")
                    ok = false
                } else {
                    container.player.setQueue(songs, 0, autoPlay = true)
                    host.log("bili playing: ${songs.first().displayName()}")
                }
            }

            "pause" -> container.player.pause()
            "resume" -> container.player.play()
            "mark-song" -> {
                markedSongKey = container.player.currentSong.value?.key
                markedQueueIndex = container.player.currentIndex.value
                host.log("mark-song key=$markedSongKey index=$markedQueueIndex")
            }

            "expect-same-song" -> {
                val currentKey = container.player.currentSong.value?.key
                val currentIndex = container.player.currentIndex.value
                if (currentKey == markedSongKey && currentIndex == markedQueueIndex) {
                    host.log("PASS expect-same-song key=$currentKey index=$currentIndex")
                } else {
                    host.log(
                        "FAIL expect-same-song 期望 key=$markedSongKey index=$markedQueueIndex " +
                            "实际 key=$currentKey index=$currentIndex"
                    )
                    ok = false
                }
            }

            "expect-paused" -> {
                val state = container.player.state.value
                if (state == moe.ouom.neriplayer.desktop.core.PlaybackState.PAUSED) {
                    host.log("PASS expect-paused position=${container.player.positionMs.value}ms")
                } else {
                    host.log("FAIL expect-paused 实际状态=$state")
                    ok = false
                }
            }

            "expect-song-changed" -> {
                val currentKey = container.player.currentSong.value?.key
                if (currentKey != null && currentKey != markedSongKey) {
                    host.log("PASS expect-song-changed 已切到 key=$currentKey")
                } else {
                    host.log("FAIL expect-song-changed 仍停留在 key=$currentKey")
                    ok = false
                }
            }

            "expect-cover-key" -> {
                // 便于人工核对：输出当前歌曲的封面来源，切歌后应随之变化
                val song = container.player.currentSong.value
                host.log(
                    "cover-key song=${song?.key} artwork=${song?.artworkPath ?: song?.artworkUrl}"
                )
            }
            "next" -> container.player.next()
            "prev" -> container.player.previous()
            "seek" -> container.player.seekTo(command.argument.toLongOrNull() ?: 0L)
            "volume" -> container.player.setVolume(command.argument.toFloatOrNull() ?: 0.8f)
            "speed" -> container.player.setSpeed(command.argument.toFloatOrNull() ?: 1f)
            "reset-effects" -> container.player.resetEffects()
            "shuffle" -> container.player.toggleShuffle()
            "repeat" -> container.player.cycleRepeatMode()
            "overlay" -> host.setOverlay(command.argument.ifBlank { null })
            "close-overlay" -> host.setOverlay(null)
            "back" -> {
                host.setOverlay(null)
                host.goBack()
            }

            "favorite" -> {
                val song = container.player.currentSong.value
                if (song != null) {
                    val added = container.playlists.toggleFavorite(song)
                    host.log("favorite ${song.displayName()} -> $added")
                }
            }

            "create-playlist" -> {
                val playlist = container.playlists.createPlaylist(command.argument.ifBlank { "测试歌单" })
                host.log("created playlist id=${playlist.id} name=${playlist.name}")
            }

            "add-current-to-playlist" -> {
                val song = container.player.currentSong.value
                val playlist = container.playlists.playlists.value.lastOrNull()
                if (song == null || playlist == null) {
                    host.log("FAIL add-current-to-playlist: 缺少歌曲或歌单")
                    ok = false
                } else {
                    container.playlists.addSongs(playlist.id, listOf(song))
                    host.log("added ${song.displayName()} to ${playlist.name}")
                }
            }

            "open-playlist" -> {
                val playlist = container.playlists.playlists.value.firstOrNull { it.name == command.argument }
                    ?: container.playlists.playlists.value.firstOrNull()
                if (playlist != null) host.openScreen("playlist", playlist.id)
            }

            "message" -> host.showMessage(command.argument)
            "login" -> host.setOverlay("login-${command.argument}")
            "login-check" -> {
                val source = when (command.argument.lowercase()) {
                    "netease" -> moe.ouom.neriplayer.desktop.core.MediaSource.NETEASE
                    else -> moe.ouom.neriplayer.desktop.core.MediaSource.BILIBILI
                }
                val result = kotlinx.coroutines.runBlocking {
                    val created = runCatching {
                        if (source == moe.ouom.neriplayer.desktop.core.MediaSource.NETEASE) {
                            container.neteaseLogin.createQrCode()
                        } else {
                            container.biliLogin.createQrCode()
                        }
                    }.getOrNull()
                    if (created == null) {
                        "二维码创建失败"
                    } else {
                        delay(1200)
                        val polled = runCatching {
                            if (source == moe.ouom.neriplayer.desktop.core.MediaSource.NETEASE) {
                                container.neteaseLogin.poll(created.first)
                            } else {
                                container.biliLogin.poll(created.first)
                            }
                        }.getOrNull()
                        "二维码已生成（${created.second.take(52)}…）轮询结果=$polled"
                    }
                }
                host.log("login-check ${source.displayName}: $result")
            }
            "log" -> host.log(command.argument)
            "floating" -> {
                val enabled = command.argument.equals("on", ignoreCase = true)
                container.settings.update { it.copy(floatingLyricsEnabled = enabled) }
                host.log("floating-lyrics enabled=$enabled")
            }
            "floating-config" -> {
                // 形如 floating-config:fontSize=44,color=YELLOW,style=OUTLINE,translation=true
                command.argument.split(',').map { it.trim() }.filter { it.contains('=') }.forEach { pair ->
                    val key = pair.substringBefore('=')
                    val value = pair.substringAfter('=')
                    container.settings.update { current ->
                        when (key) {
                            "fontSize" -> current.copy(floatingLyricsFontSize = value.toFloatOrNull() ?: current.floatingLyricsFontSize)
                            "color" -> current.copy(floatingLyricsTextColor = value)
                            "style" -> current.copy(floatingLyricsRenderStyle = value)
                            "outlineColor" -> current.copy(floatingLyricsOutlineColor = value)
                            "shadowColor" -> current.copy(floatingLyricsShadowColor = value)
                            "translation" -> current.copy(floatingLyricsShowTranslation = value.toBoolean())
                            "alpha" -> current.copy(floatingLyricsLyricAlpha = value.toFloatOrNull() ?: current.floatingLyricsLyricAlpha)
                            "background" -> current.copy(floatingLyricsBackgroundAlpha = value.toFloatOrNull() ?: current.floatingLyricsBackgroundAlpha)
                            "maxWidth" -> current.copy(floatingLyricsMaxWidthDp = value.toFloatOrNull() ?: current.floatingLyricsMaxWidthDp)
                            "positionX" -> current.copy(floatingLyricsPositionX = value.toFloatOrNull() ?: current.floatingLyricsPositionX)
                            "positionY" -> current.copy(floatingLyricsPositionY = value.toFloatOrNull() ?: current.floatingLyricsPositionY)
                            "hideInApp" -> current.copy(floatingLyricsHideInApp = value.toBoolean())
                            "locked" -> current.copy(floatingLyricsLocked = value.toBoolean())
                            else -> current
                        }
                    }
                }
                host.log("floating-config applied: ${command.argument}")
            }
            "bili-debug" -> {
                val info = kotlinx.coroutines.runBlocking {
                    runCatching { container.online.bilibili.debugSearch(command.argument.ifBlank { "音乐" }) }
                        .getOrElse { "exception: ${it.message}" }
                }
                host.log("bili-debug: $info")
            }
            "expect-playing" -> {
                delay(1200)
                val snapshot = container.player.engine.snapshot.value
                val playing = container.player.state.value == moe.ouom.neriplayer.desktop.core.PlaybackState.PLAYING
                if (!playing || snapshot.positionMs <= 0L) {
                    host.log("FAIL expect-playing: state=${container.player.state.value} position=${snapshot.positionMs}")
                    ok = false
                } else {
                    host.log("PASS expect-playing position=${snapshot.positionMs}ms")
                }
            }

            "done" -> {
                host.log("UI_SCRIPT_DONE ok=$ok")
            }

            else -> host.log("unknown command: ${command.name}")
        }
    }
    host.log("UI_SCRIPT_FINISHED ok=$ok")
    return ok
}
