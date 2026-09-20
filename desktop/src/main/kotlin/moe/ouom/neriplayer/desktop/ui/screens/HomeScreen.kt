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
import androidx.compose.ui.graphics.vector.ImageVector
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
import moe.ouom.neriplayer.desktop.ui.ResponsivePair
import moe.ouom.neriplayer.desktop.ui.SectionHeader
import moe.ouom.neriplayer.desktop.ui.SongArtwork
import moe.ouom.neriplayer.desktop.ui.SongRow
import moe.ouom.neriplayer.desktop.ui.isWideAppLayout
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

    // 首页的「歌曲卡片」区块：横屏（宽窗口）布局下两两并排，窄窗口下仍是单列
    val online = neteaseData
    val recommendedSongs = online?.recommendedSongs.orEmpty()
    val topSongs = online?.topSongs.orEmpty()
    val newSongs = online?.newSongs.orEmpty()
    val radarPlaylists = online?.radarPlaylists.orEmpty()
    val hotPlaylists = online?.hotPlaylists.orEmpty()
    val songCardSections = buildList {
        if (favoriteSongs.isNotEmpty()) {
            add(HomeSongSectionData("我喜欢的音乐（${favoriteSongs.size} 首）", Icons.Outlined.Star, favoriteSongs))
        }
        if (recommendedSongs.isNotEmpty()) {
            add(HomeSongSectionData("为你推荐", Icons.Outlined.Recommend, recommendedSongs))
        }
        if (topSongs.isNotEmpty()) {
            add(HomeSongSectionData("热歌榜", Icons.Outlined.LocalFireDepartment, topSongs))
        }
        if (newSongs.isNotEmpty()) {
            add(HomeSongSectionData("推荐新歌", Icons.Outlined.Recommend, newSongs))
        }
        if (mostPlayedLocal.isNotEmpty()) {
            add(HomeSongSectionData("常听本地歌曲", Icons.Outlined.History, mostPlayedLocal))
        }
    }
    // 横屏布局下每行两段，窄窗口下仍是一行一段
    val songCardColumns = if (isWideAppLayout) 2 else 1
    val isPlaying = playbackState == moe.ouom.neriplayer.desktop.core.PlaybackState.PLAYING

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

            if (settings.neteaseEnabled) {
                if (online?.error != null) {
                    item {
                        ErrorCard(
                            message = "加载在线推荐失败：${online.error}",
                            onRetry = { refreshToken += 1 },
                        )
                    }
                }
                if (loading && online == null) {
                    item { LoadingSection("正在为你加载首页推荐…") }
                }
            }

            songCardSections.chunked(songCardColumns).forEachIndexed { index, chunk ->
                item(key = "home-song-section-$index") {
                    if (chunk.size == 2) {
                        ResponsivePair(
                            first = {
                                HomeSongSection(
                                    section = chunk[0],
                                    currentSong = currentSong,
                                    isPlaying = isPlaying,
                                    onPlay = { song -> container.player.playSongNow(song, chunk[0].songs) },
                                )
                            },
                            second = {
                                HomeSongSection(
                                    section = chunk[1],
                                    currentSong = currentSong,
                                    isPlaying = isPlaying,
                                    onPlay = { song -> container.player.playSongNow(song, chunk[1].songs) },
                                )
                            },
                        )
                    } else {
                        HomeSongSection(
                            section = chunk[0],
                            currentSong = currentSong,
                            isPlaying = isPlaying,
                            onPlay = { song -> container.player.playSongNow(song, chunk[0].songs) },
                        )
                    }
                }
            }

            if (radarPlaylists.isNotEmpty() || hotPlaylists.isNotEmpty()) {
                item(key = "home-online-collections") {
                    ResponsivePair(
                        first = {
                            if (radarPlaylists.isNotEmpty()) {
                                OnlineCollectionRow(
                                    title = "私人雷达",
                                    icon = Icons.Outlined.Recommend,
                                    collections = radarPlaylists,
                                    onOpen = onOpenPlaylist,
                                )
                            }
                        },
                        second = {
                            if (hotPlaylists.isNotEmpty()) {
                                OnlineCollectionRow(
                                    title = "热门榜单",
                                    collections = hotPlaylists,
                                    onOpen = onOpenPlaylist,
                                )
                            }
                        },
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

/** 首页的一段「歌曲卡片」区块（标题 + 歌曲列表卡片）。 */
private data class HomeSongSectionData(
    val title: String,
    val icon: ImageVector?,
    val songs: List<Song>,
)

@Composable
private fun HomeSongSection(
    section: HomeSongSectionData,
    currentSong: Song?,
    isPlaying: Boolean,
    onPlay: (Song) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        SectionHeader(title = section.title, icon = section.icon)
        SongListCard(
            songs = section.songs.take(6),
            currentSong = currentSong,
            isPlaying = isPlaying,
            onPlay = onPlay,
        )
    }
}

/** 首页的一段在线歌单横排区块（标题 + 横向滚动的歌单卡片）。 */
@Composable
private fun OnlineCollectionRow(
    title: String,
    collections: List<OnlineCollection>,
    onOpen: (OnlineCollection) -> Unit,
    icon: ImageVector? = null,
) {
    Column(Modifier.fillMaxWidth()) {
        SectionHeader(title = title, icon = icon)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(collections, key = { it.id }) { collection ->
                CollectionCard(collection, onClick = { onOpen(collection) })
            }
        }
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
