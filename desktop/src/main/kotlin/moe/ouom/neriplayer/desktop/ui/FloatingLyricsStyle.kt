package moe.ouom.neriplayer.desktop.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import moe.ouom.neriplayer.desktop.core.AppSettings
import kotlin.math.roundToInt

/** 悬浮歌词可选颜色（与手机端一致的十种）。 */
enum class FloatingLyricColor(val label: String, val argb: Long) {
    WHITE("白色", 0xFFFFFFFF),
    BLACK("黑色", 0xFF000000),
    RED("红色", 0xFFF44336),
    ORANGE("橙色", 0xFFFF9800),
    YELLOW("黄色", 0xFFFFEB3B),
    GREEN("绿色", 0xFF4CAF50),
    CYAN("青色", 0xFF00BCD4),
    BLUE("蓝色", 0xFF2196F3),
    PURPLE("紫色", 0xFF9C27B0),
    PINK("粉色", 0xFFE91E63),
    ;

    val color: Color get() = Color(argb)

    companion object {
        fun of(name: String): FloatingLyricColor =
            entries.firstOrNull { it.name == name } ?: WHITE
    }
}

enum class FloatingLyricRenderStyle(val label: String) {
    SHADOW("阴影"),
    OUTLINE("描边"),
    ;

    companion object {
        fun of(name: String): FloatingLyricRenderStyle =
            entries.firstOrNull { it.name == name } ?: SHADOW
    }
}

enum class FloatingLyricAlignment(val label: String, val textAlign: TextAlign) {
    LEFT("居左", TextAlign.Start),
    CENTER("居中", TextAlign.Center),
    RIGHT("居右", TextAlign.End),
    ;

    companion object {
        fun of(name: String): FloatingLyricAlignment =
            entries.firstOrNull { it.name == name } ?: CENTER
    }
}

fun AppSettings.lyricTextColor(): Color = FloatingLyricColor.of(floatingLyricsTextColor).color

fun AppSettings.shadowColor(): Color = FloatingLyricColor.of(floatingLyricsShadowColor).color

fun AppSettings.outlineColor(): Color = FloatingLyricColor.of(floatingLyricsOutlineColor).color

fun AppSettings.renderStyle(): FloatingLyricRenderStyle =
    FloatingLyricRenderStyle.of(floatingLyricsRenderStyle)

fun AppSettings.alignment(): FloatingLyricAlignment = FloatingLyricAlignment.of(floatingLyricsAlignment)

/** 把屏幕比例位置换算成像素坐标（并保证窗口完整落在屏幕内）。 */
fun resolveFloatingPosition(
    screenWidth: Int,
    screenHeight: Int,
    windowWidth: Int,
    windowHeight: Int,
    ratioX: Float,
    ratioY: Float,
): Pair<Int, Int> {
    val maxX = (screenWidth - windowWidth).coerceAtLeast(0)
    val maxY = (screenHeight - windowHeight).coerceAtLeast(0)
    val x = ((screenWidth - windowWidth) * ratioX.coerceIn(0f, 1f)).roundToInt().coerceIn(0, maxX)
    val y = ((screenHeight - windowHeight) * ratioY.coerceIn(0f, 1f)).roundToInt().coerceIn(0, maxY)
    return x to y
}

/** 反过来：把像素坐标换算回屏幕比例，用于拖动后保存位置。 */
fun resolveFloatingRatio(
    screenWidth: Int,
    screenHeight: Int,
    windowWidth: Int,
    windowHeight: Int,
    x: Int,
    y: Int,
): Pair<Float, Float> {
    val spanX = (screenWidth - windowWidth).coerceAtLeast(1)
    val spanY = (screenHeight - windowHeight).coerceAtLeast(1)
    val ratioX = (x.toFloat() / spanX).coerceIn(0f, 1f)
    val ratioY = (y.toFloat() / spanY).coerceIn(0f, 1f)
    return ratioX to ratioY
}

/**
 * 拖动时的窗口位置计算。
 *
 * Compose 的拖动增量是「窗口内坐标」的增量，而窗口本身正在移动，两者会互相抵消，
 * 直接用增量累加会出现「越拖越慢 / 抖动 / 跟不上鼠标」。这里改成用抓取点跟随：
 * 指针的屏幕位置 = 窗口位置 + 窗口内坐标，窗口目标位置 = 指针屏幕位置 - 抓取偏移，
 * 因此窗口与鼠标的相对位置始终不变，拖动既精确又平滑。
 */
fun resolveDragPosition(
    windowX: Int,
    windowY: Int,
    pointerLocalX: Float,
    pointerLocalY: Float,
    grabOffsetX: Float,
    grabOffsetY: Float,
    screenWidth: Int,
    screenHeight: Int,
    windowWidth: Int,
    windowHeight: Int,
): Pair<Int, Int> {
    val pointerScreenX = windowX + pointerLocalX
    val pointerScreenY = windowY + pointerLocalY
    val targetX = (pointerScreenX - grabOffsetX).roundToInt()
    val targetY = (pointerScreenY - grabOffsetY).roundToInt()
    val maxX = (screenWidth - windowWidth).coerceAtLeast(0)
    val maxY = (screenHeight - windowHeight).coerceAtLeast(0)
    return targetX.coerceIn(0, maxX) to targetY.coerceIn(0, maxY)
}

/** 悬浮窗高度：主歌词 + 可选翻译 + 内边距。 */
fun floatingWindowHeightDp(settings: AppSettings): Float {
    val lineHeight = settings.floatingLyricsFontSize * 1.6f
    val translation = if (settings.floatingLyricsShowTranslation) {
        settings.floatingLyricsFontSize * 1.35f
    } else {
        0f
    }
    return (lineHeight + translation + 28f).coerceAtLeast(64f)
}
