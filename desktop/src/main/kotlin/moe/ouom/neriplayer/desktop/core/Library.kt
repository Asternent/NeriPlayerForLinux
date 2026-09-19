package moe.ouom.neriplayer.desktop.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.logging.Level
import java.util.logging.Logger

private val AUDIO_EXTENSIONS = setOf(
    "mp3", "flac", "m4a", "aac", "ogg", "oga", "opus", "wav", "wave", "aiff", "aif", "aifc",
    "wma", "ape", "mka", "mp4", "alac", "dsf", "dff"
)

/** 常见音频扩展名，扫描时按大小写不敏感匹配。 */
fun isSupportedAudioFile(file: File): Boolean =
    file.isFile && file.extension.lowercase(Locale.ROOT) in AUDIO_EXTENSIONS

data class ScanState(
    val running: Boolean = false,
    val total: Int = 0,
    val processed: Int = 0,
    val found: Int = 0,
    val finishedAt: Long = 0L,
    val error: String? = null,
) {
    val progress: Float
        get() = if (total <= 0) 0f else processed.toFloat() / total.toFloat()
}

private object JaudiologQuiet {
    init {
        runCatching {
            listOf("org.jaudiotagger", "org.jaudiotagger.audio", "org.jaudiotagger.tag", "org.jaudiotagger.tag.id3")
                .forEach { name ->
                    Logger.getLogger(name).apply {
                        level = Level.OFF
                        useParentHandlers = false
                    }
                }
            Logger.getLogger("").handlers.forEach { handler ->
                handler.filter = java.util.logging.Filter { record ->
                    !record.loggerName.startsWith("org.jaudiotagger")
                }
            }
        }
    }

    fun touch() = Unit
}

private fun md5Hex(input: String): String {
    val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}

/** 本地媒体库：扫描文件夹、读取标签、缓存封面。 */
class LibraryRepository(private val settings: SettingsRepository) {

    private val store = JsonFileStore(AppDirs.libraryFile, ListSerializer(Song.serializer())) { emptyList() }
    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs: StateFlow<List<Song>> = _songs.asStateFlow()

    private val _scanState = MutableStateFlow(ScanState())
    val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    private val _indexLoaded = MutableStateFlow(false)
    val indexLoaded: StateFlow<Boolean> = _indexLoaded.asStateFlow()

    fun load() {
        JaudiologQuiet.touch()
        _songs.value = store.load().sortedWith(SONG_ORDER)
        _indexLoaded.value = true
    }

    fun persist() {
        store.save(_songs.value)
    }

    fun byKey(key: String): Song? = _songs.value.firstOrNull { it.key == key }

    fun byPath(path: String): Song? = _songs.value.firstOrNull { it.filePath == path }

    fun artists(): List<ArtistGroup> =
        _songs.value.groupBy { it.artistText() }
            .map { (name, songs) -> ArtistGroup(name, songs.sortedWith(SONG_ORDER)) }
            .sortedBy { it.name.lowercase(Locale.ROOT) }

    fun albums(): List<AlbumGroup> =
        _songs.value.groupBy { it.albumText() to it.artistText() }
            .map { (pair, songs) -> AlbumGroup(pair.first, pair.second, songs.sortedWith(SONG_ORDER)) }
            .sortedBy { it.name.lowercase(Locale.ROOT) }

    suspend fun scan(): Int = withContext(Dispatchers.IO) {
        _scanState.value = ScanState(running = true)
        val folders = settings.current.musicFolders.map(::File).filter { it.isDirectory }
        if (folders.isEmpty()) {
            _scanState.value = ScanState(running = false, finishedAt = System.currentTimeMillis())
            return@withContext 0
        }
        val files = LinkedHashSet<File>()
        folders.forEach { folder -> collectAudioFiles(folder, files, 0) }
        val fileList = files.toList()
        _scanState.value = ScanState(running = true, total = fileList.size)

        val previous = _songs.value.associateBy { it.key }
        val scanned = ArrayList<Song>(fileList.size)
        var processed = 0
        fileList.chunked(64).forEach { chunk ->
            val chunkSongs = withContext(Dispatchers.Default) {
                chunk.mapNotNull { file -> readSong(file, previous[localSongKey(file.absolutePath)]) }
            }
            scanned += chunkSongs
            processed += chunk.size
            _scanState.value = ScanState(
                running = true,
                total = fileList.size,
                processed = processed,
                found = scanned.size,
            )
        }

        val ordered = scanned.sortedWith(SONG_ORDER)
        _songs.value = ordered
        persist()
        _scanState.value = ScanState(
            running = false,
            total = fileList.size,
            processed = fileList.size,
            found = ordered.size,
            finishedAt = System.currentTimeMillis(),
        )
        ordered.size
    }

    private fun collectAudioFiles(dir: File, out: MutableSet<File>, depth: Int) {
        if (depth > 12) return
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory) {
                if (child.name.startsWith(".")) continue
                collectAudioFiles(child, out, depth + 1)
            } else if (isSupportedAudioFile(child)) {
                out += child
            }
        }
    }

    private fun readSong(file: File, known: Song?): Song? {
        val path = file.absolutePath
        return runCatching {
            val audioFile = AudioFileIO.read(file)
            val header = audioFile.audioHeader
            val tag = audioFile.tag
            val title = tag?.getFirst(FieldKey.TITLE)?.trim().orEmpty().ifBlank {
                file.nameWithoutExtension
            }
            val artist = tag?.getFirst(FieldKey.ARTIST)?.trim().orEmpty()
            val album = tag?.getFirst(FieldKey.ALBUM)?.trim().orEmpty()
            val track = tag?.getFirst(FieldKey.TRACK)?.trim()?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
            val durationSec = header?.trackLength?.toLong() ?: 0L
            val embeddedLyrics = runCatching { tag?.getFirst(FieldKey.LYRICS)?.trim() }.getOrNull()
                ?.takeIf { it.isNotBlank() }
            val artworkPath = known?.artworkPath?.takeIf { it.isNotBlank() && File(it).exists() }
                ?: runCatching {
                    val artwork = tag?.firstArtwork ?: return@runCatching null
                    val bytes = artwork.binaryData ?: return@runCatching null
                    if (bytes.isEmpty()) return@runCatching null
                    val target = File(AppDirs.artworkDir, md5Hex(path) + ".img")
                    if (!target.exists() || target.length() != bytes.size.toLong()) {
                        target.writeBytes(bytes)
                    }
                    target.absolutePath
                }.getOrNull()
            Song(
                key = localSongKey(path),
                source = MediaSource.LOCAL,
                title = title,
                artist = artist,
                album = album,
                durationMs = durationSec * 1000L,
                filePath = path,
                artworkPath = artworkPath,
                lyrics = embeddedLyrics ?: known?.lyrics,
                dateAdded = known?.dateAdded?.takeIf { it > 0 } ?: file.lastModified(),
                trackNumber = track,
            )
        }.getOrNull()
    }
}

internal val SONG_ORDER: Comparator<Song> = compareBy<Song>(
    { it.artistText().lowercase(Locale.ROOT) },
    { it.albumText().lowercase(Locale.ROOT) },
    { it.trackNumber },
    { it.displayName().lowercase(Locale.ROOT) },
)
