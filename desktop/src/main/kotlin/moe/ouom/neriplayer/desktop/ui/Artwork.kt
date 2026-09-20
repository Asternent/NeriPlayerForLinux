package moe.ouom.neriplayer.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.desktop.core.AppDirs
import moe.ouom.neriplayer.desktop.core.MediaSource
import moe.ouom.neriplayer.desktop.core.Song
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * 封面解码后的最长边上限（像素）。
 *
 * 在线音源的封面经常是 3000×3000 / 4096×4096，整张解码出来单张就要 34~64 MB；
 * 而界面上最大的封面（播放页，2.5 倍缩放）也只需要 700 像素上下，
 * 所以这里统一按最长边缩放解码，单张封面的内存从几十 MB 降到 2 MB 以内。
 */
private const val ARTWORK_MAX_PX = 768

/** 封面缓存的字节预算：按实际像素占用淘汰，避免大封面把内存顶满。 */
private const val ARTWORK_CACHE_BUDGET_BYTES = 64L * 1024 * 1024

/** 条目数上限（小封面可能很多，再加一道保险）。 */
private const val MAX_ARTWORK_ENTRIES = 320

private const val ARTWORK_FAILURE_TTL_MS = 60_000L

private object ArtworkCache {
    /** 访问顺序的 LRU；淘汰由 [put] 里的循环按「字节预算 + 条目上限」执行。 */
    private val memory = LinkedHashMap<String, ImageBitmap>(64, 0.75f, true)
    private var bytes = 0L
    private val failed = HashMap<String, Long>()

    fun get(key: String): ImageBitmap? = synchronized(memory) { memory[key] }

    fun put(key: String, bitmap: ImageBitmap) = synchronized(memory) {
        memory.remove(key)?.let { previous -> bytes -= sizeOf(previous) }
        memory[key] = bitmap
        bytes += sizeOf(bitmap)
        // 一次可能淘汰多张：大封面进来时要立刻把预算压回上限之内
        val iterator = memory.entries.iterator()
        while (iterator.hasNext() &&
            (bytes > ARTWORK_CACHE_BUDGET_BYTES || memory.size > MAX_ARTWORK_ENTRIES)
        ) {
            val eldest = iterator.next()
            bytes -= sizeOf(eldest.value)
            iterator.remove()
        }
    }

    /** 缓存统计，供自检与内存回归对比使用。 */
    fun stats(): String = synchronized(memory) {
        "封面缓存 ${memory.size} 张 / ${bytes / 1048576} MB"
    }

    /** 失败记录带过期时间，网络抖动恢复后可以自动重试。 */
    fun hasFailed(key: String): Boolean = synchronized(failed) {
        val at = failed[key] ?: return false
        if (System.currentTimeMillis() - at > ARTWORK_FAILURE_TTL_MS) {
            failed.remove(key)
            false
        } else {
            true
        }
    }

    fun markFailed(key: String) = synchronized(failed) { failed[key] = System.currentTimeMillis() }
}

private fun artworkCacheKey(song: Song): String =
    song.key + "|" + (song.artworkPath ?: song.artworkUrl ?: "")

private fun sizeOf(bitmap: ImageBitmap): Long = bitmap.width.toLong() * bitmap.height * 4L

/**
 * 按最长边 [maxPx] 解码：优先用 ImageIO 带子采样读取，
 * 这样 4096×4096 的封面在解码阶段就只产出 768×768 的位图，不会先吃掉 64 MB。
 * ImageIO 不认识的格式（例如 WebP）退回 Skia 全尺寸解码。
 */
/** 封面解码入口：按最长边 [maxPx] 缩放，避免超大封面整张进内存（自检也会直接调用）。 */
fun decodeArtwork(bytes: ByteArray, maxPx: Int = ARTWORK_MAX_PX): ImageBitmap? {
    decodeDownscaled(bytes, maxPx)?.let { return it }
    return runCatching {
        org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
    }.getOrNull()
}

/** 封面缓存统计（供自检 / 内存回归对比使用）。 */
fun artworkCacheStats(): String = ArtworkCache.stats()

private fun decodeDownscaled(bytes: ByteArray, maxPx: Int): ImageBitmap? = runCatching {
    ImageIO.createImageInputStream(ByteArrayInputStream(bytes))?.use { stream ->
        val readers = ImageIO.getImageReaders(stream)
        if (!readers.hasNext()) return@use null
        val reader = readers.next()
        try {
            reader.input = stream
            val width = reader.getWidth(0)
            val height = reader.getHeight(0)
            val longest = maxOf(width, height)
            val param = reader.defaultReadParam
            if (longest > maxPx) {
                val step = kotlin.math.ceil(longest / maxPx.toDouble()).toInt().coerceAtLeast(1)
                param.setSourceSubsampling(step, step, 0, 0)
            }
            reader.read(0, param)?.toComposeImageBitmap()
        } finally {
            reader.dispose()
        }
    }
}.getOrNull()

private suspend fun loadRemoteBitmap(url: String, referer: String? = null): ImageBitmap? =
    withContext(Dispatchers.IO) {
        val cached = File(AppDirs.coverDir, url.hashCode().toString() + ".img")
        if (cached.isFile) {
            runCatching { cached.readBytes() }.getOrNull()?.let { bytes ->
                decodeArtwork(bytes)?.let { return@withContext it }
            }
        }
        val bytes = runCatching {
            val connection = java.net.URI(url).toURL().openConnection().apply {
                connectTimeout = 12_000
                readTimeout = 15_000
                setRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) NeriPlayer")
                setRequestProperty("Referer", referer ?: "https://music.163.com/")
            }
            connection.getInputStream().use { it.readBytes() }
        }.getOrNull() ?: return@withContext null
        runCatching { cached.writeBytes(bytes) }
        decodeArtwork(bytes)
    }

private suspend fun loadSongArtwork(song: Song): ImageBitmap? = withContext(Dispatchers.IO) {
    val localPath = song.artworkPath
    if (!localPath.isNullOrBlank()) {
        val file = File(localPath)
        if (file.isFile) {
            runCatching { file.readBytes() }.getOrNull()?.let { bytes ->
                decodeArtwork(bytes)?.let { return@withContext it }
            }
        }
    }
    val url = song.artworkUrl ?: return@withContext null
    loadRemoteBitmap(url)
}

@Composable
fun rememberArtwork(song: Song?): ImageBitmap? {
    if (song == null) return null
    val key = artworkCacheKey(song)
    // state 里带上 key，确保切歌后只会使用当前歌曲的封面，不会继续显示上一首
    val state by produceState<Pair<String, ImageBitmap?>?>(initialValue = null, key) {
        val cached = ArtworkCache.get(key)
        value = key to cached
        if (cached != null) return@produceState
        if (ArtworkCache.hasFailed(key)) return@produceState
        val loaded = loadSongArtwork(song)
        if (loaded == null) {
            ArtworkCache.markFailed(key)
        } else {
            ArtworkCache.put(key, loaded)
        }
        value = key to loaded
    }
    // state 尚未切到当前 key 的那一帧，直接读缓存，避免闪一下占位图
    return state?.takeIf { it.first == key }?.second ?: ArtworkCache.get(key)
}

@Composable
fun rememberRemoteArtwork(url: String?): ImageBitmap? {
    if (url.isNullOrBlank()) return null
    val key = "url|$url"
    val state by produceState<Pair<String, ImageBitmap?>?>(initialValue = null, key) {
        val cached = ArtworkCache.get(key)
        value = key to cached
        if (cached != null) return@produceState
        if (ArtworkCache.hasFailed(key)) return@produceState
        val loaded = loadRemoteBitmap(url)
        if (loaded == null) {
            ArtworkCache.markFailed(key)
        } else {
            ArtworkCache.put(key, loaded)
        }
        value = key to loaded
    }
    return state?.takeIf { it.first == key }?.second ?: ArtworkCache.get(key)
}

@Composable
fun ArtworkBox(
    painter: Painter?,
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(10.dp),
    contentDescription: String? = null,
    fallback: ImageVector = Icons.Filled.MusicNote,
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (painter != null) {
            Image(
                painter = painter,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(
                imageVector = fallback,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier.fillMaxSize(0.42f),
            )
        }
    }
}

@Composable
fun SongArtwork(
    song: Song?,
    size: Dp,
    shape: RoundedCornerShape = RoundedCornerShape(10.dp),
) {
    val bitmap = rememberArtwork(song)
    ArtworkBox(
        painter = bitmap?.let { BitmapPainter(it) },
        modifier = Modifier.size(size),
        shape = shape,
        contentDescription = song?.displayName(),
    )
}

@Composable
fun RemoteArtwork(
    url: String?,
    size: Dp,
    shape: RoundedCornerShape = RoundedCornerShape(10.dp),
    fallback: ImageVector = Icons.Filled.MusicNote,
) {
    val bitmap = rememberRemoteArtwork(url)
    ArtworkBox(
        painter = bitmap?.let { BitmapPainter(it) },
        modifier = Modifier.size(size),
        shape = shape,
        fallback = fallback,
    )
}

fun formatDuration(ms: Long): String {
    if (ms <= 0L) return "--:--"
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

fun formatLongDuration(ms: Long): String {
    if (ms <= 0L) return "0 分钟"
    val totalSeconds = ms / 1000
    if (totalSeconds < 60) return "$totalSeconds 秒"
    val totalMinutes = totalSeconds / 60
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "%d 小时 %d 分钟".format(hours, minutes) else "%d 分钟".format(minutes)
}

fun formatPlayCount(count: Long): String = when {
    count >= 100_000_000L -> "%.1f 亿".format(count / 100_000_000.0)
    count >= 10_000L -> "%.1f 万".format(count / 10_000.0)
    else -> count.toString()
}

fun buildSongSubtitle(song: Song): String {
    val parts = mutableListOf<String>()
    if (song.artist.isNotBlank()) parts += song.artist
    if (song.album.isNotBlank() && song.album != song.artist) parts += song.album
    if (song.source != MediaSource.LOCAL) parts += song.source.displayName
    return if (parts.isEmpty()) "未知艺术家" else parts.joinToString(" · ")
}
