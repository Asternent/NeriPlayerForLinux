package moe.ouom.neriplayer.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.desktop.core.AppContainer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 设置页「同步」分组：配置 GitHub Token / 仓库，查看同步状态并手动触发同步。
 */
@Composable
fun SyncSettingsSection(
    container: AppContainer,
    showMessage: (String) -> Unit,
) {
    val config by container.syncConfig.state.collectAsState()
    val syncState by container.sync.state.collectAsState()
    val scope = rememberCoroutineScope()

    var tokenInput by remember(config.token) { mutableStateOf(config.token) }
    var ownerInput by remember(config.owner) { mutableStateOf(config.owner) }
    var repoInput by remember(config.repo) { mutableStateOf(config.repo) }
    var verifying by remember { mutableStateOf(false) }
    var showRepoPicker by remember { mutableStateOf(false) }
    var repoOptions by remember { mutableStateOf<List<String>>(emptyList()) }
    var showCreateRepo by remember { mutableStateOf(false) }
    var newRepoName by remember { mutableStateOf("neriplayer-sync") }
    var creating by remember { mutableStateOf(false) }

    val timeFormatter = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    val lastSyncText = if (config.lastSyncAt > 0L) {
        timeFormatter.format(Date(config.lastSyncAt))
    } else {
        "尚未同步"
    }

    SettingsSectionCard(
        title = "同步",
        description = "通过你自己的 GitHub 仓库跨设备同步歌单、收藏、最近播放与播放统计",
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (config.configured) "已配置：${config.repoFullName}" else "未配置",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "上次同步：$lastSyncText" + if (syncState.message.isBlank()) "" else " · ${syncState.message}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (syncState.success) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
            if (syncState.running) {
                CircularProgressIndicator(modifier = Modifier.width(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Button(
                enabled = config.configured && !syncState.running,
                onClick = {
                    scope.launch {
                        val result = container.sync.performSync()
                        showMessage(result.message)
                    }
                },
            ) { Text("立即同步") }
        }

        Spacer(Modifier.height(14.dp))
        Text("步骤 1：GitHub Token", style = MaterialTheme.typography.titleSmall)
        Text(
            text = "在 GitHub → Settings → Developer settings → Personal access tokens 生成一个勾选 repo 权限的 Token（建议 fine-grained 且只授权给同步仓库）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = tokenInput,
                onValueChange = { tokenInput = it.trim() },
                label = { Text("Token") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            TextButton(
                enabled = tokenInput.isNotBlank() && !verifying,
                onClick = {
                    verifying = true
                    scope.launch {
                        val result = container.sync.verifyToken(tokenInput)
                        verifying = false
                        result.onSuccess { login ->
                            container.syncConfig.update { it.copy(token = tokenInput, owner = login) }
                            ownerInput = login
                            showMessage("Token 有效，已登录为 $login")
                        }.onFailure { error ->
                            showMessage(error.message ?: "Token 校验失败")
                        }
                    }
                },
            ) { Text(if (verifying) "校验中…" else "验证 Token") }
        }

        Spacer(Modifier.height(14.dp))
        Text("步骤 2：选择仓库", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = ownerInput,
                onValueChange = { ownerInput = it.trim() },
                label = { Text("账号 / 组织") },
                singleLine = true,
                modifier = Modifier.width(180.dp),
            )
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = repoInput,
                onValueChange = { repoInput = it.trim() },
                label = { Text("仓库名") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                enabled = config.token.isNotBlank(),
                onClick = {
                    scope.launch {
                        repoOptions = container.sync.listRepos(config.token)
                        if (repoOptions.isEmpty()) showMessage("没有读取到仓库，请检查 Token 权限") else showRepoPicker = true
                    }
                },
            ) { Text("选择现有仓库") }
            TextButton(
                enabled = config.token.isNotBlank() && !creating,
                onClick = { showCreateRepo = true },
            ) { Text("创建私有仓库") }
            TextButton(
                enabled = ownerInput.isNotBlank() && repoInput.isNotBlank(),
                onClick = {
                    container.syncConfig.update { it.copy(owner = ownerInput, repo = repoInput) }
                    container.sync.refreshConfiguredState()
                    showMessage("已保存同步仓库：$ownerInput/$repoInput")
                },
            ) { Text("保存仓库") }
        }

        Spacer(Modifier.height(6.dp))
        SwitchRowCompact(
            title = "自动同步",
            description = "歌单、收藏、最近播放或统计变化后自动同步",
            checked = config.autoSync,
            onCheckedChange = { value ->
                container.syncConfig.update { it.copy(autoSync = value) }
            },
        )
        SwitchRowCompact(
            title = "省流通道",
            description = "使用二进制压缩格式（手机端同款开关，两端需保持一致才能互相读取最新数据）",
            checked = config.useDataSaver,
            onCheckedChange = { value ->
                container.syncConfig.update { it.copy(useDataSaver = value) }
            },
        )

        Spacer(Modifier.height(6.dp))
        Text(
            text = "Token 保存在本机 ~/.config/NeriPlayer/sync.json（权限 600）；同步文件为仓库根目录的 " +
                "backup.json（普通）或 backup-raw.bin（省流），与手机端完全兼容。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        TextButton(
            onClick = {
                container.syncConfig.clear()
                tokenInput = ""
                container.sync.refreshConfiguredState()
                showMessage("已清除 GitHub 同步配置（本地数据不受影响）")
            },
        ) { Text("清除同步配置") }
    }

    if (showRepoPicker) {
        AlertDialog(
            onDismissRequest = { showRepoPicker = false },
            title = { Text("选择同步仓库") },
            text = {
                LazyColumn(Modifier.fillMaxSize(0.6f)) {
                    items(repoOptions) { fullName ->
                        Text(
                            text = fullName,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val owner = fullName.substringBefore('/')
                                    val repo = fullName.substringAfter('/', "")
                                    ownerInput = owner
                                    repoInput = repo
                                    container.syncConfig.update { it.copy(owner = owner, repo = repo) }
                                    container.sync.refreshConfiguredState()
                                    showRepoPicker = false
                                    showMessage("已选择仓库：$fullName")
                                },
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showRepoPicker = false }) { Text("关闭") } },
        )
    }

    if (showCreateRepo) {
        AlertDialog(
            onDismissRequest = { showCreateRepo = false },
            title = { Text("创建私有同步仓库") },
            text = {
                Column {
                    OutlinedTextField(
                        value = newRepoName,
                        onValueChange = { newRepoName = it.trim() },
                        label = { Text("仓库名") },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "会在你的账号下创建私有仓库，并自动写入同步文件。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = newRepoName.isNotBlank() && !creating,
                    onClick = {
                        creating = true
                        scope.launch {
                            val result = container.sync.createRepo(config.token, newRepoName)
                            creating = false
                            result.onSuccess { fullName ->
                                val owner = fullName.substringBefore('/')
                                val repo = fullName.substringAfter('/', "")
                                ownerInput = owner
                                repoInput = repo
                                container.syncConfig.update { it.copy(owner = owner, repo = repo) }
                                container.sync.refreshConfiguredState()
                                showCreateRepo = false
                                showMessage("已创建并选择仓库：$fullName")
                            }.onFailure { error ->
                                showMessage("创建失败：${error.message}")
                            }
                        }
                    },
                ) { Text(if (creating) "创建中…" else "创建") }
            },
            dismissButton = { TextButton(onClick = { showCreateRepo = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun SwitchRowCompact(
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
private fun SettingsSectionCard(
    title: String,
    description: String,
    content: @Composable () -> Unit,
) {
    androidx.compose.material3.Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(16.dp)) {
            Column(Modifier.padding(bottom = 10.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    text = description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            content()
        }
    }
}
