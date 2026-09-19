package moe.ouom.neriplayer.desktop.core

import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.Variant

const val MPRIS_BUS_NAME = "org.mpris.MediaPlayer2.neriplayer"
const val MPRIS_OBJECT_PATH = "/org/mpris/MediaPlayer2"
private const val MPRIS_ROOT_IFACE = "org.mpris.MediaPlayer2"
private const val MPRIS_PLAYER_IFACE = "org.mpris.MediaPlayer2.Player"

/** 当前播放信息快照（供 MPRIS / 托盘 / 通知使用）。 */
data class NowPlayingSnapshot(
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val positionMs: Long,
    val playing: Boolean,
    val artUrl: String?,
    val source: String,
)

/** 由当前歌曲与播放状态构造 MPRIS / 托盘用的信息快照。 */
fun buildNowPlayingSnapshot(
    song: Song?,
    durationMs: Long,
    positionMs: Long,
    playing: Boolean,
): NowPlayingSnapshot? {
    if (song == null) return null
    return NowPlayingSnapshot(
        trackId = song.key,
        title = song.displayName(),
        artist = song.artistText(),
        album = song.album,
        durationMs = durationMs,
        positionMs = positionMs,
        playing = playing,
        artUrl = song.artworkUrl,
        source = song.source.name.lowercase(),
    )
}

@DBusInterfaceName(MPRIS_ROOT_IFACE)
interface MprisRootInterface : DBusInterface {
    fun Raise()
    fun Quit()
}

@DBusInterfaceName(MPRIS_PLAYER_IFACE)
interface MprisPlayerInterface : DBusInterface {
    fun Next()
    fun Previous()
    fun Pause()
    fun PlayPause()
    fun Stop()
    fun Play()
    fun Seek(offset: Long)
    fun SetPosition(trackId: org.freedesktop.dbus.ObjectPath, position: Long)
    fun OpenUri(uri: String)
}

/**
 * MPRIS 媒体控制服务：向桌面环境（GNOME / KDE 媒体组件、playerctl、媒体键）暴露播放器，
 * 对应手机端的「通知栏/媒体键控制」。
 */
class MprisService(
    private val snapshotProvider: () -> NowPlayingSnapshot?,
    private val onPlayPause: () -> Unit,
    private val onPlay: () -> Unit,
    private val onPause: () -> Unit,
    private val onNext: () -> Unit,
    private val onPrevious: () -> Unit,
    private val onStop: () -> Unit,
    private val onSeekBy: (Long) -> Unit,
    private val onSeekTo: (Long) -> Unit,
    private val volumeProvider: () -> Double,
    private val onVolumeChange: (Double) -> Unit,
    private val shuffleProvider: () -> Boolean,
    private val onShuffleChange: (Boolean) -> Unit,
    private val repeatProvider: () -> RepeatMode,
    private val onRepeatChange: (RepeatMode) -> Unit,
) {
    var onRaise: (() -> Unit)? = null
    var onQuit: (() -> Unit)? = null

    private var connection: DBusConnection? = null
    private var exported: ExportedPlayer? = null

    val running: Boolean get() = connection != null

    /** 启动 MPRIS；D-Bus 不可用时返回 false（不影响应用其它功能）。 */
    fun start(): Boolean {
        if (connection != null) return true
        return runCatching {
            val conn = DBusConnectionBuilder.forSessionBus().build()
            val player = ExportedPlayer()
            conn.exportObject(MPRIS_OBJECT_PATH, player)
            conn.requestBusName(MPRIS_BUS_NAME)
            connection = conn
            exported = player
            println("[mpris] 已注册 $MPRIS_BUS_NAME（系统媒体控制可用）")
            true
        }.getOrElse { error ->
            println("[mpris] 启动失败：${error.message}")
            runCatching { connection?.disconnect() }
            connection = null
            exported = null
            false
        }
    }

    fun stop() {
        runCatching { connection?.disconnect() }
        connection = null
        exported = null
    }

    /** 播放状态 / 歌曲变化后调用，向外广播属性变化。 */
    fun refresh(vararg properties: String) {
        val conn = connection ?: return
        val changed = properties.ifEmpty {
            arrayOf("PlaybackStatus", "Metadata", "Volume", "LoopStatus", "Shuffle")
        }
        runCatching {
            val map = changed.associateWith { name -> exported?.variantFor(name) ?: Variant("") }
            conn.sendMessage(
                Properties.PropertiesChanged(MPRIS_OBJECT_PATH, MPRIS_PLAYER_IFACE, map, emptyList())
            )
            if (changed.any { it == "Metadata" || it == "PlaybackStatus" }) {
                conn.sendMessage(
                    Properties.PropertiesChanged(
                        MPRIS_OBJECT_PATH,
                        MPRIS_ROOT_IFACE,
                        emptyMap(),
                        emptyList(),
                    )
                )
            }
        }.onFailure { println("[mpris] 广播属性失败：${it.message}") }
    }

    /** 实际导出的 D-Bus 对象：同时实现根接口、播放器接口与属性接口。 */
    private inner class ExportedPlayer : MprisRootInterface, MprisPlayerInterface, Properties {

        override fun getObjectPath(): String = MPRIS_OBJECT_PATH

        // ---------------- 根接口 ----------------
        override fun Raise() {
            onRaise?.invoke()
        }

        override fun Quit() {
            onQuit?.invoke()
        }

        // ---------------- 播放器接口 ----------------
        override fun Next() {
            onNext()
            refresh("Metadata", "PlaybackStatus")
        }

        override fun Previous() {
            onPrevious()
            refresh("Metadata", "PlaybackStatus")
        }

        override fun Pause() {
            onPause()
            refresh("PlaybackStatus")
        }

        override fun PlayPause() {
            onPlayPause()
            refresh("PlaybackStatus")
        }

        override fun Stop() {
            onStop()
            refresh("PlaybackStatus")
        }

        override fun Play() {
            onPlay()
            refresh("PlaybackStatus")
        }

        override fun Seek(offset: Long) {
            onSeekBy(offset / 1000L)
            refresh("Position")
        }

        override fun SetPosition(trackId: org.freedesktop.dbus.ObjectPath, position: Long) {
            onSeekTo(position / 1000L)
            refresh("Position")
        }

        override fun OpenUri(uri: String) = Unit

        // ---------------- 属性 ----------------
        @Suppress("UNCHECKED_CAST")
        override fun <A : Any?> Get(interfaceName: String, propertyName: String): A =
            when (interfaceName) {
                MPRIS_ROOT_IFACE -> when (propertyName) {
                    "CanQuit", "CanRaise" -> true
                    "HasTrackList" -> false
                    "Identity" -> "NeriPlayer"
                    "DesktopEntry" -> "neriplayer"
                    // 列表必须带显式签名，否则 dbus-java 无法推断类型
                    "SupportedUriSchemes", "SupportedMimeTypes" -> Variant(emptyList<String>(), "as")
                    else -> null
                } as A

                // 返回带签名的 Variant，避免 Map/List 这类集合类型无法推断签名
                MPRIS_PLAYER_IFACE -> variantFor(propertyName) as A
                else -> null as A
            }

        override fun <A : Any?> Set(interfaceName: String, propertyName: String, value: A) {
            if (interfaceName != MPRIS_PLAYER_IFACE) return
            when (propertyName) {
                "Volume" -> (value as? Double)?.let(onVolumeChange)
                "Shuffle" -> (value as? Boolean)?.let(onShuffleChange)
                "LoopStatus" -> (value as? String)?.let { status ->
                    onRepeatChange(
                        when (status) {
                            "Track" -> RepeatMode.ONE
                            "Playlist" -> RepeatMode.ALL
                            else -> RepeatMode.OFF
                        }
                    )
                }
            }
            refresh(propertyName)
        }

        override fun GetAll(interfaceName: String): Map<String, Variant<*>> = when (interfaceName) {
            MPRIS_ROOT_IFACE -> mapOf(
                "CanQuit" to Variant(true),
                "CanRaise" to Variant(true),
                "HasTrackList" to Variant(false),
                "Identity" to Variant("NeriPlayer"),
                "DesktopEntry" to Variant("neriplayer"),
                "SupportedUriSchemes" to Variant(emptyList<String>(), "as"),
                "SupportedMimeTypes" to Variant(emptyList<String>(), "as"),
            )

            MPRIS_PLAYER_IFACE -> listOf(
                "PlaybackStatus",
                "LoopStatus",
                "Rate",
                "Shuffle",
                "Metadata",
                "Volume",
                "Position",
                "MinimumRate",
                "MaximumRate",
                "CanGoNext",
                "CanGoPrevious",
                "CanPlay",
                "CanPause",
                "CanSeek",
                "CanControl",
            ).associateWith { variantFor(it) }

            else -> emptyMap()
        }

        private fun playerProperty(name: String): Any? {
            val snapshot = snapshotProvider()
            return when (name) {
                "PlaybackStatus" -> if (snapshot?.playing == true) "Playing" else {
                    if (snapshot == null) "Stopped" else "Paused"
                }

                "LoopStatus" -> when (repeatProvider()) {
                    RepeatMode.ONE -> "Track"
                    RepeatMode.ALL -> "Playlist"
                    RepeatMode.OFF -> "None"
                }

                "Rate", "MinimumRate", "MaximumRate" -> 1.0
                "Shuffle" -> shuffleProvider()
                "Metadata" -> metadata(snapshot)
                "Volume" -> volumeProvider()
                "Position" -> (snapshot?.positionMs ?: 0L) * 1000L
                "CanGoNext", "CanGoPrevious", "CanPlay", "CanPause", "CanSeek", "CanControl" -> true
                else -> null
            }
        }

        private fun metadata(snapshot: NowPlayingSnapshot?): Map<String, Variant<*>> {
            if (snapshot == null) return mapOf("mpris:trackid" to Variant(MPRIS_OBJECT_PATH, "o"))
            return buildMap {
                put("mpris:trackid", Variant(MPRIS_OBJECT_PATH, "o"))
                put("mpris:length", Variant(snapshot.durationMs * 1000L, "x"))
                put("xesam:title", Variant(snapshot.title))
                put("xesam:artist", Variant(listOf(snapshot.artist), "as"))
                if (snapshot.album.isNotBlank()) put("xesam:album", Variant(snapshot.album))
                snapshot.artUrl?.takeIf { it.isNotBlank() }?.let { put("mpris:artUrl", Variant(it)) }
                put("xesam:url", Variant("neriplayer://${snapshot.source}/${snapshot.trackId}"))
            }
        }

        fun variantFor(name: String): Variant<*> = when (val value = playerProperty(name)) {
            is Long -> Variant(value, "x")
            is Double -> Variant(value)
            is Boolean -> Variant(value)
            is Map<*, *> -> Variant(value, "a{sv}")
            else -> Variant(value ?: "")
        }
    }
}
