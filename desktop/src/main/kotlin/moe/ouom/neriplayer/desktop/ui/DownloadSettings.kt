package moe.ouom.neriplayer.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.desktop.core.AppContainer
import javax.swing.JFileChooser
import javax.swing.UIManager

/** 设置页「下载」分组：目录、并发数、音质与占用情况。 */
@Composable
fun DownloadSettingsSection(
    container: AppContainer,
    showMessage: (String) -> Unit,
) {
    val settings by container.settings.state.collectAsState()
    val downloaded by container.downloadCatalog.items.collectAsState()
    val directory = container.downloads.downloadDirectory()

    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("下载", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                text = "把在线歌曲保存到本地，离线也能播放（已下载歌曲显示离线标记）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(10.dp))
            Text("下载目录", style = MaterialTheme.typography.labelLarge)
            Text(
                text = directory.absolutePath,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    val chosen = chooseDownloadDirectory()
                    if (chosen != null) {
                        container.settings.update { it.copy(downloadDirectory = chosen) }
                        showMessage("下载目录已设为：$chosen")
                    }
                }) { Text("选择目录") }
                TextButton(onClick = { openDirectory(directory, showMessage) }) { Text("打开目录") }
                TextButton(onClick = {
                    container.settings.update { it.copy(downloadDirectory = "") }
                    showMessage("已恢复默认下载目录")
                }) { Text("恢复默认") }
            }

            Spacer(Modifier.height(6.dp))
            Text("并发下载", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 2, 3, 4).forEach { count ->
                    FilterChip(
                        selected = settings.downloadConcurrency == count,
                        onClick = { container.settings.update { it.copy(downloadConcurrency = count) } },
                        label = { Text("$count 个") },
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            Text("下载音质（在线音源）", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "standard" to "标准",
                    "higher" to "较高",
                    "exhigh" to "极高",
                    "lossless" to "无损",
                ).forEach { (value, label) ->
                    FilterChip(
                        selected = settings.downloadQuality == value,
                        onClick = { container.settings.update { it.copy(downloadQuality = value) } },
                        label = { Text(label) },
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "已下载 ${downloaded.size} 首 · 占用 ${formatSize(downloaded.values.sumOf { it.sizeBytes })}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    val missing = downloaded.values.filter { !java.io.File(it.filePath).isFile }
                    missing.forEach { container.downloadCatalog.remove(it.songKey) }
                    showMessage("已清理 ${missing.size} 条失效下载记录")
                }) { Text("清理失效记录") }
            }
        }
    }
}

private fun chooseDownloadDirectory(): String? = runCatching {
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    val chooser = JFileChooser().apply {
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        dialogTitle = "选择下载目录"
        isMultiSelectionEnabled = false
    }
    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile?.absolutePath
    } else {
        null
    }
}.getOrNull()
