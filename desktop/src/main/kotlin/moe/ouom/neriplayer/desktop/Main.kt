package moe.ouom.neriplayer.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import moe.ouom.neriplayer.desktop.core.AppContainer
import moe.ouom.neriplayer.desktop.ui.NeriApp
import moe.ouom.neriplayer.desktop.ui.FloatingLyricsWindow
import kotlinx.coroutines.delay

fun main() {
    val container = AppContainer()
    container.bootstrap()
    application {
        val settings by container.settings.state.collectAsState()
        var mainWindowFocused by remember { mutableStateOf(true) }
        // 允许通过环境变量覆盖初始窗口尺寸（便于截图与多屏使用）
        val sizeOverride = System.getenv("NERIPLAYER_WINDOW_SIZE").orEmpty()
        val windowSize = sizeOverride.split('x').mapNotNull { it.trim().toIntOrNull() }
            .takeIf { it.size == 2 }
            ?.let { DpSize(it[0].dp, it[1].dp) }
            ?: DpSize(1180.dp, 820.dp)
        val windowState = rememberWindowState(
            size = windowSize,
            position = WindowPosition(Alignment.Center),
        )
        Window(
            onCloseRequest = {
                container.player.persistQueueState()
                exitApplication()
            },
            state = windowState,
            title = "音理音理!! · NeriPlayer",
            onKeyEvent = { event -> handleShortcut(event, container) },
        ) {
            LaunchedEffect(Unit) {
                while (true) {
                    mainWindowFocused = window.isFocused
                    delay(350)
                }
            }
            NeriApp(container)
        }

        // 悬浮歌词：主窗口聚焦且开启「应用内隐藏」时临时隐藏
        if (settings.floatingLyricsEnabled && !(settings.floatingLyricsHideInApp && mainWindowFocused)) {
            FloatingLyricsWindow(
                container = container,
                settings = settings,
                onClose = { container.settings.update { it.copy(floatingLyricsEnabled = false) } },
            )
        }
    }
}

private fun handleShortcut(event: KeyEvent, container: AppContainer): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val player = container.player
    return when {
        event.key == Key.Spacebar -> {
            player.togglePlayPause()
            true
        }

        event.isCtrlPressed && event.key == Key.DirectionRight -> {
            player.next()
            true
        }

        event.isCtrlPressed && event.key == Key.DirectionLeft -> {
            player.previous()
            true
        }

        event.key == Key.DirectionRight -> {
            player.seekTo(player.positionMs.value + 5_000L)
            true
        }

        event.key == Key.DirectionLeft -> {
            player.seekTo(player.positionMs.value - 5_000L)
            true
        }

        event.key == Key.DirectionUp -> {
            player.setVolume(player.volume.value + 0.05f)
            true
        }

        event.key == Key.DirectionDown -> {
            player.setVolume(player.volume.value - 0.05f)
            true
        }

        event.isCtrlPressed && event.key == Key.L -> {
            val enabled = !container.settings.current.floatingLyricsEnabled
            container.settings.update { it.copy(floatingLyricsEnabled = enabled) }
            true
        }

        else -> false
    }
}
