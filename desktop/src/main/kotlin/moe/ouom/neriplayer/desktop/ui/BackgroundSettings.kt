package moe.ouom.neriplayer.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import moe.ouom.neriplayer.desktop.core.AppContainer

/** 设置页「后台与系统控制」分组：托盘常驻、后台播放与系统媒体控制。 */
@Composable
fun BackgroundSettingsSection(
    container: AppContainer,
    showMessage: (String) -> Unit,
) {
    val settings by container.settings.state.collectAsState()
    val trayAvailable = remember { isTrayAvailable() }
    val mprisRunning by produceState(initialValue = container.mpris.running) {
        while (true) {
            value = container.mpris.running
            delay(2_000)
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("后台与系统控制", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                text = "关闭窗口后继续在后台播放，并可从系统托盘与桌面媒体控件操作播放",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(10.dp))
            SettingSwitchRow(
                title = "关闭窗口时最小化到托盘",
                description = if (trayAvailable) {
                    "关闭窗口只是隐藏，音乐继续播放；从托盘菜单可重新打开或退出"
                } else {
                    "当前桌面环境没有系统托盘，关闭窗口将直接退出"
                },
                checked = settings.closeToTray && trayAvailable,
                enabled = trayAvailable,
                onCheckedChange = { value -> container.settings.update { it.copy(closeToTray = value) } },
            )
            SettingSwitchRow(
                title = "最小化时隐藏到托盘",
                description = "把窗口最小化时收进托盘，继续后台播放",
                checked = settings.minimizeToTray && trayAvailable,
                enabled = trayAvailable,
                onCheckedChange = { value -> container.settings.update { it.copy(minimizeToTray = value) } },
            )
            SettingSwitchRow(
                title = "歌曲变化时发送系统通知",
                description = "仅当窗口隐藏 / 最小化时提示，避免打扰",
                checked = settings.notifyOnSongChange,
                onCheckedChange = { value -> container.settings.update { it.copy(notifyOnSongChange = value) } },
            )
            SettingSwitchRow(
                title = "启用系统媒体控制（MPRIS）",
                description = if (mprisRunning) {
                    "已注册 org.mpris.MediaPlayer2.neriplayer，可用媒体键、桌面媒体组件或 playerctl 控制"
                } else {
                    "未注册（需要 D-Bus 会话总线）"
                },
                checked = settings.mprisEnabled,
                onCheckedChange = { value ->
                    container.settings.update { it.copy(mprisEnabled = value) }
                    showMessage(if (value) "正在启用系统媒体控制…" else "已关闭系统媒体控制")
                },
            )

            Spacer(Modifier.height(6.dp))
            Text(
                text = buildString {
                    append("系统托盘：")
                    append(if (trayAvailable) "可用" else "不可用（无托盘面板的极简桌面）")
                    append("　·　MPRIS：")
                    append(if (mprisRunning) "已注册" else "未注册")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "提示：托盘图标点击可显示窗口，右键菜单包含上一首 / 播放暂停 / 下一首 / 悬浮歌词 / 退出；" +
                    "MPRIS 让键盘媒体键与 GNOME / KDE 媒体组件直接控制本应用。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}
