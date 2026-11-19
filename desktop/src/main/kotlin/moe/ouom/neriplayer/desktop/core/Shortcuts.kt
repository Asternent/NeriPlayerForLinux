package moe.ouom.neriplayer.desktop.core

import kotlinx.serialization.Serializable

/**
 * 可绑定快捷键的动作。
 *
 * 每个动作有两套独立绑定：
 * - **应用内**（[defaultLocalBinding]）：窗口有焦点时由 Qt 前端处理；[systemKey] 为 true 的是基础操作
 *   （如空格播放暂停、Esc 返回），界面里只读展示、不允许改。
 * - **全局**（[globalSupported]）：应用在后台也能触发，通过 xdg-desktop-portal 注册，逐个动作单独设置。
 *
 * 页面导航类不提供全局绑定：注册到桌面会抢走其它应用的按键。
 *
 * 键名使用 xkb/XDG shortcuts 规范的写法：字母大写（`L`）、特殊键用规范名（`space`/`Right`/
 * `XF86AudioNext`/`Escape`）。修饰键由 [ShortcutBinding] 的布尔字段表示。
 */
enum class ShortcutAction(
    val id: String,
    val label: String,
    val category: String,
    val systemKey: Boolean,
    val globalSupported: Boolean,
    val defaultLocalBinding: ShortcutBinding,
    val defaultGlobalBinding: ShortcutBinding,
) {
    PLAY_PAUSE(
        "play_pause", "播放 / 暂停", "播放控制",
        systemKey = true, globalSupported = true,
        defaultLocalBinding = ShortcutBinding.bound("space"),
        defaultGlobalBinding = ShortcutBinding.bound("space", ctrl = true, shift = true),
    ),
    NEXT_SONG(
        "next_song", "下一首", "播放控制",
        systemKey = false, globalSupported = true,
        defaultLocalBinding = ShortcutBinding.bound("XF86AudioNext"),
        defaultGlobalBinding = ShortcutBinding.bound("Right", ctrl = true, shift = true),
    ),
    PREVIOUS_SONG(
        "previous_song", "上一首", "播放控制",
        systemKey = false, globalSupported = true,
        defaultLocalBinding = ShortcutBinding.bound("XF86AudioPrev"),
        defaultGlobalBinding = ShortcutBinding.bound("Left", ctrl = true, shift = true),
    ),
    VOLUME_UP(
        "volume_up", "音量 +", "播放控制",
        systemKey = false, globalSupported = true,
        defaultLocalBinding = ShortcutBinding.bound("Up", ctrl = true),
        defaultGlobalBinding = ShortcutBinding.NONE,
    ),
    VOLUME_DOWN(
        "volume_down", "音量 −", "播放控制",
        systemKey = false, globalSupported = true,
        defaultLocalBinding = ShortcutBinding.bound("Down", ctrl = true),
        defaultGlobalBinding = ShortcutBinding.NONE,
    ),
    SEEK_FORWARD(
        "seek_forward", "快进 5 秒", "播放控制",
        systemKey = false, globalSupported = false,
        defaultLocalBinding = ShortcutBinding.bound("Right", ctrl = true),
        defaultGlobalBinding = ShortcutBinding.NONE,
    ),
    SEEK_BACKWARD(
        "seek_backward", "后退 5 秒", "播放控制",
        systemKey = false, globalSupported = false,
        defaultLocalBinding = ShortcutBinding.bound("Left", ctrl = true),
        defaultGlobalBinding = ShortcutBinding.NONE,
    ),
    TOGGLE_FLOATING_LYRICS(
        "toggle_floating_lyrics", "开关悬浮歌词", "功能与窗口",
        systemKey = false, globalSupported = true,
        defaultLocalBinding = ShortcutBinding.bound("L", ctrl = true),
        defaultGlobalBinding = ShortcutBinding.bound("L", ctrl = true, alt = true),
    ),
    TOGGLE_FLOATING_LYRICS_LOCK(
        "toggle_floating_lyrics_lock", "固定 / 取消固定歌词位置", "功能与窗口",
        systemKey = false, globalSupported = true,
        defaultLocalBinding = ShortcutBinding.bound("L", ctrl = true, alt = true),
        defaultGlobalBinding = ShortcutBinding.bound("L", ctrl = true, alt = true, shift = true),
    ),
    OPEN_NOW_PLAYING(
        "open_now_playing", "打开播放页", "功能与窗口",
        systemKey = false, globalSupported = true,
        defaultLocalBinding = ShortcutBinding.bound("P", ctrl = true),
        defaultGlobalBinding = ShortcutBinding.bound("P", ctrl = true, alt = true),
    ),
    TOGGLE_TRAY_PANEL(
        "toggle_tray_panel", "打开 / 关闭控制面板", "功能与窗口",
        systemKey = false, globalSupported = true,
        defaultLocalBinding = ShortcutBinding.bound("M", ctrl = true),
        defaultGlobalBinding = ShortcutBinding.bound("M", ctrl = true, alt = true),
    ),
    TOGGLE_DOWNLOADS(
        "toggle_downloads", "打开下载管理", "功能与窗口",
        systemKey = false, globalSupported = false,
        defaultLocalBinding = ShortcutBinding.bound("D", ctrl = true),
        defaultGlobalBinding = ShortcutBinding.NONE,
    ),
    NAV_BACK(
        "nav_back", "返回上一页", "页面导航",
        systemKey = true, globalSupported = false,
        defaultLocalBinding = ShortcutBinding.bound("Escape"),
        defaultGlobalBinding = ShortcutBinding.NONE,
    ),
    NAV_HOME(
        "nav_home", "回到首页", "页面导航",
        systemKey = false, globalSupported = false,
        defaultLocalBinding = ShortcutBinding.bound("H", ctrl = true),
        defaultGlobalBinding = ShortcutBinding.NONE,
    ),
    NAV_EXPLORE(
        "nav_explore", "切换到探索", "页面导航",
        systemKey = false, globalSupported = false,
        defaultLocalBinding = ShortcutBinding.bound("N", ctrl = true),
        defaultGlobalBinding = ShortcutBinding.NONE,
    ),
    NAV_LIBRARY(
        "nav_library", "切换到媒体库", "页面导航",
        systemKey = false, globalSupported = false,
        defaultLocalBinding = ShortcutBinding.bound("J", ctrl = true),
        defaultGlobalBinding = ShortcutBinding.NONE,
    ),
    NAV_SETTINGS(
        "nav_settings", "切换到设置", "页面导航",
        systemKey = false, globalSupported = false,
        defaultLocalBinding = ShortcutBinding.bound("S", ctrl = true),
        defaultGlobalBinding = ShortcutBinding.NONE,
    ),
    ;

    companion object {
        fun fromId(id: String): ShortcutAction? = entries.firstOrNull { it.id == id }
    }
}

/**
 * 一个按键绑定：xkb 键名 + 修饰键。
 * key 为空表示未绑定；字母键写成大写，特殊键用 xkb 键名（space/Right/XF86AudioNext…）。
 */
@Serializable
data class ShortcutBinding(
    val key: String = "",
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
    val meta: Boolean = false,
) {
    val isBound: Boolean get() = key.isNotBlank()

    /** 人类可读的组合键文本，例如「Ctrl+Shift+L」。 */
    fun display(): String {
        if (!isBound) return "未设置"
        val parts = buildList {
            if (ctrl) add("Ctrl")
            if (alt) add("Alt")
            if (shift) add("Shift")
            if (meta) add("Meta")
            add(displayKeyName(key))
        }
        return parts.joinToString("+")
    }

    companion object {
        val NONE = ShortcutBinding()

        fun bound(
            key: String,
            ctrl: Boolean = false,
            alt: Boolean = false,
            shift: Boolean = false,
            meta: Boolean = false,
        ): ShortcutBinding = ShortcutBinding(key = key, ctrl = ctrl, alt = alt, shift = shift, meta = meta)
    }
}

/** xkb 键名 → 界面展示名（覆盖常用键；未知键原样返回）。 */
fun displayKeyName(key: String): String = when (key) {
    "" -> ""
    "space" -> "Space"
    "Return" -> "Enter"
    "Escape" -> "Esc"
    "Tab" -> "Tab"
    "BackSpace" -> "Backspace"
    "Delete" -> "Delete"
    "Up" -> "↑"
    "Down" -> "↓"
    "Left" -> "←"
    "Right" -> "→"
    "XF86AudioPrev" -> "媒体上一首"
    "XF86AudioNext" -> "媒体下一首"
    "XF86AudioStop" -> "媒体停止"
    "XF86AudioPlay" -> "媒体播放"
    "XF86AudioPause" -> "媒体暂停"
    "XF86AudioPlayPause" -> "媒体播放/暂停"
    else -> if (key.length == 1) key.uppercase() else key
}

/** 应用内/全局绑定表（持久化在 AppSettings.shortcuts）。 */
@Serializable
data class ShortcutSettings(
    val local: Map<String, ShortcutBinding> = emptyMap(),
    val global: Map<String, ShortcutBinding> = emptyMap(),
) {
    fun localBindingFor(action: ShortcutAction): ShortcutBinding =
        local[action.id] ?: action.defaultLocalBinding

    fun globalBindingFor(action: ShortcutAction): ShortcutBinding =
        global[action.id] ?: ShortcutBinding.NONE

    fun withLocal(action: ShortcutAction, binding: ShortcutBinding): ShortcutSettings =
        copy(local = local + (action.id to binding))

    fun withGlobal(action: ShortcutAction, binding: ShortcutBinding): ShortcutSettings =
        copy(global = global + (action.id to binding))
}
