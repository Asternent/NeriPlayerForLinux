package moe.ouom.neriplayer.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Lyrics
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.RepeatOne
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.ouom.neriplayer.desktop.core.AppContainer
import moe.ouom.neriplayer.desktop.core.MediaSource
import moe.ouom.neriplayer.desktop.core.PlaybackState
import moe.ouom.neriplayer.desktop.core.RepeatMode
import moe.ouom.neriplayer.desktop.core.currentLyricIndex
import moe.ouom.neriplayer.desktop.ui.EmptyState
import moe.ouom.neriplayer.desktop.ui.LyricsPane
import moe.ouom.neriplayer.desktop.ui.PlaybackEffectsPanel
import moe.ouom.neriplayer.desktop.ui.PlayerProgressBar
import moe.ouom.neriplayer.desktop.ui.QueuePanel
import moe.ouom.neriplayer.desktop.ui.SleepTimerPanel
import moe.ouom.neriplayer.desktop.ui.SongArtwork
import moe.ouom.neriplayer.desktop.ui.VolumePanel

@Composable
fun NowPlayingScreen(
    container: AppContainer,
    onBack: () -> Unit,
    showMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
    externalOverlay: String? = null,
) {
    val song by container.player.currentSong.collectAsState()
    val state by container.player.state.collectAsState()
    val position by container.player.positionMs.collectAsState()
    val duration by container.player.durationMs.collectAsState()
    val shuffle by container.player.shuffle.collectAsState()
    val repeatMode by container.player.repeatMode.collectAsState()
    val volume by container.player.volume.collectAsState()
    val speed by container.player.speed.collectAsState()
    val pitch by container.player.pitch.collectAsState()
    val loudness by container.player.loudness.collectAsState()
    val eqEnabled by container.player.equalizerEnabled.collectAsState()
    val eqBands by container.player.equalizerBands.collectAsState()
    val lyrics by container.player.lyrics.collectAsState()
    val lyricsLoading by container.player.lyricsLoading.collectAsState()
    val queue by container.player.queue.collectAsState()
    val currentIndex by container.player.currentIndex.collectAsState()
    val sleepTimer by container.player.sleepTimer.collectAsState()
    val buffering by container.player.buffering.collectAsState()
    val settings by container.settings.state.collectAsState()
    val playlists by container.playlists.playlists.collectAsState()

    var showQueue by remember { mutableStateOf(false) }
    var showVolume by remember { mutableStateOf(false) }
    var showSleepTimer by remember { mutableStateOf(false) }
    var showEffects by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    var showAddToPlaylist by remember { mutableStateOf(false) }
    var showFullLyrics by remember { mutableStateOf(false) }

    LaunchedEffect(externalOverlay) {
        when (externalOverlay) {
            "queue" -> showQueue = true
            "volume" -> showVolume = true
            "sleep" -> showSleepTimer = true
            "effects" -> showEffects = true
            "more" -> showMore = true
            "add" -> showAddToPlaylist = true
            "lyrics" -> showFullLyrics = true
            null -> Unit
        }
    }

    val lyricIndex = remember(lyrics, position) { currentLyricIndex(lyrics.lines, position) }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Outlined.ArrowBack, contentDescription = "返回")
                }
                if (settings.showNowPlayingTitle) {
                    Column(Modifier.weight(1f)) {
                        Text("正在播放", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = song?.source?.displayName.orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                }
                IconButton(onClick = { showAddToPlaylist = true }) {
                    Icon(Icons.Outlined.Add, contentDescription = "添加到歌单")
                }
                IconButton(onClick = { showMore = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "更多选项")
                }
            }

            if (song == null) {
                EmptyState(
                    title = "暂无播放",
                    hint = "在媒体库或探索中选择一首歌开始播放",
                    icon = Icons.Outlined.MusicNote,
                    modifier = Modifier.weight(1f),
                )
            } else {
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val wide = maxWidth >= 900.dp
                    if (wide) {
                        Row(Modifier.fillMaxSize()) {
                            Column(
                                modifier = Modifier.weight(1f).fillMaxHeight().padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                PlayerCore(
                                    container = container,
                                    showMessage = showMessage,
                                    song = song,
                                    position = position,
                                    duration = duration,
                                    state = state,
                                    buffering = buffering,
                                    shuffle = shuffle,
                                    repeatMode = repeatMode,
                                    coverSize = 300.dp,
                                    onOpenQueue = { showQueue = true },
                                    onOpenSleepTimer = { showSleepTimer = true },
                                    onOpenVolume = { showVolume = true },
                                    onOpenEffects = { showEffects = true },
                                    onToggleLyrics = { showFullLyrics = !showFullLyrics },
                                    showLyricsShortcut = true,
                                )
                            }
                            Surface(
                                modifier = Modifier.width(420.dp).fillMaxHeight().padding(end = 16.dp, top = 8.dp, bottom = 16.dp),
                                shape = RoundedCornerShape(24.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                            ) {
                                LyricsPane(
                                    lines = lyrics.lines,
                                    currentIndex = lyricIndex,
                                    loading = lyricsLoading,
                                    fontScale = settings.lyricsFontScale,
                                    showTranslation = settings.showLyricTranslation,
                                    onSeekLine = { container.player.seekTo(it) },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            PlayerCore(
                                container = container,
                                showMessage = showMessage,
                                song = song,
                                position = position,
                                duration = duration,
                                state = state,
                                buffering = buffering,
                                shuffle = shuffle,
                                repeatMode = repeatMode,
                                coverSize = 220.dp,
                                onOpenQueue = { showQueue = true },
                                onOpenSleepTimer = { showSleepTimer = true },
                                onOpenVolume = { showVolume = true },
                                onOpenEffects = { showEffects = true },
                                onToggleLyrics = { showFullLyrics = true },
                                showLyricsShortcut = true,
                            )
                        }
                    }
                }
            }
        }

        if (showFullLyrics) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { showFullLyrics = false }) {
                            Icon(Icons.Outlined.ArrowBack, contentDescription = "返回")
                        }
                        Text(
                            text = song?.displayName().orEmpty(),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "字号 %.2fx".format(settings.lyricsFontScale),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        IconButton(onClick = {
                            container.settings.update { current ->
                                current.copy(lyricsFontScale = (current.lyricsFontScale + 0.1f).coerceAtMost(1.8f))
                            }
                        }) { Text("A+") }
                        IconButton(onClick = {
                            container.settings.update { current ->
                                current.copy(lyricsFontScale = (current.lyricsFontScale - 0.1f).coerceAtLeast(0.7f))
                            }
                        }) { Text("A-") }
                    }
                    LyricsPane(
                        lines = lyrics.lines,
                        currentIndex = lyricIndex,
                        loading = lyricsLoading,
                        fontScale = settings.lyricsFontScale,
                        showTranslation = settings.showLyricTranslation,
                        onSeekLine = { container.player.seekTo(it) },
                        modifier = Modifier.weight(1f),
                    )
                    if (!song?.filePath.isNullOrBlank()) {
                        Text(
                            text = song?.filePath.orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }

        if (showQueue) {
            QueuePanel(
                queue = queue,
                currentIndex = currentIndex,
                onClose = { showQueue = false },
                onPlayIndex = { index ->
                    container.player.setQueue(queue, index, autoPlay = true)
                },
                onRemoveIndex = { index -> container.player.removeFromQueue(index) },
                onMoveIndex = { from, to -> container.player.moveInQueue(from, to) },
                onClear = {
                    container.player.clearQueue()
                    showQueue = false
                },
            )
        }

        if (showVolume) {
            VolumePanel(
                volume = volume,
                onVolume = { container.player.setVolume(it) },
                onClose = { showVolume = false },
            )
        }

        if (showSleepTimer) {
            SleepTimerPanel(
                remainingSeconds = sleepTimer.remainingSeconds,
                stopAfterCurrent = sleepTimer.stopAfterCurrent,
                defaultMinutes = settings.sleepTimerMinutes,
                onStart = { minutes ->
                    container.player.startSleepTimer(minutes)
                    showMessage("睡眠定时器已启动：$minutes 分钟")
                },
                onStopAfterCurrent = {
                    container.player.stopAfterCurrentSong()
                    showSleepTimer = false
                },
                onCancelTimer = {
                    container.player.cancelSleepTimer()
                    showMessage("睡眠定时器已取消")
                },
                onClose = { showSleepTimer = false },
            )
        }

        if (showEffects) {
            PlaybackEffectsPanel(
                speed = speed,
                pitch = pitch,
                loudness = loudness,
                equalizerEnabled = eqEnabled,
                equalizerPreset = settings.equalizerPreset,
                equalizerBands = eqBands,
                supportsEffects = container.player.supportsEffects,
                onSpeed = { container.player.setSpeed(it) },
                onPitch = { container.player.setPitch(it) },
                onLoudness = { container.player.setLoudness(it) },
                onPreset = { name, bands -> container.player.setEqualizerPreset(name, bands) },
                onBand = { index, gain -> container.player.setEqualizerBand(index, gain) },
                onReset = { container.player.resetEffects() },
                onClose = { showEffects = false },
            )
        }

        if (showAddToPlaylist) {
            AlertDialog(
                onDismissRequest = { showAddToPlaylist = false },
                title = { Text("添加到歌单") },
                text = {
                    Column {
                        playlists.forEach { playlist ->
                            Text(
                                text = playlist.name + if (playlist.system) "（我喜欢的音乐）" else "",
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val current = song
                                        if (current != null) {
                                            container.playlists.addSongs(playlist.id, listOf(current))
                                            showMessage("已添加到「${playlist.name}」")
                                        }
                                        showAddToPlaylist = false
                                    }
                                    .padding(vertical = 10.dp),
                            )
                        }
                        if (playlists.isEmpty()) {
                            Text("还没有歌单，可在媒体库中新建", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val current = song
                        if (current != null) {
                            val name = "新歌单 ${playlists.count { !it.system } + 1}"
                            val created = container.playlists.createPlaylist(name)
                            container.playlists.addSongs(created.id, listOf(current))
                            showMessage("已创建歌单「$name」并添加歌曲")
                        }
                        showAddToPlaylist = false
                    }) { Text("新建歌单并添加") }
                },
                dismissButton = { TextButton(onClick = { showAddToPlaylist = false }) { Text("取消") } },
            )
        }

        if (showMore) {
            MoreOptionsDialog(
                container = container,
                showMessage = showMessage,
                onDismiss = { showMore = false },
                onOpenPlaylistDetail = { id -> showMore = false },
            )
        }
    }
}

@Composable
private fun PlayerCore(
    container: AppContainer,
    showMessage: (String) -> Unit,
    song: moe.ouom.neriplayer.desktop.core.Song?,
    position: Long,
    duration: Long,
    state: PlaybackState,
    buffering: Boolean,
    shuffle: Boolean,
    repeatMode: RepeatMode,
    coverSize: androidx.compose.ui.unit.Dp,
    onOpenQueue: () -> Unit,
    onOpenSleepTimer: () -> Unit,
    onOpenVolume: () -> Unit,
    onOpenEffects: () -> Unit,
    onToggleLyrics: () -> Unit,
    showLyricsShortcut: Boolean,
) {
    val current = song ?: return
    val isPlaying = state == PlaybackState.PLAYING
    val isFavorite = container.playlists.isFavorite(current)
    val settings by container.settings.state.collectAsState()

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Box(contentAlignment = Alignment.Center) {
            SongArtwork(current, size = coverSize, shape = RoundedCornerShape(24.dp))
            if (buffering && state == PlaybackState.PREPARING) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f),
                    modifier = Modifier.size(coverSize),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = current.displayName(),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = current.artistText(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (current.album.isNotBlank()) {
                        Text(
                            text = " · ${current.album}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            IconButton(onClick = {
                val added = container.playlists.toggleFavorite(current)
                showMessage(if (added) "已收藏" else "已取消收藏")
            }) {
                Icon(
                    imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = "收藏",
                    tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        PlayerProgressBar(
            positionMs = position,
            durationMs = duration,
            enabled = true,
            onSeek = { container.player.seekTo(it) },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = qualityLabel(settings.qualityPreference),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.weight(1f))
            if (current.source != MediaSource.LOCAL) {
                Icon(
                    imageVector = Icons.Outlined.Cloud,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = current.source.displayName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            IconButton(onClick = { container.player.toggleShuffle() }) {
                Icon(
                    imageVector = Icons.Outlined.Shuffle,
                    contentDescription = "随机",
                    tint = if (shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
            IconButton(onClick = { container.player.previous() }, modifier = Modifier.size(52.dp)) {
                Icon(Icons.Outlined.SkipPrevious, contentDescription = "上一首", modifier = Modifier.size(34.dp))
            }
            FilledIconButton(
                onClick = { container.player.togglePlayPause() },
                modifier = Modifier.size(68.dp),
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "暂停" else "播放",
                    modifier = Modifier.size(36.dp),
                )
            }
            IconButton(onClick = { container.player.next() }, modifier = Modifier.size(52.dp)) {
                Icon(Icons.Outlined.SkipNext, contentDescription = "下一首", modifier = Modifier.size(34.dp))
            }
            IconButton(onClick = { container.player.cycleRepeatMode() }) {
                Icon(
                    imageVector = if (repeatMode == RepeatMode.ONE) Icons.Outlined.RepeatOne else Icons.Outlined.Repeat,
                    contentDescription = "循环",
                    tint = if (repeatMode == RepeatMode.OFF) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            IconButton(onClick = onOpenQueue) {
                Icon(Icons.Outlined.QueueMusic, contentDescription = "播放队列")
            }
            IconButton(onClick = onOpenSleepTimer) {
                Icon(Icons.Outlined.Timer, contentDescription = "睡眠定时器")
            }
            IconButton(onClick = onOpenVolume) {
                Icon(Icons.Outlined.VolumeUp, contentDescription = "音量")
            }
            IconButton(onClick = onOpenEffects) {
                Icon(Icons.Outlined.GraphicEq, contentDescription = "音效与倍速")
            }
            if (showLyricsShortcut) {
                IconButton(onClick = onToggleLyrics) {
                    Icon(Icons.Outlined.Lyrics, contentDescription = "歌词")
                }
            }
        }
        if (settings.coverShowsLyrics) {
            Spacer(Modifier.height(14.dp))
            CoverLyricsLine(container)
        }
    }
}

@Composable
private fun CoverLyricsLine(container: AppContainer) {
    val lyrics by container.player.lyrics.collectAsState()
    val position by container.player.positionMs.collectAsState()
    val index = remember(lyrics, position) { currentLyricIndex(lyrics.lines, position) }
    if (lyrics.lines.isEmpty()) return
    val line = lyrics.lines.getOrNull(index) ?: return
    Text(
        text = line.text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 2,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
    )
}

private fun qualityLabel(quality: String): String = when (quality) {
    "standard" -> "标准音质"
    "higher" -> "较高音质"
    "exhigh" -> "极高音质"
    "lossless" -> "无损音质"
    else -> quality
}

@Composable
private fun MoreOptionsDialog(
    container: AppContainer,
    showMessage: (String) -> Unit,
    onDismiss: () -> Unit,
    onOpenPlaylistDetail: (String) -> Unit,
) {
    val song by container.player.currentSong.collectAsState()
    val current = song
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("更多选项") },
        text = {
            Column {
                MoreOption("下一首播放") {
                    if (current != null) container.player.enqueueNext(listOf(current))
                    onDismiss()
                }
                MoreOption("添加到播放队列") {
                    if (current != null) container.player.enqueue(listOf(current))
                    onDismiss()
                }
                MoreOption("重新加载歌词") {
                    container.player.reloadLyrics()
                    showMessage("正在重新获取歌词")
                    onDismiss()
                }
                MoreOption("重新播放当前歌曲") {
                    container.player.restartCurrent()
                    onDismiss()
                }
                if (current?.source == MediaSource.LOCAL) {
                    MoreOption("在文件管理器中显示") {
                        runCatching {
                            val path = current.filePath
                            if (!path.isNullOrBlank() && java.awt.Desktop.isDesktopSupported()) {
                                java.awt.Desktop.getDesktop().open(java.io.File(path).parentFile)
                            }
                        }
                        onDismiss()
                    }
                }
                MoreOption("复制歌曲信息") {
                    if (current != null) {
                        val text = "${current.displayName()} - ${current.artistText()}"
                        java.awt.Toolkit.getDefaultToolkit().systemClipboard
                            .setContents(java.awt.datatransfer.StringSelection(text), null)
                        showMessage("已复制歌曲信息")
                    }
                    onDismiss()
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun MoreOption(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    )
}
