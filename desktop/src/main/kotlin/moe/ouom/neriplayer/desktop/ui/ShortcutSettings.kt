package moe.ouom.neriplayer.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.desktop.core.AppContainer
import moe.ouom.neriplayer.desktop.core.ShortcutAction
import moe.ouom.neriplayer.desktop.core.ShortcutBinding
import moe.ouom.neriplayer.desktop.core.ShortcutSettings

/**
 * 设置页「快捷键」分组：分组列出可绑定动作，点按键框后按下新组合键即可改键。
 * 支持程序内快捷键与全局快捷键（全局走 xdg-desktop-portal，需桌面授权）。
 */
@Composable
fun ShortcutSettingsSection(
    container: AppContainer,
    showMessage: (String) -> Unit,
) {
    val settings by container.settings.state.collectAsState()
    val shortcuts = settings.shortcuts
    // 正在录制的动作（同时只能录一个）
    var capturing by remember { mutableStateOf<ShortcutAction?>(null) }

    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(16.dp)) {
            Column {
                Text("快捷键", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "点击右侧按键框后按下新组合键即可改键；按 Esc 取消录制",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))

            // 全局快捷键开关
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("启用全局快捷键", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "应用在后台时也能触发。首次启用时桌面会弹窗请求授权，最终按键以系统确认为准",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = shortcuts.globalEnabled,
                    onCheckedChange = { enabled ->
                        container.settings.update { it.copy(shortcuts = it.shortcuts.copy(globalEnabled = enabled)) }
                        showMessage(if (enabled) "已请求注册全局快捷键" else "已关闭全局快捷键")
                    },
                )
            }

            // 分组列出动作
            ShortcutAction.entries.groupBy { it.category }.forEach { (category, actions) ->
                Spacer(Modifier.height(12.dp))
                Text(
                    text = category,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                actions.forEach { action ->
                    ShortcutRow(
                        action = action,
                        binding = shortcuts.bindingFor(action),
                        conflict = shortcuts.conflictFor(action, shortcuts.bindingFor(action)),
                        capturing = capturing == action,
                        globalEnabled = shortcuts.globalEnabled,
                        onStartCapture = { capturing = action },
                        onCancelCapture = { capturing = null },
                        onCaptured = { binding ->
                            capturing = null
                            val conflict = shortcuts.conflictFor(action, binding)
                            if (conflict != null) {
                                showMessage("该按键已被「${conflict.label}」占用")
                            } else {
                                container.settings.update {
                                    it.copy(shortcuts = it.shortcuts.withBinding(action, binding))
                                }
                            }
                        },
                        onReset = {
                            container.settings.update {
                                it.copy(shortcuts = it.shortcuts.clearOverride(action))
                            }
                        },
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = {
                    container.settings.update { it.copy(shortcuts = ShortcutSettings()) }
                    showMessage("已恢复默认快捷键")
                }) { Text("恢复默认") }
            }
        }
    }
}

@Composable
private fun ShortcutRow(
    action: ShortcutAction,
    binding: ShortcutBinding,
    conflict: ShortcutAction?,
    capturing: Boolean,
    globalEnabled: Boolean,
    onStartCapture: () -> Unit,
    onCancelCapture: () -> Unit,
    onCaptured: (ShortcutBinding) -> Unit,
    onReset: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(action.label, style = MaterialTheme.typography.bodyMedium)
            val hint = buildString {
                if (!action.globalSupported) append("仅程序内生效")
                if (conflict != null) {
                    if (isNotEmpty()) append(" · ")
                    append("与「${conflict.label}」冲突")
                }
            }
            if (hint.isNotEmpty()) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (conflict != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        // 按键录制框：获得焦点后按下按键即写入
        Box(
            modifier = Modifier
                .width(150.dp)
                .height(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(
                    if (capturing) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    }
                )
                .border(
                    width = 1.dp,
                    color = if (capturing) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
                    shape = RoundedCornerShape(10.dp),
                )
                .focusRequester(focusRequester)
                .onFocusChanged { focused = it.isFocused }
                .clickable {
                    onStartCapture()
                    runCatching { focusRequester.requestFocus() }
                }
                .onKeyEvent { event ->
                    if (!capturing || event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    if (event.key == Key.Escape) {
                        onCancelCapture()
                        return@onKeyEvent true
                    }
                    val captured = ShortcutBinding.fromEvent(event) ?: return@onKeyEvent true
                    onCaptured(captured)
                    true
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (capturing) "按下新的组合键…" else binding.display(),
                style = MaterialTheme.typography.labelLarge,
                color = if (capturing) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else if (binding.isBound) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        if (binding != action.defaultBinding) {
            TextButton(onClick = onReset) { Text("默认") }
        } else {
            Spacer(Modifier.width(8.dp))
        }
    }
}
