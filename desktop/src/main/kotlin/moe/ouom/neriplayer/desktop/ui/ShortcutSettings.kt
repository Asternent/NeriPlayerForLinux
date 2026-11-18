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

/** 正在录制的动作（仅应用内快捷键支持录制）。 */
private data class CaptureTarget(val action: ShortcutAction)

/**
 * 设置页「快捷键」分组：左列为应用内快捷键，右列为全局快捷键，逐行对应同一动作。
 *
 * 应用内：Space / Esc 这类基础操作固定不可改（[ShortcutAction.systemKey]），其余可录制。
 * 全局：启动时按内置默认值自动注册，由桌面系统（KDE 系统设置等）统一管理；
 * 这里只读展示，实际改键通过「在系统设置中修改…」打开桌面配置页完成。
 */
@Composable
fun ShortcutSettingsSection(
    container: AppContainer,
    showMessage: (String) -> Unit,
) {
    val settings by container.settings.state.collectAsState()
    val shortcuts = settings.shortcuts
    var capturing by remember { mutableStateOf<CaptureTarget?>(null) }

    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("快捷键", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                text = "左列在应用窗口内生效，点击按键框后按下新的组合键即可改键，Esc 取消录制；" +
                    "全局快捷键由桌面系统统一管理，启动时自动注册，实际按键以系统设置为准。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            // 表头
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "动作",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "应用内",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(132.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "全局",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(132.dp),
                )
            }

            ShortcutAction.entries.groupBy { it.category }.forEach { (category, actions) ->
                Spacer(Modifier.height(10.dp))
                Text(
                    text = category,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                actions.forEach { action ->
                    ShortcutRow(
                        action = action,
                        localBinding = shortcuts.localBindingFor(action),
                        localConflict = shortcuts.localConflict(action, shortcuts.localBindingFor(action)),
                        captureTarget = capturing,
                        onStartCapture = {
                            capturing = CaptureTarget(action)
                            ShortcutDispatcher.capturePaused = true
                        },
                        onCancelCapture = {
                            capturing = null
                            ShortcutDispatcher.capturePaused = false
                        },
                        onCaptured = { binding ->
                            capturing = null
                            ShortcutDispatcher.capturePaused = false
                            val conflict = shortcuts.localConflict(action, binding)
                            if (conflict != null) {
                                showMessage("该按键已被「${conflict.label}」占用")
                            } else {
                                container.settings.update { current ->
                                    current.copy(shortcuts = current.shortcuts.withLocal(action, binding))
                                }
                            }
                        },
                        onResetLocal = {
                            container.settings.update {
                                it.copy(shortcuts = it.shortcuts.clearLocalOverride(action))
                            }
                        },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = {
                    // 全局列由桌面系统管理，这里不提供恢复入口；只恢复应用内。
                    container.settings.update { it.copy(shortcuts = ShortcutSettings()) }
                    showMessage("已恢复默认快捷键")
                }) { Text("恢复默认") }
                TextButton(onClick = {
                    val ok = AppIntents.openGlobalShortcutSettings?.invoke() ?: false
                    showMessage(
                        if (ok) "已打开系统设置，修改后回到应用即可生效"
                        else "当前桌面不支持在应用内打开全局快捷键设置，请到系统设置 → 快捷键 手动修改"
                    )
                }) { Text("在系统设置中修改…") }
            }
        }
    }
}

@Composable
private fun ShortcutRow(
    action: ShortcutAction,
    localBinding: ShortcutBinding,
    localConflict: ShortcutAction?,
    captureTarget: CaptureTarget?,
    onStartCapture: () -> Unit,
    onCancelCapture: () -> Unit,
    onCaptured: (ShortcutBinding) -> Unit,
    onResetLocal: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(action.label, style = MaterialTheme.typography.bodyMedium)
            if (localConflict != null) {
                Text(
                    text = "应用内与「${localConflict.label}」冲突",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                )
            }
        }

        // 应用内：基础键只读，其余可录制
        BindingBox(
            binding = localBinding,
            capturing = captureTarget?.let { it.action == action } == true,
            enabled = !action.systemKey,
            onStartCapture = onStartCapture,
            onCancelCapture = onCancelCapture,
            onCaptured = onCaptured,
        )
        Spacer(Modifier.width(8.dp))
        // 全局：只读展示
        if (action.globalSupported) {
            Box(
                modifier = Modifier
                    .width(132.dp)
                    .height(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(10.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (action.defaultGlobalBinding.isBound) {
                        action.defaultGlobalBinding.display()
                    } else {
                        "未设置"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (action.defaultGlobalBinding.isBound) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        } else {
            Box(Modifier.width(132.dp).height(36.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = "—",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // 重置入口：应用内改过显示「默认」
        Spacer(Modifier.width(56.dp))
        if (localBinding != action.defaultLocalBinding && !action.systemKey) {
            TextButton(onClick = onResetLocal, contentPadding = androidx.compose.foundation.layout.PaddingValues(2.dp)) {
                Text("默认", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun BindingBox(
    binding: ShortcutBinding,
    capturing: Boolean,
    enabled: Boolean,
    onStartCapture: () -> Unit,
    onCancelCapture: () -> Unit,
    onCaptured: (ShortcutBinding) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    Box(
        modifier = Modifier
            .width(132.dp)
            .height(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    capturing -> MaterialTheme.colorScheme.primaryContainer
                    !enabled -> MaterialTheme.colorScheme.surfaceContainer
                    else -> MaterialTheme.colorScheme.surfaceContainerHigh
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
            .then(
                if (enabled) {
                    Modifier.clickable {
                        onStartCapture()
                        runCatching { focusRequester.requestFocus() }
                    }
                } else {
                    Modifier
                }
            )
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
            text = when {
                capturing -> "按下组合键…"
                else -> binding.display()
            },
            style = MaterialTheme.typography.labelLarge,
            color = when {
                capturing -> MaterialTheme.colorScheme.onPrimaryContainer
                !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                binding.isBound -> MaterialTheme.colorScheme.onSurface
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}