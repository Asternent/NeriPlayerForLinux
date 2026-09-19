package moe.ouom.neriplayer.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Lyrics
import androidx.compose.material.icons.outlined.QueueMusic
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.ouom.neriplayer.desktop.core.LyricLine
import moe.ouom.neriplayer.desktop.core.Song
import kotlin.math.roundToInt

@Composable
fun NeriBottomBar(
    selected: MainTab,
    onSelect: (MainTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationBar(
        modifier = modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 0.dp,
    ) {
        MainTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = selected == tab,
                onClick = { onSelect(tab) },
                icon = { Icon(tab.icon, contentDescription = tab.label) },
                label = { Text(tab.label, maxLines = 1) },
                alwaysShowLabel = true,
            )
        }
    }
}

@Composable
fun MiniPlayer(
    song: Song?,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (song == null) return
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
    ) {
        Column(Modifier.clickable(onClick = onOpen)) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SongArtwork(song, size = 42.dp, shape = RoundedCornerShape(10.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = song.displayName(),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.basicMarqueeIfLong(),
                    )
                    Text(
                        text = song.artistText(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.basicMarqueeIfLong(),
                    )
                }
                IconButton(onClick = onPrevious, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Outlined.SkipPrevious, contentDescription = "上一首", modifier = Modifier.size(22.dp))
                }
                FilledIconButton(onClick = onToggle, modifier = Modifier.size(38.dp)) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "暂停" else "播放",
                        modifier = Modifier.size(22.dp),
                    )
                }
                IconButton(onClick = onNext, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Outlined.SkipNext, contentDescription = "下一首", modifier = Modifier.size(22.dp))
                }
            }
            if (durationMs > 0L) {
                LinearProgressIndicator(
                    progress = { (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
        }
    }
}

@Composable
fun Modifier.basicMarqueeIfLong(): Modifier =
    this.basicMarquee()

@Composable
fun PlayerProgressBar(
    positionMs: Long,
    durationMs: Long,
    enabled: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val safeDuration = durationMs.coerceAtLeast(1L)
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val displayValue = if (dragging) dragValue else (positionMs.toFloat() / safeDuration).coerceIn(0f, 1f)
    Column(modifier) {
        Slider(
            value = displayValue,
            onValueChange = { value ->
                dragging = true
                dragValue = value
            },
            onValueChangeFinished = {
                dragging = false
                onSeek((dragValue * safeDuration).toLong())
            },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            val shownPosition = if (dragging) (dragValue * safeDuration).toLong() else positionMs
            Text(
                text = formatDuration(shownPosition),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = formatDuration(durationMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun LyricsPane(
    lines: List<LyricLine>,
    currentIndex: Int,
    loading: Boolean,
    fontScale: Float,
    showTranslation: Boolean,
    onSeekLine: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(currentIndex, lines.size) {
        if (currentIndex in lines.indices) {
            listState.animateScrollToItem(index = (currentIndex - 2).coerceAtLeast(0))
        }
    }
    Box(modifier.fillMaxSize()) {
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
            }

            lines.isEmpty() -> EmptyState(
                title = "暂无歌词",
                hint = "本地歌曲可放置同名 .lrc 文件，在线歌曲会自动获取歌词",
                icon = Icons.Outlined.Lyrics,
                modifier = Modifier.align(Alignment.Center),
            )

            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 24.dp, horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                itemsIndexed(lines) { index, line ->
                    val active = index == currentIndex
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onSeekLine(line.timeMs) }
                            .padding(vertical = 2.dp),
                    ) {
                        Text(
                            text = line.text.ifBlank { "♪" },
                            fontSize = (20f * fontScale).sp,
                            lineHeight = (28f * fontScale).sp,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                            color = if (active) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            textAlign = TextAlign.Start,
                        )
                        if (showTranslation && !line.translation.isNullOrBlank()) {
                            Text(
                                text = line.translation,
                                fontSize = (15f * fontScale).sp,
                                lineHeight = (21f * fontScale).sp,
                                color = if (active) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun OverlayPanel(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(onClick = onClose),
    ) {
        Surface(
            modifier = modifier
                .fillMaxHeight()
                .width(420.dp)
                .padding(12.dp)
                .clickable(enabled = false) {},
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onClose) {
                        Icon(Icons.Outlined.Close, contentDescription = "关闭")
                    }
                }
                content()
            }
        }
    }
}

@Composable
fun QueuePanel(
    queue: List<Song>,
    currentIndex: Int,
    onClose: () -> Unit,
    onPlayIndex: (Int) -> Unit,
    onRemoveIndex: (Int) -> Unit,
    onMoveIndex: (Int, Int) -> Unit,
    onClear: () -> Unit,
) {
    OverlayPanel(title = "播放队列（${queue.size} 首）", onClose = onClose) {
        if (queue.isEmpty()) {
            EmptyState(
                title = "播放队列是空的",
                hint = "在媒体库或探索中选择歌曲后会自动加入队列",
                icon = Icons.Outlined.QueueMusic,
                modifier = Modifier.weight(1f),
            )
        } else {
            // 与手机端一致：打开队列时自动定位到正在播放，切换歌曲后也会跟随
            val listState = rememberLazyListState(
                initialFirstVisibleItemIndex = currentIndex.coerceIn(0, queue.lastIndex)
            )
            val locator = rememberListLocator(listState)
            LaunchedEffect(currentIndex, queue.size) {
                if (currentIndex in queue.indices) {
                    listState.animateScrollToItem(currentIndex)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (currentIndex in queue.indices) "正在播放：第 ${currentIndex + 1} 首" else "未在播放",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { locator.locate(currentIndex, queue.getOrNull(currentIndex)?.key) },
                ) { Text("定位到正在播放") }
            }
            Box(Modifier.weight(1f)) {
                LazyColumn(
                    state = listState,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 12.dp),
                ) {
                    itemsIndexed(queue, key = { index, song -> "$index:${song.key}" }) { index, song ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (locator.pulsedSongKey == song.key) {
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                                    } else if (index == currentIndex) {
                                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                                    } else {
                                        Color.Transparent
                                    }
                                )
                                .clickable { onPlayIndex(index) }
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.width(26.dp), contentAlignment = Alignment.Center) {
                                if (index == currentIndex) {
                                    PlayingIndicator(isPlaying = true, modifier = Modifier.size(14.dp))
                                } else {
                                    Text(
                                        text = (index + 1).toString(),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = song.displayName(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = if (index == currentIndex) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                )
                                Text(
                                    text = song.artistText(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(
                                onClick = { if (index > 0) onMoveIndex(index, index - 1) },
                                modifier = Modifier.size(30.dp),
                            ) {
                                Icon(Icons.Outlined.ArrowUpward, contentDescription = "上移", modifier = Modifier.size(16.dp))
                            }
                            IconButton(
                                onClick = { if (index < queue.lastIndex) onMoveIndex(index, index + 1) },
                                modifier = Modifier.size(30.dp),
                            ) {
                                Icon(Icons.Outlined.ArrowDownward, contentDescription = "下移", modifier = Modifier.size(16.dp))
                            }
                            IconButton(onClick = { onRemoveIndex(index) }, modifier = Modifier.size(30.dp)) {
                                Icon(Icons.Outlined.Delete, contentDescription = "移除", modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onClear) { Text("清空队列") }
            }
        }
    }
}

@Composable
fun VolumePanel(
    volume: Float,
    onVolume: (Float) -> Unit,
    onClose: () -> Unit,
) {
    OverlayPanel(title = "音量", onClose = onClose) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.VolumeUp, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Slider(
                    value = volume,
                    onValueChange = onVolume,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                Text("${(volume * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
fun SleepTimerPanel(
    remainingSeconds: Long,
    stopAfterCurrent: Boolean,
    defaultMinutes: Int,
    onStart: (Int) -> Unit,
    onStopAfterCurrent: () -> Unit,
    onCancelTimer: () -> Unit,
    onClose: () -> Unit,
) {
    OverlayPanel(title = "睡眠定时器", onClose = onClose) {
        Column(Modifier.padding(20.dp)) {
            if (remainingSeconds > 0) {
                Text(
                    text = "剩余 %d:%02d".format(remainingSeconds / 60, remainingSeconds % 60),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = onCancelTimer, modifier = Modifier.fillMaxWidth()) { Text("取消定时器") }
            } else if (stopAfterCurrent) {
                Text("播完当前歌曲后停止", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onCancelTimer, modifier = Modifier.fillMaxWidth()) { Text("取消定时器") }
            } else {
                Text("倒计时结束后暂停播放", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15, 30, 60, 90).forEach { minutes ->
                        Button(onClick = { onStart(minutes) }) { Text("$minutes 分") }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("默认时长：$defaultMinutes 分钟", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(18.dp))
            TextButton(onClick = onStopAfterCurrent, modifier = Modifier.fillMaxWidth()) {
                Text("播放完当前歌曲后停止")
            }
        }
    }
}

@Composable
fun PlaybackEffectsPanel(
    speed: Float,
    pitch: Float,
    loudness: Boolean,
    equalizerEnabled: Boolean,
    equalizerPreset: String,
    equalizerBands: List<Float>,
    supportsEffects: Boolean,
    onSpeed: (Float) -> Unit,
    onPitch: (Float) -> Unit,
    onLoudness: (Boolean) -> Unit,
    onPreset: (String, List<Float>) -> Unit,
    onBand: (Int, Float) -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
) {
    OverlayPanel(title = "音效与倍速", onClose = onClose) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            if (!supportsEffects) {
                ErrorCard(message = "当前环境未安装 ffmpeg，倍速、变调、响度与均衡器不可用。安装 ffmpeg 后重启应用即可启用。")
                Spacer(Modifier.height(12.dp))
            }
            Text("播放速度", style = MaterialTheme.typography.titleSmall)
            Slider(
                value = speed,
                onValueChange = onSpeed,
                valueRange = 0.5f..3f,
                enabled = supportsEffects,
            )
            Text("%.2fx".format(speed), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(14.dp))
            Text("音调", style = MaterialTheme.typography.titleSmall)
            Slider(
                value = pitch,
                onValueChange = onPitch,
                valueRange = -12f..12f,
                enabled = supportsEffects,
            )
            Text("%.1f 半音".format(pitch), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("响度增强", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "补一点厚度和能量，但不会改变音高",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                androidx.compose.material3.Switch(
                    checked = loudness,
                    onCheckedChange = onLoudness,
                    enabled = supportsEffects,
                )
            }
            Spacer(Modifier.height(18.dp))
            Text("均衡器", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                EqualizerPresets.Preset.entries.take(4).forEach { preset ->
                    val selected = equalizerPreset == preset.label
                    androidx.compose.material3.FilterChip(
                        selected = selected,
                        onClick = { onPreset(preset.label, preset.bands) },
                        enabled = supportsEffects,
                        label = { Text(preset.label) },
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                EqualizerPresets.Preset.entries.drop(4).forEach { preset ->
                    val selected = equalizerPreset == preset.label
                    androidx.compose.material3.FilterChip(
                        selected = selected,
                        onClick = { onPreset(preset.label, preset.bands) },
                        enabled = supportsEffects,
                        label = { Text(preset.label) },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("频段微调（dB）", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            equalizerBands.forEachIndexed { index, gain ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = EqualizerPresets.FREQUENCIES.getOrElse(index) { "" },
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.width(58.dp),
                    )
                    Slider(
                        value = gain,
                        onValueChange = { onBand(index, it) },
                        valueRange = -12f..12f,
                        enabled = supportsEffects && equalizerEnabled,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "%+.1f".format(gain),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.width(46.dp),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onReset) { Text("重置音效与倍速") }
            Spacer(Modifier.height(20.dp))
        }
    }
}

object EqualizerPresets {
    val FREQUENCIES = listOf("31Hz", "62Hz", "125Hz", "250Hz", "500Hz", "1kHz", "2kHz", "4kHz", "8kHz", "16kHz")

    enum class Preset(val label: String, val bands: List<Float>) {
        FLAT("平直", List(10) { 0f }),
        ACOUSTIC("原声", listOf(3f, 2f, 1f, 0.5f, 0.5f, 1.5f, 2f, 2.5f, 2f, 1f)),
        BASS_BOOST("低音增强", listOf(6f, 5f, 4f, 2f, 0f, -1f, -2f, -1f, 0f, 0f)),
        TREBLE_BOOST("高频增强", listOf(0f, 0f, -1f, -1f, 0f, 1f, 3f, 4f, 5f, 5f)),
        VOCAL("人声增强", listOf(-2f, -1f, 0f, 2f, 4f, 4f, 3f, 1.5f, 0f, -1f)),
        ROCK("摇滚", listOf(5f, 4f, 2f, 0f, -1f, 0f, 2f, 4f, 4f, 3f)),
        JAZZ("爵士", listOf(3f, 2f, 1f, 2f, -1f, -1f, 0f, 1f, 2f, 3f)),
        CLASSICAL("古典", listOf(4f, 3f, 2f, 0f, 0f, 0f, -1f, 0f, 2f, 3f)),
    }
}

@Composable
fun CreatePlaylistDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建歌单") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("歌单名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank(),
                onClick = {
                    onConfirm(name.trim())
                    onDismiss()
                },
            ) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
