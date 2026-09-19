package moe.ouom.neriplayer.desktop.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import moe.ouom.neriplayer.desktop.core.OnlineArtist
import moe.ouom.neriplayer.desktop.core.OnlineCollection

enum class MainTab(val label: String, val icon: ImageVector) {
    HOME("首页", Icons.Outlined.Home),
    EXPLORE("探索", Icons.Outlined.Explore),
    LIBRARY("媒体库", Icons.Outlined.LibraryMusic),
    SETTINGS("设置", Icons.Outlined.Settings),
}

sealed interface Screen {
    data class Tab(val tab: MainTab) : Screen
    data object NowPlaying : Screen
    data object Recent : Screen
    data object Stats : Screen
    data class LocalPlaylistDetail(val playlistId: String) : Screen
    data class OnlineCollectionDetail(val collection: OnlineCollection) : Screen
    data class LocalArtistDetail(val name: String) : Screen
    data class RemoteArtistDetail(val artist: OnlineArtist) : Screen
}
