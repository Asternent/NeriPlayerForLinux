package moe.ouom.neriplayer.desktop.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.desktop.net.OnlineRepository
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.absoluteValue

/** 回退音源缓存时长：B 站音频直链有有效期，过期后重新解析。 */
private const val AUTO_SOURCE_CACHE_TTL_MS = 30 * 60 * 1000L

/** 基础分（标题/歌手/时长）低于该分数的候选几乎不可能同名，直接放弃，避免播到无关视频。 */
private const val AUTO_SOURCE_MIN_BASE_SCORE = 60

/** 每条查询最多尝试解析的候选数（解析一个候选要额外请求分P与音频流）。 */
private const val AUTO_SOURCE_CANDIDATES_PER_QUERY = 6

/** 整次回退最多尝试解析几个候选，防止网络慢时卡太久。 */
private const val AUTO_SOURCE_MAX_ATTEMPTS = 12

/** 命中纯音乐/伴奏/卡拉OK/MIDI 等版本关键词时的降权分值。 */
private const val AUTO_SOURCE_VERSION_PENALTY = -40

/** 命中 remix/教程/合集等「可能仍是原曲」的版本关键词时的降权分值。 */
private const val AUTO_SOURCE_VERSION_WEAK_PENALTY = -15

/**
 * 版本启发式总降权上限。
 *
 * 降权只用于调整候选顺序、不用于硬排除：命中版本关键词的候选仍参与排序，
 * 其它候选都解析不出音频时它还能作为兜底，避免整首歌唱不了。
 */
private const val AUTO_SOURCE_VERSION_PENALTY_CAP = -45

/** 命中官方/原曲/MV/完整版等正向关键词时的加分。 */
private const val AUTO_SOURCE_VERSION_BONUS = 6
private const val AUTO_SOURCE_VERSION_BONUS_CAP = 12

private val autoSourceNonTextRegex = Regex("[^\\p{L}\\p{N}]+")
private val autoSourceWhitespaceRegex = Regex("\\s+")
private val autoSourceKeywordRegexCache = ConcurrentHashMap<String, Regex>()

/**
 * 版本类型关键词：命中说明视频不是原曲人声版，而是纯音乐/伴奏/卡拉OK/翻弹等。
 *
 * 同时含中英文，是因为 B 站投稿标题两种写法都很常见（如「ニコカラ」「Karaoke」「MIDI」）。
 */
private val autoSourcePureMusicKeywords = listOf(
    "纯音乐", "纯音", "伴奏", "off vocal", "offvocal", "instrumental",
    "カラオケ", "ニコカラ", "karaoke", "卡拉ok", "midi", "钢琴版", "钢琴曲", "钢琴",
    "piano", "消音", "8bit", "8-bit", "无声版",
)

/** 仍可能是正常歌曲的版本关键词：只小幅降权，避免误伤翻唱/演奏/剪辑类投稿。 */
private val autoSourceWeakVersionKeywords = listOf("remix", "教程", "合集", "翻奏")

/** 更接近原曲的版本关键词：小幅加分。 */
private val autoSourcePreferredKeywords = listOf(
    "官方", "official", "原曲", "完整版", "无损", "mv", "原版",
)

/**
 * 网易云音源不可播时，到哔哩哔哩搜索同名音频兜底，对齐 Android 端 neteaseAutoSourceSwitch。
 *
 * 只负责「构造查询 → 搜索 → 打分 → 解析音频流」，不判断设置开关、不发提示消息；
 * 解析成功的结果按 songKey 在内存里缓存，供 PlayerManager 在有效期内的重复播放直接复用。
 */
internal class NeteaseAutoSource(private val online: OnlineRepository) {

    private data class CachedTrack(val input: AudioInput, val expiresAt: Long)

    /** 单个候选的打分结果，保留基础分与版本修正，便于日志说明为什么被降权。 */
    private data class CandidateScore(
        val baseScore: Int,
        val score: Int,
        val versionDelta: Int,
        val versionReasons: List<String>,
    )

    private data class RankedCandidate(val song: Song, val candidateScore: CandidateScore)

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
        // 与 Android 一致：优先带「无损」后缀搜索；个别投稿只在裸关键词下出现，
        // 所以第一轮没解析出音频时再用不带后缀的查询兜底。
        for (suffix in AUTO_SOURCE_QUERY_SUFFIXES) {
            for (query in queries) {
                if (attempts >= AUTO_SOURCE_MAX_ATTEMPTS) break
                val keyword = (query + suffix).trim()
                val durationFilter = durationFilterForBiliSearch(song.durationMs)
                val results = searchCandidates(keyword, durationFilter)
                val ranked = rankCandidates(song, results)
                println(
                    "[fallback] 哔哩哔哩搜索结果：query=$keyword，durationFilter=$durationFilter，" +
                        "候选=${results.size}，可用=${ranked.size}"
                )
                // 打印前几名候选及其版本修正，便于确认 MIDI/卡拉OK 版本被降权后排在正常版本之后
                ranked.take(AUTO_SOURCE_CANDIDATES_PER_QUERY + 4).forEach { candidate ->
                    println("[fallback] 候选评分：${describeCandidate(candidate)}")
                }
                // 被版本启发式降权的候选单独列出，即便最终没进入尝试名单也能确认规则生效
                val penalized = ranked.filter { it.candidateScore.versionDelta < 0 }
                if (penalized.isNotEmpty()) {
                    println(
                        "[fallback] 已降权候选：${penalized.take(3).joinToString("；") { describeCandidate(it) }}"
                    )
                }

                var triedForQuery = 0
                for (candidate in ranked) {
                    if (attempts >= AUTO_SOURCE_MAX_ATTEMPTS) break
                    if (triedForQuery >= AUTO_SOURCE_CANDIDATES_PER_QUERY) break
                    val bvid = candidate.song.remoteId?.takeIf { it.isNotBlank() } ?: continue
                    // 不同查询会返回同一批视频，已试过的 bvid 不重复解析
                    if (!visited.add(bvid)) continue
                    triedForQuery += 1
                    attempts += 1
                    val input = resolveCandidate(song, candidate) ?: continue
                    cache[song.key] = CachedTrack(
                        input = input,
                        expiresAt = System.currentTimeMillis() + AUTO_SOURCE_CACHE_TTL_MS,
                    )
                    appliedKeys.add(song.key)
                    println(
                        "[fallback] 已切换到哔哩哔哩音源：${song.displayName()} → " +
                            "${describeCandidate(candidate)}"
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

    private fun describeCandidate(candidate: RankedCandidate): String {
        val score = candidate.candidateScore
        val version = if (score.versionReasons.isEmpty()) {
            ""
        } else {
            "，版本修正=${score.versionDelta}（${score.versionReasons.joinToString("、")}）"
        }
        return "bvid=${candidate.song.remoteId} score=${score.score} base=${score.baseScore}$version" +
            " title=${candidate.song.title}"
    }

    /**
     * 按 Android 的 durationFilterForBiliSearch 选择时长档位。
     *
     * 搜索接口的 duration 是粗粒度分档，能让「整场 live/合集」这类时长差异巨大的视频
     * 直接不进入候选；第一档/无时长的歌曲传 0 表示不过滤。
     */
    private fun durationFilterForBiliSearch(durationMs: Long): Int {
        val durationSec = (durationMs / 1000L).toInt()
        return when {
            durationSec <= 0 -> 0
            durationSec < 10 * 60 -> 1
            durationSec < 30 * 60 -> 2
            durationSec < 60 * 60 -> 3
            else -> 4
        }
    }

    /** 带时长档位搜索；结果为空时按 Android 逻辑退回不限时长再搜一次。 */
    private fun searchCandidates(keyword: String, durationFilter: Int): List<Song> {
        val filtered = runCatching {
            online.bilibili.searchVideos(keyword = keyword, duration = durationFilter)
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            println(
                "[fallback] 哔哩哔哩搜索失败：query=$keyword，duration=$durationFilter，" +
                    "error=${error.message}"
            )
            emptyList()
        }
        if (filtered.isNotEmpty() || durationFilter == 0) return filtered

        return runCatching {
            online.bilibili.searchVideos(keyword = keyword)
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            println("[fallback] 哔哩哔哩搜索重试失败：query=$keyword，error=${error.message}")
            emptyList()
        }
    }

    /**
     * 过滤掉基础分过低的无关视频，再按「基础分 + 版本修正」排序。
     *
     * 过滤依据是基础分而不是最终分：版本关键词只影响优先级，不会把候选硬排除，
     * 其它候选都不可用时仍会尝试这些版本作为兜底。同分时保持搜索结果原始顺序
     * （综合排序越靠前通常越接近原曲）。
     */
    private fun rankCandidates(song: Song, results: List<Song>): List<RankedCandidate> =
        results.withIndex()
            .mapNotNull { (index, candidate) ->
                if (candidate.remoteId.isNullOrBlank()) return@mapNotNull null
                val candidateScore = scoreCandidate(
                    song = song,
                    title = candidate.title,
                    author = candidate.artist,
                    durationSec = (candidate.durationMs / 1000L).toInt(),
                )
                if (candidateScore.baseScore < AUTO_SOURCE_MIN_BASE_SCORE) return@mapNotNull null
                index to RankedCandidate(candidate, candidateScore)
            }
            .sortedWith(
                compareByDescending<Pair<Int, RankedCandidate>> { it.second.candidateScore.score }
                    .thenBy { it.first }
            )
            .map { it.second }

    private fun resolveCandidate(song: Song, candidate: RankedCandidate): AudioInput? {
        val bvid = candidate.song.remoteId?.takeIf { it.isNotBlank() } ?: return null
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
                    title = "$part ${candidate.song.title}",
                    author = candidate.song.artist,
                    durationSec = 0,
                ).score
            }?.first
        } ?: return null

        val url = runCatching { online.bilibili.audioUrl(bvid, cid) }
            .getOrElse { error ->
                if (error is CancellationException) throw error
                println("[fallback] 解析哔哩哔哩音频流失败：bvid=$bvid cid=$cid，error=${error.message}")
                null
            }
        if (url.isNullOrBlank()) return null

        println(
            "[fallback] 哔哩哔哩音频流解析成功：bvid=$bvid cid=$cid（score=${candidate.candidateScore.score}）"
        )
        return AudioInput(url = url, headers = online.bilibili.biliHeaders())
    }

    private companion object {
        /** 与 Android 一致优先带「无损」；裸查询仅在带后缀搜不到可用候选时兜底。 */
        val AUTO_SOURCE_QUERY_SUFFIXES = listOf(" 无损", "")

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

        fun scoreCandidate(song: Song, title: String, author: String, durationSec: Int): CandidateScore {
            val normalizedTitle = normalizeAutoSourceText(title)
            val normalizedAuthor = normalizeAutoSourceText(author)
            val titleHit = containsAutoSourceText(normalizedTitle, song.title)
            val artistHit = song.artist.isNotBlank() && (
                containsAutoSourceText(normalizedTitle, song.artist) ||
                    containsAutoSourceText(normalizedAuthor, song.artist)
                )

            var baseScore = if (titleHit) {
                55
            } else {
                tokenOverlapScore(normalizedTitle, song.title)
            }
            if (artistHit) baseScore += 25
            baseScore += durationSimilarityScore(song.durationMs, durationSec)

            val verdict = versionTypeVerdict(song, title, normalizedTitle)
            return CandidateScore(
                baseScore = baseScore,
                score = baseScore + verdict.delta,
                versionDelta = verdict.delta,
                versionReasons = verdict.reasons,
            )
        }

        private data class VersionVerdict(val delta: Int, val reasons: List<String>)

        /**
         * 版本类型启发式：标题里出现纯音乐/伴奏/卡拉OK/MIDI 等词说明不是原曲人声版，明显降权；
         * remix/教程/合集等可能仍是正常歌曲，小幅降权；官方/原曲/MV/完整版等小幅加分。
         *
         * 歌曲自身标题/歌手就带这些词时（例如原曲本身就是钢琴曲）不做降权，
         * 避免把这首歌曲的全部候选一起压掉。
         */
        fun versionTypeVerdict(song: Song, title: String, normalizedTitle: String): VersionVerdict {
            val normalizedSelf = normalizeAutoSourceText("${song.title} ${song.artist}")
            var penalty = 0
            val reasons = mutableListOf<String>()

            for (keyword in autoSourcePureMusicKeywords) {
                if (!matchesAutoSourceKeyword(normalizedTitle, keyword)) continue
                if (matchesAutoSourceKeyword(normalizedSelf, keyword)) continue
                penalty += AUTO_SOURCE_VERSION_PENALTY
                reasons += keyword
            }
            for (keyword in autoSourceWeakVersionKeywords) {
                if (!matchesAutoSourceKeyword(normalizedTitle, keyword)) continue
                if (matchesAutoSourceKeyword(normalizedSelf, keyword)) continue
                penalty += AUTO_SOURCE_VERSION_WEAK_PENALTY
                reasons += keyword
            }
            penalty = penalty.coerceAtLeast(AUTO_SOURCE_VERSION_PENALTY_CAP)

            var bonus = 0
            for (keyword in autoSourcePreferredKeywords) {
                if (matchesAutoSourceKeyword(normalizedTitle, keyword)) bonus += AUTO_SOURCE_VERSION_BONUS
            }
            bonus = bonus.coerceAtMost(AUTO_SOURCE_VERSION_BONUS_CAP)

            val delta = penalty + bonus
            return if (delta == 0) VersionVerdict(0, emptyList()) else VersionVerdict(delta, reasons)
        }

        /**
         * 关键词匹配。中文按紧凑子串匹配（「钢琴」也能命中「钢琴版」）；
         * 英文/数字按紧凑文本上的词边界匹配，避免 `mv` 命中 `mvp` 之类误判，
         * 同时兼容「干活用midi」这种中英混排、没有分隔符的标题。
         */
        fun matchesAutoSourceKeyword(normalizedText: String, rawKeyword: String): Boolean {
            val normalizedKeyword = normalizeAutoSourceText(rawKeyword)
            if (normalizedKeyword.isBlank()) return false
            val compactKeyword = normalizedKeyword.replace(" ", "")
            val compactText = normalizedText.replace(" ", "")
            val asciiOnly = normalizedKeyword.all { it in 'a'..'z' || it in '0'..'9' || it == ' ' }
            if (!asciiOnly) return compactText.contains(compactKeyword)
            val regex = autoSourceKeywordRegexCache.getOrPut(compactKeyword) {
                Regex("(?<![a-z0-9])${Regex.escape(compactKeyword)}(?![a-z0-9])")
            }
            return regex.containsMatchIn(compactText)
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
