package moe.ouom.neriplayer.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.desktop.core.AppContainer
import moe.ouom.neriplayer.desktop.core.DownloadState
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.io.File

/**
 * 下载任务面板：进度、取消、重试、清空已完成与打开下载目录。
 * 对应手机端的「下载管理」页面。
 */
@Composable
fun DownloadPanel(
    container: AppContainer,
    onClose: () -> Unit,
    showMessage: (String) -> Unit,
) {
    val tasks by container.downloads.tasks.collectAsState()
    val downloaded by container.downloadCatalog.items.collectAsState()
    val directory = container.downloads.downloadDirectory()
    val active = tasks.count { it.state == DownloadState.QUEUED || it.state == DownloadState.RUNNING }
    val scope = rememberCoroutineScope()

    OverlayPanel(
        title = if (active > 0) "下载管理（进行中 $active）" else "下载管理",
        onClose = onClose,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "已下载 ${downloaded.size} 首 · ${formatSize(downloaded.values.sumOf { it.sizeBytes })}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = directory.absolutePath,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = { openDirectory(directory, showMessage) }) {
                Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("打开目录")
            }
            TextButton(onClick = {
                scope.launch {
                    val count = container.downloads.repairMetadata()
                    showMessage("已为 $count 首已下载歌曲补齐封面与标签")
                }
            }) { Text("补齐标签") }
        }

        if (tasks.isEmpty()) {
            EmptyState(
                title = "没有下载任务",
                hint = "在歌曲的更多菜单里选择「下载」，或在歌单 / 收藏夹顶部点「下载全部」",
                modifier = Modifier.weight(1f),
            )
            return@OverlayPanel
        }

        LazyColumn(Modifier.weight(1f)) {
            items(tasks, key = { it.id }) { task ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SongArtwork(task.song, size = 38.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = task.song.displayName(),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = buildString {
                                    append(task.song.artistText())
                                    append(" · ")
                                    append(
                                        when (task.state) {
                                            DownloadState.QUEUED -> "排队中"
                                            DownloadState.RUNNING -> if (task.hasKnownSize) {
                                                "${formatSize(task.downloadedBytes)} / ${formatSize(task.totalBytes)}"
                                            } else {
                                                formatSize(task.downloadedBytes)
                                            }

                                            DownloadState.DONE -> "已完成"
                                            DownloadState.FAILED -> task.error ?: "失败"
                                            DownloadState.CANCELED -> "已取消"
                                        }
                                    )
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (task.state == DownloadState.FAILED) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        when (task.state) {
                            DownloadState.QUEUED, DownloadState.RUNNING -> IconButton(
                                onClick = { container.downloads.cancel(task.id) },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(Icons.Outlined.Cancel, contentDescription = "取消", modifier = Modifier.size(17.dp))
                            }

                            DownloadState.FAILED, DownloadState.CANCELED -> IconButton(
                                onClick = { container.downloads.retry(task.id) },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(Icons.Outlined.Refresh, contentDescription = "重试", modifier = Modifier.size(17.dp))
                            }

                            DownloadState.DONE -> IconButton(
                                onClick = {
                                    container.downloads.deleteDownload(task.song.key)
                                    showMessage("已删除下载：${task.song.displayName()}")
                                },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(Icons.Outlined.Delete, contentDescription = "删除文件", modifier = Modifier.size(17.dp))
                            }
                        }
                    }
                    if (task.state == DownloadState.RUNNING || task.state == DownloadState.QUEUED) {
                        Spacer(Modifier.height(6.dp))
                        if (task.hasKnownSize) {
                            LinearProgressIndicator(
                                progress = { task.progress },
                                modifier = Modifier.fillMaxWidth().height(4.dp),
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp))
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "并发下载 ${container.settings.current.downloadConcurrency} 个 · 可在设置 → 下载中调整",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row {
                TextButton(onClick = { container.downloads.removeFinished() }) { Text("清空已完成") }
                TextButton(onClick = { container.downloads.clearAll() }) { Text("清空列表") }
            }
        }
    }
}

/** 打开下载目录（不存在时先创建）。 */
fun openDirectory(directory: File, showMessage: (String) -> Unit) {
    runCatching {
        directory.mkdirs()
        if (Desktop.isDesktopSupported()) {
            Desktop.getDesktop().open(directory)
        } else {
            showMessage("下载目录：${directory.absolutePath}")
        }
    }.onFailure { showMessage("无法打开目录：${it.message}") }
}

fun formatSize(bytes: Long): String = when {
    bytes <= 0L -> "0 B"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}
