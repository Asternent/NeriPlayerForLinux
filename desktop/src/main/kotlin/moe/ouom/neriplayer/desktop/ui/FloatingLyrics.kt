package moe.ouom.neriplayer.desktop.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import moe.ouom.neriplayer.desktop.core.AppContainer
import moe.ouom.neriplayer.desktop.core.AppSettings
import moe.ouom.neriplayer.desktop.core.PlaybackState
import moe.ouom.neriplayer.desktop.core.currentLyricIndex
import java.awt.GraphicsEnvironment
import kotlin.math.roundToInt

/**
 * 悬浮歌词窗口：无边框 + 置顶 + 透明背景，拖动即可调整位置。
 * 对应手机端的「悬浮歌词」覆盖层，样式设置与手机端保持一致。
 */
@Composable
fun FloatingLyricsWindow(
    container: AppContainer,
    settings: AppSettings,
    onClose: () -> Unit,
) {
    val song by container.player.currentSong.collectAsState()
    val lyrics by container.player.lyrics.collectAsState()
    val positionMs by container.player.positionMs.collectAsState()
    val playbackState by container.player.state.collectAsState()

    val screenBounds = remember {
        runCatching {
            GraphicsEnvironment.getLocalGraphicsEnvironment()
                .defaultScreenDevice
                .defaultConfiguration
                .bounds
        }.getOrNull()
    }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val widthDp = settings.floatingLyricsMaxWidthDp.dp
    val heightDp = floatingWindowHeightDp(settings).dp

    // 初始位置：按屏幕比例换算，避免窗口先出现在默认位置再跳动
    val initialPosition = remember {
        val bounds = screenBounds
        if (bounds == null) {
            WindowPosition(Alignment.Center)
        } else {
            val (x, y) = resolveFloatingPosition(
                screenWidth = bounds.width,
                screenHeight = bounds.height,
                windowWidth = (widthDp.value * density.density).roundToInt(),
                windowHeight = (heightDp.value * density.density).roundToInt(),
                ratioX = settings.floatingLyricsPositionX,
                ratioY = settings.floatingLyricsPositionY,
            )
            WindowPosition(x.dp, y.dp)
        }
    }

    val windowState = rememberWindowState(
        size = DpSize(widthDp, heightDp),
        position = initialPosition,
    )

    val densityValue = density.density
    // 位置：屏幕比例 → 像素（跟随设置即时更新，拖动结束后回写比例）
    LaunchedEffect(
        settings.floatingLyricsPositionX,
        settings.floatingLyricsPositionY,
        settings.floatingLyricsMaxWidthDp,
        settings.floatingLyricsFontSize,
        settings.floatingLyricsShowTranslation,
        screenBounds,
    ) {
        val bounds = screenBounds ?: return@LaunchedEffect
        val widthPx = with(density) { widthDp.roundToPx() }
        val heightPx = with(density) { heightDp.roundToPx() }
        val (x, y) = resolveFloatingPosition(
            screenWidth = bounds.width,
            screenHeight = bounds.height,
            windowWidth = widthPx,
            windowHeight = heightPx,
            ratioX = settings.floatingLyricsPositionX,
            ratioY = settings.floatingLyricsPositionY,
        )
        windowState.size = DpSize(widthDp, heightDp)
        windowState.position = WindowPosition(x.dp, y.dp)
    }

    val currentLineIndex = remember(lyrics, positionMs) { currentLyricIndex(lyrics.lines, positionMs) }
    val currentLine = lyrics.lines.getOrNull(currentLineIndex)
    val lyricText = currentLine?.text?.takeIf { it.isNotBlank() }
        ?: when {
            song == null -> "未在播放"
            lyrics.lines.isEmpty() -> "暂无歌词"
            else -> "♪"
        }
    val translationText = currentLine?.translation?.takeIf { it.isNotBlank() }
    val alignment = settings.alignment()
    val isPlaying = playbackState == PlaybackState.PLAYING

    Window(
        state = windowState,
        undecorated = true,
        transparent = true,
        alwaysOnTop = true,
        resizable = false,
        focusable = false,
        title = "NeriPlayer 悬浮歌词",
        onCloseRequest = onClose,
    ) {
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(600)
            println(
                "[floating] 实际窗口位置 x=${window.x} y=${window.y} 尺寸=${window.width}x${window.height} " +
                    "屏幕=${screenBounds?.width}x${screenBounds?.height} density=$densityValue " +
                    "比例=(${settings.floatingLyricsPositionX}, ${settings.floatingLyricsPositionY})"
            )
        }
        val interaction = remember { MutableInteractionSource() }
        val hovered by interaction.collectIsHoveredAsState()
        val dragModifier = if (settings.floatingLyricsLocked) {
            Modifier
        } else {
            Modifier.pointerInput(settings.floatingLyricsLocked) {
                detectDragGestures(
                    onDragEnd = {
                        val bounds = screenBounds ?: return@detectDragGestures
                        val (ratioX, ratioY) = resolveFloatingRatio(
                            screenWidth = bounds.width,
                            screenHeight = bounds.height,
                            windowWidth = window.width,
                            windowHeight = window.height,
                            x = window.x,
                            y = window.y,
                        )
                        container.settings.update {
                            it.copy(
                                floatingLyricsPositionX = ratioX,
                                floatingLyricsPositionY = ratioY,
                            )
                        }
                    },
                ) { change, dragAmount ->
                    change.consume()
                    window.setLocation(
                        window.x + dragAmount.x.roundToInt(),
                        window.y + dragAmount.y.roundToInt(),
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(14.dp))
                .background(
                    FloatingLyricColor.of(settings.floatingLyricsBackgroundColor).color
                        .copy(alpha = settings.floatingLyricsBackgroundAlpha)
                )
                .then(dragModifier)
                .hoverable(interaction)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = when (alignment) {
                    FloatingLyricAlignment.LEFT -> Alignment.Start
                    FloatingLyricAlignment.CENTER -> Alignment.CenterHorizontally
                    FloatingLyricAlignment.RIGHT -> Alignment.End
                },
            ) {
                FloatingLyricLine(
                    text = lyricText,
                    settings = settings,
                    fontSizeSp = settings.floatingLyricsFontSize,
                    alpha = settings.floatingLyricsLyricAlpha,
                    bold = true,
                    animateReveal = settings.floatingLyricsRevealAnimation,
                )
                if (settings.floatingLyricsShowTranslation && !translationText.isNullOrBlank()) {
                    Spacer(Modifier.size(2.dp))
                    FloatingLyricLine(
                        text = translationText,
                        settings = settings,
                        fontSizeSp = settings.floatingLyricsFontSize * 0.78f,
                        alpha = settings.floatingLyricsTranslationAlpha,
                        bold = false,
                        animateReveal = settings.floatingLyricsRevealAnimation,
                    )
                }
            }

            if (hovered) {
                Row(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black.copy(alpha = 0.55f)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FloatingControl(Icons.Outlined.SkipPrevious, "上一首") { container.player.previous() }
                    FloatingControl(
                        if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        if (isPlaying) "暂停" else "播放",
                    ) { container.player.togglePlayPause() }
                    FloatingControl(Icons.Outlined.SkipNext, "下一首") { container.player.next() }
                    FloatingControl(Icons.Outlined.Close, "关闭悬浮歌词", tint = Color(0xFFFF8A80)) {
                        container.settings.update { it.copy(floatingLyricsEnabled = false) }
                    }
                }
            }
        }
    }
}

@Composable
private fun FloatingControl(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    tint: Color = Color.White,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(30.dp)) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(17.dp))
    }
}

/** 单行歌词：按设置渲染阴影或描边，并处理超长歌词的滚动。 */
@Composable
internal fun FloatingLyricLine(
    text: String,
    settings: AppSettings,
    fontSizeSp: Float,
    alpha: Float,
    bold: Boolean,
    animateReveal: Boolean,
) {
    val reveal = remember { Animatable(if (animateReveal) 0f else 1f) }
    LaunchedEffect(text, animateReveal) {
        if (animateReveal) {
            reveal.snapTo(0f)
            reveal.animateTo(1f, tween(durationMillis = 260))
        } else {
            reveal.snapTo(1f)
        }
    }
    val effectiveAlpha = (alpha * reveal.value).coerceIn(0f, 1f)
    val textColor = settings.lyricTextColor().copy(alpha = effectiveAlpha)
    val align = settings.alignment().textAlign
    val baseStyle = TextStyle(
        fontSize = fontSizeSp.sp,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        textAlign = align,
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1500),
        contentAlignment = when (align) {
            TextAlign.Start -> Alignment.CenterStart
            TextAlign.End -> Alignment.CenterEnd
            else -> Alignment.Center
        },
    ) {
        when (settings.renderStyle()) {
            FloatingLyricRenderStyle.SHADOW -> {
                Text(
                    text = text,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Visible,
                    style = baseStyle.copy(
                        color = textColor,
                        shadow = Shadow(
                            color = settings.shadowColor().copy(alpha = effectiveAlpha * 0.9f),
                            offset = Offset(0f, 1.5f),
                            blurRadius = settings.floatingLyricsShadowBlur,
                        ),
                    ),
                )
            }

            FloatingLyricRenderStyle.OUTLINE -> {
                Text(
                    text = text,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Visible,
                    style = baseStyle.copy(
                        color = settings.outlineColor().copy(alpha = effectiveAlpha),
                        drawStyle = Stroke(width = settings.floatingLyricsOutlineWidth),
                    ),
                )
                Text(
                    text = text,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Visible,
                    style = baseStyle.copy(color = textColor),
                )
            }
        }
    }
}

/** 设置页里的悬浮效果预览（与真实悬浮窗使用同一套渲染）。 */
@Composable
fun FloatingLyricsPreview(settings: AppSettings) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                FloatingLyricColor.of(settings.floatingLyricsBackgroundColor).color
                    .copy(alpha = settings.floatingLyricsBackgroundAlpha)
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = when (settings.alignment()) {
                FloatingLyricAlignment.LEFT -> Alignment.Start
                FloatingLyricAlignment.CENTER -> Alignment.CenterHorizontally
                FloatingLyricAlignment.RIGHT -> Alignment.End
            },
        ) {
            FloatingLyricLine(
                text = "这里会显示正在播放的歌词",
                settings = settings,
                fontSizeSp = settings.floatingLyricsFontSize * 0.7f,
                alpha = settings.floatingLyricsLyricAlpha,
                bold = true,
                animateReveal = false,
            )
            if (settings.floatingLyricsShowTranslation) {
                FloatingLyricLine(
                    text = "翻译会更轻地跟在下面",
                    settings = settings,
                    fontSizeSp = settings.floatingLyricsFontSize * 0.55f,
                    alpha = settings.floatingLyricsTranslationAlpha,
                    bold = false,
                    animateReveal = false,
                )
            }
        }
    }
}
