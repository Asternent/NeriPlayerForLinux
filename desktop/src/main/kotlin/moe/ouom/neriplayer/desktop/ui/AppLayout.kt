package moe.ouom.neriplayer.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import kotlin.math.abs

/**
 * 内容最大宽度。
 *
 * 界面本身是按手机版复刻的纵向布局，窗口被放大或最大化后如果继续整行铺开，
 * 卡片、开关、滑块会被拉到屏幕两端，观感很散。这里给主内容区一个阅读宽度上限并居中，
 * 窗口再怎么拉宽都保持紧凑（对应桌面端常见的「居中列」布局）。
 */
val AppContentMaxWidth = 1180.dp

/** 底部导航栏在宽窗口下的收拢宽度。 */
val AppBottomBarMaxWidth = 720.dp

/** 把屏幕内容限制在 [AppContentMaxWidth] 内并水平居中。 */
@Composable
fun AppContentContainer(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(
            modifier = Modifier.widthIn(max = AppContentMaxWidth).fillMaxHeight(),
            content = content,
        )
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
                fontScale = base.fontScale * factor,
            ),
            content = content,
        )
    }
}
