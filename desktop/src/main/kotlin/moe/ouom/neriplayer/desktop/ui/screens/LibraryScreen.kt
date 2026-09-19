package moe.ouom.neriplayer.desktop.ui.screens

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.desktop.core.AppContainer
import moe.ouom.neriplayer.desktop.core.AlbumGroup
import moe.ouom.neriplayer.desktop.core.ArtistGroup
import moe.ouom.neriplayer.desktop.core.Playlist
import moe.ouom.neriplayer.desktop.core.MediaSource
import moe.ouom.neriplayer.desktop.core.OnlineArtist
import moe.ouom.neriplayer.desktop.core.OnlineCollection
import moe.ouom.neriplayer.desktop.core.Song
import moe.ouom.neriplayer.desktop.ui.AlbumTile
import moe.ouom.neriplayer.desktop.ui.ArtistTile
import moe.ouom.neriplayer.desktop.ui.CollectionCard
import moe.ouom.neriplayer.desktop.ui.CreatePlaylistDialog
import moe.ouom.neriplayer.desktop.ui.EmptyState
import moe.ouom.neriplayer.desktop.ui.LocalPlaylistCard
import moe.ouom.neriplayer.desktop.ui.RemoteArtwork
import moe.ouom.neriplayer.desktop.ui.SectionHeader
import moe.ouom.neriplayer.desktop.ui.SongRow
import moe.ouom.neriplayer.desktop.ui.formatLongDuration

private enum class LibraryPrimaryTab(val label: String) {
    LOCAL("本地"),
    FAVORITE("收藏"),
    NETEASE("网易云"),
    BILI("哔哩哔哩"),
    QQ("QQ 音乐"),
}

private enum class LocalCategory(val label: String) {
    SONGS("歌曲"),
    ARTISTS("歌手"),
    ALBUMS("专辑"),
    PLAYLISTS("歌单"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    container: AppContainer,
    onOpenLocalPlaylist: (String) -> Unit,
    onOpenCollection: (OnlineCollection) -> Unit,
    onOpenLocalArtist: (String) -> Unit,
    onOpenRemoteArtist: (OnlineArtist) -> Unit,
    onOpenRecent: () -> Unit,
    onOpenStats: () -> Unit,
    showMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val songs by container.library.songs.collectAsState()
    val scanState by container.library.scanState.collectAsState()
    val playlists by container.playlists.playlists.collectAsState()
    val settings by container.settings.state.collectAsState()
    val accounts by container.accounts.state.collectAsState()
    var primaryTab by remember { mutableStateOf(LibraryPrimaryTab.LOCAL) }
    var category by remember { mutableStateOf(LocalCategory.SONGS) }
    var query by remember { mutableStateOf("") }
    var showCreatePlaylist by remember { mutableStateOf(false) }
    var onlineCollections by remember { mutableStateOf<List<OnlineCollection>>(emptyList()) }
    var onlineArtists by remember { mutableStateOf<List<OnlineArtist>>(emptyList()) }
    var onlineSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var onlineLoading by remember { mutableStateOf(false) }
    var onlineApplied by remember { mutableStateOf(false) }
    var myCollections by remember { mutableStateOf<List<OnlineCollection>>(emptyList()) }

    val filteredSongs = remember(songs, query) {
        if (query.isBlank()) songs else songs.filter {
            it.displayName().contains(query, ignoreCase = true) ||
                it.artist.contains(query, ignoreCase = true) ||
                it.album.contains(query, ignoreCase = true)
        }
    }
    val artists = remember(filteredSongs) { groupArtists(filteredSongs) }
    val albums = remember(filteredSongs) { container.library.albums().filter { group -> filteredSongs.any { it.key == group.songs.first().key } } }
    val favorites = playlists.firstOrNull { it.system }
    val userPlaylists = playlists.filter { !it.system }

    LaunchedEffect(primaryTab, query, accounts) {
        if (primaryTab != LibraryPrimaryTab.NETEASE && primaryTab != LibraryPrimaryTab.BILI) return@LaunchedEffect
        onlineLoading = true
        val keyword = query.ifBlank { "热门" }
        val neteaseUid = container.accounts.accountOf(MediaSource.NETEASE)?.userId.orEmpty()
        val biliMid = container.accounts.accountOf(MediaSource.BILIBILI)?.userId.orEmpty()
        withContext(Dispatchers.IO) {
            myCollections = when (primaryTab) {
                LibraryPrimaryTab.NETEASE -> if (neteaseUid.isBlank()) {
                    emptyList()
                } else {
                    runCatching { container.online.netease.userPlaylists(neteaseUid) }.getOrDefault(emptyList())
                }

                LibraryPrimaryTab.BILI -> if (biliMid.isBlank()) {
                    emptyList()
                } else {
                    runCatching { container.online.bilibili.favoriteFolders(biliMid) }.getOrDefault(emptyList())
                }

                else -> emptyList()
            }
            when (primaryTab) {
                LibraryPrimaryTab.NETEASE -> {
                    onlineCollections = if (query.isBlank()) {
                        runCatching { container.online.netease.recommendedPlaylists(12) }.getOrDefault(emptyList())
                    } else {
                        runCatching { container.online.netease.searchPlaylists(keyword, 20) }.getOrDefault(emptyList())
                    }
                    onlineArtists = if (query.isBlank()) emptyList() else {
                        runCatching { container.online.netease.searchArtists(keyword, 20) }.getOrDefault(emptyList())
                    }
                    onlineSongs = emptyList()
                }

                LibraryPrimaryTab.BILI -> {
                    onlineSongs = if (query.isBlank()) emptyList() else {
                        runCatching { container.online.bilibili.searchVideos(keyword) }.getOrDefault(emptyList())
                    }
                    onlineCollections = emptyList()
                    onlineArtists = emptyList()
                }

                else -> Unit
            }
        }
        onlineApplied = query.isNotBlank()
        onlineLoading = false
    }

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("媒体库") },
            actions = {
                IconButton(onClick = { onOpenRecent() }) {
                    Icon(Icons.Outlined.History, contentDescription = "最近播放")
                }
                IconButton(onClick = { onOpenStats() }) {
                    Icon(Icons.Outlined.BarChart, contentDescription = "播放统计")
                }
                if (primaryTab == LibraryPrimaryTab.LOCAL) {
                    IconButton(onClick = { container.scope.launch { container.library.scan() } }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "重新扫描")
                    }
                }
            },
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LibraryPrimaryTab.entries.forEach { tab ->
                FilterChip(
                    selected = primaryTab == tab,
                    onClick = { primaryTab = tab },
                    label = { Text(tab.label) },
                )
            }
        }

        when (primaryTab) {
            LibraryPrimaryTab.LOCAL, LibraryPrimaryTab.FAVORITE -> {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    placeholder = { Text("搜索本地歌曲 / 歌手 / 专辑") },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    singleLine = true,
                )
            }

            else -> {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    placeholder = {
                        Text(if (primaryTab == LibraryPrimaryTab.NETEASE) "搜索网易云歌单 / 歌手" else "搜索哔哩哔哩视频")
                    },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    singleLine = true,
                )
            }
        }

        if (scanState.running) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                LinearProgressIndicator(
                    progress = { scanState.progress },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                )
                Text(
                    text = "正在扫描本地音乐 ${scanState.processed}/${scanState.total}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }

        Box(Modifier.fillMaxSize()) {
            when (primaryTab) {
                LibraryPrimaryTab.LOCAL -> LocalLibraryContent(
                    container = container,
                    category = category,
                    onCategoryChange = { category = it },
                    songs = filteredSongs,
                    artists = artists,
                    albums = albums,
                    favorites = favorites?.songs.orEmpty(),
                    userPlaylists = userPlaylists,
                    onCreatePlaylist = { showCreatePlaylist = true },
                    onOpenLocalPlaylist = onOpenLocalPlaylist,
                    onOpenLocalArtist = onOpenLocalArtist,
                    showMessage = showMessage,
                )

                LibraryPrimaryTab.FAVORITE -> FavoriteContent(
                    container = container,
                    favorites = favorites?.songs.orEmpty(),
                    userPlaylists = userPlaylists.map { it.id to it.name },
                    onOpenLocalPlaylist = onOpenLocalPlaylist,
                    onCreatePlaylist = { showCreatePlaylist = true },
                    onOpenRecent = onOpenRecent,
                    onOpenStats = onOpenStats,
                    showMessage = showMessage,
                )

                LibraryPrimaryTab.NETEASE, LibraryPrimaryTab.BILI -> {
                    if (onlineLoading) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(26.dp))
                        }
                    } else {
                        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                            if (primaryTab == LibraryPrimaryTab.NETEASE) {
                                item {
                                    Text(
                                        text = "网易云未登录时展示公开推荐内容，登录后可查看自己的歌单与收藏。",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                                    )
                                }
                                if (myCollections.isNotEmpty()) {
                                    item { SectionHeader(title = "我的歌单（${myCollections.size}）") }
                                    item {
                                        LazyRow(
                                            contentPadding = PaddingValues(horizontal = 12.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            items(myCollections, key = { "mine-${it.id}" }) { collection ->
                                                CollectionCard(collection, onClick = { onOpenCollection(collection) })
                                            }
                                        }
                                    }
                                }
                                if (onlineCollections.isNotEmpty()) {
                                    item { SectionHeader(title = if (onlineApplied) "搜索结果" else "推荐歌单") }
                                    item {
                                        LazyRow(
                                            contentPadding = PaddingValues(horizontal = 12.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            items(onlineCollections, key = { it.id }) { collection ->
                                                CollectionCard(collection, onClick = { onOpenCollection(collection) })
                                            }
                                        }
                                    }
                                }
                                if (onlineArtists.isNotEmpty()) {
                                    item { SectionHeader(title = "歌手") }
                                    items(onlineArtists, key = { it.id }) { artist ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            RemoteArtwork(artist.avatarUrl, 44.dp)
                                            Spacer(Modifier.width(12.dp))
                                            Text(artist.name, modifier = Modifier.weight(1f))
                                            TextButton(onClick = { onOpenRemoteArtist(artist) }) { Text("查看") }
                                        }
                                    }
                                }
                                if (onlineCollections.isEmpty() && onlineArtists.isEmpty()) {
                                    item {
                                        EmptyState(
                                            title = "没有找到匹配的内容",
                                            hint = "试试输入歌单名称或歌手名",
                                        )
                                    }
                                }
                            } else {
                                item {
                                    Text(
                                        text = "哔哩哔哩收藏夹与合集需要登录后加载，当前可直接搜索视频并播放音频。",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                                    )
                                }
                                if (myCollections.isNotEmpty()) {
                                    item { SectionHeader(title = "我的收藏夹（${myCollections.size}）") }
                                    item {
                                        LazyRow(
                                            contentPadding = PaddingValues(horizontal = 12.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            items(myCollections, key = { "fav-${it.id}" }) { collection ->
                                                CollectionCard(collection, onClick = { onOpenCollection(collection) })
                                            }
                                        }
                                    }
                                }
                                if (onlineSongs.isNotEmpty()) {
                                    item { SectionHeader(title = "搜索结果（${onlineSongs.size}）") }
                                    itemsIndexed(onlineSongs) { index, song ->
                                        SongRow(
                                            song = song,
                                            index = index,
                                            onClick = { container.player.playSongNow(song, onlineSongs) },
                                        )
                                    }
                                } else {
                                    item {
                                        EmptyState(
                                            title = "输入关键词搜索哔哩哔哩视频",
                                            hint = "支持搜索视频并直接播放音频轨道",
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                LibraryPrimaryTab.QQ -> EmptyState(
                    title = "QQ 音乐功能开发中…",
                    hint = "该音源尚未接入",
                    icon = Icons.Outlined.LibraryMusic,
                )
            }
        }
    }

    if (showCreatePlaylist) {
        CreatePlaylistDialog(
            onDismiss = { showCreatePlaylist = false },
            onConfirm = { name ->
                container.playlists.createPlaylist(name)
                showMessage("已创建歌单「$name」")
            },
        )
    }
}

private fun groupArtists(songs: List<Song>): List<ArtistGroup> =
    songs.groupBy { it.artistText() }
        .map { (name, list) -> ArtistGroup(name, list) }
        .sortedBy { it.name.lowercase() }

@Composable
private fun LocalLibraryContent(
    container: AppContainer,
    category: LocalCategory,
    onCategoryChange: (LocalCategory) -> Unit,
    songs: List<Song>,
    artists: List<ArtistGroup>,
    albums: List<AlbumGroup>,
    favorites: List<Song>,
    userPlaylists: List<Playlist>,
    onCreatePlaylist: () -> Unit,
    onOpenLocalPlaylist: (String) -> Unit,
    onOpenLocalArtist: (String) -> Unit,
    showMessage: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LocalCategory.entries.forEach { value ->
                FilterChip(
                    selected = category == value,
                    onClick = { onCategoryChange(value) },
                    label = { Text(value.label) },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        when (category) {
            LocalCategory.SONGS -> {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${songs.size} 首 · ${formatLongDuration(songs.sumOf { it.durationMs })}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { if (songs.isNotEmpty()) container.player.playSongNow(songs.first(), songs) }) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("播放全部")
                    }
                    TextButton(onClick = {
                        if (songs.isNotEmpty()) {
                            container.player.setQueue(songs.shuffled(), 0, autoPlay = true)
                        }
                    }) {
                        Icon(Icons.Outlined.Shuffle, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("随机")
                    }
                }
                if (songs.isEmpty()) {
                    EmptyState(
                        title = "还没有本地音乐",
                        hint = "在设置 - 媒体库 中添加音乐文件夹，然后点击右上角刷新按钮扫描",
                        icon = Icons.Outlined.Folder,
                    )
                } else {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                        itemsIndexed(songs, key = { _, song -> song.key }) { index, song ->
                            SongRow(
                                song = song,
                                index = index,
                                onClick = { container.player.playSongNow(song, songs) },
                                onMore = { container.player.enqueueNext(listOf(song)) },
                            )
                        }
                    }
                }
            }

            LocalCategory.ARTISTS -> {
                if (artists.isEmpty()) {
                    EmptyState(
                        title = "暂无本地歌手",
                        hint = "导入本地音频后，会按歌曲艺术家自动分类",
                        icon = Icons.Outlined.Person,
                    )
                } else {
                    LazyRow(
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(artists, key = { it.name }) { group ->
                            ArtistTile(group, onClick = { onOpenLocalArtist(group.name) })
                        }
                    }
                }
            }

            LocalCategory.ALBUMS -> {
                if (albums.isEmpty()) {
                    EmptyState(
                        title = "暂无本地专辑",
                        hint = "带有专辑标签的本地音乐会显示在这里",
                        icon = Icons.Outlined.Album,
                    )
                } else {
                    LazyRow(
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(albums, key = { it.name + it.artist }) { group ->
                            AlbumTile(group, onClick = { container.player.playSongNow(group.songs.first(), group.songs) })
                        }
                    }
                }
            }

            LocalCategory.PLAYLISTS -> {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(listOf("favorites") + userPlaylists.map { it.id }) { id ->
                                if (id == "favorites") {
                                    LocalPlaylistCard(
                                        name = "我喜欢的音乐",
                                        songs = favorites,
                                        onClick = { onOpenLocalPlaylist("favorites") },
                                    )
                                } else {
                                    val playlist = userPlaylists.first { it.id == id }
                                    LocalPlaylistCard(
                                        name = playlist.name,
                                        songs = playlist.songs,
                                        onClick = { onOpenLocalPlaylist(playlist.id) },
                                    )
                                }
                            }
                        }
                    }
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = onCreatePlaylist) { Text("新建歌单") }
                        }
                    }
                    if (favorites.isEmpty() && userPlaylists.isEmpty()) {
                        item {
                            EmptyState(
                                title = "还没有歌单",
                                hint = "新建歌单后，可以从歌曲列表把喜欢的歌加进来",
                                icon = Icons.Outlined.LibraryMusic,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FavoriteContent(
    container: AppContainer,
    favorites: List<Song>,
    userPlaylists: List<Pair<String, String>>,
    onOpenLocalPlaylist: (String) -> Unit,
    onCreatePlaylist: () -> Unit,
    onOpenRecent: () -> Unit,
    onOpenStats: () -> Unit,
    showMessage: (String) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.Favorite,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "我喜欢的音乐",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "${favorites.size} 首",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { if (favorites.isNotEmpty()) container.player.playSongNow(favorites.first(), favorites) }) {
                            Text("播放全部")
                        }
                        TextButton(onClick = { onOpenLocalPlaylist("favorites") }) { Text("查看歌单") }
                    }
                }
            }
        }
        item { SectionHeader(title = "我的歌单", icon = Icons.Outlined.LibraryMusic) }
        if (userPlaylists.isEmpty()) {
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text(
                        text = "还没有创建歌单",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            items(userPlaylists, key = { it.first }) { (id, name) ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = { onOpenLocalPlaylist(id) }) { Text("打开") }
                }
            }
        }
        item {
            TextButton(onClick = onCreatePlaylist, modifier = Modifier.padding(horizontal = 12.dp)) {
                Text("＋ 新建歌单")
            }
        }
        item { SectionHeader(title = "更多", icon = Icons.Outlined.History) }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onOpenRecent) { Text("最近播放") }
                TextButton(onClick = onOpenStats) { Text("播放统计") }
            }
        }
        if (favorites.isNotEmpty()) {
            item { SectionHeader(title = "喜欢的歌曲（${favorites.size}）") }
            itemsIndexed(favorites, key = { _, song -> song.key }) { index, song ->
                SongRow(
                    song = song,
                    index = index,
                    onClick = { container.player.playSongNow(song, favorites) },
                    onMore = { showMessage("在歌单详情中可移除该歌曲") },
                )
            }
        }
    }
}
