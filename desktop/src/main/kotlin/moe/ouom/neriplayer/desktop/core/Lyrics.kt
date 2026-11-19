package moe.ouom.neriplayer.desktop.core

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** 内存中保留的歌词上限（超出后按最近最少使用淘汰）。 */
private const val MAX_LYRICS_CACHE = 120

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

/** 歌词里的制作信息行（作词: xxx 等），逐词歌词里出现在首尾时没有演唱价值。 */
private val LYRIC_CREDIT_REGEX = Regex(
    "^(?:作词|作曲|编曲|填词|演唱|歌手|混音|母带|制作|监制|录音|和声|配唱|" +
        "吉他(?:solo)?|贝斯|鼓|键盘|弦乐|vo(?:/mix)?|mix|tune|inst|guitar|bass|" +
        "drums?|vocal|lyrics|music|arrangement|produced)\\s*[:：]",
    RegexOption.IGNORE_CASE,
)

/**
 * NetEase yrc（逐词歌词）解析。
 *
 * 行格式 `[行开始,行时长](词开始,词时长,0)词…`；eapi 响应里同一字段前半段还有
 * `{"t":...}` 形式的 JSON 制作信息注释行，这些不是标准行，按「不以 `[` 开头就跳过」忽略。
 */
object YrcParser {

    private val headerRegex = Regex("""\[(\d{1,19}),\s*(\d{1,19})]""")
    private val segmentRegex = Regex("""\((\d{1,19}),\s*(\d{1,19}),\s*[-\d]{1,20}\)([^()\n\r]*)""")

    fun parse(raw: String): List<LyricLine> {
        if (raw.isBlank()) return emptyList()
        val lines = ArrayList<LyricLine>()
        raw.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || !line.startsWith("[")) return@forEach
            val header = headerRegex.find(line) ?: return@forEach
            val start = header.groupValues[1].toLongOrNull() ?: return@forEach
            val duration = header.groupValues[2].toLongOrNull() ?: return@forEach
            val segments = segmentRegex.findAll(line).toList()
            if (segments.isEmpty()) {
                // 没有逐词段时保留整行文本，前端会回退成整行线性进度
                val text = line.substringAfter("]").trim()
                if (text.isNotEmpty()) {
                    lines += LyricLine(timeMs = start, text = text, endMs = start + duration)
                }
                return@forEach
            }
            val words = ArrayList<LyricWord>(segments.size)
            val text = StringBuilder()
            for (segment in segments) {
                val wordStart = segment.groupValues[1].toLongOrNull() ?: continue
                val wordDuration = segment.groupValues[2].toLongOrNull() ?: continue
                val wordText = segment.groupValues[3]
                text.append(wordText)
                words += LyricWord(startMs = wordStart, durationMs = wordDuration, text = wordText)
            }
            if (words.isEmpty()) return@forEach
            lines += LyricLine(
                timeMs = start,
                text = text.toString(),
                endMs = start + duration,
                words = words,
            )
        }
        return dropEdgeCredits(lines.sortedBy { it.timeMs })
    }

    /** 只去掉开头/结尾连续的制作信息行；正文中间的冒号行可能是歌词本身，保留。 */
    private fun dropEdgeCredits(lines: List<LyricLine>): List<LyricLine> {
        if (lines.isEmpty()) return lines
        var start = 0
        var end = lines.size
        while (start < end && LYRIC_CREDIT_REGEX.containsMatchIn(lines[start].text)) start++
        while (end > start && LYRIC_CREDIT_REGEX.containsMatchIn(lines[end - 1].text)) end--
        // 整份都是制作信息时保留原样，避免把唯一的歌词内容也过滤掉
        if (start == end) return lines
        return lines.subList(start, end)
    }
}

/** 翻译行与逐词行的时间戳来源不同，允许几百毫秒偏差后按就近匹配。 */
private const val TRANSLATION_MATCH_TOLERANCE_MS = 1200L

/** 把普通 LRC 原文（可选翻译原文）构建成结构化歌词。 */
fun buildLrcLyrics(raw: String, translatedRaw: String?, source: String?): Lyrics {
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

/**
 * 把 yrc（逐词）歌词构建成结构化歌词。
 *
 * @param lrcRaw eapi 同时返回的普通 LRC，作为 `raw`（下载歌词文件时仍然保存普通 LRC）
 * @param translatedYrc 与 yrc 行时间对齐的翻译（`ytlrc`），没有时用 [translatedRaw]
 * @return yrc 解析不出任何行时返回 null，让调用方回退普通 LRC
 */
fun buildYrcLyrics(
    yrcRaw: String,
    lrcRaw: String?,
    translatedYrc: String?,
    translatedRaw: String?,
    source: String?,
): Lyrics? {
    val yrcLines = YrcParser.parse(yrcRaw)
    if (yrcLines.isEmpty()) return null
    val translationLines = when {
        !translatedYrc.isNullOrBlank() -> LrcParser.parse(translatedYrc)
        !translatedRaw.isNullOrBlank() -> LrcParser.parse(translatedRaw)
        else -> emptyList()
    }
    return Lyrics(
        raw = lrcRaw?.takeIf { it.isNotBlank() } ?: yrcRaw,
        lines = mergeNearestTranslation(yrcLines, translationLines),
        translated = translationLines,
        source = source,
    )
}

/** 按时间就近把翻译行合并进主行（yrc 行时间与翻译时间戳通常差几百毫秒）。 */
fun mergeNearestTranslation(base: List<LyricLine>, translated: List<LyricLine>): List<LyricLine> {
    val candidates = translated.filter { it.text.isNotBlank() }.sortedBy { it.timeMs }
    if (base.isEmpty() || candidates.isEmpty()) return base
    val used = BooleanArray(candidates.size)
    return base.map { line ->
        var best = -1
        var bestDistance = Long.MAX_VALUE
        candidates.forEachIndexed { index, candidate ->
            if (used[index]) return@forEachIndexed
            val distance = kotlin.math.abs(candidate.timeMs - line.timeMs)
            if (distance < bestDistance) {
                bestDistance = distance
                best = index
            }
        }
        if (best < 0 || bestDistance > TRANSLATION_MATCH_TOLERANCE_MS) {
            line
        } else {
            used[best] = true
            val text = candidates[best].text.takeIf { it != line.text }
            line.copy(translation = text)
        }
    }
}

/** 歌词仓库：本地 .lrc → 内嵌标签 → 在线接口。 */
class LyricsRepository(
    private val remoteProvider: suspend (Song) -> Lyrics? = { null },
) {
    // 歌词缓存按 LRU 限长：听歌久了缓存会一直涨，而歌词只在当前/最近几首之间来回用
    private val cache = object : LinkedHashMap<String, Lyrics>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Lyrics>): Boolean =
            size > MAX_LYRICS_CACHE
    }
    private val cacheLock = Any()
    private val missCache = ConcurrentHashMap.newKeySet<String>()

    fun cached(song: Song): Lyrics? = synchronized(cacheLock) { cache[song.key] }

    fun put(song: Song, lyrics: Lyrics) {
        synchronized(cacheLock) { cache[song.key] = lyrics }
    }

    fun invalidate(song: Song) {
        synchronized(cacheLock) { cache.remove(song.key) }
        missCache.remove(song.key)
    }

    suspend fun load(song: Song): Lyrics {
        synchronized(cacheLock) { cache[song.key] }?.let { return it }
        localLyrics(song)?.let { lyrics ->
            synchronized(cacheLock) { cache[song.key] = lyrics }
            return lyrics
        }
        if (song.key !in missCache) {
            val remote = runCatching { remoteProvider(song) }.getOrNull()
            if (remote != null && !remote.isEmpty) {
                synchronized(cacheLock) { cache[song.key] = remote }
                return remote
            }
            missCache += song.key
        }
        val empty = Lyrics()
        synchronized(cacheLock) { cache[song.key] = empty }
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
