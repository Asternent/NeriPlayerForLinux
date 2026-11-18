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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.desktop.core.AppContainer
import kotlin.math.roundToInt

/**
 * 设置页「悬浮歌词」分组：与手机端一致的样式项 + 效果预览。
 */
@Composable
fun FloatingLyricsSettingsSection(
    container: AppContainer,
    showMessage: (String) -> Unit,
) {
    val settings by container.settings.state.collectAsState()

    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("悬浮歌词", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = "在桌面上显示轻量悬浮歌词，样式与位置可自定义（快捷键 Ctrl+L）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.floatingLyricsEnabled,
                    onCheckedChange = { enabled ->
                        container.settings.update { it.copy(floatingLyricsEnabled = enabled) }
                        showMessage(if (enabled) "已开启悬浮歌词，拖动歌词即可调整位置" else "已关闭悬浮歌词")
                    },
                )
            }

            Spacer(Modifier.height(10.dp))
            FloatingLyricsPreview(settings)
            Spacer(Modifier.height(12.dp))

            SettingSwitch(
                title = "应用内隐藏",
                description = "主窗口获得焦点时隐藏悬浮歌词，避免遮挡页面",
                checked = settings.floatingLyricsHideInApp,
                onCheckedChange = { value -> container.settings.update { it.copy(floatingLyricsHideInApp = value) } },
            )
            SettingSwitch(
                title = "锁定位置",
                description = "锁定后无法拖动悬浮歌词",
                checked = settings.floatingLyricsLocked,
                onCheckedChange = { value -> container.settings.update { it.copy(floatingLyricsLocked = value) } },
            )
            SettingSwitch(
                title = "显示翻译",
                description = "有翻译歌词时一起显示在第二行",
                checked = settings.floatingLyricsShowTranslation,
                onCheckedChange = { value -> container.settings.update { it.copy(floatingLyricsShowTranslation = value) } },
            )
            SettingSwitch(
                title = "切换歌词时淡入",
                description = "关闭后歌词直接整句切换",
                checked = settings.floatingLyricsRevealAnimation,
                onCheckedChange = { value -> container.settings.update { it.copy(floatingLyricsRevealAnimation = value) } },
            )

            Spacer(Modifier.height(8.dp))
            OptionLabel("渲染方式")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FloatingLyricRenderStyle.entries.forEach { style ->
                    FilterChip(
                        selected = settings.renderStyle() == style,
                        onClick = { container.settings.update { it.copy(floatingLyricsRenderStyle = style.name) } },
                        label = { Text(style.label) },
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            OptionLabel("歌词颜色")
            ColorRow(
                selected = FloatingLyricColor.of(settings.floatingLyricsTextColor),
                onSelect = { value -> container.settings.update { it.copy(floatingLyricsTextColor = value.name) } },
            )

            if (settings.renderStyle() == FloatingLyricRenderStyle.OUTLINE) {
                Spacer(Modifier.height(10.dp))
                OptionLabel("描边颜色")
                ColorRow(
                    selected = FloatingLyricColor.of(settings.floatingLyricsOutlineColor),
                    onSelect = { value -> container.settings.update { it.copy(floatingLyricsOutlineColor = value.name) } },
                )
                ValueSlider(
                    label = "歌词描边宽度 %.1f dp".format(settings.floatingLyricsOutlineWidth),
                    value = settings.floatingLyricsOutlineWidth,
                    range = 0.5f..6f,
                    onChange = { value -> container.settings.update { it.copy(floatingLyricsOutlineWidth = value) } },
                )
            } else {
                Spacer(Modifier.height(10.dp))
                OptionLabel("阴影颜色")
                ColorRow(
                    selected = FloatingLyricColor.of(settings.floatingLyricsShadowColor),
                    onSelect = { value -> container.settings.update { it.copy(floatingLyricsShadowColor = value.name) } },
                )
                ValueSlider(
                    label = "歌词阴影模糊 %.1f dp".format(settings.floatingLyricsShadowBlur),
                    value = settings.floatingLyricsShadowBlur,
                    range = 0f..20f,
                    onChange = { value -> container.settings.update { it.copy(floatingLyricsShadowBlur = value) } },
                )
            }

            Spacer(Modifier.height(6.dp))
            ValueSlider(
                label = "字号 %d".format(settings.floatingLyricsFontSize.roundToInt()),
                value = settings.floatingLyricsFontSize,
                range = 14f..72f,
                onChange = { value -> container.settings.update { it.copy(floatingLyricsFontSize = value) } },
            )
            ValueSlider(
                label = "主歌词不透明度 ${(settings.floatingLyricsLyricAlpha * 100).roundToInt()}%",
                value = settings.floatingLyricsLyricAlpha,
                range = 0.1f..1f,
                onChange = { value -> container.settings.update { it.copy(floatingLyricsLyricAlpha = value) } },
            )
            ValueSlider(
                label = "翻译不透明度 ${(settings.floatingLyricsTranslationAlpha * 100).roundToInt()}%",
                value = settings.floatingLyricsTranslationAlpha,
                range = 0.1f..1f,
                onChange = { value -> container.settings.update { it.copy(floatingLyricsTranslationAlpha = value) } },
            )
            ValueSlider(
                label = "背景不透明度 ${(settings.floatingLyricsBackgroundAlpha * 100).roundToInt()}%",
                value = settings.floatingLyricsBackgroundAlpha,
                range = 0f..1f,
                onChange = { value -> container.settings.update { it.copy(floatingLyricsBackgroundAlpha = value) } },
            )
            OptionLabel("背景颜色")
            ColorRow(
                selected = FloatingLyricColor.of(settings.floatingLyricsBackgroundColor),
                onSelect = { value -> container.settings.update { it.copy(floatingLyricsBackgroundColor = value.name) } },
            )
            ValueSlider(
                label = "最大宽度 %d dp".format(settings.floatingLyricsMaxWidthDp.roundToInt()),
                value = settings.floatingLyricsMaxWidthDp,
                range = 320f..1600f,
                onChange = { value -> container.settings.update { it.copy(floatingLyricsMaxWidthDp = value) } },
            )

            Spacer(Modifier.height(6.dp))
            OptionLabel("对齐方式")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FloatingLyricAlignment.entries.forEach { alignment ->
                    FilterChip(
                        selected = settings.alignment() == alignment,
                        onClick = { container.settings.update { it.copy(floatingLyricsAlignment = alignment.name) } },
                        label = { Text(alignment.label) },
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            ValueSlider(
                label = "水平位置 ${(settings.floatingLyricsPositionX * 100).roundToInt()}%",
                value = settings.floatingLyricsPositionX,
                range = 0f..1f,
                onChange = { value -> container.settings.update { it.copy(floatingLyricsPositionX = value) } },
            )
            ValueSlider(
                label = "垂直位置 ${(settings.floatingLyricsPositionY * 100).roundToInt()}%",
                value = settings.floatingLyricsPositionY,
                range = 0f..1f,
                onChange = { value -> container.settings.update { it.copy(floatingLyricsPositionY = value) } },
            )
            Text(
                text = "提示：也可以直接用鼠标拖动悬浮歌词调整位置，位置会按屏幕比例保存，适配不同分辨率；" +
                    "若系统未启用窗口合成器，透明背景会显示为不透明。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
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
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun OptionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun ColorRow(
    selected: FloatingLyricColor,
    onSelect: (FloatingLyricColor) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FloatingLyricColor.entries.forEach { option ->
            val isSelected = option == selected
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(option.color)
                    .border(
                        width = if (isSelected) 3.dp else 1.dp,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                        shape = CircleShape,
                    )
                    .clickable { onSelect(option) },
            )
        }
    }
    Text(
        text = selected.label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ValueSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
        )
    }
}
