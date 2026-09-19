package moe.ouom.neriplayer.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import moe.ouom.neriplayer.desktop.core.AppContainer
import moe.ouom.neriplayer.desktop.core.DarkModeSetting
import moe.ouom.neriplayer.desktop.ui.screens.ExploreScreen
import moe.ouom.neriplayer.desktop.ui.screens.HomeScreen
import moe.ouom.neriplayer.desktop.ui.screens.LibraryScreen
import moe.ouom.neriplayer.desktop.ui.screens.LocalArtistDetailScreen
import moe.ouom.neriplayer.desktop.ui.screens.LocalPlaylistDetailScreen
import moe.ouom.neriplayer.desktop.ui.screens.NowPlayingScreen
import moe.ouom.neriplayer.desktop.ui.screens.OnlineCollectionDetailScreen
import moe.ouom.neriplayer.desktop.ui.screens.RecentScreen
import moe.ouom.neriplayer.desktop.ui.screens.RemoteArtistDetailScreen
import moe.ouom.neriplayer.desktop.ui.screens.SettingsScreen
import moe.ouom.neriplayer.desktop.ui.screens.StatsScreen
import moe.ouom.neriplayer.desktop.ui.theme.NeriTheme
import moe.ouom.neriplayer.desktop.tools.UiScriptHost
import moe.ouom.neriplayer.desktop.tools.runUiScript

@Composable
fun NeriApp(container: AppContainer) {
    val settings by container.settings.state.collectAsState()
    val coverSeed by container.player.coverSeedColor.collectAsState()
    val systemDark = isSystemInDarkTheme()
    val isDark = when (settings.darkMode) {
        DarkModeSetting.AUTO -> systemDark
        DarkModeSetting.LIGHT -> false
        DarkModeSetting.DARK -> true
    }
    val seed = if (settings.dynamicColor && !coverSeed.isNullOrBlank()) {
        coverSeed!!
    } else {
        settings.themeSeedColor
    }
    NeriTheme(
        seedColorHex = seed,
        isDark = isDark,
        paletteStyle = settings.paletteStyle,
        colorSpec = settings.colorSpec,
    ) {
        AppScaffold(container)
    }
}

@Composable
private fun AppScaffold(container: AppContainer) {
    val backStack = remember { mutableStateListOf<Screen>(Screen.Tab(MainTab.HOME)) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val settings by container.settings.state.collectAsState()
    val song by container.player.currentSong.collectAsState()
    val state by container.player.state.collectAsState()
    val position by container.player.positionMs.collectAsState()
    val duration by container.player.durationMs.collectAsState()
    var showOnboarding by remember { mutableStateOf(!settings.onboardingAccepted) }
    var uiTestOverlay by remember { mutableStateOf<String?>(null) }
    var loginSource by remember { mutableStateOf<moe.ouom.neriplayer.desktop.core.MediaSource?>(null) }
    val testScript = remember { System.getenv("NERIPLAYER_UI_TEST").orEmpty() }

    LaunchedEffect(Unit) {
        container.player.messages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    LaunchedEffect(testScript) {
        if (testScript.isBlank()) return@LaunchedEffect
        showOnboarding = false
        delay(1500)
        val host = object : UiScriptHost {
            override fun selectTab(tab: String) {
                val target = when (tab.lowercase()) {
                    "home" -> MainTab.HOME
                    "explore" -> MainTab.EXPLORE
                    "library" -> MainTab.LIBRARY
                    "settings" -> MainTab.SETTINGS
                    else -> MainTab.HOME
                }
                backStack.clear()
                backStack.add(Screen.Tab(target))
                println("[ui-test] tab=$tab")
            }

            override fun openScreen(screen: String, argument: String) {
                val target = when (screen.lowercase()) {
                    "nowplaying" -> Screen.NowPlaying
                    "recent" -> Screen.Recent
                    "stats" -> Screen.Stats
                    "playlist" -> Screen.LocalPlaylistDetail(argument)
                    "artist" -> Screen.LocalArtistDetail(argument)
                    else -> null
                }
                if (target != null) {
                    backStack.add(target)
                    println("[ui-test] screen=$screen")
                }
            }

            override fun openCollection(collection: moe.ouom.neriplayer.desktop.core.OnlineCollection) {
                backStack.add(Screen.OnlineCollectionDetail(collection))
                println("[ui-test] collection=${collection.name}")
            }

            override fun goBack() {
                if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
            }

            override fun setOverlay(name: String?) {
                uiTestOverlay = name
                if (name != null && name.startsWith("login")) {
                    loginSource = when (name.removePrefix("login").removePrefix("-").lowercase()) {
                        "netease" -> moe.ouom.neriplayer.desktop.core.MediaSource.NETEASE
                        "bilibili", "bili" -> moe.ouom.neriplayer.desktop.core.MediaSource.BILIBILI
                        else -> null
                    }
                }
            }

            override fun showMessage(message: String) {
                scope.launch { snackbarHostState.showSnackbar(message) }
            }

            override fun log(message: String) {
                println("[ui-test] $message")
            }
        }
        val ok = runUiScript(testScript, container, host)
        println("[ui-test] UI_SCRIPT_RESULT ok=$ok")
        if (System.getenv("NERIPLAYER_UI_TEST_EXIT") == "1") {
            delay(500)
            kotlin.system.exitProcess(if (ok) 0 else 1)
        }
    }

    val current = backStack.last()
    val showBottomBar = current is Screen.Tab

    fun navigate(screen: Screen) {
        backStack.add(screen)
    }

    fun goBack() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (showBottomBar) {
                Column {
                    MiniPlayer(
                        song = song,
                        isPlaying = state == moe.ouom.neriplayer.desktop.core.PlaybackState.PLAYING,
                        positionMs = position,
                        durationMs = duration,
                        onToggle = { container.player.togglePlayPause() },
                        onNext = { container.player.next() },
                        onPrevious = { container.player.previous() },
                        onOpen = { navigate(Screen.NowPlaying) },
                    )
                    NeriBottomBar(
                        selected = (current as Screen.Tab).tab,
                        onSelect = { tab ->
                            backStack.clear()
                            backStack.add(Screen.Tab(tab))
                        },
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            when (current) {
                is Screen.Tab -> when (current.tab) {
                    MainTab.HOME -> HomeScreen(
                        container = container,
                        onOpenPlaylist = { navigate(Screen.OnlineCollectionDetail(it)) },
                        onOpenRecent = { navigate(Screen.Recent) },
                        onOpenSettings = {
                            backStack.clear()
                            backStack.add(Screen.Tab(MainTab.SETTINGS))
                        },
                        showMessage = { message -> scope.launch { snackbarHostState.showSnackbar(message) } },
                    )

                    MainTab.EXPLORE -> ExploreScreen(
                        container = container,
                        onOpenCollection = { navigate(Screen.OnlineCollectionDetail(it)) },
                        onOpenRemoteArtist = { navigate(Screen.RemoteArtistDetail(it)) },
                        showMessage = { message -> scope.launch { snackbarHostState.showSnackbar(message) } },
                    )

                    MainTab.LIBRARY -> LibraryScreen(
                        container = container,
                        onOpenLocalPlaylist = { navigate(Screen.LocalPlaylistDetail(it)) },
                        onOpenCollection = { navigate(Screen.OnlineCollectionDetail(it)) },
                        onOpenLocalArtist = { navigate(Screen.LocalArtistDetail(it)) },
                        onOpenRemoteArtist = { navigate(Screen.RemoteArtistDetail(it)) },
                        onOpenRecent = { navigate(Screen.Recent) },
                        onOpenStats = { navigate(Screen.Stats) },
                        showMessage = { message -> scope.launch { snackbarHostState.showSnackbar(message) } },
                    )

                    MainTab.SETTINGS -> SettingsScreen(
                        container = container,
                        showMessage = { message -> scope.launch { snackbarHostState.showSnackbar(message) } },
                        onRequestLogin = { source -> loginSource = source },
                    )
                }

                Screen.NowPlaying -> NowPlayingScreen(
                    container = container,
                    onBack = { goBack() },
                    showMessage = { message -> scope.launch { snackbarHostState.showSnackbar(message) } },
                    externalOverlay = uiTestOverlay,
                )

                Screen.Recent -> RecentScreen(
                    container = container,
                    onBack = { goBack() },
                    showMessage = { message -> scope.launch { snackbarHostState.showSnackbar(message) } },
                )

                Screen.Stats -> StatsScreen(
                    container = container,
                    onBack = { goBack() },
                    showMessage = { message -> scope.launch { snackbarHostState.showSnackbar(message) } },
                )

                is Screen.LocalPlaylistDetail -> LocalPlaylistDetailScreen(
                    container = container,
                    playlistId = current.playlistId,
                    onBack = { goBack() },
                    showMessage = { message -> scope.launch { snackbarHostState.showSnackbar(message) } },
                )

                is Screen.LocalArtistDetail -> LocalArtistDetailScreen(
                    container = container,
                    artistName = current.name,
                    onBack = { goBack() },
                    showMessage = { message -> scope.launch { snackbarHostState.showSnackbar(message) } },
                )

                is Screen.OnlineCollectionDetail -> OnlineCollectionDetailScreen(
                    container = container,
                    collection = current.collection,
                    onBack = { goBack() },
                    showMessage = { message -> scope.launch { snackbarHostState.showSnackbar(message) } },
                )

                is Screen.RemoteArtistDetail -> RemoteArtistDetailScreen(
                    container = container,
                    artist = current.artist,
                    onBack = { goBack() },
                    showMessage = { message -> scope.launch { snackbarHostState.showSnackbar(message) } },
                )
            }
        }
    }

    if (showOnboarding) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("使用须知") },
            text = {
                Text(
                    text = "NeriPlayer 桌面版是原 Android 应用在 Linux 上的原生 Compose Desktop 复刻，仅供学习与研究使用。\n\n" +
                        "在线音源通过各平台公开接口访问，内容版权归原平台与权利人所有；请在你有权访问的范围内使用，" +
                        "并遵守对应平台的服务条款。应用不提供任何媒体内容、密钥或绕过付费/DRM/地区限制的能力。\n\n" +
                        "本地播放依赖系统 ffmpeg 进行解码，未安装时会自动使用 Java Sound 回退引擎（支持格式较少）。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    container.settings.update { it.copy(onboardingAccepted = true) }
                    showOnboarding = false
                }) { Text("我已了解并同意") }
            },
        )
    }

    val pendingLogin = loginSource
    if (pendingLogin != null) {
        LoginDialog(
            container = container,
            source = pendingLogin,
            onDismiss = { loginSource = null },
            showMessage = { message -> scope.launch { snackbarHostState.showSnackbar(message) } },
        )
    }
}
