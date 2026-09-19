package moe.ouom.neriplayer.desktop.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.desktop.core.AppContainer
import moe.ouom.neriplayer.desktop.core.OnlineArtist
import moe.ouom.neriplayer.desktop.core.OnlineCollection
import moe.ouom.neriplayer.desktop.core.Song
import moe.ouom.neriplayer.desktop.core.StatsPeriod
import moe.ouom.neriplayer.desktop.core.StatsSort
import moe.ouom.neriplayer.desktop.ui.EmptyState
import moe.ouom.neriplayer.desktop.ui.ErrorCard
import moe.ouom.neriplayer.desktop.ui.RemoteArtwork
import moe.ouom.neriplayer.desktop.ui.SectionHeader
import moe.ouom.neriplayer.desktop.ui.SongArtwork
import moe.ouom.neriplayer.desktop.ui.SongRow
import moe.ouom.neriplayer.desktop.ui.formatDuration
import moe.ouom.neriplayer.desktop.ui.formatLongDuration
import moe.ouom.neriplayer.desktop.ui.formatPlayCount
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScaffold(
    title: String,
    onBack: () -> Unit,
    actions: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Outlined.ArrowBack, contentDescription = "返回")
                }
            },
            actions = { actions() },
        )
        content()
    }
}

@Composable
fun CollectionHeader(
    name: String,
    subtitle: String,
    cover: @Composable () -> Unit,
    onPlayAll: () -> Unit,
    onShuffle: (() -> Unit)? = null,
    extra: @Composable () -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        cover()
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onPlayAll) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("播放全部")
                }
                if (onShuffle != null) {
                    TextButton(onClick = onShuffle) {
                        Icon(Icons.Outlined.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("随机播放")
                    }
                }
                extra()
            }
        }
    }
}

@Composable
fun LocalPlaylistDetailScreen(
    container: AppContainer,
    playlistId: String,
    onBack: () -> Unit,
    showMessage: (String) -> Unit,
) {
    val playlists by container.playlists.playlists.collectAsState()
    val playlist = playlists.firstOrNull { it.id == playlistId }
    var actionSong by remember { mutableStateOf<Song?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (playlist == null) {
        DetailScaffold(title = "歌单", onBack = onBack) {
            EmptyState(title = "歌单不存在", hint = "它可能已经被删除")
        }
        return
    }
    val songs = playlist.songs

    DetailScaffold(
        title = playlist.name,
        onBack = onBack,
        actions = {
            if (!playlist.system) {
                IconButton(onClick = { showDeleteConfirm = true }) {
                    Icon(Icons.Outlined.Delete, contentDescription = "删除歌单")
                }
            }
        },
    ) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                CollectionHeader(
                    name = playlist.name,
                    subtitle = "${songs.size} 首 · ${formatLongDuration(songs.sumOf { it.durationMs })}" +
                        if (playlist.system) " · 我喜欢的音乐" else "",
                    cover = { SongArtwork(songs.firstOrNull(), size = 132.dp, shape = RoundedCornerShape(20.dp)) },
                    onPlayAll = {
                        if (songs.isNotEmpty()) container.player.setQueue(songs, 0, autoPlay = true)
                    },
                    onShuffle = {
                        if (songs.isNotEmpty()) container.player.setQueue(songs.shuffled(), 0, autoPlay = true)
                    },
                )
            }
            if (songs.isEmpty()) {
                item {
                    EmptyState(
                        title = "歌单还是空的",
                        hint = "在歌曲的更多菜单中选择「添加到歌单」",
                    )
                }
            }
            itemsIndexed(songs, key = { _, song -> song.key }) { index, song ->
                SongRow(
                    song = song,
                    index = index,
                    onClick = { container.player.playSongNow(song, songs) },
                    onMore = { actionSong = song },
                )
            }
        }
    }

    val current = actionSong
    if (current != null) {
        SongActionsDialog(
            container = container,
            song = current,
            showMessage = showMessage,
            onRemoveFromPlaylist = {
                container.playlists.removeSong(playlist.id, current.key)
                showMessage("已从歌单移除")
            },
            onDismiss = { actionSong = null },
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除歌单") },
            text = { Text("确定要删除歌单“${playlist.name}”吗？此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    container.playlists.deletePlaylist(playlist.id)
                    showDeleteConfirm = false
                    showMessage("歌单已删除")
                    onBack()
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") } },
        )
    }
}

@Composable
fun SongActionsDialog(
    container: AppContainer,
    song: Song,
    showMessage: (String) -> Unit,
    onDismiss: () -> Unit,
    onRemoveFromPlaylist: (() -> Unit)? = null,
) {
    val playlists by container.playlists.playlists.collectAsState()
    var showPlaylistPicker by remember { mutableStateOf(false) }
    val isFavorite = container.playlists.isFavorite(song)

    if (showPlaylistPicker) {
        AlertDialog(
            onDismissRequest = { showPlaylistPicker = false },
            title = { Text("添加到歌单") },
            text = {
                Column {
                    playlists.forEach { playlist ->
                        Text(
                            text = playlist.name,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    container.playlists.addSongs(playlist.id, listOf(song))
                                    showMessage("已添加到「${playlist.name}」")
                                    showPlaylistPicker = false
                                    onDismiss()
                                }
                                .padding(vertical = 10.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val created = container.playlists.createPlaylist("新歌单")
                    container.playlists.addSongs(created.id, listOf(song))
                    showMessage("已创建歌单并添加歌曲")
                    showPlaylistPicker = false
                    onDismiss()
                }) { Text("新建歌单") }
            },
            dismissButton = { TextButton(onClick = { showPlaylistPicker = false }) { Text("取消") } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(song.displayName(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                ActionEntry("下一首播放") {
                    container.player.enqueueNext(listOf(song))
                    onDismiss()
                }
                ActionEntry("添加到播放队列") {
                    container.player.enqueue(listOf(song))
                    onDismiss()
                }
                ActionEntry("添加到歌单") { showPlaylistPicker = true }
                ActionEntry(if (isFavorite) "取消收藏" else "收藏") {
                    val added = container.playlists.toggleFavorite(song)
                    showMessage(if (added) "已收藏" else "已取消收藏")
                    onDismiss()
                }
                if (onRemoveFromPlaylist != null) {
                    ActionEntry("从歌单移除") {
                        onRemoveFromPlaylist()
                        onDismiss()
                    }
                }
                if (!song.filePath.isNullOrBlank()) {
                    ActionEntry("在文件管理器中显示") {
                        runCatching {
                            if (java.awt.Desktop.isDesktopSupported()) {
                                java.awt.Desktop.getDesktop().open(java.io.File(song.filePath).parentFile)
                            }
                        }
                        onDismiss()
                    }
                }
                ActionEntry("复制歌曲信息") {
                    java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(
                        java.awt.datatransfer.StringSelection("${song.displayName()} - ${song.artistText()}"),
                        null,
                    )
                    showMessage("已复制歌曲信息")
                    onDismiss()
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun ActionEntry(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    )
}

@Composable
fun LocalArtistDetailScreen(
    container: AppContainer,
    artistName: String,
    onBack: () -> Unit,
    showMessage: (String) -> Unit,
) {
    val songs by container.library.songs.collectAsState()
    val artistSongs = remember(songs, artistName) { songs.filter { it.artistText() == artistName } }
    var actionSong by remember { mutableStateOf<Song?>(null) }

    DetailScaffold(title = "歌手", onBack = onBack) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                CollectionHeader(
                    name = artistName,
                    subtitle = "${artistSongs.size} 首歌曲 · 本地",
                    cover = { SongArtwork(artistSongs.firstOrNull(), size = 132.dp, shape = RoundedCornerShape(66.dp)) },
                    onPlayAll = {
                        if (artistSongs.isNotEmpty()) container.player.setQueue(artistSongs, 0, autoPlay = true)
                    },
                    onShuffle = {
                        if (artistSongs.isNotEmpty()) container.player.setQueue(artistSongs.shuffled(), 0, autoPlay = true)
                    },
                )
            }
            if (artistSongs.isEmpty()) {
                item { EmptyState(title = "暂无歌曲") }
            }
            itemsIndexed(artistSongs, key = { _, song -> song.key }) { index, song ->
                SongRow(
                    song = song,
                    index = index,
                    onClick = { container.player.playSongNow(song, artistSongs) },
                    onMore = { actionSong = song },
                )
            }
        }
    }
    actionSong?.let { song ->
        SongActionsDialog(
            container = container,
            song = song,
            showMessage = showMessage,
            onDismiss = { actionSong = null },
        )
    }
}

@Composable
fun OnlineCollectionDetailScreen(
    container: AppContainer,
    collection: OnlineCollection,
    onBack: () -> Unit,
    showMessage: (String) -> Unit,
) {
    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var actionSong by remember { mutableStateOf<Song?>(null) }
    var reloadToken by remember { mutableStateOf(0) }

    LaunchedEffect(collection.id, reloadToken) {
        loading = true
        error = null
        runCatching {
            withContext(Dispatchers.IO) {
                when (collection.source) {
                    moe.ouom.neriplayer.desktop.core.MediaSource.NETEASE -> {
                        val detail = container.online.netease.playlistDetail(collection.id)
                        if (detail == null || detail.second.isEmpty()) {
                            container.online.netease.albumDetail(collection.id)?.second.orEmpty()
                        } else {
                            detail.second
                        }
                    }

                    else -> emptyList()
                }
            }
        }.onSuccess { songs = it }.onFailure { error = it.message ?: it.javaClass.simpleName }
        loading = false
    }

    DetailScaffold(title = collection.name, onBack = onBack) {
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
            }

            error != null -> ErrorCard(message = "加载失败：$error", onRetry = { reloadToken += 1 })

            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    CollectionHeader(
                        name = collection.name,
                        subtitle = buildString {
                            if (collection.creator.isNotBlank()) append(collection.creator)
                            if (collection.playCount > 0) {
                                if (isNotEmpty()) append(" · ")
                                append("播放 ${formatPlayCount(collection.playCount)}")
                            }
                            if (isNotEmpty()) append(" · ")
                            append("${songs.size} 首")
                        },
                        cover = {
                            RemoteArtwork(
                                url = collection.coverUrl,
                                size = 132.dp,
                                shape = RoundedCornerShape(20.dp),
                                fallback = Icons.Outlined.History,
                            )
                        },
                        onPlayAll = { if (songs.isNotEmpty()) container.player.setQueue(songs, 0, autoPlay = true) },
                        onShuffle = { if (songs.isNotEmpty()) container.player.setQueue(songs.shuffled(), 0, autoPlay = true) },
                    )
                }
                if (songs.isEmpty()) {
                    item { EmptyState(title = "暂时没有获取到歌曲", hint = "该歌单可能需要登录后才能完整访问") }
                }
                itemsIndexed(songs, key = { _, song -> song.key }) { index, song ->
                    SongRow(
                        song = song,
                        index = index,
                        onClick = { container.player.playSongNow(song, songs) },
                        onMore = { actionSong = song },
                    )
                }
            }
        }
    }
    actionSong?.let { song ->
        SongActionsDialog(
            container = container,
            song = song,
            showMessage = showMessage,
            onDismiss = { actionSong = null },
        )
    }
}

@Composable
fun RemoteArtistDetailScreen(
    container: AppContainer,
    artist: OnlineArtist,
    onBack: () -> Unit,
    showMessage: (String) -> Unit,
) {
    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var actionSong by remember { mutableStateOf<Song?>(null) }

    LaunchedEffect(artist.id) {
        loading = true
        songs = withContext(Dispatchers.IO) {
            runCatching { container.online.artistSongs(artist.id) }.getOrDefault(emptyList())
        }
        loading = false
    }

    DetailScaffold(title = artist.name, onBack = onBack) {
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    CollectionHeader(
                        name = artist.name,
                        subtitle = "网易云歌手 · 热门 ${songs.size} 首",
                        cover = { RemoteArtwork(artist.avatarUrl, 132.dp, RoundedCornerShape(66.dp)) },
                        onPlayAll = { if (songs.isNotEmpty()) container.player.setQueue(songs, 0, autoPlay = true) },
                        onShuffle = { if (songs.isNotEmpty()) container.player.setQueue(songs.shuffled(), 0, autoPlay = true) },
                    )
                }
                if (songs.isEmpty()) {
                    item { EmptyState(title = "暂无歌曲", hint = "该歌手的热门歌曲暂时不可用") }
                }
                itemsIndexed(songs, key = { _, song -> song.key }) { index, song ->
                    SongRow(
                        song = song,
                        index = index,
                        onClick = { container.player.playSongNow(song, songs) },
                        onMore = { actionSong = song },
                    )
                }
            }
        }
    }
    actionSong?.let { song ->
        SongActionsDialog(
            container = container,
            song = song,
            showMessage = showMessage,
            onDismiss = { actionSong = null },
        )
    }
}

@Composable
fun RecentScreen(
    container: AppContainer,
    onBack: () -> Unit,
    showMessage: (String) -> Unit,
) {
    val entries by container.history.entries.collectAsState()
    val songs = entries.map { it.song }
    var showClear by remember { mutableStateOf(false) }
    val formatter = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

    DetailScaffold(
        title = "最近播放",
        onBack = onBack,
        actions = {
            if (entries.isNotEmpty()) {
                IconButton(onClick = { showClear = true }) {
                    Icon(Icons.Outlined.Delete, contentDescription = "清空最近播放")
                }
            }
        },
    ) {
        if (entries.isEmpty()) {
            EmptyState(
                title = "暂无最近播放",
                hint = "播放过的歌曲会出现在这里",
                icon = Icons.Outlined.History,
            )
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    Text(
                        text = "共 ${entries.size} 条记录",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                itemsIndexed(entries, key = { _, entry -> entry.song.key }) { index, entry ->
                    SongRow(
                        song = entry.song,
                        index = index,
                        onClick = { container.player.playSongNow(entry.song, songs) },
                        subtitle = "${entry.song.artistText()} · ${formatter.format(Date(entry.playedAt))} · 播放 ${entry.playCount} 次",
                    )
                }
            }
        }
    }

    if (showClear) {
        AlertDialog(
            onDismissRequest = { showClear = false },
            title = { Text("清空最近播放") },
            text = { Text("确定要清空全部最近播放记录吗？此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    container.history.clear()
                    showClear = false
                    showMessage("最近播放已清空")
                }) { Text("清空") }
            },
            dismissButton = { TextButton(onClick = { showClear = false }) { Text("取消") } },
        )
    }
}

@Composable
fun StatsScreen(
    container: AppContainer,
    onBack: () -> Unit,
    showMessage: (String) -> Unit,
) {
    val stats by container.stats.stats.collectAsState()
    val history by container.history.entries.collectAsState()
    var period by remember { mutableStateOf(StatsPeriod.WEEK) }
    var sort by remember { mutableStateOf(StatsSort.PLAY_COUNT) }
    var showClear by remember { mutableStateOf(false) }

    val summary = remember(stats, period) { container.stats.summary(period) }
    val top = remember(stats, history, period, sort) { container.stats.topSongs(period, sort, 50) }

    DetailScaffold(
        title = "播放统计",
        onBack = onBack,
        actions = {
            IconButton(onClick = { showClear = true }) {
                Icon(Icons.Outlined.Delete, contentDescription = "清空统计")
            }
        },
    ) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    StatCard("播放次数", summary.plays.toString(), Modifier.weight(1f))
                    StatCard("累计时长", formatLongDuration(summary.listenMs), Modifier.weight(1f))
                    StatCard("曲目数", summary.trackCount.toString(), Modifier.weight(1f))
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(
                        StatsPeriod.DAY to "日",
                        StatsPeriod.WEEK to "周",
                        StatsPeriod.MONTH to "月",
                        StatsPeriod.YEAR to "年",
                        StatsPeriod.ALL to "总",
                    ).forEach { (value, label) ->
                        FilterChip(
                            selected = period == value,
                            onClick = { period = value },
                            label = { Text(label) },
                        )
                    }
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(
                        StatsSort.PLAY_COUNT to "按播放次数",
                        StatsSort.LISTEN_TIME to "按累计时长",
                        StatsSort.RECENT to "按最近播放",
                    ).forEach { (value, label) ->
                        FilterChip(
                            selected = sort == value,
                            onClick = { sort = value },
                            label = { Text(label) },
                        )
                    }
                }
            }
            item { SectionHeader(title = "最常播放") }
            if (top.isEmpty()) {
                item {
                    EmptyState(
                        title = "当前时间范围内还没有播放统计",
                        hint = "开始听歌后这里会显示统计数据",
                    )
                }
            }
            itemsIndexed(top, key = { _, item -> item.song.key }) { index, item ->
                SongRow(
                    song = item.song,
                    index = index,
                    onClick = { container.player.playSongNow(item.song, top.map { it.song }) },
                    subtitle = "${item.playCount} 次 · ${formatLongDuration(item.listenMs)} · ${formatDuration(item.song.durationMs)}",
                )
            }
        }
    }

    if (showClear) {
        AlertDialog(
            onDismissRequest = { showClear = false },
            title = { Text("清空统计数据") },
            text = { Text("确定要清空所有播放统计数据吗？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    container.stats.clear()
                    showClear = false
                    showMessage("播放统计已清空")
                }) { Text("清空") }
            },
            dismissButton = { TextButton(onClick = { showClear = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
