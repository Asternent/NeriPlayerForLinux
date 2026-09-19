package moe.ouom.neriplayer.desktop.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import moe.ouom.neriplayer.desktop.net.OnlineRepository
import java.io.File
import java.util.UUID

/** 已下载到本地的歌曲记录。 */
@Serializable
data class DownloadedSong(
    val songKey: String,
    val filePath: String,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0L,
    val artworkUrl: String? = null,
    /** 下载时保存到本地的封面文件（离线可用）。 */
    val artworkPath: String? = null,
    /** 下载时保存到本地的歌词文件。 */
    val lyricsPath: String? = null,
    val source: String = MediaSource.LOCAL.name,
    val sizeBytes: Long = 0L,
    val downloadedAt: Long = 0L,
) {
    /** 转成可播放的本地歌曲（保留原歌曲 key，便于同步下载记录与队列）。 */
    fun toSong(): Song = Song(
        key = songKey,
        source = MediaSource.LOCAL,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        filePath = filePath,
        artworkPath = artworkPath,
        artworkUrl = artworkUrl,
        dateAdded = downloadedAt,
    )
}

enum class DownloadState { QUEUED, RUNNING, DONE, FAILED, CANCELED }

data class DownloadTask(
    val id: String,
    val song: Song,
    val state: DownloadState = DownloadState.QUEUED,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = -1L,
    val error: String? = null,
    val filePath: String? = null,
) {
    val progress: Float
        get() = when {
            state == DownloadState.DONE -> 1f
            totalBytes > 0L -> (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
            else -> 0f
        }

    val hasKnownSize: Boolean get() = totalBytes > 0L
}

/** 下载记录（跨启动保留），同时作为「离线播放」的索引。 */
class DownloadCatalog(catalogFile: File = AppDirs.downloadCatalogFile) {
    private val store = JsonFileStore(catalogFile, ListSerializer(DownloadedSong.serializer())) {
        emptyList()
    }

    private val _items = MutableStateFlow<Map<String, DownloadedSong>>(emptyMap())
    val items: StateFlow<Map<String, DownloadedSong>> = _items.asStateFlow()

    fun load() {
        _items.value = store.load()
            .filter { File(it.filePath).isFile }
            .associateBy { it.songKey }
    }

    private fun persist() = store.save(_items.value.values.toList())

    fun record(entry: DownloadedSong) {
        _items.value = _items.value + (entry.songKey to entry)
        persist()
    }

    fun remove(songKey: String) {
        _items.value = _items.value - songKey
        persist()
    }

    fun fileFor(songKey: String): File? = _items.value[songKey]?.filePath?.let { File(it) }?.takeIf { it.isFile }

    fun contains(songKey: String): Boolean = _items.value.containsKey(songKey)

    fun totalSizeBytes(): Long = _items.value.values.sumOf { it.sizeBytes }
}

/**
 * 下载管理器：把在线歌曲下载到本地目录，供离线播放与「下载」分栏浏览。
 * 与原应用一致支持批量下载、进度、取消、重试、清空已完成与打开下载目录。
 */
class DownloadManager(
    private val online: OnlineRepository,
    private val settings: SettingsRepository,
    private val catalog: DownloadCatalog,
    private val scope: CoroutineScope,
    private val directoryOverride: File? = null,
    /** 下载时一并保存歌词文件（来自歌词仓库）。 */
    private val lyricsProvider: suspend (Song) -> String? = { null },
) {

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    private val _activeCount = MutableStateFlow(0)
    val activeCount: StateFlow<Int> = _activeCount.asStateFlow()

    private val canceled = HashSet<String>()
    private var running = false

    fun downloadDirectory(): File {
        directoryOverride?.let { return it }
        val configured = settings.current.downloadDirectory.takeIf { it.isNotBlank() }?.let(::File)
        return configured ?: File(System.getProperty("user.home"), "Music/NeriPlayer")
    }

    /** 入队下载；已在本地或已在队列中的歌曲会被跳过。 */
    fun enqueue(songs: List<Song>, showMessage: ((String) -> Unit)? = null): Int {
        val existing = _tasks.value.mapTo(HashSet()) { it.song.key }
        val added = songs.filter { song ->
            song.source != MediaSource.LOCAL && song.key !in existing && !catalog.contains(song.key)
        }
        if (added.isEmpty()) {
            showMessage?.invoke("这些歌曲都已在本地或下载队列中")
            return 0
        }
        val newTasks = added.map { song ->
            DownloadTask(id = UUID.randomUUID().toString(), song = song)
        }
        _tasks.value = _tasks.value + newTasks
        showMessage?.invoke("已加入下载队列：${added.size} 首")
        startWorkers()
        return added.size
    }

    fun cancel(taskId: String) {
        canceled += taskId
        _tasks.value = _tasks.value.map { task ->
            if (task.id == taskId && task.state in setOf(DownloadState.QUEUED, DownloadState.RUNNING)) {
                task.copy(state = DownloadState.CANCELED, error = "已取消")
            } else {
                task
            }
        }
    }

    fun retry(taskId: String) {
        canceled -= taskId
        _tasks.value = _tasks.value.map { task ->
            if (task.id == taskId && task.state in setOf(DownloadState.FAILED, DownloadState.CANCELED)) {
                task.copy(state = DownloadState.QUEUED, error = null, downloadedBytes = 0L)
            } else {
                task
            }
        }
        startWorkers()
    }

    fun removeFinished() {
        _tasks.value = _tasks.value.filter {
            it.state in setOf(DownloadState.QUEUED, DownloadState.RUNNING)
        }
    }

    fun clearAll() {
        _tasks.value.filter { it.state in setOf(DownloadState.QUEUED, DownloadState.RUNNING) }
            .forEach { cancel(it.id) }
        _tasks.value = emptyList()
    }

    /** 删除已下载文件（同时移除记录）。 */
    fun deleteDownload(songKey: String): Boolean {
        val entry = catalog.items.value[songKey] ?: return false
        val deleted = runCatching { File(entry.filePath).delete() }.getOrDefault(false)
        catalog.remove(songKey)
        return deleted
    }

    private fun startWorkers() {
        if (running) return
        running = true
        scope.launch {
            try {
                while (true) {
                    val queued = _tasks.value.filter { it.state == DownloadState.QUEUED && it.id !in canceled }
                    if (queued.isEmpty()) break
                    val concurrency = settings.current.downloadConcurrency.coerceIn(1, 4)
                    val active = _tasks.value.count { it.state == DownloadState.RUNNING }
                    val slots = (concurrency - active).coerceAtLeast(0)
                    if (slots == 0) {
                        kotlinx.coroutines.delay(300)
                        continue
                    }
                    queued.take(slots).forEach { task ->
                        markRunning(task.id)
                        scope.launch { runTask(task) }
                    }
                    kotlinx.coroutines.delay(200)
                }
            } finally {
                running = false
                updateActiveCount()
            }
        }
    }

    private fun markRunning(taskId: String) {
        _tasks.value = _tasks.value.map { task ->
            if (task.id == taskId) task.copy(state = DownloadState.RUNNING) else task
        }
        updateActiveCount()
    }

    private fun updateActiveCount() {
        _activeCount.value = _tasks.value.count {
            it.state == DownloadState.QUEUED || it.state == DownloadState.RUNNING
        }
    }

    private suspend fun runTask(task: DownloadTask) {
        val song = task.song
        val input = runCatching {
            online.resolvePlayback(song, settings.current.downloadQuality)
        }.getOrNull()
        if (input?.url.isNullOrBlank()) {
            fail(task.id, "无法获取播放地址")
            return
        }
        val target = buildTargetFile(song, input!!.url)
        var lastError: String? = null
        val ok = runCatching {
            online.httpService.downloadToFile(input.url!!, input.headers, target) { downloaded, total ->
                if (canceled.contains(task.id)) throw DownloadCanceledException()
                _tasks.value = _tasks.value.map { item ->
                    if (item.id == task.id) {
                        item.copy(
                            downloadedBytes = downloaded,
                            totalBytes = if (total > 0) total else item.totalBytes,
                        )
                    } else {
                        item
                    }
                }
            }
        }.getOrElse { error ->
            if (error !is DownloadCanceledException) lastError = "下载失败（网络或地址过期）"
            false
        }
        if (canceled.contains(task.id)) {
            runCatching { target.delete() }
            _tasks.value = _tasks.value.map {
                if (it.id == task.id) it.copy(state = DownloadState.CANCELED, error = "已取消") else it
            }
            updateActiveCount()
            return
        }
        if (!ok) {
            fail(task.id, lastError ?: "下载失败")
            return
        }

        val metadata = writeMetadata(target, song)
        println(
            "[download] 元数据写入：标签=${metadata.tags.propertyWritten} 内嵌封面=${metadata.tags.coverWritten} " +
                "歌词=${metadata.tags.lyricWritten} 封面文件=${metadata.coverFile?.name ?: "无"}"
        )

        val size = target.length()
        catalog.record(
            DownloadedSong(
                songKey = song.key,
                filePath = target.absolutePath,
                title = song.displayName(),
                artist = song.artist,
                album = song.album,
                durationMs = song.durationMs,
                artworkUrl = song.artworkUrl,
                artworkPath = metadata.coverFile?.absolutePath,
                lyricsPath = metadata.lyricFile?.absolutePath,
                source = song.source.name,
                sizeBytes = size,
                downloadedAt = System.currentTimeMillis(),
            )
        )
        _tasks.value = _tasks.value.map {
            if (it.id == task.id) {
                it.copy(state = DownloadState.DONE, filePath = target.absolutePath, downloadedBytes = size, totalBytes = size)
            } else {
                it
            }
        }
        updateActiveCount()
        println("[download] 完成：${song.displayName()} → ${target.absolutePath} (${size / 1024} KB)")
    }

    /**
     * 写入封面 / 歌词 / 内嵌标签。
     * 下载完成时调用，也可用于为旧版本下载的文件补齐信息（不重新下载音频）。
     */
    private suspend fun writeMetadata(target: File, song: Song): MetadataOutcome {
        val coverBytes = fetchCoverBytes(song)
        val coverFile = coverBytes?.let { bytes ->
            runCatching {
                val (extension, _) = detectImageType(bytes)
                File(target.parentFile, "${target.nameWithoutExtension}.$extension").apply { writeBytes(bytes) }
            }.getOrNull()
        }
        val lyricText = runCatching { lyricsProvider(song) }.getOrNull()?.takeIf { it.isNotBlank() }
        val lyricFile = lyricText?.let { text ->
            runCatching {
                File(target.parentFile, "${target.nameWithoutExtension}.lrc").apply { writeText(text) }
            }.getOrNull()
        }
        val metadata = DownloadMetadataWriter.write(
            file = target,
            title = song.displayName(),
            artist = song.artist,
            album = song.album,
            lyrics = lyricText,
            coverBytes = coverBytes,
            coverMimeType = coverBytes?.let(::imageMimeType),
        )
        return MetadataOutcome(metadata, coverFile, lyricFile, lyricText)
    }

    /**
     * 为已下载的文件补齐封面与标签（对应旧版本下载的文件，或标签被清空的情况）。
     * @return 成功处理的条目数
     */
    suspend fun repairMetadata(): Int {
        val entries = catalog.items.value.values.toList()
        var repaired = 0
        entries.forEach { entry ->
            val file = File(entry.filePath)
            if (!file.isFile) return@forEach
            val song = entry.toSong()
            val outcome = writeMetadata(file, song)
            catalog.record(
                entry.copy(
                    artworkPath = outcome.coverFile?.absolutePath ?: entry.artworkPath,
                    lyricsPath = outcome.lyricFile?.absolutePath ?: entry.lyricsPath,
                    sizeBytes = file.length(),
                )
            )
            repaired += 1
            println("[download] 补齐元数据：${file.name} 标签=${outcome.tags.propertyWritten} 封面=${outcome.tags.coverWritten}")
        }
        return repaired
    }

    private fun fail(taskId: String, message: String) {
        _tasks.value = _tasks.value.map { task ->
            if (task.id == taskId) task.copy(state = DownloadState.FAILED, error = message) else task
        }
        updateActiveCount()
        println("[download] 失败：$message")
    }

    /** 生成下载文件名：`歌手 - 标题.扩展名`，并清理非法字符。 */
    fun buildTargetFile(song: Song, url: String): File {
        val extension = extensionFor(song, url)
        val baseName = listOf(song.artistText(), song.displayName())
            .filter { it.isNotBlank() }
            .joinToString(" - ")
        return File(downloadDirectory(), "${sanitizeFileName(baseName)}.$extension")
    }

    private fun extensionFor(song: Song, url: String): String {
        val fromUrl = url.substringBefore('?').substringAfterLast('.', "")
        val candidate = fromUrl.takeIf { it.length in 2..4 && it.all(Char::isLetterOrDigit) }
        return candidate?.lowercase() ?: "mp3"
    }

    /** 抓取封面字节；不同平台用各自的 Referer，失败不影响音频下载。 */
    private fun fetchCoverBytes(song: Song): ByteArray? {
        val url = song.artworkUrl?.takeIf { it.isNotBlank() } ?: return null
        val referer = when (song.source) {
            MediaSource.BILIBILI -> "https://www.bilibili.com/"
            else -> "https://music.163.com/"
        }
        val bytes = online.httpService.download(url, mapOf("Referer" to referer)) ?: return null
        // 封面通常几十 KB，超过 6MB 视为异常数据
        return bytes.takeIf { it.size in 1..(6 * 1024 * 1024) }
    }
}

/** 清理文件名中的非法字符并限制长度。 */
fun sanitizeFileName(name: String): String {
    val cleaned = name.map { char ->
        if (char in "\\/:*?\"<>|\u0000" || char.code < 32) '_' else char
    }.joinToString("").trim().trim('.')
    val collapsed = cleaned.replace(Regex("\\s+"), " ")
    return collapsed.take(120).ifBlank { "unknown" }
}

/** 内部用：下载被用户取消。 */
internal class DownloadCanceledException : RuntimeException("download canceled")

/** 元数据写入结果（内嵌标签 / 封面文件 / 歌词文件）。 */
private data class MetadataOutcome(
    val tags: DownloadMetadataWriter.Result,
    val coverFile: File?,
    val lyricFile: File?,
    val lyricText: String?,
)
