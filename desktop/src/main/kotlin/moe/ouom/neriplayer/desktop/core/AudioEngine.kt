package moe.ouom.neriplayer.desktop.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import kotlin.math.abs
import kotlin.math.pow

data class EngineSnapshot(
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val error: String? = null,
)

data class AudioInput(
    val path: String? = null,
    val url: String? = null,
    val headers: Map<String, String> = emptyMap(),
) {
    val target: String get() = path ?: url.orEmpty()

    fun displayTarget(): String = path?.substringAfterLast('/') ?: url.orEmpty().substringAfterLast('/')
}

interface AudioEngine {
    val snapshot: StateFlow<EngineSnapshot>
    val supportsEffects: Boolean
    var onCompleted: (() -> Unit)?
    var onError: ((String) -> Unit)?

    fun open(input: AudioInput, startMs: Long, durationMs: Long)

    /** 切歌时先重置进度与时长，避免界面短暂显示上一首的进度信息。 */
    fun prepare(durationMs: Long)
    fun play()
    fun pause()
    fun release()
    fun seekTo(positionMs: Long)
    fun setVolume(volume: Float)
    fun setSpeed(speed: Float)
    fun setPitch(semitones: Float)
    fun setLoudness(enabled: Boolean)
    fun setEqualizer(bands: List<Float>?)
}

private const val SAMPLE_RATE = 48_000
private const val CHANNELS = 2
private const val BYTES_PER_FRAME = CHANNELS * 2
private const val EQ_FREQUENCIES = "31,62,125,250,500,1000,2000,4000,8000,16000"

private val EQ_FREQ_LIST = EQ_FREQUENCIES.split(',').map { it.trim() }

object FfmpegSupport {
    val available: Boolean by lazy {
        runCatching {
            val process = ProcessBuilder("ffmpeg", "-version")
                .redirectErrorStream(true)
                .start()
            process.inputStream.readBytes()
            process.waitFor() == 0
        }.getOrDefault(false)
    }

    val version: String by lazy {
        runCatching {
            val process = ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start()
            val text = process.inputStream.readBytes().decodeToString().lineSequence().firstOrNull().orEmpty()
            process.waitFor()
            text
        }.getOrDefault("")
    }

    fun probeDurationMs(input: AudioInput): Long = runCatching {
        val command = mutableListOf("ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "default=nw=1:nk=1")
        if (input.headers.isNotEmpty()) {
            command += listOf("-headers", input.headers.entries.joinToString("\r\n") { "${it.key}: ${it.value}" } + "\r\n")
        }
        command += input.target
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val text = process.inputStream.readBytes().decodeToString().trim()
        process.waitFor()
        val seconds = text.toDoubleOrNull() ?: return@runCatching 0L
        (seconds * 1000.0).toLong()
    }.getOrDefault(0L)
}

/** 主播放引擎：ffmpeg 解码为 PCM，Java Sound 输出。支持变速、变调、响度与均衡器。 */
class FfmpegAudioEngine : AudioEngine {

    override val supportsEffects: Boolean = true

    private val _snapshot = MutableStateFlow(EngineSnapshot())
    override val snapshot: StateFlow<EngineSnapshot> = _snapshot.asStateFlow()

    override var onCompleted: (() -> Unit)? = null
    override var onError: ((String) -> Unit)? = null

    private val lock = Any()
    private var process: Process? = null
    private var line: SourceDataLine? = null
    private var readerThread: Thread? = null
    private var tickerThread: Thread? = null

    private var input: AudioInput? = null
    private var offsetMs: Long = 0L
    private var durationMs: Long = 0L
    private var volume: Float = 0.85f
    private var speed: Float = 1.0f
    private var pitch: Float = 0.0f
    private var loudness: Boolean = false
    private var equalizer: List<Float>? = null
    private var playRequested = false
    private var restarting = false
    private var generation = 0
    private val pauseGate = Object()

    @Volatile
    private var paused = false

    override fun open(input: AudioInput, startMs: Long, durationMs: Long) {
        synchronized(lock) {
            stopPipelineLocked()
            this.input = input
            this.offsetMs = startMs.coerceAtLeast(0L)
            this.durationMs = durationMs
            playRequested = false
            paused = false
            _snapshot.value = EngineSnapshot(
                positionMs = this.offsetMs,
                durationMs = durationMs,
                playing = false,
                buffering = true,
            )
        }
        startPipeline(startMs = offsetMs, autoPlay = false)
    }

    override fun prepare(durationMs: Long) {
        synchronized(lock) {
            offsetMs = 0L
            this.durationMs = durationMs
        }
        _snapshot.value = EngineSnapshot(
            positionMs = 0L,
            durationMs = durationMs,
            playing = false,
            buffering = true,
        )
    }

    override fun play() {
        val current = synchronized(lock) {
            playRequested = true
            paused = false
            line
        }
        synchronized(pauseGate) { pauseGate.notifyAll() }
        if (current == null) {
            startPipeline(startMs = synchronized(lock) { offsetMs }, autoPlay = true)
        } else {
            runCatching { current.start() }
            _snapshot.value = _snapshot.value.copy(playing = true, buffering = false)
        }
    }

    override fun pause() {
        val current = synchronized(lock) {
            playRequested = false
            paused = true
            line
        }
        runCatching { current?.stop() }
        _snapshot.value = _snapshot.value.copy(playing = false)
    }

    override fun release() {
        synchronized(lock) {
            paused = false
            generation += 1
            stopPipelineLocked()
            input = null
        }
        synchronized(pauseGate) { pauseGate.notifyAll() }
        _snapshot.value = EngineSnapshot()
    }

    override fun seekTo(positionMs: Long) {
        val target = positionMs.coerceAtLeast(0L).let { if (durationMs > 0) it.coerceAtMost(durationMs) else it }
        val shouldPlay = synchronized(lock) {
            offsetMs = target
            playRequested
        }
        startPipeline(startMs = target, autoPlay = shouldPlay)
    }

    override fun setVolume(volume: Float) {
        this.volume = volume.coerceIn(0f, 2f)
    }

    override fun setSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 3.0f)
        if (abs(clamped - this.speed) < 0.0001f) return
        this.speed = clamped
        restartPreservingPosition()
    }

    override fun setPitch(semitones: Float) {
        val clamped = semitones.coerceIn(-12f, 12f)
        if (abs(clamped - this.pitch) < 0.0001f) return
        this.pitch = clamped
        restartPreservingPosition()
    }

    override fun setLoudness(enabled: Boolean) {
        if (this.loudness == enabled) return
        this.loudness = enabled
        restartPreservingPosition()
    }

    override fun setEqualizer(bands: List<Float>?) {
        this.equalizer = bands
        restartPreservingPosition()
    }

    private fun restartPreservingPosition() {
        val current = input ?: return
        val position = currentPositionMs()
        val shouldPlay = synchronized(lock) { playRequested }
        synchronized(lock) { offsetMs = position }
        startPipeline(startMs = position, autoPlay = shouldPlay, input = current)
    }

    private fun currentPositionMs(): Long = synchronized(lock) {
        val frames = runCatching { line?.longFramePosition?.toLong() ?: 0L }.getOrDefault(0L)
        offsetMs + frames * 1000L / SAMPLE_RATE
    }

    private fun buildFilterChain(): String {
        val filters = mutableListOf<String>()
        val pitchRatio = 2.0.pow(pitch / 12.0)
        if (abs(pitchRatio - 1.0) > 1e-3) {
            filters += "asetrate=${(SAMPLE_RATE * pitchRatio).toInt()}"
            filters += "aresample=$SAMPLE_RATE"
        }
        var tempo = speed.toDouble()
        if (abs(pitchRatio - 1.0) > 1e-3) tempo /= pitchRatio
        filters += tempoChain(tempo)
        if (loudness) {
            filters += "dynaudnorm=f=250:g=15:p=0.9"
        }
        val bands = equalizer
        if (bands != null) {
            bands.take(EQ_FREQ_LIST.size).forEachIndexed { index, gain ->
                if (abs(gain) > 0.05f) {
                    filters += "equalizer=f=${EQ_FREQ_LIST[index]}:t=q:w=1.2:g=${"%.1f".format(gain)}"
                }
            }
        }
        return filters.joinToString(",")
    }

    private fun tempoChain(tempo: Double): List<String> {
        var value = tempo.coerceIn(0.25, 4.0)
        val out = mutableListOf<String>()
        while (value > 2.0) {
            out += "atempo=2.0"
            value /= 2.0
        }
        while (value < 0.5) {
            out += "atempo=0.5"
            value /= 0.5
        }
        out += "atempo=${"%.4f".format(value)}"
        return out
    }

    private fun buildCommand(input: AudioInput, startMs: Long): List<String> {
        val command = mutableListOf(
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin",
        )
        if (startMs > 0) {
            command += listOf("-ss", "%.3f".format(startMs / 1000.0))
        }
        if (input.headers.isNotEmpty()) {
            command += listOf(
                "-headers",
                input.headers.entries.joinToString("\r\n") { "${it.key}: ${it.value}" } + "\r\n",
            )
        }
        command += "-i"
        command += input.target
        command += listOf("-vn", "-sn", "-dn")
        val filters = buildFilterChain()
        if (filters.isNotEmpty()) {
            command += listOf("-af", filters)
        }
        command += listOf("-f", "s16le", "-ac", CHANNELS.toString(), "-ar", SAMPLE_RATE.toString(), "-")
        return command
    }

    private fun startPipeline(startMs: Long, autoPlay: Boolean, input: AudioInput? = null) {
        val target = input ?: synchronized(lock) { this.input } ?: return
        val myGeneration: Int
        synchronized(lock) {
            stopPipelineLocked()
            generation += 1
            myGeneration = generation
            offsetMs = startMs.coerceAtLeast(0L)
            playRequested = autoPlay || playRequested
        }
        val format = AudioFormat(SAMPLE_RATE.toFloat(), 16, CHANNELS, true, false)
        val info = DataLine.Info(SourceDataLine::class.java, format)
        val newLine = runCatching {
            (AudioSystem.getLine(info) as SourceDataLine).apply {
                open(format, SAMPLE_RATE * BYTES_PER_FRAME)
            }
        }.getOrElse { error ->
            val message = "无法打开音频输出设备：${error.message ?: error.javaClass.simpleName}"
            _snapshot.value = _snapshot.value.copy(playing = false, buffering = false, error = message)
            onError?.invoke(message)
            return
        }
        val newProcess = runCatching {
            ProcessBuilder(buildCommand(target, startMs)).start()
        }.getOrElse { error ->
            runCatching { newLine.close() }
            val message = "无法启动解码器：${error.message ?: error.javaClass.simpleName}"
            _snapshot.value = _snapshot.value.copy(playing = false, buffering = false, error = message)
            onError?.invoke(message)
            return
        }

        synchronized(lock) {
            line = newLine
            process = newProcess
            _snapshot.value = _snapshot.value.copy(
                positionMs = startMs,
                durationMs = durationMs,
                buffering = true,
                playing = false,
                error = null,
            )
        }

        val shouldPlayNow = synchronized(lock) { playRequested }
        if (shouldPlayNow) {
            runCatching { newLine.start() }
            _snapshot.value = _snapshot.value.copy(playing = true, buffering = false)
        }

        val reader = Thread({ readLoop(myGeneration, newProcess, newLine, startMs) }, "neri-audio-reader")
        reader.isDaemon = true
        readerThread = reader
        reader.start()

        if (tickerThread == null) {
            val ticker = Thread({ tickLoop() }, "neri-audio-ticker")
            ticker.isDaemon = true
            tickerThread = ticker
            ticker.start()
        }
    }

    private fun readLoop(myGeneration: Int, process: Process, line: SourceDataLine, startMs: Long) {
        val inputStream: InputStream = BufferedInputStream(process.inputStream, 1 shl 16)
        val buffer = ByteArray(1 shl 15)
        var finishedNormally = false
        try {
            while (true) {
                if (synchronized(lock) { generation != myGeneration || restarting }) break
                // 暂停时在此等待，避免继续消费解码数据（否则会一路读到文件尾并误判为播放结束）
                awaitResume(myGeneration)
                if (synchronized(lock) { generation != myGeneration || restarting }) break
                val read = inputStream.read(buffer)
                if (read < 0) {
                    // 处于暂停状态读到结尾时不立即切歌，等恢复播放后再结算
                    awaitResume(myGeneration)
                    if (synchronized(lock) { generation != myGeneration }) return
                    finishedNormally = true
                    break
                }
                if (read == 0) continue
                applyVolume(buffer, read)
                var written = 0
                while (written < read) {
                    if (synchronized(lock) { generation != myGeneration }) return
                    val count = line.write(buffer, written, read - written)
                    if (count <= 0) break
                    written += count
                }
            }
        } catch (_: Throwable) {
            // 管道被主动关闭时忽略
        } finally {
            runCatching { inputStream.close() }
            val stillCurrent = synchronized(lock) { generation == myGeneration }
            if (finishedNormally && stillCurrent) {
                runCatching { line.drain() }
                runCatching { line.stop() }
                _snapshot.value = _snapshot.value.copy(
                    playing = false,
                    buffering = false,
                    positionMs = durationMs.takeIf { it > 0 } ?: currentPositionMs(),
                )
                val errorText = runCatching { process.errorStream.readBytes().decodeToString().trim() }.getOrDefault("")
                if (errorText.isNotBlank() && _snapshot.value.durationMs == 0L && offsetMs == 0L) {
                    _snapshot.value = _snapshot.value.copy(error = errorText.take(300))
                    onError?.invoke(errorText.take(300))
                } else {
                    onCompleted?.invoke()
                }
            }
        }
    }

    private fun applyVolume(buffer: ByteArray, length: Int) {
        val gain = volume
        if (abs(gain - 1f) < 0.001f) return
        var index = 0
        while (index + 1 < length) {
            val sample = ((buffer[index].toInt() and 0xFF) or (buffer[index + 1].toInt() shl 8)).toShort()
            val scaled = (sample * gain).toInt().coerceIn(-32768, 32767)
            buffer[index] = (scaled and 0xFF).toByte()
            buffer[index + 1] = ((scaled shr 8) and 0xFF).toByte()
            index += 2
        }
    }

    private fun tickLoop() {
        while (true) {
            try {
                Thread.sleep(200)
            } catch (_: InterruptedException) {
                return
            }
            val active = synchronized(lock) { line != null }
            if (!active) continue
            val position = currentPositionMs()
            val playingNow = synchronized(lock) { playRequested }
            _snapshot.value = _snapshot.value.copy(
                positionMs = if (playingNow) position else _snapshot.value.positionMs,
                playing = playingNow,
            )
        }
    }

    /** 暂停期间阻塞读取线程，直到恢复播放、切换歌曲或释放引擎。 */
    private fun awaitResume(myGeneration: Int) {
        if (!paused) return
        synchronized(pauseGate) {
            while (paused && synchronized(lock) { generation == myGeneration }) {
                runCatching { pauseGate.wait(200L) }
            }
        }
    }

    private fun stopPipelineLocked() {
        restarting = true
        runCatching { process?.destroy() }
        process = null
        runCatching { line?.stop() }
        runCatching { line?.close() }
        line = null
        restarting = false
        synchronized(pauseGate) { pauseGate.notifyAll() }
    }
}

/** 回退引擎：纯 Java Sound（mp3 / flac / ogg / wav / aiff），不支持变速与均衡器。 */
class JavaSoundAudioEngine : AudioEngine {

    override val supportsEffects: Boolean = false

    private val _snapshot = MutableStateFlow(EngineSnapshot())
    override val snapshot: StateFlow<EngineSnapshot> = _snapshot.asStateFlow()

    override var onCompleted: (() -> Unit)? = null
    override var onError: ((String) -> Unit)? = null

    private val lock = Any()
    private var line: SourceDataLine? = null
    private var thread: Thread? = null
    private var input: AudioInput? = null
    private var offsetMs = 0L
    private var durationMs = 0L
    private var volume = 0.85f
    private var playRequested = false
    private var generation = 0
    private val closing = AtomicBoolean(false)
    private val pauseGate = Object()

    @Volatile
    private var paused = false

    override fun open(input: AudioInput, startMs: Long, durationMs: Long) {
        synchronized(lock) {
            closeLocked()
            this.input = input
            offsetMs = startMs
            this.durationMs = durationMs
            generation += 1
            playRequested = false
            paused = false
        }
        synchronized(pauseGate) { pauseGate.notifyAll() }
        _snapshot.value = EngineSnapshot(positionMs = startMs, durationMs = durationMs, buffering = true)
    }

    override fun prepare(durationMs: Long) {
        synchronized(lock) {
            offsetMs = 0L
            this.durationMs = durationMs
        }
        _snapshot.value = EngineSnapshot(
            positionMs = 0L,
            durationMs = durationMs,
            playing = false,
            buffering = true,
        )
    }

    override fun play() {
        val shouldStart = synchronized(lock) {
            playRequested = true
            paused = false
            thread == null
        }
        synchronized(pauseGate) { pauseGate.notifyAll() }
        if (shouldStart) {
            startStream()
        } else {
            synchronized(lock) { line }?.let { runCatching { it.start() } }
            _snapshot.value = _snapshot.value.copy(playing = true, buffering = false)
        }
    }

    override fun pause() {
        synchronized(lock) {
            playRequested = false
            paused = true
        }
        synchronized(lock) { line }?.let { runCatching { it.stop() } }
        _snapshot.value = _snapshot.value.copy(playing = false)
    }

    override fun release() {
        synchronized(lock) {
            paused = false
            closeLocked()
            input = null
            generation += 1
        }
        synchronized(pauseGate) { pauseGate.notifyAll() }
        _snapshot.value = EngineSnapshot()
    }

    override fun seekTo(positionMs: Long) {
        synchronized(lock) {
            offsetMs = positionMs.coerceAtLeast(0L)
            generation += 1
            closeLocked()
        }
        if (synchronized(lock) { playRequested }) startStream()
        _snapshot.value = _snapshot.value.copy(positionMs = positionMs)
    }

    override fun setVolume(volume: Float) {
        this.volume = volume.coerceIn(0f, 2f)
    }

    override fun setSpeed(speed: Float) = Unit

    override fun setPitch(semitones: Float) = Unit

    override fun setLoudness(enabled: Boolean) = Unit

    override fun setEqualizer(bands: List<Float>?) = Unit

    private fun openStream(target: AudioInput): javax.sound.sampled.AudioInputStream? = runCatching {
        if (target.path != null) {
            AudioSystem.getAudioInputStream(File(target.path))
        } else {
            AudioSystem.getAudioInputStream(java.net.URL(target.url))
        }
    }.getOrNull()

    private fun startStream() {
        val target = synchronized(lock) { input } ?: return
        val myGeneration = synchronized(lock) { generation }
        val skipMs = synchronized(lock) { offsetMs }
        val raw = openStream(target)
        if (raw == null) {
            val message = "无法解码该音频文件（Java Sound 回退引擎不支持该格式）"
            _snapshot.value = _snapshot.value.copy(error = message, buffering = false)
            onError?.invoke(message)
            return
        }
        val decoded = runCatching { AudioSystem.getAudioInputStream(AudioFormat.Encoding.PCM_SIGNED, raw) }
            .getOrElse { raw }
        val format = decoded.format
        val info = DataLine.Info(SourceDataLine::class.java, format)
        val newLine = runCatching { (AudioSystem.getLine(info) as SourceDataLine).apply { open(format) } }
            .getOrElse { error ->
                val message = "无法打开音频输出设备：${error.message}"
                _snapshot.value = _snapshot.value.copy(error = message, buffering = false)
                onError?.invoke(message)
                return
            }
        if (skipMs > 0) {
            val bytesToSkip = skipMs * format.frameSize * format.frameRate.toLong() / 1000L
            var remaining = bytesToSkip
            while (remaining > 0) {
                val skipped = runCatching { decoded.skip(remaining) }.getOrDefault(0L)
                if (skipped <= 0) break
                remaining -= skipped
            }
        }
        synchronized(lock) {
            line = newLine
            _snapshot.value = _snapshot.value.copy(buffering = true, playing = false, error = null)
        }
        if (synchronized(lock) { playRequested }) {
            runCatching { newLine.start() }
            _snapshot.value = _snapshot.value.copy(playing = true, buffering = false)
        }
        val worker = Thread({
            val buffer = ByteArray(1 shl 15)
            var finished = false
            try {
                while (true) {
                    if (synchronized(lock) { generation != myGeneration }) return@Thread
                    awaitResume(myGeneration)
                    if (synchronized(lock) { generation != myGeneration }) return@Thread
                    val read = decoded.read(buffer)
                    if (read < 0) {
                        awaitResume(myGeneration)
                        if (synchronized(lock) { generation != myGeneration }) return@Thread
                        finished = true
                        break
                    }
                    applyVolume(buffer, read)
                    var written = 0
                    while (written < read) {
                        val count = newLine.write(buffer, written, read - written)
                        if (count <= 0) break
                        written += count
                    }
                }
            } catch (_: Throwable) {
                // 关闭时忽略
            } finally {
                runCatching { decoded.close() }
                if (finished && synchronized(lock) { generation == myGeneration }) {
                    runCatching { newLine.drain() }
                    runCatching { newLine.stop() }
                    _snapshot.value = _snapshot.value.copy(playing = false, buffering = false)
                    onCompleted?.invoke()
                }
            }
        }, "neri-javasound-reader")
        worker.isDaemon = true
        synchronized(lock) { thread = worker }
        worker.start()
    }

    private fun applyVolume(buffer: ByteArray, length: Int) {
        val gain = volume
        if (abs(gain - 1f) < 0.001f) return
        var index = 0
        while (index + 1 < length) {
            val sample = ((buffer[index].toInt() and 0xFF) or (buffer[index + 1].toInt() shl 8)).toShort()
            val scaled = (sample * gain).toInt().coerceIn(-32768, 32767)
            buffer[index] = (scaled and 0xFF).toByte()
            buffer[index + 1] = ((scaled shr 8) and 0xFF).toByte()
            index += 2
        }
    }

    /** 暂停期间阻塞读取线程，直到恢复播放或释放引擎。 */
    private fun awaitResume(myGeneration: Int) {
        if (!paused) return
        synchronized(pauseGate) {
            while (paused && synchronized(lock) { generation == myGeneration }) {
                runCatching { pauseGate.wait(200L) }
            }
        }
    }

    private fun closeLocked() {
        runCatching { line?.stop() }
        runCatching { line?.close() }
        line = null
        thread = null
        synchronized(pauseGate) { pauseGate.notifyAll() }
    }
}

fun createAudioEngine(): AudioEngine =
    if (FfmpegSupport.available) FfmpegAudioEngine() else JavaSoundAudioEngine()
