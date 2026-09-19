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
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.desktop.core.AppContainer
import moe.ouom.neriplayer.desktop.core.MediaSource
import moe.ouom.neriplayer.desktop.core.OnlineArtist
import moe.ouom.neriplayer.desktop.core.OnlineCollection
import moe.ouom.neriplayer.desktop.core.SearchKind
import moe.ouom.neriplayer.desktop.core.SearchPayload
import moe.ouom.neriplayer.desktop.core.Song
import moe.ouom.neriplayer.desktop.ui.CollectionCard
import moe.ouom.neriplayer.desktop.ui.EmptyState
import moe.ouom.neriplayer.desktop.ui.ErrorCard
import moe.ouom.neriplayer.desktop.ui.RemoteArtwork
import moe.ouom.neriplayer.desktop.ui.SectionHeader
import moe.ouom.neriplayer.desktop.ui.SongRow

private val SEARCH_SOURCES = listOf(
    MediaSource.NETEASE to "网易云",
    MediaSource.BILIBILI to "哔哩哔哩",
    MediaSource.LOCAL to "本地媒体库",
)

private val NETEASE_KINDS = listOf(
    SearchKind.SONG to "歌曲",
    SearchKind.PLAYLIST to "歌单",
    SearchKind.ARTIST to "歌手",
    SearchKind.ALBUM to "专辑",
)

private val TAG_KEYWORDS = listOf(
    "流行", "摇滚", "电子", "民谣", "说唱", "轻音乐", "影视原声", "华语", "欧美", "日语",
    "粤语", "治愈", "伤感", "浪漫", "清新", "怀旧", "夜晚", "学习", "工作", "下午茶",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(
    container: AppContainer,
    onOpenCollection: (OnlineCollection) -> Unit,
    onOpenRemoteArtist: (OnlineArtist) -> Unit,
    showMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by container.settings.state.collectAsState()
    val library by container.library.songs.collectAsState()
    var query by remember { mutableStateOf("") }
    var source by remember { mutableStateOf(MediaSource.NETEASE) }
    var kind by remember { mutableStateOf(SearchKind.SONG) }
    var results by remember { mutableStateOf(SearchPayload()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var hotKeywords by remember { mutableStateOf<List<String>>(emptyList()) }
    var recommended by remember { mutableStateOf<List<OnlineCollection>>(emptyList()) }
    var submittedQuery by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        if (!settings.neteaseEnabled) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            hotKeywords = runCatching { container.online.netease.hotSearchKeywords() }.getOrDefault(emptyList())
            recommended = runCatching { container.online.netease.recommendedPlaylists(12) }.getOrDefault(emptyList())
        }
    }

    fun runSearch(keyword: String) {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return
        query = trimmed
        submittedQuery = trimmed
        if (source == MediaSource.LOCAL) {
            val matches = library.filter {
                it.displayName().contains(trimmed, ignoreCase = true) ||
                    it.artist.contains(trimmed, ignoreCase = true) ||
                    it.album.contains(trimmed, ignoreCase = true)
            }
            results = SearchPayload(songs = matches)
            return
        }
        container.settings.update { current ->
            current.copy(exploreSearchHistory = (listOf(trimmed) + current.exploreSearchHistory).distinct().take(12))
        }
    }

    LaunchedEffect(source, kind, submittedQuery) {
        if (submittedQuery.isBlank() || source == MediaSource.LOCAL) return@LaunchedEffect
        loading = true
        error = null
        val payload = runCatching {
            withContext(Dispatchers.IO) { container.online.search(source, kind, submittedQuery) }
        }.getOrElse { throwable ->
            error = throwable.message ?: throwable.javaClass.simpleName
            SearchPayload()
        }
        results = payload
        loading = false
    }

    Column(modifier.fillMaxSize()) {
        TopAppBar(title = { Text("探索") })
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            placeholder = { Text("搜索歌曲、歌单、歌手、专辑") },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = {
                        query = ""
                        submittedQuery = ""
                        results = SearchPayload()
                    }) {
                        Icon(Icons.Outlined.Close, contentDescription = "清空")
                    }
                }
            },
            singleLine = true,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SEARCH_SOURCES.forEach { (value, label) ->
                FilterChip(
                    selected = source == value,
                    onClick = {
                        source = value
                        if (value == MediaSource.LOCAL && submittedQuery.isNotBlank()) {
                            val matches = library.filter {
                                it.displayName().contains(submittedQuery, ignoreCase = true) ||
                                    it.artist.contains(submittedQuery, ignoreCase = true)
                            }
                            results = SearchPayload(songs = matches)
                        } else if (submittedQuery.isNotBlank()) {
                            results = SearchPayload()
                        }
                    },
                    label = { Text(label) },
                )
            }
        }
        if (source == MediaSource.NETEASE) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                NETEASE_KINDS.forEach { (value, label) ->
                    FilterChip(
                        selected = kind == value,
                        onClick = { kind = value },
                        label = { Text(label) },
                    )
                }
            }
        }
        TextButton(
            onClick = { runSearch(query) },
            modifier = Modifier.padding(horizontal = 8.dp),
        ) { Text("搜索") }

        Box(Modifier.fillMaxSize()) {
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                }

                error != null -> ErrorCard(
                    message = "搜索失败：$error",
                    onRetry = { runSearch(submittedQuery) },
                    modifier = Modifier.padding(top = 12.dp),
                )

                submittedQuery.isNotBlank() -> SearchResults(
                    container = container,
                    payload = results,
                    source = source,
                    onOpenCollection = onOpenCollection,
                    onOpenRemoteArtist = onOpenRemoteArtist,
                    showMessage = showMessage,
                )

                else -> DefaultExploreContent(
                    container = container,
                    searchHistory = settings.exploreSearchHistory,
                    hotKeywords = hotKeywords,
                    recommended = recommended,
                    onKeyword = { keyword ->
                        query = keyword
                        runSearch(keyword)
                    },
                    onClearHistory = { container.settings.update { it.copy(exploreSearchHistory = emptyList()) } },
                    onOpenCollection = onOpenCollection,
                    showMessage = showMessage,
                )
            }
        }
    }
}

@Composable
private fun DefaultExploreContent(
    container: AppContainer,
    searchHistory: List<String>,
    hotKeywords: List<String>,
    recommended: List<OnlineCollection>,
    onKeyword: (String) -> Unit,
    onClearHistory: () -> Unit,
    onOpenCollection: (OnlineCollection) -> Unit,
    showMessage: (String) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (searchHistory.isNotEmpty()) {
            item {
                SectionHeader(
                    title = "搜索历史",
                    icon = Icons.Outlined.History,
                    trailing = { TextButton(onClick = onClearHistory) { Text("清空") } },
                )
            }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(searchHistory) { keyword ->
                        FilterChip(selected = false, onClick = { onKeyword(keyword) }, label = { Text(keyword) })
                    }
                }
            }
        }
        if (hotKeywords.isNotEmpty()) {
            item { SectionHeader(title = "热门搜索", icon = Icons.Outlined.LocalFireDepartment) }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(hotKeywords.take(16)) { keyword ->
                        FilterChip(selected = false, onClick = { onKeyword(keyword) }, label = { Text(keyword) })
                    }
                }
            }
        }
        item { SectionHeader(title = "风格标签") }
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                TAG_KEYWORDS.chunked(6).forEach { rowTags ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowTags.forEach { tag ->
                            FilterChip(selected = false, onClick = { onKeyword(tag) }, label = { Text(tag) })
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
        if (recommended.isNotEmpty()) {
            item { SectionHeader(title = "推荐歌单（网易云）") }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(recommended, key = { it.id }) { collection ->
                        CollectionCard(collection, onClick = { onOpenCollection(collection) })
                    }
                }
            }
        }
        item { SectionHeader(title = "本地媒体库") }
        item {
            val songs by container.library.songs.collectAsState()
            if (songs.isEmpty()) {
                EmptyState(
                    title = "还没有本地音乐",
                    hint = "在设置 - 媒体库中添加音乐文件夹并扫描",
                )
            } else {
                Column {
                    songs.take(8).forEach { song ->
                        SongRow(
                            song = song,
                            onClick = { container.player.playSongNow(song, songs) },
                        )
                    }
                    TextButton(
                        onClick = { showMessage("在媒体库页面可以浏览全部本地音乐") },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    ) { Text("共 ${songs.size} 首本地歌曲") }
                }
            }
        }
    }
}

@Composable
private fun SearchResults(
    container: AppContainer,
    payload: SearchPayload,
    source: MediaSource,
    onOpenCollection: (OnlineCollection) -> Unit,
    onOpenRemoteArtist: (OnlineArtist) -> Unit,
    showMessage: (String) -> Unit,
) {
    val currentSong by container.player.currentSong.collectAsState()
    val playbackState by container.player.state.collectAsState()
    val empty = payload.songs.isEmpty() && payload.collections.isEmpty() && payload.artists.isEmpty()
    if (empty) {
        EmptyState(title = "无搜索结果", hint = "尝试使用其他关键词搜索")
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (payload.songs.isNotEmpty()) {
            item { SectionHeader(title = "${source.displayName}歌曲（${payload.songs.size}）") }
            itemsIndexed(payload.songs) { index, song ->
                SongRow(
                    song = song,
                    index = index,
                    onClick = { container.player.playSongNow(song, payload.songs) },
                    isCurrent = song.key == currentSong?.key,
                    isPlaying = playbackState == moe.ouom.neriplayer.desktop.core.PlaybackState.PLAYING,
                    trailing = {
                        TextButton(onClick = { container.player.enqueueNext(listOf(song)) }) { Text("下一首") }
                    },
                )
            }
        }
        if (payload.collections.isNotEmpty()) {
            item { SectionHeader(title = "歌单 / 专辑（${payload.collections.size}）") }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(payload.collections, key = { it.id }) { collection ->
                        CollectionCard(collection, onClick = { onOpenCollection(collection) })
                    }
                }
            }
        }
        if (payload.artists.isNotEmpty()) {
            item { SectionHeader(title = "歌手（${payload.artists.size}）") }
            items(payload.artists, key = { it.id }) { artist ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RemoteArtwork(url = artist.avatarUrl, size = 48.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(artist.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            text = if (artist.songCount > 0) "${artist.songCount} 首作品" else "网易云歌手",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { onOpenRemoteArtist(artist) }) { Text("查看") }
                }
            }
        }
    }
}
