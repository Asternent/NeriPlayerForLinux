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
import java.io.File

private const val MAX_ARTWORK_ENTRIES = 180

private object ArtworkCache {
    private val memory = object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>): Boolean =
            size > MAX_ARTWORK_ENTRIES
    }
    private val failed = HashSet<String>()

    fun get(key: String): ImageBitmap? = synchronized(memory) { memory[key] }

    fun put(key: String, bitmap: ImageBitmap) = synchronized(memory) { memory[key] = bitmap }

    fun hasFailed(key: String): Boolean = synchronized(failed) { key in failed }

    fun markFailed(key: String) = synchronized(failed) { failed += key }
}

private fun artworkCacheKey(song: Song): String =
    song.key + "|" + (song.artworkPath ?: song.artworkUrl ?: "")

private fun decode(bytes: ByteArray): ImageBitmap? =
    runCatching { org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()

private suspend fun loadRemoteBitmap(url: String, referer: String? = null): ImageBitmap? =
    withContext(Dispatchers.IO) {
        val cached = File(AppDirs.coverDir, url.hashCode().toString() + ".img")
        if (cached.isFile) {
            runCatching { cached.readBytes() }.getOrNull()?.let { bytes ->
                decode(bytes)?.let { return@withContext it }
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
        decode(bytes)
    }

private suspend fun loadSongArtwork(song: Song): ImageBitmap? = withContext(Dispatchers.IO) {
    val localPath = song.artworkPath
    if (!localPath.isNullOrBlank()) {
        val file = File(localPath)
        if (file.isFile) {
            runCatching { file.readBytes() }.getOrNull()?.let { bytes ->
                decode(bytes)?.let { return@withContext it }
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
    val bitmap by produceState<ImageBitmap?>(initialValue = ArtworkCache.get(key), key) {
        if (value != null) return@produceState
        if (ArtworkCache.hasFailed(key)) return@produceState
        val loaded = loadSongArtwork(song)
        if (loaded == null) {
            ArtworkCache.markFailed(key)
        } else {
            ArtworkCache.put(key, loaded)
        }
        value = loaded
    }
    return bitmap
}

@Composable
fun rememberRemoteArtwork(url: String?): ImageBitmap? {
    if (url.isNullOrBlank()) return null
    val key = "url|$url"
    val bitmap by produceState<ImageBitmap?>(initialValue = ArtworkCache.get(key), key) {
        if (value != null) return@produceState
        if (ArtworkCache.hasFailed(key)) return@produceState
        val loaded = loadRemoteBitmap(url)
        if (loaded == null) {
            ArtworkCache.markFailed(key)
        } else {
            ArtworkCache.put(key, loaded)
        }
        value = loaded
    }
    return bitmap
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
