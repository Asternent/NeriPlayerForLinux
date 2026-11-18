package moe.ouom.neriplayer.desktop.backend.api

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import moe.ouom.neriplayer.desktop.backend.CoreServer
import moe.ouom.neriplayer.desktop.backend.ShortcutCatalog
import moe.ouom.neriplayer.desktop.backend.PlayerWire
import moe.ouom.neriplayer.desktop.backend.ThemePalette
import moe.ouom.neriplayer.desktop.backend.WireJson
import moe.ouom.neriplayer.desktop.backend.badRequest
import moe.ouom.neriplayer.desktop.backend.boolOrNull
import moe.ouom.neriplayer.desktop.backend.doubleOrNull
import moe.ouom.neriplayer.desktop.backend.encodeAny
import moe.ouom.neriplayer.desktop.backend.encodeJson
import moe.ouom.neriplayer.desktop.backend.objectOrNull
import moe.ouom.neriplayer.desktop.backend.stringOrNull
import moe.ouom.neriplayer.desktop.core.AppContainer
import moe.ouom.neriplayer.desktop.core.AppSettings
import moe.ouom.neriplayer.desktop.core.DarkModeSetting
import moe.ouom.neriplayer.desktop.core.Song

/** 前端上报的系统主题（KDE 强调色 + 深浅色）。 */
object SystemThemeState {
    @Volatile
    var isDark: Boolean? = null

    @Volatile
    var accent: String = ThemePalette.DEFAULT_SEED_COLOR

    @Volatile
    var known: Boolean = false
}

/** 应用级命令：启动快照、设置、主题。 */
object AppApi {

    const val PROTOCOL_VERSION = 1

    /** 托盘是否可用（由 BackendMain 启动托盘后写入）。 */
    @Volatile
    var trayAvailable: Boolean = false

    /** 打开桌面系统的全局快捷键配置界面（由 BackendMain 注入）。 */
    @Volatile
    var openGlobalShortcutSettings: (() -> Unit)? = null

    fun register(server: CoreServer, container: AppContainer) {
        server.on("app.ping") { buildJsonObject { put("pong", true) } }

        server.on("app.shutdown") {
            // 前端退出时请求后端收尾（Qt 侧也会 kill 进程，这里只是留出优雅退出路径）
            Thread {
                Thread.sleep(150)
                Runtime.getRuntime().halt(0)
            }.apply { isDaemon = true }.start()
            buildJsonObject { put("ok", true) }
        }

        server.on("app.bootstrap") {
            bootstrapPayload(container)
        }

        server.on("app.setSystemTheme") { params ->
            SystemThemeState.isDark = params.boolOrNull("isDark")
            params.stringOrNull("accent")?.let { SystemThemeState.accent = it }
            SystemThemeState.known = true
            val theme = themePayload(container)
            server.broadcast("theme", theme)
            buildJsonObject { put("ok", true) }
        }

        server.on("app.refreshTheme") {
            val theme = themePayload(container)
            server.broadcast("theme", theme)
            theme
        }

        server.on("app.theme") {
            themePayload(container)
        }

        server.on("shortcuts.catalog") {
            buildJsonObject { put("actions", encodeAny(ShortcutCatalog.payload())) }
        }

        server.on("shortcut.openGlobalSettings") {
            openGlobalShortcutSettings?.invoke()
            buildJsonObject { put("ok", openGlobalShortcutSettings != null) }
        }

        server.on("settings.get") {
            buildJsonObject { put("settings", encodeJson(container.settings.current)) }
        }

        server.on("settings.set") { params ->
            val patch = params.objectOrNull("patch") ?: badRequest("缺少 patch")
            val current = encodeJson(container.settings.current).jsonObject
            val merged = JsonObject(current + patch)
            val next = runCatching {
                WireJson.decodeFromJsonElement(AppSettings.serializer(), merged)
            }.getOrElse { error ->
                badRequest("设置字段非法: ${error.message}")
            }
            container.settings.replace(next)
            server.broadcast("settings", encodeJson(container.settings.current))
            server.broadcast("theme", themePayload(container))
            buildJsonObject {
                put("settings", encodeJson(container.settings.current))
            }
        }

        server.on("app.setVolume") { params ->
            container.player.setVolume(params.doubleOrNull("value")?.toFloat() ?: badRequest("缺少 value"))
            buildJsonObject { put("ok", true) }
        }
    }

    /** 首次连接时推送完整初始状态（前端即使不调用 bootstrap 也能显示）。 */
    fun pushInitialState(server: CoreServer, container: AppContainer) {
        runCatching {
            server.broadcast("settings", encodeJson(container.settings.current))
            server.broadcast("theme", themePayload(container))
            server.broadcast("player", PlayerWire.state(container.player))
            server.broadcast("player.queue", PlayerWire.queue(container.player))
            server.broadcast("player.lyrics", PlayerWire.lyrics(container.player.lyrics.value))
        }
    }

    fun bootstrapPayload(container: AppContainer): JsonObject {
        val player = container.player
        val library = container.library
        val scan = library.scanState.value
        val download = container.downloads
        val syncState = container.sync.state.value
        return buildJsonObject {
            put("protocol", PROTOCOL_VERSION)
            put("appVersion", "2.0.0")
            put("settings", encodeJson(container.settings.current))
            put("theme", themePayload(container))
            put("player", PlayerWire.state(player))
            put("queue", encodeJson(player.queue.value))
            put("lyrics", PlayerWire.lyrics(player.lyrics.value))
            put("accounts", encodeJson(container.accounts.state.value.accounts))
            put("sync", buildJsonObject {
                put("configured", syncState.configured)
                put("running", syncState.running)
                put("lastSyncAt", syncState.lastSyncAt)
                put("message", syncState.message)
                put("success", syncState.success)
            })
            put("downloads", buildJsonObject {
                put("activeCount", download.activeCount.value)
                put("taskCount", download.tasks.value.size)
                put("itemCount", container.downloadCatalog.items.value.size)
            })
            put("library", buildJsonObject {
                put("songCount", library.songs.value.size)
                put("scanning", scan.running)
                put("scanProcessed", scan.processed)
                put("scanTotal", scan.total)
                put("scanFound", scan.found)
                put("folders", encodeAny(container.settings.current.musicFolders))
                put("indexLoaded", library.indexLoaded.value)
            })
            put("tray", buildJsonObject {
                put("available", trayAvailable)
            })
            put("shortcuts", buildJsonObject {
                put("actions", encodeAny(ShortcutCatalog.payload()))
            })
            put("playlists", buildJsonObject {
                put("count", container.playlists.playlists.value.size)
            })
            put("history", buildJsonObject {
                put("count", container.history.entries.value.size)
            })
        }
    }

    /**
     * 计算当前主题：
     * - 深浅色：darkMode=AUTO 时跟随系统；
     * - 种子色优先级：动态取色（封面）> 跟随系统强调色 > 设置里的主题色。
     */
    fun themePayload(container: AppContainer): JsonObject {
        val settings = container.settings.current
        val systemDark = SystemThemeState.isDark
        val isDark = when (settings.darkMode) {
            DarkModeSetting.LIGHT -> false
            DarkModeSetting.DARK -> true
            DarkModeSetting.AUTO -> systemDark ?: true
        }
        val coverSeed = container.player.coverSeedColor.value?.takeIf { it.isNotBlank() }
        val seed: String
        val seedSource: String
        when {
            settings.dynamicColor && coverSeed != null -> {
                seed = ThemePalette.sanitizeSeedColor(coverSeed)
                seedSource = "cover"
            }
            settings.followSystemTheme && SystemThemeState.known -> {
                seed = ThemePalette.sanitizeSeedColor(SystemThemeState.accent)
                seedSource = "system"
            }
            else -> {
                seed = ThemePalette.sanitizeSeedColor(settings.themeSeedColor)
                seedSource = "settings"
            }
        }
        val roles = ThemePalette.compute(
            seedHex = seed,
            isDark = isDark,
            paletteStyle = settings.paletteStyle,
            colorSpec = settings.colorSpec,
        )
        return buildJsonObject {
            put("isDark", isDark)
            put("seed", seed)
            put("seedSource", seedSource)
            put("paletteStyle", settings.paletteStyle)
            put("colorSpec", settings.colorSpec)
            put("followSystemTheme", settings.followSystemTheme)
            put("roles", encodeAny(roles))
        }
    }
}
