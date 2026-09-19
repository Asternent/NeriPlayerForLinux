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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Recommend
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.desktop.core.AppContainer
import moe.ouom.neriplayer.desktop.core.MediaSource
import moe.ouom.neriplayer.desktop.core.OnlineCollection
import moe.ouom.neriplayer.desktop.core.Song
import moe.ouom.neriplayer.desktop.net.NeteaseHomeData
import moe.ouom.neriplayer.desktop.ui.CollectionCard
import moe.ouom.neriplayer.desktop.ui.EmptyState
import moe.ouom.neriplayer.desktop.ui.ErrorCard
import moe.ouom.neriplayer.desktop.ui.SectionHeader
import moe.ouom.neriplayer.desktop.ui.SongArtwork
import moe.ouom.neriplayer.desktop.ui.SongRow
import kotlin.random.Random

private val BRAND_TITLES = listOf("NeriPlayer", "音理音理!!", "音理音理~")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    container: AppContainer,
    onOpenPlaylist: (OnlineCollection) -> Unit,
    onOpenRecent: () -> Unit,
    onOpenSettings: () -> Unit,
    showMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by container.settings.state.collectAsState()
    val historyEntries by container.history.entries.collectAsState()
    val library by container.library.songs.collectAsState()
    val playlists by container.playlists.playlists.collectAsState()
    val currentSong by container.player.currentSong.collectAsState()
    val playbackState by container.player.state.collectAsState()
    val title = remember { BRAND_TITLES[Random.nextInt(BRAND_TITLES.size)] }
    val topBarState = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var neteaseData by remember { mutableStateOf<NeteaseHomeData?>(null) }
    var loading by remember { mutableStateOf(false) }
    var refreshToken by remember { mutableStateOf(0) }

    LaunchedEffect(refreshToken, settings.neteaseEnabled) {
        if (!settings.neteaseEnabled) {
            neteaseData = null
            return@LaunchedEffect
        }
        loading = true
        val data = withContext(Dispatchers.IO) { container.online.neteaseHome() }
        neteaseData = data
        loading = false
    }

    val continueEntries = remember(historyEntries) { historyEntries.take(12) }
    val favoritePlaylist = playlists.firstOrNull { it.system }
    val favoriteSongs = favoritePlaylist?.songs.orEmpty()
    val recentlyAdded = remember(library) { library.sortedByDescending { it.dateAdded }.take(12) }
    val mostPlayedLocal = remember(historyEntries) {
        historyEntries.sortedByDescending { it.playCount }.map { it.song }
            .filter { it.source == MediaSource.LOCAL }.take(12)
    }

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                LargeTopAppBar(
                    title = { Text(title, fontWeight = FontWeight.Bold) },
                    actions = {
                        IconButton(onClick = { refreshToken += 1 }) {
                            Icon(Icons.Outlined.Refresh, contentDescription = "刷新推荐")
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Outlined.Star, contentDescription = "设置")
                        }
                    },
                    scrollBehavior = topBarState,
                )
            }

            if (settings.homeCards.continuePlaying && continueEntries.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = "继续播放",
                        icon = Icons.Outlined.History,
                        trailing = {
                            TextButton(onClick = onOpenRecent) { Text("最近播放") }
                        },
                    )
                }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(continueEntries, key = { it.song.key }) { entry ->
                            ContinueCard(
                                song = entry.song,
                                onPlay = { container.player.playSongNow(entry.song, continueEntries.map { it.song }) },
                                onRemove = {
                                    container.history.removeEntry(entry.song.key)
                                    showMessage("已从最近播放移除")
                                },
                            )
                        }
                    }
                }
            }

            val continueSongs = continueEntries.map { it.song }
            if (favoriteSongs.isNotEmpty()) {
                item {
                    SectionHeader(title = "我喜欢的音乐（${favoriteSongs.size} 首）", icon = Icons.Outlined.Star)
                }
                item {
                    SongListCard(
                        songs = favoriteSongs.take(6),
                        currentSong = currentSong,
                        isPlaying = playbackState == moe.ouom.neriplayer.desktop.core.PlaybackState.PLAYING,
                        onPlay = { song -> container.player.playSongNow(song, favoriteSongs) },
                    )
                }
            }

            if (settings.neteaseEnabled) {
                val data = neteaseData
                if (data?.error != null) {
                    item {
                        ErrorCard(
                            message = "加载在线推荐失败：${data.error}",
                            onRetry = { refreshToken += 1 },
                        )
                    }
                }
                if (loading && data == null) {
                    item { LoadingSection("正在为你加载首页推荐…") }
                }
                if (!data?.recommendedSongs.isNullOrEmpty()) {
                    item { SectionHeader(title = "为你推荐", icon = Icons.Outlined.Recommend) }
                    item {
                        SongListCard(
                            songs = data!!.recommendedSongs.take(6),
                            currentSong = currentSong,
                            isPlaying = playbackState == moe.ouom.neriplayer.desktop.core.PlaybackState.PLAYING,
                            onPlay = { song -> container.player.playSongNow(song, data!!.recommendedSongs) },
                        )
                    }
                }
                if (!data?.topSongs.isNullOrEmpty()) {
                    item { SectionHeader(title = "热歌榜", icon = Icons.Outlined.LocalFireDepartment) }
                    item {
                        SongListCard(
                            songs = data!!.topSongs.take(6),
                            currentSong = currentSong,
                            isPlaying = playbackState == moe.ouom.neriplayer.desktop.core.PlaybackState.PLAYING,
                            onPlay = { song -> container.player.playSongNow(song, data!!.topSongs) },
                        )
                    }
                }
                if (!data?.newSongs.isNullOrEmpty()) {
                    item { SectionHeader(title = "推荐新歌", icon = Icons.Outlined.Recommend) }
                    item {
                        SongListCard(
                            songs = data!!.newSongs.take(6),
                            currentSong = currentSong,
                            isPlaying = playbackState == moe.ouom.neriplayer.desktop.core.PlaybackState.PLAYING,
                            onPlay = { song -> container.player.playSongNow(song, data!!.newSongs) },
                        )
                    }
                }
                if (!data?.radarPlaylists.isNullOrEmpty()) {
                    item { SectionHeader(title = "私人雷达", icon = Icons.Outlined.Recommend) }
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(data!!.radarPlaylists, key = { it.id }) { collection ->
                                CollectionCard(collection, onClick = { onOpenPlaylist(collection) })
                            }
                        }
                    }
                }
                if (!data?.hotPlaylists.isNullOrEmpty()) {
                    item { SectionHeader(title = "热门榜单") }
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(data!!.hotPlaylists, key = { it.id }) { collection ->
                                CollectionCard(collection, onClick = { onOpenPlaylist(collection) })
                            }
                        }
                    }
                }
            }

            if (mostPlayedLocal.isNotEmpty()) {
                item { SectionHeader(title = "常听本地歌曲", icon = Icons.Outlined.History) }
                item {
                    SongListCard(
                        songs = mostPlayedLocal.take(6),
                        currentSong = currentSong,
                        isPlaying = playbackState == moe.ouom.neriplayer.desktop.core.PlaybackState.PLAYING,
                        onPlay = { song -> container.player.playSongNow(song, mostPlayedLocal) },
                    )
                }
            }

            if (recentlyAdded.isNotEmpty()) {
                item { SectionHeader(title = "最近添加", icon = Icons.Outlined.History) }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(recentlyAdded, key = { it.key }) { song ->
                            RecentlyAddedCard(
                                song = song,
                                onClick = { container.player.playSongNow(song, recentlyAdded) },
                            )
                        }
                    }
                }
            }

            if (continueEntries.isEmpty() && favoriteSongs.isEmpty() && recentlyAdded.isEmpty() && !settings.neteaseEnabled) {
                item {
                    EmptyState(
                        title = "首页还没有内容",
                        hint = "在设置中添加音乐文件夹并扫描本地音乐，或开启在线音源",
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadingSection(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ContinueCard(
    song: Song,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
) {
    Surface(
        modifier = Modifier.width(220.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                SongArtwork(song, size = 56.dp, shape = RoundedCornerShape(12.dp))
                Surface(
                    modifier = Modifier.align(Alignment.Center).size(26.dp),
                    shape = RoundedCornerShape(13.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = "播放",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(16.dp).clickable(onClick = onPlay),
                        )
                    }
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = song.displayName(),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = song.artistText(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Outlined.Close, contentDescription = "移除", modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun RecentlyAddedCard(song: Song, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(150.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(6.dp),
    ) {
        SongArtwork(song, size = 138.dp, shape = RoundedCornerShape(14.dp))
        Spacer(Modifier.height(8.dp))
        Text(
            text = song.displayName(),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = song.artistText(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SongListCard(
    songs: List<Song>,
    currentSong: Song?,
    isPlaying: Boolean,
    onPlay: (Song) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(vertical = 6.dp)) {
            songs.forEachIndexed { index, song ->
                SongRow(
                    song = song,
                    index = index,
                    onClick = { onPlay(song) },
                    isCurrent = song.key == currentSong?.key,
                    isPlaying = isPlaying,
                )
            }
        }
    }
}
