package moe.ouom.neriplayer.desktop.core

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** LRC 歌词解析（支持一行多时间标签、翻译行合并与 offset 偏移）。 */
object LrcParser {

    private val timeTagRegex = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
    private val metaRegex = Regex("^\\[([a-zA-Z#]+):(.*)]$")

    private fun fractionToMs(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        return when (raw.length) {
            1 -> raw.toLong() * 100
            2 -> raw.toLong() * 10
            else -> raw.take(3).toLong()
        }
    }

    fun parse(raw: String): List<LyricLine> {
        if (raw.isBlank()) return emptyList()
        var offset = 0L
        val collected = ArrayList<Pair<Long, String>>()
        raw.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach
            metaRegex.find(trimmed)?.let { meta ->
                if (meta.groupValues[1].lowercase() == "offset") {
                    offset = meta.groupValues[2].trim().toLongOrNull() ?: 0L
                }
                return@forEach
            }
            val matches = timeTagRegex.findAll(trimmed).toList()
            if (matches.isEmpty()) return@forEach
            val text = trimmed.substring(matches.last().range.last + 1).trim()
            matches.forEach { match ->
                val minutes = match.groupValues[1].toLongOrNull() ?: 0L
                val seconds = match.groupValues[2].toLongOrNull() ?: 0L
                val ms = minutes * 60_000L + seconds * 1_000L + fractionToMs(match.groupValues[3])
                collected += ms to text
            }
        }
        return collected
            .sortedBy { it.first }
            .map { (time, text) -> LyricLine((time - offset).coerceAtLeast(0L), text) }
    }

    /** 把翻译歌词按时间戳合并进主歌词。 */
    fun merge(base: List<LyricLine>, translated: List<LyricLine>): List<LyricLine> {
        if (translated.isEmpty()) return base
        val translationByTime = LinkedHashMap<Long, String>()
        translated.forEach { line ->
            if (line.text.isNotBlank()) {
                translationByTime.putIfAbsent(line.timeMs, line.text)
            }
        }
        if (translationByTime.isEmpty()) return base
        return base.map { line ->
            val translation = translationByTime[line.timeMs]
                ?.takeIf { it.isNotBlank() && it != line.text }
            line.copy(translation = translation)
        }
    }

    /** 同一文件内同时包含原文与翻译（相同时间戳连写两行）时拆分为翻译。 */
    fun splitDuplicatedTimestamps(raw: String): Pair<List<LyricLine>, List<LyricLine>> {
        val lines = parse(raw)
        val grouped = LinkedHashMap<Long, MutableList<String>>()
        lines.forEach { line -> grouped.getOrPut(line.timeMs) { mutableListOf() }.add(line.text) }
        val main = ArrayList<LyricLine>()
        val translation = ArrayList<LyricLine>()
        grouped.forEach { (time, texts) ->
            val distinct = texts.filter { it.isNotBlank() }.distinct()
            if (distinct.isEmpty()) return@forEach
            main += LyricLine(time, distinct.first())
            distinct.drop(1).forEach { extra -> translation += LyricLine(time, extra) }
        }
        return main.sortedBy { it.timeMs } to translation.sortedBy { it.timeMs }
    }
}

/** 歌词仓库：本地 .lrc → 内嵌标签 → 在线接口。 */
class LyricsRepository(
    private val remoteProvider: suspend (Song) -> Pair<String, String?>? = { null },
) {
    private val cache = ConcurrentHashMap<String, Lyrics>()
    private val missCache = ConcurrentHashMap.newKeySet<String>()

    fun cached(song: Song): Lyrics? = cache[song.key]

    fun put(song: Song, lyrics: Lyrics) {
        cache[song.key] = lyrics
    }

    fun invalidate(song: Song) {
        cache.remove(song.key)
        missCache.remove(song.key)
    }

    suspend fun load(song: Song): Lyrics {
        cache[song.key]?.let { return it }
        localLyrics(song)?.let { lyrics ->
            cache[song.key] = lyrics
            return lyrics
        }
        if (song.key !in missCache) {
            val remote = runCatching { remoteProvider(song) }.getOrNull()
            if (remote != null && remote.first.isNotBlank()) {
                val lyrics = fromRaw(remote.first, remote.second, source = song.source.displayName)
                cache[song.key] = lyrics
                return lyrics
            }
            missCache += song.key
        }
        val empty = Lyrics()
        cache[song.key] = empty
        return empty
    }

    private fun localLyrics(song: Song): Lyrics? {
        val path = song.filePath
        if (!path.isNullOrBlank()) {
            val audio = File(path)
            val baseName = audio.nameWithoutExtension
            val candidates = listOf(
                File(audio.parentFile, "$baseName.lrc"),
                File(audio.parentFile, "$baseName.LRC"),
                File(audio.parentFile, "$baseName.zh.lrc"),
                File(audio.parentFile, "$baseName.trans.lrc"),
            )
            val lrc = candidates.firstOrNull { it.isFile }
            if (lrc != null) {
                val raw = runCatching { lrc.readText() }.getOrNull()
                if (!raw.isNullOrBlank()) {
                    val (main, translation) = LrcParser.splitDuplicatedTimestamps(raw)
                    return Lyrics(
                        raw = raw,
                        lines = LrcParser.merge(main, translation),
                        translated = translation,
                        source = "本地歌词",
                    )
                }
            }
        }
        val embedded = song.lyrics
        if (!embedded.isNullOrBlank()) {
            val (main, translation) = LrcParser.splitDuplicatedTimestamps(embedded)
            return Lyrics(
                raw = embedded,
                lines = LrcParser.merge(main, translation),
                translated = translation,
                source = "内嵌歌词",
            )
        }
        return null
    }

    private fun fromRaw(raw: String, translatedRaw: String?, source: String?): Lyrics {
        val (main, inlineTranslation) = LrcParser.splitDuplicatedTimestamps(raw)
        val translationLines = if (!translatedRaw.isNullOrBlank()) {
            LrcParser.parse(translatedRaw)
        } else {
            inlineTranslation
        }
        return Lyrics(
            raw = raw,
            lines = LrcParser.merge(main, translationLines),
            translated = translationLines,
            source = source,
        )
    }
}

/** 找出当前播放位置对应的歌词行下标。 */
fun currentLyricIndex(lines: List<LyricLine>, positionMs: Long): Int {
    if (lines.isEmpty()) return -1
    var low = 0
    var high = lines.size - 1
    var result = -1
    while (low <= high) {
        val mid = (low + high) / 2
        if (lines[mid].timeMs <= positionMs) {
            result = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return result
}
