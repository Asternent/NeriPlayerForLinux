package moe.ouom.neriplayer.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import kotlin.math.abs

/**
 * 窄窗口下的内容最大宽度。
 *
 * 界面本身是按手机版复刻的纵向布局，窗口被放大或最大化后如果继续整行铺开，
 * 卡片、开关、滑块会被拉到屏幕两端，观感很散。这里给主内容区一个阅读宽度上限并居中，
 * 窗口再怎么拉宽都保持紧凑（对应桌面端常见的「居中列」布局）。
 *
 * 窗口宽度达到 [AppWideBreakpoint] 后会切换到横屏（宽窗口）布局：内容铺满窗口，
 * 由各页面的多栏排版来消化横向空间，而不是继续拉长单栏控件。
 */
val AppContentMaxWidth = 1180.dp

/** 窄窗口下底部导航栏的收拢宽度。 */
val AppBottomBarMaxWidth = 720.dp

/** 横屏（宽窗口）布局的触发宽度：窗口内容宽度达到该值就切换到横屏排版。 */
val AppWideBreakpoint = 900.dp

/** 横屏布局下内容区左右留白。 */
val AppWideHorizontalPadding = 20.dp

/** 全屏歌词页在横屏布局下的最大正文宽度：太宽的歌词行很难扫读，这里保持一个阅读宽度。 */
val AppLyricsMaxWidth = 960.dp

/** 当前窗口使用的排版模式。 */
enum class AppLayoutMode { COMPACT, WIDE }

val LocalAppLayoutMode = staticCompositionLocalOf { AppLayoutMode.COMPACT }

/** 当前是否处于横屏（宽窗口）布局。 */
val isWideAppLayout: Boolean
    @Composable get() = LocalAppLayoutMode.current == AppLayoutMode.WIDE

/**
 * 把屏幕内容限制在 [AppContentMaxWidth] 内并水平居中（窄窗口）；
 * 横屏布局下改为铺满窗口，只留出边距。
 */
@Composable
fun AppContentContainer(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    if (isWideAppLayout) {
        Box(modifier.fillMaxSize().padding(horizontal = AppWideHorizontalPadding), content = content)
    } else {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Box(
                modifier = Modifier.widthIn(max = AppContentMaxWidth).fillMaxHeight(),
                content = content,
            )
        }
    }
}

/**
 * 两段内容：横屏布局下并排放置（各占一半宽度，充分利用横向空间），
 * 窄窗口下仍然纵向排列，保持手机版的观感。
 */
@Composable
fun ResponsivePair(
    modifier: Modifier = Modifier,
    spacing: Dp = 12.dp,
    first: @Composable () -> Unit,
    second: @Composable () -> Unit,
) {
    if (isWideAppLayout) {
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalAlignment = Alignment.Top,
        ) {
            Box(Modifier.weight(1f)) { first() }
            Box(Modifier.weight(1f)) { second() }
        }
    } else {
        Column(modifier.fillMaxWidth()) {
            first()
            second()
        }
    }
}

/**
 * 标签流式布局：标签自动换行并铺满可用宽度。
 *
 * 手机版里标签是「固定 6 个一行」的写法，窗口一宽右侧就会空出一大片；
 * 这里改成按剩余宽度自动折行，窄窗口不会溢出，横屏（宽窗口）一行能放下更多。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagFlow(
    tags: List<String>,
    onKeyword: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        tags.forEach { tag ->
            FilterChip(
                selected = false,
                onClick = { onKeyword(tag) },
                label = { Text(tag) },
            )
        }
    }
}

/**
 * 平台自身的缩放（Compose 用它把 dp 换算成物理像素）。
 * 在 Linux/X11 上通常是 1.0：桌面开缩放时也只有字体变，Compose 不跟随。
 */
fun platformUiScale(): Float {
    val value = runCatching {
        GraphicsEnvironment.getLocalGraphicsEnvironment()
            .defaultScreenDevice
            .defaultConfiguration
            .defaultTransform
            .scaleX
            .toFloat()
    }.getOrDefault(1f)
    return if (value > 0f) value else 1f
}

/**
 * 主窗口的起始尺寸：按界面缩放等比放大（同一个逻辑尺寸在高分屏上就是两倍像素），
 * 并且不超过屏幕可视区域，避免「跟随系统」在大缩放时把窗口撑出屏幕。
 */
fun mainWindowSize(
    baseWidthDp: Float,
    baseHeightDp: Float,
    uiScale: Float,
): DpSize {
    val physical = platformUiScale()
    var width = baseWidthDp * uiScale / physical
    var height = baseHeightDp * uiScale / physical
    val screen = runCatching { Toolkit.getDefaultToolkit().screenSize }.getOrNull()
    if (screen != null && screen.width > 0 && screen.height > 0) {
        width = width.coerceAtMost(screen.width * 0.94f / physical)
        height = height.coerceAtMost(screen.height * 0.92f / physical)
    }
    return DpSize(width.dp, height.dp)
}

/**
 * 按 [scale] 缩放界面（字号、控件、间距一起放大）。
 *
 * Compose Desktop 在 Linux 上不会读桌面的 Xft.dpi，所以这里把最终密度直接设为 [scale]；
 * 若平台自己已经缩放过（[LocalDensity] 已经是 2.0），换算后不会重复放大。
 *
 * 注意：sp 的像素值 = sp × fontScale × density，字号本身已经跟着 density 一起放大，
 * 这里必须保留原 fontScale（否则文字会被放大两次，字号相对控件大一倍）。
 */
@Composable
fun ApplyUiScale(scale: Float, content: @Composable () -> Unit) {
    val base = LocalDensity.current
    if (scale <= 0f || abs(base.density - scale) < 0.01f) {
        content()
    } else {
        val factor = scale / base.density
        CompositionLocalProvider(
            LocalDensity provides Density(
                density = base.density * factor,
                fontScale = base.fontScale,
            ),
            content = content,
        )
    }
}
