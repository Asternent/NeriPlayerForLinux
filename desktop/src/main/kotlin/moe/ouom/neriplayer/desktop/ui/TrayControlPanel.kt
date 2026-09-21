package moe.ouom.neriplayer.desktop.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.SubtitlesOff
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.delay
import moe.ouom.neriplayer.desktop.core.AppContainer
import moe.ouom.neriplayer.desktop.core.AppSettings
import moe.ouom.neriplayer.desktop.core.PlaybackState
import moe.ouom.neriplayer.desktop.core.UiScale
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import kotlin.math.roundToInt

private const val PANEL_WIDTH = 340
private const val PANEL_HEIGHT = 436

@Composable
fun TrayControlPanel(
    container: AppContainer,
    visible: Boolean,
    onDismiss: () -> Unit,
    onShowWindow: () -> Unit,
) {
    if (!visible) return
    val song by container.player.currentSong.collectAsState()
    val state by container.player.state.collectAsState()
    val position by container.player.positionMs.collectAsState()
    val duration by container.player.durationMs.collectAsState()
    val settings by container.settings.state.collectAsState()
    val queue by container.player.queue.collectAsState()
    val index by container.player.currentIndex.collectAsState()
    val playing = state == PlaybackState.PLAYING

    // 界面缩放：面板尺寸与内容一起放大（Compose 在 Linux 上不读桌面缩放）
    val panelScale = UiScale.resolve(settings.uiScale)
    val panelDpFactor = panelScale / platformUiScale()
    val panelWidthPx = (PANEL_WIDTH * panelScale).roundToInt()
    val panelHeightPx = (PANEL_HEIGHT * panelScale).roundToInt()
    val windowState = rememberWindowState(
        size = DpSize(
            PANEL_WIDTH.dp * panelDpFactor,
            PANEL_HEIGHT.dp * panelDpFactor,
        ),
    )

    Window(
        visible = visible,
        state = windowState,
        undecorated = true,
        transparent = true,
        alwaysOnTop = true,
        resizable = false,
        focusable = true,
        title = "NeriPlayer 控制面板",
        onCloseRequest = onDismiss,
        onKeyEvent = { event ->
            val esc = event.type == KeyEventType.KeyDown && event.key == Key.Escape
            if (esc) onDismiss()
            esc
        },
    ) {
        ApplyUiScale(panelScale) {
            NeriThemeForSettings(container) {
                PanelContent(
                    container = container,
                    song = song,
                    playing = playing,
                    position = position,
                    duration = duration,
                    queueLabel = if (queue.isEmpty()) null else "${index + 1} / ${queue.size}",
                    settings = settings,
                    panelWidthPx = panelWidthPx,
                    panelHeightPx = panelHeightPx,
                    onDismiss = onDismiss,
                    onShowWindow = onShowWindow,
                )
            }
        }
    }
}

@Composable
private fun FrameWindowScope.PanelContent(
    container: AppContainer,
    song: moe.ouom.neriplayer.desktop.core.Song?,
    playing: Boolean,
    position: Long,
    duration: Long,
    queueLabel: String?,
    settings: AppSettings,
    panelWidthPx: Int,
    panelHeightPx: Int,
    onDismiss: () -> Unit,
    onShowWindow: () -> Unit,
) {
    // 弹出式行为
    LaunchedEffect(Unit) {
        // 1) 出现在鼠标附近（托盘图标通常在屏幕边缘），并夹在屏幕可视区域内。
        //    直接用窗口坐标定位，不依赖窗口状态里的 position：面板每次弹出都要贴着当前鼠标，
        //    否则会出现「面板在屏幕上半、鼠标在下半」这种点不到的情况。
        val pointer = runCatching { MouseInfo.getPointerInfo()?.location }.getOrNull()
        // 多屏时按「鼠标所在的那块屏幕」定位：托盘图标可能不在主屏上
        val bounds = runCatching {
            val environment = GraphicsEnvironment.getLocalGraphicsEnvironment()
            val screen = pointer?.let { location ->
                environment.screenDevices.firstOrNull { device ->
                    device.defaultConfiguration.bounds.contains(location)
                }
            }
            (screen ?: environment.defaultScreenDevice).defaultConfiguration.bounds
        }.getOrNull()
        if (bounds != null) {
            val maxX = (bounds.width - panelWidthPx - 8).coerceAtLeast(8)
            val maxY = (bounds.height - panelHeightPx - 8).coerceAtLeast(8)
            val centeredX = (pointer?.x ?: (bounds.width - panelWidthPx / 2)) - panelWidthPx / 2
            val aboveY = (pointer?.y ?: (bounds.height - panelHeightPx / 2)) - panelHeightPx - 16
            runCatching {
                window.setLocation(centeredX.coerceIn(8, maxX), aboveY.coerceIn(8, maxY))
            }
        }
        // 2) 主动要一次焦点：Esc 关闭、键盘操作需要它。
        //    但**不能依赖**它——应用在后台时，窗口管理器经常不给弹出窗口焦点
        //    （焦点跟随鼠标、或焦点窃取保护），此时窗口一「失焦」就把面板收起来，
        //    用户看到的就是「菜单弹出来了，但点按钮没反应」。
        runCatching {
            window.toFront()
            window.requestFocus()
            window.requestFocusInWindow()
        }

        // 3) 收起规则：只看鼠标，不看焦点。
        //    面板是贴着鼠标弹出的（托盘图标就在旁边），鼠标移开到别处一会儿就收起；
        //    这里刻意**不用**「失焦就收起」——应用在后台时窗口管理器经常不给弹出窗口焦点
        //    （焦点跟随鼠标 / 焦点窃取保护），那样面板会刚出现就自己消失，
        //    用户看到的就是「菜单弹出来了，但点按钮没反应」。
        delay(1500)
        var awaySince = 0L
        while (true) {
            delay(150)
            val pointer = runCatching { MouseInfo.getPointerInfo()?.location }.getOrNull() ?: continue
            // 留出较宽的邻域：面板弹在托盘图标上方，鼠标此时还在图标上（面板外十几像素）
            val area = java.awt.Rectangle(window.bounds).also { it.grow(200, 200) }
            if (area.contains(pointer)) {
                awaySince = 0L
                continue
            }
            if (awaySince == 0L) {
                awaySince = System.currentTimeMillis()
            } else if (System.currentTimeMillis() - awaySince > 1200) {
                onDismiss()
                break
            }
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            // 诊断用：打印面板窗口收到的指针事件（确认点击是否真的到达面板内容）
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                shape = RoundedCornerShape(20.dp),
            ),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp,
        shadowElevation = 16.dp,
    ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                PanelHeader(
                    song = song,
                    playing = playing,
                    queueLabel = queueLabel,
                    onDismiss = onDismiss,
                )

                Spacer(Modifier.height(12.dp))
                val progress by animateFloatAsState(
                    targetValue = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                    label = "panel-progress",
                )
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        text = formatDuration(position),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = formatDuration(duration),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { container.player.previous() }, modifier = Modifier.size(40.dp)) {
                        Icon(
                            Icons.Outlined.SkipPrevious,
                            contentDescription = "上一首",
                            modifier = Modifier.size(26.dp),
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    FilledIconButton(
                        onClick = { container.player.togglePlayPause() },
                        modifier = Modifier.size(52.dp),
                    ) {
                        Icon(
                            imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (playing) "暂停" else "播放",
                            modifier = Modifier.size(28.dp),
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    IconButton(onClick = { container.player.next() }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Outlined.SkipNext, contentDescription = "下一首", modifier = Modifier.size(26.dp))
                    }
                }

                Spacer(Modifier.height(2.dp))
                VolumeRow(container)

                Spacer(Modifier.height(4.dp))
                PanelDivider()
                Spacer(Modifier.height(4.dp))

                PanelAction(Icons.Outlined.Home, "显示主窗口") {
                    onShowWindow()
                    onDismiss()
                }
                PanelAction(
                    icon = if (settings.floatingLyricsEnabled) Icons.Outlined.SubtitlesOff else Icons.Outlined.Subtitles,
                    label = if (settings.floatingLyricsEnabled) "关闭悬浮歌词" else "开启悬浮歌词",
                    hint = if (settings.floatingLyricsEnabled) "桌面歌词已开启" else "桌面歌词已关闭",
                ) {
                    container.settings.update { it.copy(floatingLyricsEnabled = !it.floatingLyricsEnabled) }
                }
                PanelAction(Icons.Outlined.Download, "下载管理", hint = "已下载歌曲与队列") {
                    AppIntents.openDownloads?.invoke()
                    onShowWindow()
                    onDismiss()
                }
                PanelAction(Icons.Outlined.Settings, "设置", hint = "账号、主题与播放选项") {
                    AppIntents.openSettings?.invoke()
                    onShowWindow()
                    onDismiss()
                }

                Spacer(Modifier.weight(1f))
                PanelDivider()
                Spacer(Modifier.height(4.dp))
                PanelAction(Icons.Outlined.PowerSettingsNew, "退出 NeriPlayer", danger = true) {
                    AppIntents.quit?.invoke()
                }
            }
    }
}

@Composable
private fun VolumeRow(container: AppContainer) {
    val volume by container.player.volume.collectAsState()
    val muted = volume <= 0.001f
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = { container.player.setVolume(if (muted) 0.7f else 0f) },
            modifier = Modifier.size(28.dp),
        ) {
            Icon(
                imageVector = if (muted) {
                    Icons.AutoMirrored.Outlined.VolumeOff
                } else {
                    Icons.AutoMirrored.Outlined.VolumeUp
                },
                contentDescription = if (muted) "取消静音" else "静音",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(6.dp))
        Slider(
            value = volume.coerceIn(0f, 1f),
            onValueChange = { container.player.setVolume(it) },
            modifier = Modifier.weight(1f).height(24.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "${(volume.coerceIn(0f, 1f) * 100).toInt()}%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PanelHeader(
    song: moe.ouom.neriplayer.desktop.core.Song?,
    playing: Boolean,
    queueLabel: String?,
    onDismiss: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SongArtwork(song, size = 46.dp, shape = RoundedCornerShape(12.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = song?.displayName() ?: "未在播放",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(
                            if (playing) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline
                            }
                        )
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = song?.artistText() ?: "在媒体库中选择歌曲开始播放",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (queueLabel != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = queueLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = onDismiss, modifier = Modifier.size(30.dp)) {
            Icon(Icons.Outlined.Close, contentDescription = "收起", modifier = Modifier.size(17.dp))
        }
    }
}

@Composable
private fun PanelDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
    )
}

@Composable
private fun PanelAction(
    icon: ImageVector,
    label: String,
    hint: String? = null,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    val background by animateColorAsState(
        targetValue = when {
            hovered && danger -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
            hovered -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
            else -> Color.Transparent
        },
        label = "panel-action-bg",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (hint != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
