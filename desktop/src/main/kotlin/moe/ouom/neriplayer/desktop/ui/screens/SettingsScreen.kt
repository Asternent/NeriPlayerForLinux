package moe.ouom.neriplayer.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Lyrics
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.desktop.core.AppContainer
import moe.ouom.neriplayer.desktop.core.AppDirs
import moe.ouom.neriplayer.desktop.core.DarkModeSetting
import moe.ouom.neriplayer.desktop.core.FfmpegSupport
import moe.ouom.neriplayer.desktop.core.MediaSource
import moe.ouom.neriplayer.desktop.core.displayName
import moe.ouom.neriplayer.desktop.ui.AccountRow
import moe.ouom.neriplayer.desktop.ui.EqualizerPresets
import moe.ouom.neriplayer.desktop.ui.LoginDialog
import moe.ouom.neriplayer.desktop.ui.SyncSettingsSection
import moe.ouom.neriplayer.desktop.ui.FloatingLyricsSettingsSection
import moe.ouom.neriplayer.desktop.ui.DownloadSettingsSection
import moe.ouom.neriplayer.desktop.ui.theme.colorFromHex
import moe.ouom.neriplayer.desktop.ui.theme.COLOR_SPECS
import moe.ouom.neriplayer.desktop.ui.theme.PALETTE_STYLES
import moe.ouom.neriplayer.desktop.ui.theme.PRESET_SEED_COLORS
import moe.ouom.neriplayer.desktop.ui.theme.paletteStyleDescription
import moe.ouom.neriplayer.desktop.ui.theme.paletteStyleLabel
import moe.ouom.neriplayer.desktop.ui.theme.sanitizeSeedColorHex
import java.awt.Desktop
import java.io.File
import java.net.URI
import javax.swing.JFileChooser
import javax.swing.UIManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    container: AppContainer,
    showMessage: (String) -> Unit,
    onRequestLogin: (MediaSource) -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by container.settings.state.collectAsState()
    val songs by container.library.songs.collectAsState()
    val scanState by container.library.scanState.collectAsState()
    val accounts by container.accounts.state.collectAsState()
    var seedInput by remember(settings.themeSeedColor) { mutableStateOf(settings.themeSeedColor) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        item { TopAppBar(title = { Text("设置") }) }

        item {
            AccountSettingsSection(
                container = container,
                showMessage = showMessage,
                onRequestLogin = onRequestLogin,
            )
        }

        item {
            SettingsSection(
                title = "主题设置",
                description = "深浅色、动态取色和主题调色",
                icon = Icons.Outlined.ColorLens,
            ) {
                SettingLabel("深浅色模式")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        DarkModeSetting.LIGHT to "浅色",
                        DarkModeSetting.DARK to "深色",
                        DarkModeSetting.AUTO to "跟随系统",
                    ).forEach { (mode, label) ->
                        FilterChip(
                            selected = settings.darkMode == mode,
                            onClick = { container.settings.update { it.copy(darkMode = mode) } },
                            label = { Text(label) },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                SwitchRow(
                    title = "动态取色",
                    description = "跟随当前歌曲封面主题色（无封面时使用下方主题色）",
                    checked = settings.dynamicColor,
                    onCheckedChange = { enabled -> container.settings.update { it.copy(dynamicColor = enabled) } },
                )
                Spacer(Modifier.height(12.dp))
                SettingLabel("主题色")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PRESET_SEED_COLORS.forEach { hex ->
                        val selected = settings.themeSeedColor.equals(hex, ignoreCase = true)
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(colorFromHex(hex))
                                .border(
                                    width = if (selected) 3.dp else 1.dp,
                                    color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant,
                                    shape = CircleShape,
                                )
                                .clickable {
                                    container.settings.update { it.copy(themeSeedColor = hex, dynamicColor = false) }
                                },
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = seedInput,
                        onValueChange = { seedInput = it.uppercase().removePrefix("#").take(6) },
                        label = { Text("自定义主题色（6 位 HEX）") },
                        singleLine = true,
                        modifier = Modifier.width(240.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    TextButton(onClick = {
                        val sanitized = sanitizeSeedColorHex(seedInput)
                        container.settings.update { it.copy(themeSeedColor = sanitized, dynamicColor = false) }
                        seedInput = sanitized
                        showMessage("主题色已更新为 #$sanitized")
                    }) { Text("应用") }
                }
                Spacer(Modifier.height(14.dp))
                SettingLabel("色彩风格")
                Column {
                    PALETTE_STYLES.chunked(3).forEach { rowStyles ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                            rowStyles.forEach { style ->
                                FilterChip(
                                    selected = settings.paletteStyle == style,
                                    onClick = { container.settings.update { it.copy(paletteStyle = style) } },
                                    label = { Text(paletteStyleLabel(style)) },
                                )
                            }
                        }
                    }
                }
                Text(
                    text = paletteStyleDescription(settings.paletteStyle),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                SettingLabel("色彩空间")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    COLOR_SPECS.forEach { spec ->
                        FilterChip(
                            selected = settings.colorSpec == spec,
                            onClick = { container.settings.update { it.copy(colorSpec = spec) } },
                            label = { Text(if (spec == "SPEC_2025") "2025" else "2021") },
                        )
                    }
                }
            }
        }

        item {
            SettingsSection(
                title = "播放",
                description = "音质、音量、倍速与音效",
                icon = Icons.Outlined.GraphicEq,
            ) {
                SettingLabel("默认音质（在线音源）")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "standard" to "标准",
                        "higher" to "较高",
                        "exhigh" to "极高",
                        "lossless" to "无损",
                    ).forEach { (value, label) ->
                        FilterChip(
                            selected = settings.qualityPreference == value,
                            onClick = { container.settings.update { it.copy(qualityPreference = value) } },
                            label = { Text(label) },
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                SettingLabel("音量 ${(settings.volume * 100).toInt()}%")
                Slider(
                    value = settings.volume,
                    onValueChange = { container.player.setVolume(it) },
                )
                Spacer(Modifier.height(8.dp))
                SettingLabel("默认播放速度 %.2fx".format(settings.playbackSpeed))
                Slider(
                    value = settings.playbackSpeed,
                    onValueChange = { container.player.setSpeed(it) },
                    valueRange = 0.5f..3f,
                    enabled = container.player.supportsEffects,
                )
                Spacer(Modifier.height(8.dp))
                SwitchRow(
                    title = "响度增强",
                    description = "使用动态归一化提升整体响度",
                    checked = settings.loudnessEnhancer,
                    enabled = container.player.supportsEffects,
                    onCheckedChange = { container.player.setLoudness(it) },
                )
                SwitchRow(
                    title = "均衡器",
                    description = "启用十段均衡器，可在播放页进一步微调",
                    checked = settings.equalizerEnabled,
                    enabled = container.player.supportsEffects,
                    onCheckedChange = { container.player.setEqualizerEnabled(it) },
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    EqualizerPresets.Preset.entries.forEach { preset ->
                        FilterChip(
                            selected = settings.equalizerPreset == preset.label,
                            onClick = { container.player.setEqualizerPreset(preset.label, preset.bands) },
                            enabled = container.player.supportsEffects,
                            label = { Text(preset.label) },
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                SwitchRow(
                    title = "启动时恢复上次队列",
                    description = "打开应用后继续显示上次的播放队列与进度",
                    checked = settings.resumeLastQueue,
                    onCheckedChange = { enabled -> container.settings.update { it.copy(resumeLastQueue = enabled) } },
                )
                Spacer(Modifier.height(10.dp))
                SettingLabel("睡眠定时器默认时长")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15, 30, 60, 90).forEach { minutes ->
                        FilterChip(
                            selected = settings.sleepTimerMinutes == minutes,
                            onClick = { container.settings.update { it.copy(sleepTimerMinutes = minutes) } },
                            label = { Text("$minutes 分") },
                        )
                    }
                }
            }
        }

        item {
            SettingsSection(
                title = "媒体库",
                description = "本地音乐文件夹、扫描与缓存",
                icon = Icons.Outlined.LibraryMusic,
            ) {
                SettingLabel("音乐文件夹")
                settings.musicFolders.forEach { folder ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = folder,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            container.settings.update { it.copy(musicFolders = it.musicFolders - folder) }
                        }) { Text("移除") }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        val chosen = chooseDirectory()
                        if (chosen != null) {
                            container.settings.update { current ->
                                if (chosen in current.musicFolders) current
                                else current.copy(musicFolders = current.musicFolders + chosen)
                            }
                            showMessage("已添加文件夹：$chosen")
                        }
                    }) { Text("＋ 添加文件夹") }
                    TextButton(onClick = {
                        container.scope.launch {
                            showMessage("开始扫描本地音乐…")
                            val count = container.library.scan()
                            showMessage("扫描完成，共 $count 首歌曲")
                        }
                    }) { Text("立即扫描") }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (scanState.running) {
                        "正在扫描 ${scanState.processed}/${scanState.total}"
                    } else {
                        "媒体库共 ${songs.size} 首歌曲"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { openInFileManager(AppDirs.dataDir) }) { Text("打开数据目录") }
                    TextButton(onClick = {
                        container.scope.launch {
                            withContext(Dispatchers.IO) {
                                AppDirs.coverDir.listFiles()?.forEach { it.delete() }
                                AppDirs.artworkDir.listFiles()?.forEach { it.delete() }
                            }
                            showMessage("封面缓存已清理")
                        }
                    }) { Text("清理封面缓存") }
                }
            }
        }

        item {
            SyncSettingsSection(
                container = container,
                showMessage = showMessage,
            )
        }

        item {
            DownloadSettingsSection(
                container = container,
                showMessage = showMessage,
            )
        }

        item {
            SettingsSection(
                title = "在线音源",
                description = "网易云、哔哩哔哩与 YouTube 开关",
                icon = Icons.Outlined.Public,
            ) {
                SwitchRow(
                    title = "网易云音乐",
                    description = "搜索、推荐、播放与歌词（无需登录即可使用公开接口）",
                    checked = settings.neteaseEnabled,
                    onCheckedChange = { value -> container.settings.update { current -> current.copy(neteaseEnabled = value) } },
                )
                SwitchRow(
                    title = "哔哩哔哩",
                    description = "视频搜索与音频轨道播放",
                    checked = settings.bilibiliEnabled,
                    onCheckedChange = { value -> container.settings.update { current -> current.copy(bilibiliEnabled = value) } },
                )
                SwitchRow(
                    title = "YouTube Music",
                    description = "桌面端暂未接入（需要账号授权与专用解析）",
                    checked = false,
                    enabled = false,
                    onCheckedChange = {},
                )
            }
        }

        item {
            SettingsSection(
                title = "歌词与显示",
                description = "歌词翻译、封面歌词与字号",
                icon = Icons.Outlined.Lyrics,
            ) {
                SwitchRow(
                    title = "显示歌词翻译",
                    description = "在线歌词包含翻译时同时显示",
                    checked = settings.showLyricTranslation,
                    onCheckedChange = { value -> container.settings.update { current -> current.copy(showLyricTranslation = value) } },
                )
                SwitchRow(
                    title = "封面页显示歌词",
                    description = "在播放页封面下方显示逐行歌词",
                    checked = settings.coverShowsLyrics,
                    onCheckedChange = { value -> container.settings.update { current -> current.copy(coverShowsLyrics = value) } },
                )
                SwitchRow(
                    title = "显示“正在播放”标题",
                    description = "控制播放页顶部是否显示标题",
                    checked = settings.showNowPlayingTitle,
                    onCheckedChange = { value -> container.settings.update { current -> current.copy(showNowPlayingTitle = value) } },
                )
                SwitchRow(
                    title = "滚动显示长歌曲名",
                    description = "标题超出宽度时平滑滚动",
                    checked = settings.songTitleMarquee,
                    onCheckedChange = { value -> container.settings.update { current -> current.copy(songTitleMarquee = value) } },
                )
                Spacer(Modifier.height(8.dp))
                SettingLabel("歌词字号 %.2fx".format(settings.lyricsFontScale))
                Slider(
                    value = settings.lyricsFontScale,
                    onValueChange = { value -> container.settings.update { it.copy(lyricsFontScale = value) } },
                    valueRange = 0.7f..1.8f,
                )
            }
        }

        item {
            FloatingLyricsSettingsSection(
                container = container,
                showMessage = showMessage,
            )
        }

        item {
            SettingsSection(
                title = "首页栏目",
                description = "控制首页展示的卡片",
                icon = Icons.Outlined.Tune,
            ) {
                SwitchRow(
                    title = "继续播放",
                    checked = settings.homeCards.continuePlaying,
                    onCheckedChange = { value ->
                        container.settings.update { it.copy(homeCards = it.homeCards.copy(continuePlaying = value)) }
                    },
                )
                SwitchRow(
                    title = "为你推荐",
                    checked = settings.homeCards.recommended,
                    onCheckedChange = { value ->
                        container.settings.update { it.copy(homeCards = it.homeCards.copy(recommended = value)) }
                    },
                )
                SwitchRow(
                    title = "热歌榜",
                    checked = settings.homeCards.hotTracks,
                    onCheckedChange = { value ->
                        container.settings.update { it.copy(homeCards = it.homeCards.copy(hotTracks = value)) }
                    },
                )
                SwitchRow(
                    title = "私人雷达",
                    checked = settings.homeCards.radar,
                    onCheckedChange = { value ->
                        container.settings.update { it.copy(homeCards = it.homeCards.copy(radar = value)) }
                    },
                )
            }
        }

        item {
            SettingsSection(
                title = "关于",
                description = "版本信息、运行环境与数据管理",
                icon = Icons.Outlined.Info,
            ) {
                InfoRow("应用版本", "NeriPlayer Desktop 1.3.0")
                InfoRow("音频引擎", if (FfmpegSupport.available) "ffmpeg（${FfmpegSupport.version.take(28)}…）" else "Java Sound 回退引擎")
                InfoRow("音效支持", if (container.player.supportsEffects) "倍速 / 变调 / 响度 / 均衡器可用" else "当前不可用（缺少 ffmpeg）")
                InfoRow("数据目录", AppDirs.dataDir.absolutePath)
                InfoRow("缓存目录", AppDirs.cacheDir.absolutePath)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        runCatching {
                            if (Desktop.isDesktopSupported()) {
                                Desktop.getDesktop().browse(URI("https://github.com/cwuom/NeriPlayer"))
                            }
                        }
                    }) { Text("项目主页") }
                    TextButton(onClick = {
                        container.stats.clear()
                        showMessage("播放统计已清空")
                    }) { Text("清空播放统计") }
                    TextButton(onClick = {
                        container.history.clear()
                        showMessage("最近播放已清空")
                    }) { Text("清空最近播放") }
                }
            }
        }
    }

}

private fun chooseDirectory(): String? = runCatching {
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    val chooser = JFileChooser().apply {
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        dialogTitle = "选择音乐文件夹"
        isMultiSelectionEnabled = false
    }
    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile?.absolutePath
    } else {
        null
    }
}.getOrNull()

private fun openInFileManager(directory: File) {
    runCatching {
        if (Desktop.isDesktopSupported()) {
            Desktop.getDesktop().open(directory)
        }
    }
}

@Composable
private fun AccountSettingsSection(
    container: AppContainer,
    showMessage: (String) -> Unit,
    onRequestLogin: (MediaSource) -> Unit,
) {
    val accounts by container.accounts.state.collectAsState()
    SettingsSection(
        title = "账号",
        description = "网易云与哔哩哔哩扫码登录，登录后可访问自己的歌单与收藏夹",
        icon = Icons.Outlined.AccountCircle,
    ) {
        val neteaseAccount = accounts.accounts.firstOrNull { it.source == MediaSource.NETEASE.name }
        val biliAccount = accounts.accounts.firstOrNull { it.source == MediaSource.BILIBILI.name }
        AccountRow(
            source = MediaSource.NETEASE,
            nickname = neteaseAccount?.displayName(),
            detail = if (neteaseAccount != null) {
                "UID ${neteaseAccount.userId.ifBlank { "—" }}${if (neteaseAccount.vip) " · 会员" else ""}"
            } else {
                "未登录：仅能使用公开搜索、推荐与试听音质"
            },
            isLoggedIn = neteaseAccount != null,
            onLogin = { onRequestLogin(MediaSource.NETEASE) },
            onLogout = {
                container.accounts.logout(MediaSource.NETEASE, "netease")
                showMessage("已退出网易云账号")
            },
        )
        AccountRow(
            source = MediaSource.BILIBILI,
            nickname = biliAccount?.displayName(),
            detail = if (biliAccount != null) {
                "UID ${biliAccount.userId.ifBlank { "—" }}"
            } else {
                "未登录：可搜索与播放视频音频轨，收藏夹需要登录"
            },
            isLoggedIn = biliAccount != null,
            onLogin = { onRequestLogin(MediaSource.BILIBILI) },
            onLogout = {
                container.accounts.logout(MediaSource.BILIBILI, "bilibili")
                showMessage("已退出哔哩哔哩账号")
            },
        )
        Text(
            text = "登录凭据（Cookie）仅保存在本机 ~/.config/NeriPlayer/accounts.json（权限 600）；" +
                "YouTube Music 需要 Google 账号授权，桌面版暂未接入。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    description: String,
    icon: ImageVector,
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(true) }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = description,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(if (expanded) "收起" else "展开", style = MaterialTheme.typography.labelMedium)
            }
            if (expanded) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) { content() }
            }
        }
    }
}

@Composable
private fun SettingLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun SwitchRow(
    title: String,
    description: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
