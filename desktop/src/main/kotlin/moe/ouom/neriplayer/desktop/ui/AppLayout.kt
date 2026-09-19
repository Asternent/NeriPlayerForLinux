package moe.ouom.neriplayer.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

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
