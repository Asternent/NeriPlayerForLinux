package moe.ouom.neriplayer.desktop.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 「定位到正在播放」控制器：滚动到目标行，并让该行短暂闪烁，便于一眼看到位置。
 */
class ListLocator {
    internal var scrollAction: ((Int) -> Unit)? = null
    internal var pulseAction: ((String) -> Unit)? = null

    /** 正在闪烁的歌曲 key。 */
    var pulsedSongKey by mutableStateOf<String?>(null)
        internal set

    /**
     * @param index 目标行下标，-1 表示当前歌曲不在该列表中
     * @param songKey 用于闪烁高亮的歌曲 key
     */
    fun locate(index: Int, songKey: String?) {
        if (index >= 0) scrollAction?.invoke(index)
        if (songKey.isNullOrBlank()) return
        pulsedSongKey = songKey
        pulseAction?.invoke(songKey)
    }
}

@Composable
fun rememberListLocator(
    listState: LazyListState,
    animated: Boolean = true,
): ListLocator {
    val scope = rememberCoroutineScope()
    val locator = remember { ListLocator() }
    locator.scrollAction = { index ->
        val target = index.coerceAtLeast(0)
        scope.launch {
            if (animated) listState.animateScrollToItem(target) else listState.scrollToItem(target)
        }
    }
    locator.pulseAction = { key ->
        scope.launch {
            delay(1300)
            if (locator.pulsedSongKey == key) locator.pulsedSongKey = null
        }
    }
    return locator
}
