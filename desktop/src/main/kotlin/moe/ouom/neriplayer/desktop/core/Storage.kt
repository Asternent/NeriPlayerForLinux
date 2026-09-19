package moe.ouom.neriplayer.desktop.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** XDG 目录布局。 */
object AppDirs {
    private val home: String = System.getProperty("user.home")

    private fun xdg(env: String, fallback: String): File {
        val raw = System.getenv(env)?.takeIf { it.isNotBlank() } ?: "$home/$fallback"
        return File(raw)
    }

    val dataDir: File by lazy { File(xdg("XDG_DATA_HOME", ".local/share"), "NeriPlayer").apply { mkdirs() } }
    val configDir: File by lazy { File(xdg("XDG_CONFIG_HOME", ".config"), "NeriPlayer").apply { mkdirs() } }
    val cacheDir: File by lazy { File(xdg("XDG_CACHE_HOME", ".cache"), "NeriPlayer").apply { mkdirs() } }
    val artworkDir: File by lazy { File(cacheDir, "artwork").apply { mkdirs() } }
    val coverDir: File by lazy { File(cacheDir, "covers").apply { mkdirs() } }
    val logDir: File by lazy { File(dataDir, "logs").apply { mkdirs() } }

    val libraryFile: File get() = File(dataDir, "library.json")
    val playlistFile: File get() = File(dataDir, "playlists.json")
    val historyFile: File get() = File(dataDir, "history.json")
    val statsFile: File get() = File(dataDir, "stats.json")
    val settingsFile: File get() = File(configDir, "settings.json")
    val searchHistoryFile: File get() = File(dataDir, "search_history.json")
    val accountFile: File get() = File(configDir, "accounts.json")
    val syncConfigFile: File get() = File(configDir, "sync.json")
    val downloadCatalogFile: File get() = File(dataDir, "downloads.json")
}

val NeriJson: Json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
    isLenient = true
}

/** 简单的 JSON 文件存储，写入采用「临时文件 + 原子重命名」。 */
class JsonFileStore<T>(
    private val file: File,
    private val serializer: KSerializer<T>,
    private val defaultProvider: () -> T,
) {
    private val lock = Any()

    fun load(): T = synchronized(lock) {
        if (!file.exists()) return@synchronized defaultProvider()
        runCatching { NeriJson.decodeFromString(serializer, file.readText()) }
            .getOrElse { defaultProvider() }
    }

    fun save(value: T) = synchronized(lock) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(NeriJson.encodeToString(serializer, value))
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        }
    }
}

enum class DarkModeSetting { AUTO, LIGHT, DARK }

@Serializable
data class HomeCardSettings(
    val continuePlaying: Boolean = true,
    val guessYouLike: Boolean = true,
    val hotTracks: Boolean = true,
    val radar: Boolean = true,
    val recommended: Boolean = true,
)

@Serializable
data class AppSettings(
    val themeSeedColor: String = "0061A4",
    val customSeedColors: List<String> = emptyList(),
    val dynamicColor: Boolean = false,
    val darkMode: DarkModeSetting = DarkModeSetting.AUTO,
    val paletteStyle: String = "TonalSpot",
    val colorSpec: String = "SPEC_2021",
    val musicFolders: List<String> = emptyList(),
    val volume: Float = 0.85f,
    val playbackSpeed: Float = 1.0f,
    val pitchSemitone: Float = 0.0f,
    val loudnessEnhancer: Boolean = false,
    val equalizerEnabled: Boolean = false,
    val equalizerPreset: String = "平直",
    val equalizerBands: List<Float> = List(10) { 0f },
    val lyricsFontScale: Float = 1.0f,
    val showLyricTranslation: Boolean = true,
    val coverShowsLyrics: Boolean = true,
    val showNowPlayingTitle: Boolean = true,
    val songTitleMarquee: Boolean = true,
    val qualityPreference: String = "exhigh",
    val neteaseEnabled: Boolean = true,
    val bilibiliEnabled: Boolean = true,
    val youtubeEnabled: Boolean = false,
    val defaultStartTab: String = "home",
    val sleepTimerMinutes: Int = 30,
    val resumeLastQueue: Boolean = true,
    val lastQueue: List<Song> = emptyList(),
    val lastQueueIndex: Int = 0,
    val lastPositionMs: Long = 0L,
    val homeCards: HomeCardSettings = HomeCardSettings(),
    val libraryViewMode: String = "list",
    val cacheLimitMb: Int = 1024,
    val onboardingAccepted: Boolean = false,
    val exploreSearchHistory: List<String> = emptyList(),

    // ---------------------------------------------------------------- 悬浮歌词
    val floatingLyricsEnabled: Boolean = false,
    /** 主窗口获得焦点时隐藏悬浮歌词，避免遮挡应用页面。 */
    val floatingLyricsHideInApp: Boolean = false,
    /** 锁定位置后不可拖动。 */
    val floatingLyricsLocked: Boolean = false,
    val floatingLyricsTextColor: String = "WHITE",
    /** SHADOW（阴影）或 OUTLINE（描边）。 */
    val floatingLyricsRenderStyle: String = "SHADOW",
    val floatingLyricsShadowColor: String = "BLACK",
    val floatingLyricsOutlineColor: String = "BLACK",
    val floatingLyricsFontSize: Float = 30f,
    val floatingLyricsOutlineWidth: Float = 2.0f,
    val floatingLyricsShadowBlur: Float = 6.0f,
    val floatingLyricsLyricAlpha: Float = 1.0f,
    val floatingLyricsTranslationAlpha: Float = 0.75f,
    val floatingLyricsBackgroundAlpha: Float = 0.35f,
    /** 悬浮窗底色（配合背景不透明度使用）。 */
    val floatingLyricsBackgroundColor: String = "BLACK",
    val floatingLyricsShowTranslation: Boolean = true,
    val floatingLyricsRevealAnimation: Boolean = true,
    val floatingLyricsMaxWidthDp: Float = 900f,
    /** LEFT / CENTER / RIGHT */
    val floatingLyricsAlignment: String = "CENTER",
    /** 位置用屏幕比例表示，支持多分辨率与多屏。 */
    val floatingLyricsPositionX: Float = 0.5f,
    val floatingLyricsPositionY: Float = 0.85f,

    // ---------------------------------------------------------------- 下载
    /** 下载目录，留空表示默认 ~/Music/NeriPlayer。 */
    val downloadDirectory: String = "",
    val downloadQuality: String = "exhigh",
    val downloadConcurrency: Int = 2,
    val downloadNotifyOnComplete: Boolean = true,
)

/** 设置仓库：内存 StateFlow + JSON 持久化。 */
class SettingsRepository {
    private val store = JsonFileStore(AppDirs.settingsFile, AppSettings.serializer()) { AppSettings() }
    private val _state = MutableStateFlow(loadDefaults())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    private fun loadDefaults(): AppSettings {
        val loaded = store.load()
        val defaultMusic = File(System.getProperty("user.home"), "Music")
        return if (loaded.musicFolders.isEmpty() && defaultMusic.isDirectory) {
            loaded.copy(musicFolders = listOf(defaultMusic.absolutePath))
        } else {
            loaded
        }
    }

    val current: AppSettings get() = _state.value

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_state.value)
        _state.value = next
        store.save(next)
    }

    fun replace(next: AppSettings) {
        _state.value = next
        store.save(next)
    }
}
