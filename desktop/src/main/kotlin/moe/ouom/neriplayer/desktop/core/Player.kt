package moe.ouom.neriplayer.desktop.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.desktop.net.OnlineRepository
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.pow
import kotlin.random.Random

data class SleepTimerState(
    val active: Boolean = false,
    val stopAfterCurrent: Boolean = false,
    val remainingSeconds: Long = 0L,
    val totalSeconds: Long = 0L,
)

/** 播放器核心：队列、播放控制、歌词、统计与音效。 */
class PlayerManager(
    private val settings: SettingsRepository,
    private val history: HistoryRepository,
    private val stats: StatsRepository,
    private val lyricsRepository: LyricsRepository,
    private val online: OnlineRepository,
    private val scope: CoroutineScope,
) {

    val engine: AudioEngine = createAudioEngine()

    private val _queue = MutableStateFlow<List<Song>>(emptyList())
    val queue: StateFlow<List<Song>> = _queue.asStateFlow()

    private val _currentIndex = MutableStateFlow(-1)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _currentSong.asStateFlow()

    private val _state = MutableStateFlow(PlaybackState.IDLE)
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _lyrics = MutableStateFlow(Lyrics())
    val lyrics: StateFlow<Lyrics> = _lyrics.asStateFlow()

    private val _lyricsLoading = MutableStateFlow(false)
    val lyricsLoading: StateFlow<Boolean> = _lyricsLoading.asStateFlow()

    private val _coverSeedColor = MutableStateFlow<String?>(null)
    val coverSeedColor: StateFlow<String?> = _coverSeedColor.asStateFlow()

    private val _shuffle = MutableStateFlow(false)
    val shuffle: StateFlow<Boolean> = _shuffle.asStateFlow()

    private val _repeatMode = MutableStateFlow(RepeatMode.OFF)
    val repeatMode: StateFlow<RepeatMode> = _repeatMode.asStateFlow()

    private val _volume = MutableStateFlow(settings.current.volume)
    val volume: StateFlow<Float> = _volume.asStateFlow()

    private val _speed = MutableStateFlow(settings.current.playbackSpeed)
    val speed: StateFlow<Float> = _speed.asStateFlow()

    private val _pitch = MutableStateFlow(settings.current.pitchSemitone)
    val pitch: StateFlow<Float> = _pitch.asStateFlow()

    private val _loudness = MutableStateFlow(settings.current.loudnessEnhancer)
    val loudness: StateFlow<Boolean> = _loudness.asStateFlow()

    private val _equalizerEnabled = MutableStateFlow(settings.current.equalizerEnabled)
    val equalizerEnabled: StateFlow<Boolean> = _equalizerEnabled.asStateFlow()

    private val _equalizerBands = MutableStateFlow(settings.current.equalizerBands)
    val equalizerBands: StateFlow<List<Float>> = _equalizerBands.asStateFlow()

    private val _sleepTimer = MutableStateFlow(SleepTimerState())
    val sleepTimer: StateFlow<SleepTimerState> = _sleepTimer.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    private val _buffering = MutableStateFlow(false)
    val buffering: StateFlow<Boolean> = _buffering.asStateFlow()

    private var shuffleOrder: List<Int> = emptyList()
    private var currentPlayingKey: String? = null
    private var playbackStartedAt = 0L
    private var sleepTimerJob: Job? = null
    private val resolveMutex = Mutex()
    private val resolvedCache = ConcurrentHashMap<String, AudioInput>()
    private val seedCache = ConcurrentHashMap<String, String>()

    val supportsEffects: Boolean get() = engine.supportsEffects

    init {
        engine.onCompleted = { scope.launch { handleTrackCompleted() } }
        engine.onError = { message ->
            _state.value = PlaybackState.ERROR
            _buffering.value = false
            scope.launch { _messages.emit(message) }
        }
        engine.setVolume(_volume.value)
        if (engine.supportsEffects) {
            engine.setSpeed(_speed.value)
            engine.setPitch(_pitch.value)
            engine.setLoudness(_loudness.value)
            engine.setEqualizer(if (_equalizerEnabled.value) _equalizerBands.value else null)
        }
        // 边播放边累计统计，保证统计页面实时更新
        scope.launch {
            while (true) {
                delay(10_000)
                if (_state.value == PlaybackState.PLAYING) {
                    flushListeningTime()
                }
            }
        }
    }

    fun attachSnapshotFlow() {
        scope.launch {
            engine.snapshot.collect { snapshot ->
                _buffering.value = snapshot.buffering
                if (snapshot.playing != (_state.value == PlaybackState.PLAYING)) {
                    _state.value = when {
                        snapshot.error != null -> PlaybackState.ERROR
                        snapshot.playing -> PlaybackState.PLAYING
                        _state.value == PlaybackState.PLAYING -> PlaybackState.PAUSED
                        else -> _state.value
                    }
                }
            }
        }
    }

    val positionMs: StateFlow<Long> = engine.snapshot.let { snapshot ->
        derivedState(snapshot) { it.positionMs }
    }

    val durationMs: StateFlow<Long> = engine.snapshot.let { snapshot ->
        derivedState(snapshot) { it.durationMs }
    }

    private fun <T> derivedState(
        source: StateFlow<EngineSnapshot>,
        selector: (EngineSnapshot) -> T,
    ): StateFlow<T> {
        val holder = MutableStateFlow(selector(source.value))
        scope.launch {
            source.collect { holder.value = selector(it) }
        }
        return holder.asStateFlow()
    }

    // ---------------- 队列与播放控制 ----------------

    fun setQueue(songs: List<Song>, startIndex: Int, autoPlay: Boolean = true) {
        if (songs.isEmpty()) {
            clearQueue()
            return
        }
        _queue.value = songs
        rebuildShuffleOrder(startIndex.coerceIn(0, songs.lastIndex))
        _currentIndex.value = startIndex.coerceIn(0, songs.lastIndex)
        if (autoPlay) {
            playCurrent()
        } else {
            _currentSong.value = songs[_currentIndex.value]
        }
    }

    fun playSongNow(song: Song, queue: List<Song>? = null) {
        val targetQueue = (queue ?: _queue.value).ifEmpty { listOf(song) }
        val index = targetQueue.indexOfFirst { it.key == song.key }.takeIf { it >= 0 } ?: 0
        setQueue(targetQueue, index, autoPlay = true)
    }

    fun enqueueNext(songs: List<Song>) {
        if (songs.isEmpty()) return
        val current = _currentSong.value
        val list = _queue.value.toMutableList()
        if (current == null) {
            setQueue(songs, 0, autoPlay = true)
            return
        }
        val insertAt = (_currentIndex.value + 1).coerceIn(0, list.size)
        list.addAll(insertAt, songs)
        _queue.value = list
        rebuildShuffleOrder(_currentIndex.value)
        scope.launch { _messages.emit("已添加到下一首播放") }
    }

    fun enqueue(songs: List<Song>) {
        if (songs.isEmpty()) return
        if (_currentSong.value == null) {
            setQueue(songs, 0, autoPlay = true)
            return
        }
        _queue.value = _queue.value + songs
        rebuildShuffleOrder(_currentIndex.value)
        scope.launch { _messages.emit("已添加到播放队列") }
    }

    fun removeFromQueue(index: Int) {
        val list = _queue.value.toMutableList()
        if (index !in list.indices) return
        list.removeAt(index)
        _queue.value = list
        when {
            list.isEmpty() -> clearQueue()
            index < _currentIndex.value -> _currentIndex.value -= 1
            index == _currentIndex.value -> playCurrent()
        }
        rebuildShuffleOrder(_currentIndex.value)
    }

    fun moveInQueue(from: Int, to: Int) {
        val list = _queue.value.toMutableList()
        if (from !in list.indices || to !in list.indices || from == to) return
        val playing = _currentSong.value
        val item = list.removeAt(from)
        list.add(to, item)
        _queue.value = list
        _currentIndex.value = playing?.let { song -> list.indexOfFirst { it.key == song.key } } ?: -1
        rebuildShuffleOrder(_currentIndex.value)
    }

    fun clearQueue() {
        stopPlaybackInternal()
        _queue.value = emptyList()
        _currentIndex.value = -1
        _currentSong.value = null
        _state.value = PlaybackState.IDLE
        _lyrics.value = Lyrics()
    }

    fun play() {
        if (_currentSong.value == null) {
            val queue = _queue.value
            if (queue.isNotEmpty()) {
                _currentIndex.value = _currentIndex.value.coerceIn(0, queue.lastIndex)
                playCurrent()
            }
            return
        }
        if (_state.value == PlaybackState.ERROR || engine.snapshot.value.error != null) {
            playCurrent()
            return
        }
        engine.play()
        _state.value = PlaybackState.PLAYING
        playbackStartedAt = System.currentTimeMillis()
    }

    fun pause() {
        flushListeningTime()
        engine.pause()
        _state.value = PlaybackState.PAUSED
    }

    fun togglePlayPause() {
        if (_state.value == PlaybackState.PLAYING) pause() else play()
    }

    fun next(userInitiated: Boolean = true) {
        val queue = _queue.value
        if (queue.isEmpty()) return
        val nextIndex = resolveNextIndex(userInitiated) ?: run {
            if (userInitiated) {
                _currentIndex.value = 0
                playCurrent()
            } else {
                pause()
                engine.seekTo(0)
            }
            return
        }
        _currentIndex.value = nextIndex
        playCurrent()
    }

    fun previous() {
        val queue = _queue.value
        if (queue.isEmpty()) return
        if (engine.snapshot.value.positionMs > 4000L) {
            seekTo(0)
            return
        }
        val prevIndex = resolvePreviousIndex()
        _currentIndex.value = prevIndex
        playCurrent()
    }

    fun seekTo(positionMs: Long) {
        engine.seekTo(positionMs)
    }

    fun restartCurrent() {
        playCurrent()
    }

    private fun resolveNextIndex(userInitiated: Boolean): Int? {
        val queue = _queue.value
        if (queue.isEmpty()) return null
        val current = _currentIndex.value
        if (_shuffle.value && shuffleOrder.isNotEmpty()) {
            val position = shuffleOrder.indexOf(current)
            if (position >= 0 && position < shuffleOrder.lastIndex) return shuffleOrder[position + 1]
            return if (_repeatMode.value != RepeatMode.OFF || userInitiated) shuffleOrder.firstOrNull() else null
        }
        return when {
            current < queue.lastIndex -> current + 1
            _repeatMode.value == RepeatMode.ALL -> 0
            userInitiated -> 0
            else -> null
        }
    }

    private fun resolvePreviousIndex(): Int {
        val queue = _queue.value
        if (queue.isEmpty()) return -1
        val current = _currentIndex.value
        if (_shuffle.value && shuffleOrder.isNotEmpty()) {
            val position = shuffleOrder.indexOf(current)
            if (position > 0) return shuffleOrder[position - 1]
            return shuffleOrder.lastOrNull() ?: 0
        }
        return if (current > 0) current - 1 else queue.lastIndex
    }

    suspend fun handleTrackCompleted() {
        flushListeningTime()
        if (_sleepTimer.value.stopAfterCurrent) {
            stopAfterCurrentFinished()
            return
        }
        if (_repeatMode.value == RepeatMode.ONE) {
            seekTo(0)
            play()
            return
        }
        next(userInitiated = false)
    }

    private fun rebuildShuffleOrder(anchorIndex: Int) {
        val size = _queue.value.size
        if (size == 0) {
            shuffleOrder = emptyList()
            return
        }
        val indices = (0 until size).filter { it != anchorIndex }.shuffled(Random(System.nanoTime()))
        shuffleOrder = if (anchorIndex in 0 until size) listOf(anchorIndex) + indices else indices
    }

    fun toggleShuffle() {
        _shuffle.value = !_shuffle.value
        rebuildShuffleOrder(_currentIndex.value)
    }

    fun cycleRepeatMode() {
        _repeatMode.value = when (_repeatMode.value) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
    }

    // ---------------- 播放核心 ----------------

    private fun playCurrent() {
        val queue = _queue.value
        val index = _currentIndex.value
        if (index !in queue.indices) return
        val song = queue[index]
        _currentSong.value = song
        _state.value = PlaybackState.PREPARING
        _buffering.value = true
        engine.prepare(song.durationMs)
        _lyrics.value = lyricsRepository.cached(song) ?: Lyrics()
        scope.launch { loadLyricsFor(song) }
        scope.launch { updateCoverSeed(song) }
        scope.launch {
            val input = resolveInput(song)
            if (input == null) {
                _state.value = PlaybackState.ERROR
                _buffering.value = false
                _messages.emit("无法获取播放地址，请稍后重试")
                return@launch
            }
            val duration = song.durationMs.takeIf { it > 0 }
                ?: FfmpegSupport.probeDurationMs(input)
            engine.open(input, 0L, duration)
            engine.setVolume(_volume.value)
            if (engine.supportsEffects) {
                engine.setSpeed(_speed.value)
                engine.setPitch(_pitch.value)
                engine.setLoudness(_loudness.value)
                engine.setEqualizer(if (_equalizerEnabled.value) _equalizerBands.value else null)
            }
            engine.play()
            currentPlayingKey = song.key
            playbackStartedAt = System.currentTimeMillis()
            _state.value = PlaybackState.PLAYING
            history.record(song)
            stats.recordPlay(song)
            persistQueueState()
        }
    }

    private fun stopPlaybackInternal() {
        flushListeningTime()
        engine.release()
        currentPlayingKey = null
        playbackStartedAt = 0L
    }

    /** 把当前播放片段的时长写入统计，并重新开始计时。 */
    fun flushListeningTime() {
        val song = _currentSong.value ?: return
        val started = playbackStartedAt
        if (started <= 0L) return
        val now = System.currentTimeMillis()
        val delta = (now - started).coerceAtLeast(0L)
        playbackStartedAt = if (_state.value == PlaybackState.PLAYING) now else 0L
        if (delta > 0L) {
            stats.addListenTime(song, delta)
        }
    }

    private suspend fun resolveInput(song: Song): AudioInput? = resolveMutex.withLock {
        resolvedCache[song.key]?.let { cached ->
            if (song.source == MediaSource.LOCAL) {
                val path = cached.path
                if (path != null && File(path).isFile) return@withLock cached
            } else {
                return@withLock cached
            }
        }
        val resolved = online.resolvePlayback(song, settings.current.qualityPreference)
        if (resolved != null) resolvedCache[song.key] = resolved
        resolved
    }

    private suspend fun loadLyricsFor(song: Song) {
        _lyricsLoading.value = true
        val loaded = lyricsRepository.load(song)
        if (_currentSong.value?.key == song.key) {
            _lyrics.value = loaded
        }
        _lyricsLoading.value = false
    }

    fun reloadLyrics() {
        val song = _currentSong.value ?: return
        lyricsRepository.invalidate(song)
        scope.launch { loadLyricsFor(song) }
    }

    private suspend fun updateCoverSeed(song: Song) {
        val cached = seedCache[song.key]
        if (cached != null) {
            _coverSeedColor.value = cached
            return
        }
        val color = withContext(Dispatchers.IO) { extractSeedColor(song) }
        if (color != null) {
            seedCache[song.key] = color
            if (_currentSong.value?.key == song.key) _coverSeedColor.value = color
        } else if (_currentSong.value?.key == song.key) {
            _coverSeedColor.value = null
        }
    }

    private fun artworkBytes(song: Song): ByteArray? {
        val path = song.artworkPath
        if (!path.isNullOrBlank()) {
            val file = File(path)
            if (file.isFile) return runCatching { file.readBytes() }.getOrNull()
        }
        val url = song.artworkUrl ?: return null
        val cached = File(AppDirs.coverDir, song.key.replace(Regex("[^A-Za-z0-9]"), "_") + ".img")
        if (cached.isFile) return runCatching { cached.readBytes() }.getOrNull()
        val bytes = runCatching { java.net.URI(url).toURL().openStream().use { it.readBytes() } }.getOrNull() ?: return null
        runCatching { cached.writeBytes(bytes) }
        return bytes
    }

    private fun extractSeedColor(song: Song): String? {
        val bytes = artworkBytes(song) ?: return null
        val image = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull() ?: return null
        if (image.width <= 0 || image.height <= 0) return null
        val stepX = (image.width / 32).coerceAtLeast(1)
        val stepY = (image.height / 32).coerceAtLeast(1)
        var bestScore = -1.0
        var bestColor: Int? = null
        var x = 0
        while (x < image.width) {
            var y = 0
            while (y < image.height) {
                val rgb = image.getRGB(x, y)
                val r = (rgb shr 16) and 0xFF
                val g = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF
                val max = maxOf(r, g, b)
                val min = minOf(r, g, b)
                val chroma = (max - min).toDouble() / 255.0
                val brightness = max / 255.0
                val score = chroma * 1.6 + brightness * 0.4
                if (score > bestScore) {
                    bestScore = score
                    bestColor = rgb
                }
                y += stepY
            }
            x += stepX
        }
        val color = bestColor ?: return null
        return "%06X".format(color and 0xFFFFFF)
    }

    // ---------------- 音效 ----------------

    fun setVolume(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        _volume.value = clamped
        engine.setVolume(clamped)
        settings.update { it.copy(volume = clamped) }
    }

    fun setSpeed(value: Float) {
        val clamped = value.coerceIn(0.5f, 3f)
        _speed.value = clamped
        engine.setSpeed(clamped)
        settings.update { it.copy(playbackSpeed = clamped) }
    }

    fun setPitch(semitones: Float) {
        val clamped = semitones.coerceIn(-12f, 12f)
        _pitch.value = clamped
        engine.setPitch(clamped)
        settings.update { it.copy(pitchSemitone = clamped) }
    }

    fun setLoudness(enabled: Boolean) {
        _loudness.value = enabled
        engine.setLoudness(enabled)
        settings.update { it.copy(loudnessEnhancer = enabled) }
    }

    fun setEqualizerEnabled(enabled: Boolean) {
        _equalizerEnabled.value = enabled
        engine.setEqualizer(if (enabled) _equalizerBands.value else null)
        settings.update { it.copy(equalizerEnabled = enabled) }
    }

    fun setEqualizerPreset(name: String, bands: List<Float>) {
        _equalizerBands.value = bands
        _equalizerEnabled.value = true
        engine.setEqualizer(bands)
        settings.update { it.copy(equalizerPreset = name, equalizerBands = bands, equalizerEnabled = true) }
    }

    fun setEqualizerBand(index: Int, gain: Float) {
        val bands = _equalizerBands.value.toMutableList()
        if (index !in bands.indices) return
        bands[index] = gain
        _equalizerBands.value = bands
        engine.setEqualizer(bands)
        settings.update { it.copy(equalizerBands = bands, equalizerPreset = "自定义") }
    }

    fun resetEffects() {
        setSpeed(1f)
        setPitch(0f)
        setLoudness(false)
        _equalizerBands.value = List(10) { 0f }
        setEqualizerEnabled(false)
        settings.update { it.copy(equalizerPreset = "平直") }
    }

    // ---------------- 睡眠定时器 ----------------

    fun startSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        val total = minutes.coerceAtLeast(1) * 60L
        _sleepTimer.value = SleepTimerState(active = true, remainingSeconds = total, totalSeconds = total)
        sleepTimerJob = scope.launch {
            var remaining = total
            while (remaining > 0) {
                delay(1_000)
                remaining -= 1
                _sleepTimer.value = _sleepTimer.value.copy(remainingSeconds = remaining)
            }
            pause()
            _sleepTimer.value = SleepTimerState()
            _messages.emit("睡眠定时器已结束，播放已暂停")
        }
    }

    fun stopAfterCurrentSong() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        _sleepTimer.value = SleepTimerState(active = true, stopAfterCurrent = true)
        _messages.tryEmit("播完当前歌曲后停止")
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        _sleepTimer.value = SleepTimerState()
    }

    private suspend fun stopAfterCurrentFinished() {
        _sleepTimer.value = SleepTimerState()
        pause()
        _messages.emit("已播放完当前歌曲，播放暂停")
    }

    // ---------------- 状态持久化 ----------------

    fun persistQueueState() {
        settings.update {
            it.copy(
                lastQueue = _queue.value.take(200),
                lastQueueIndex = _currentIndex.value,
                lastPositionMs = engine.snapshot.value.positionMs,
            )
        }
    }

    fun restoreLastQueue() {
        val saved = settings.current
        if (!saved.resumeLastQueue || saved.lastQueue.isEmpty()) return
        val index = saved.lastQueueIndex.coerceIn(0, saved.lastQueue.lastIndex)
        _queue.value = saved.lastQueue
        _currentIndex.value = index
        _currentSong.value = saved.lastQueue[index]
        _state.value = PlaybackState.PAUSED
    }
}
