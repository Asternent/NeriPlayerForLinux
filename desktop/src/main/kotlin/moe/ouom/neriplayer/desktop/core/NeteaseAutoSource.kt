package moe.ouom.neriplayer.desktop.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.desktop.net.OnlineRepository
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.absoluteValue

/** 回退音源缓存时长：B 站音频直链有有效期，过期后重新解析。 */
private const val AUTO_SOURCE_CACHE_TTL_MS = 30 * 60 * 1000L

/** 低于该分数的候选几乎不可能同名，直接放弃，避免播到无关视频。 */
private const val AUTO_SOURCE_MIN_SCORE = 60

/** 每条查询最多尝试解析的候选数（解析一个候选要额外请求分P与音频流）。 */
private const val AUTO_SOURCE_CANDIDATES_PER_QUERY = 6

/** 整次回退最多尝试解析几个候选，防止网络慢时卡太久。 */
private const val AUTO_SOURCE_MAX_ATTEMPTS = 12

private val autoSourceNonTextRegex = Regex("[^\\p{L}\\p{N}]+")
private val autoSourceWhitespaceRegex = Regex("\\s+")

/**
 * 网易云音源不可播时，到哔哩哔哩搜索同名音频兜底，对齐 Android 端 neteaseAutoSourceSwitch。
 *
 * 只负责「构造查询 → 搜索 → 打分 → 解析音频流」，不判断设置开关、不发提示消息；
 * 解析成功的结果按 songKey 在内存里缓存，供 PlayerManager 在有效期内的重复播放直接复用。
 */
internal class NeteaseAutoSource(private val online: OnlineRepository) {

    private data class CachedTrack(val input: AudioInput, val expiresAt: Long)

    private val cache = ConcurrentHashMap<String, CachedTrack>()
    private val notifiedKeys = ConcurrentHashMap.newKeySet<String>()
    private val appliedKeys = ConcurrentHashMap.newKeySet<String>()
    private val engineRetryRequestedKeys = ConcurrentHashMap.newKeySet<String>()
    private val pendingEngineRetryKeys = ConcurrentHashMap.newKeySet<String>()

    /** 30 分钟内解析过的 B 站音源，过期自动失效。 */
    fun cached(songKey: String): AudioInput? {
        val entry = cache[songKey] ?: return null
        if (System.currentTimeMillis() >= entry.expiresAt) {
            cache.remove(songKey, entry)
            return null
        }
        return entry.input
    }

    /** 提示去重：同一首歌只在第一次成功回退时提示一次。 */
    fun takeNotice(songKey: String): Boolean = notifiedKeys.add(songKey)

    /**
     * 网易云直链在引擎侧播放失败（例如 403）时申请一次回退重试。
     * 同一首歌只允许申请一次，且已经用 B 站音源播放过的歌曲不再申请，避免来回切换。
     */
    fun requestEngineFailureRetry(songKey: String): Boolean {
        if (songKey in appliedKeys) return false
        if (!engineRetryRequestedKeys.add(songKey)) return false
        pendingEngineRetryKeys.add(songKey)
        return true
    }

    /** 取出并清除挂起的重试申请，避免同一请求被解析两次。 */
    fun consumeEngineFailureRetry(songKey: String): Boolean = pendingEngineRetryKeys.remove(songKey)

    /** 搜索并解析 B 站同名音频；失败返回 null（调用方保持原来的报错行为）。 */
    suspend fun resolve(song: Song): AudioInput? = withContext(Dispatchers.IO) {
        val queries = buildQueries(song)
        if (queries.isEmpty()) return@withContext null

        println(
            "[fallback] 网易云音源不可用，尝试哔哩哔哩兜底：${song.displayName()} - ${song.artist}"
        )

        val visited = mutableSetOf<String>()
        var attempts = 0
        // 第一轮用原始关键词；都没能解析出音频时第二轮带「无损」后缀再试，扩大命中率
        for (suffix in AUTO_SOURCE_QUERY_SUFFIXES) {
            for (query in queries) {
                if (attempts >= AUTO_SOURCE_MAX_ATTEMPTS) break
                val keyword = (query + suffix).trim()
                val results = runCatching { online.bilibili.searchVideos(keyword) }
                    .getOrElse { error ->
                        if (error is CancellationException) throw error
                        println("[fallback] 哔哩哔哩搜索失败：query=$keyword，error=${error.message}")
                        emptyList()
                    }
                val ranked = results
                    .mapNotNull { candidate ->
                        if (candidate.remoteId.isNullOrBlank()) return@mapNotNull null
                        candidate to scoreCandidate(
                            song = song,
                            title = candidate.title,
                            author = candidate.artist,
                            durationSec = (candidate.durationMs / 1000L).toInt(),
                        )
                    }
                    .filter { it.second >= AUTO_SOURCE_MIN_SCORE }
                    .sortedByDescending { it.second }

                var triedForQuery = 0
                for ((candidate, score) in ranked) {
                    if (attempts >= AUTO_SOURCE_MAX_ATTEMPTS) break
                    if (triedForQuery >= AUTO_SOURCE_CANDIDATES_PER_QUERY) break
                    val bvid = candidate.remoteId?.takeIf { it.isNotBlank() } ?: continue
                    // 不同查询会返回同一批视频，已试过的 bvid 不重复解析
                    if (!visited.add(bvid)) continue
                    triedForQuery += 1
                    attempts += 1
                    val input = resolveCandidate(song, candidate, score) ?: continue
                    cache[song.key] = CachedTrack(
                        input = input,
                        expiresAt = System.currentTimeMillis() + AUTO_SOURCE_CACHE_TTL_MS,
                    )
                    appliedKeys.add(song.key)
                    println(
                        "[fallback] 已切换到哔哩哔哩音源：" +
                            "${song.displayName()} → $bvid（score=$score）"
                    )
                    return@withContext input
                }
            }
        }

        println(
            "[fallback] 未找到可用的哔哩哔哩音源：${song.displayName()} - ${song.artist}，" +
                "已尝试 $attempts 个候选"
        )
        null
    }

    private fun resolveCandidate(song: Song, candidate: Song, score: Int): AudioInput? {
        val bvid = candidate.remoteId?.takeIf { it.isNotBlank() } ?: return null
        val pages = runCatching { online.bilibili.videoPages(bvid) }
            .getOrElse { error ->
                if (error is CancellationException) throw error
                println("[fallback] 读取分P失败：bvid=$bvid，error=${error.message}")
                emptyList()
            }
        if (pages.isEmpty()) return null

        val cid = if (pages.size == 1) {
            pages.first().first
        } else {
            // 多P视频按「分P标题 + 视频标题」重新打分，选与歌曲最接近的一P
            pages.maxByOrNull { (_, part) ->
                scoreCandidate(
                    song = song,
                    title = "$part ${candidate.title}",
                    author = candidate.artist,
                    durationSec = 0,
                )
            }?.first
        } ?: return null

        val url = runCatching { online.bilibili.audioUrl(bvid, cid) }
            .getOrElse { error ->
                if (error is CancellationException) throw error
                println("[fallback] 解析哔哩哔哩音频流失败：bvid=$bvid cid=$cid，error=${error.message}")
                null
            }
        if (url.isNullOrBlank()) return null

        println("[fallback] 哔哩哔哩音频流解析成功：bvid=$bvid cid=$cid（score=$score）")
        return AudioInput(url = url, headers = online.bilibili.biliHeaders())
    }

    private companion object {
        val AUTO_SOURCE_QUERY_SUFFIXES = listOf("", " 无损")

        /** 与 Android 端一致：歌名+歌手、歌手+歌名、仅歌名。 */
        fun buildQueries(song: Song): List<String> {
            val title = song.title.trim()
            val artist = song.artist.trim()
            if (title.isBlank()) return emptyList()
            return listOf(
                "$title $artist",
                "$artist $title",
                title,
            )
                .map { autoSourceWhitespaceRegex.replace(it.trim(), " ") }
                .filter { it.isNotBlank() }
                .distinct()
        }

        fun scoreCandidate(song: Song, title: String, author: String, durationSec: Int): Int {
            val normalizedTitle = normalizeAutoSourceText(title)
            val normalizedAuthor = normalizeAutoSourceText(author)
            val titleHit = containsAutoSourceText(normalizedTitle, song.title)
            val artistHit = song.artist.isNotBlank() && (
                containsAutoSourceText(normalizedTitle, song.artist) ||
                    containsAutoSourceText(normalizedAuthor, song.artist)
                )

            var score = if (titleHit) {
                55
            } else {
                tokenOverlapScore(normalizedTitle, song.title)
            }
            if (artistHit) score += 25
            score += durationSimilarityScore(song.durationMs, durationSec)
            return score
        }

        fun normalizeAutoSourceText(value: String): String =
            autoSourceWhitespaceRegex.replace(autoSourceNonTextRegex.replace(value.lowercase(), " "), " ").trim()

        fun compactAutoSourceText(value: String): String = normalizeAutoSourceText(value).replace(" ", "")

        fun containsAutoSourceText(normalizedText: String, rawNeedle: String): Boolean {
            val needle = compactAutoSourceText(rawNeedle)
            if (needle.length < 2) return false
            return normalizedText.replace(" ", "").contains(needle)
        }

        fun tokenOverlapScore(normalizedText: String, rawNeedle: String): Int {
            val tokens = normalizeAutoSourceText(rawNeedle)
                .split(' ')
                .filter { it.length >= 2 }
            if (tokens.isEmpty()) return 0
            val hits = tokens.count { normalizedText.contains(it) }
            return when {
                hits == tokens.size -> 35
                hits > 0 -> 18
                else -> 0
            }
        }

        /** 时长越接近分越高；未知时长不加减分，明显更长（现场/合集）扣分。 */
        fun durationSimilarityScore(originalDurationMs: Long, candidateDurationSec: Int): Int {
            if (originalDurationMs <= 0L || candidateDurationSec <= 0) return 0
            val candidateDurationMs = candidateDurationSec * 1000L
            val diffMs = (candidateDurationMs - originalDurationMs).absoluteValue
            return when {
                diffMs <= 8_000L -> 30
                diffMs <= 20_000L -> 22
                diffMs <= 45_000L -> 12
                candidateDurationMs > originalDurationMs * 2 -> -15
                else -> 0
            }
        }
    }
}
