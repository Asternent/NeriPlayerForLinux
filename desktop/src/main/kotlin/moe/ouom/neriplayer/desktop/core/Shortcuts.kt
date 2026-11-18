package moe.ouom.neriplayer.desktop.core

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import kotlinx.serialization.Serializable

/**
 * 可绑定的快捷键动作。
 *
 * [scope] 说明该动作是否支持注册为全局（应用在后台也能触发）：
 * 页面导航类只在程序内有效，全局注册交给桌面会抢走其它应用的按键。
 */
enum class ShortcutAction(
    val id: String,
    val label: String,
    val category: String,
    val globalSupported: Boolean,
    val defaultBinding: ShortcutBinding,
) {
    PLAY_PAUSE(
        "play_pause", "播放 / 暂停", "播放控制", globalSupported = true,
        defaultBinding = ShortcutBinding.bound(Key.Spacebar),
    ),
    NEXT_SONG(
        "next_song", "下一首", "播放控制", globalSupported = true,
        defaultBinding = ShortcutBinding.bound(Key.MediaNext),
    ),
    PREVIOUS_SONG(
        "previous_song", "上一首", "播放控制", globalSupported = true,
        defaultBinding = ShortcutBinding.bound(Key.MediaPrevious),
    ),
    VOLUME_UP(
        "volume_up", "音量 +", "播放控制", globalSupported = true,
        defaultBinding = ShortcutBinding.NONE,
    ),
    VOLUME_DOWN(
        "volume_down", "音量 −", "播放控制", globalSupported = true,
        defaultBinding = ShortcutBinding.NONE,
    ),
    SEEK_FORWARD(
        "seek_forward", "快进 5 秒", "播放控制", globalSupported = false,
        defaultBinding = ShortcutBinding(),
    ),
    SEEK_BACKWARD(
        "seek_backward", "后退 5 秒", "播放控制", globalSupported = false,
        defaultBinding = ShortcutBinding(),
    ),
    TOGGLE_FLOATING_LYRICS(
        "toggle_floating_lyrics", "开关悬浮歌词", "功能与窗口", globalSupported = true,
        defaultBinding = ShortcutBinding.bound(Key.L, ctrl = true),
    ),
    TOGGLE_FLOATING_LYRICS_LOCK(
        "toggle_floating_lyrics_lock", "固定 / 取消固定歌词位置", "功能与窗口", globalSupported = true,
        defaultBinding = ShortcutBinding(),
    ),
    OPEN_NOW_PLAYING(
        "open_now_playing", "打开播放页", "功能与窗口", globalSupported = true,
        defaultBinding = ShortcutBinding(),
    ),
    TOGGLE_TRAY_PANEL(
        "toggle_tray_panel", "打开 / 关闭控制面板", "功能与窗口", globalSupported = true,
        defaultBinding = ShortcutBinding(),
    ),
    TOGGLE_DOWNLOADS(
        "toggle_downloads", "打开下载管理", "功能与窗口", globalSupported = false,
        defaultBinding = ShortcutBinding(),
    ),
    NAV_BACK(
        "nav_back", "返回上一页", "页面导航", globalSupported = false,
        defaultBinding = ShortcutBinding.bound(Key.Escape),
    ),
    NAV_HOME(
        "nav_home", "回到首页", "页面导航", globalSupported = false,
        defaultBinding = ShortcutBinding(),
    ),
    NAV_EXPLORE(
        "nav_explore", "切换到探索", "页面导航", globalSupported = false,
        defaultBinding = ShortcutBinding(),
    ),
    NAV_LIBRARY(
        "nav_library", "切换到媒体库", "页面导航", globalSupported = false,
        defaultBinding = ShortcutBinding(),
    ),
    NAV_SETTINGS(
        "nav_settings", "切换到设置", "页面导航", globalSupported = false,
        defaultBinding = ShortcutBinding(),
    ),
    ;

    companion object {
        fun fromId(id: String): ShortcutAction? = entries.firstOrNull { it.id == id }
    }
}

/**
 * 一个按键绑定：Compose 的 keyCode 加上修饰键。
 * keyCode 为 0 表示未绑定。
 */
@Serializable
data class ShortcutBinding(
    val keyCode: Long = 0L,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
    val meta: Boolean = false,
) {
    val isBound: Boolean get() = keyCode != 0L

    fun matches(event: KeyEvent): Boolean {
        if (!isBound) return false
        if (event.key.keyCode != keyCode) return false
        if (event.isCtrlPressed != ctrl) return false
        if (event.isAltPressed != alt) return false
        if (event.isShiftPressed != shift) return false
        if (event.isMetaPressed != meta) return false
        return true
    }

    /** 人类可读的组合键文本，例如「Ctrl+Shift+L」。 */
    fun display(): String {
        if (!isBound) return "未设置"
        val parts = buildList {
            if (ctrl) add("Ctrl")
            if (alt) add("Alt")
            if (shift) add("Shift")
            if (meta) add("Meta")
            add(keyDisplayName(keyCode))
        }
        return parts.joinToString("+")
    }

    companion object {
        val NONE = ShortcutBinding()

        fun bound(
            key: Key,
            ctrl: Boolean = false,
            alt: Boolean = false,
            shift: Boolean = false,
            meta: Boolean = false,
        ): ShortcutBinding = ShortcutBinding(key.keyCode, ctrl, alt, shift, meta)

        /** 从按键事件生成绑定；纯修饰键按下时返回 null（继续等待主键）。 */
        fun fromEvent(event: KeyEvent): ShortcutBinding? {
            val code = event.key.keyCode
            if (keyDisplayName(code) == "") return null
            return ShortcutBinding(
                keyCode = code,
                ctrl = event.isCtrlPressed,
                alt = event.isAltPressed,
                shift = event.isShiftPressed,
                meta = event.isMetaPressed,
            )
        }
    }
}

/** Compose keyCode 到显示名的映射（覆盖常用键；未知键回退为 Key.toString）。 */
fun keyDisplayName(keyCode: Long): String = when (keyCode) {
    0L -> ""
    Key.Spacebar.keyCode -> "Space"
    Key.Enter.keyCode -> "Enter"
    Key.Escape.keyCode -> "Esc"
    Key.Tab.keyCode -> "Tab"
    Key.Backspace.keyCode -> "Backspace"
    Key.Delete.keyCode -> "Delete"
    Key.DirectionUp.keyCode -> "↑"
    Key.DirectionDown.keyCode -> "↓"
    Key.DirectionLeft.keyCode -> "←"
    Key.DirectionRight.keyCode -> "→"
    Key.MediaPrevious.keyCode -> "媒体上一首"
    Key.MediaNext.keyCode -> "媒体下一首"
    Key.MediaStop.keyCode -> "媒体停止"
    Key.MediaPlay.keyCode -> "媒体播放"
    Key.MediaPause.keyCode -> "媒体暂停"
    Key.MediaPlayPause.keyCode -> "媒体播放/暂停"
    else -> {
        val k = runCatching { Key(keyCode) }.getOrNull()
        val text = k?.toString().orEmpty()
        // Compose 对字母键返回 "KeyL" 这类名字，去掉前缀便于显示
        when {
            text.length == 4 && text.startsWith("Key") -> text.substring(3)
            text.startsWith("NumPad") && text.length > 6 -> "Num" + text.substring(6)
            else -> text
        }
    }
}

/** 当前生效的快捷键表：动作 -> 绑定。未出现的动作使用默认值。 */
@Serializable
data class ShortcutSettings(
    val bindings: Map<String, ShortcutBinding> = emptyMap(),
    /** 是否把标记为 globalSupported 的动作注册为全局快捷键。 */
    val globalEnabled: Boolean = false,
) {
    /** 取出某动作最终生效的绑定（用户自定义优先，否则用默认值）。 */
    fun bindingFor(action: ShortcutAction): ShortcutBinding =
        bindings[action.id] ?: action.defaultBinding

    fun withBinding(action: ShortcutAction, binding: ShortcutBinding): ShortcutSettings =
        copy(bindings = bindings + (action.id to binding))

    fun clearing(action: ShortcutAction): ShortcutSettings =
        copy(bindings = bindings + (action.id to ShortcutBinding.NONE))

    /** 删除用户覆盖，回落该动作的默认绑定。 */
    fun clearOverride(action: ShortcutAction): ShortcutSettings =
        copy(bindings = bindings - action.id)

    /** 同一按键是否已被其它动作占用（用于冲突提示）。 */
    fun conflictFor(action: ShortcutAction, binding: ShortcutBinding): ShortcutAction? {
        if (!binding.isBound) return null
        return ShortcutAction.entries.firstOrNull { other ->
            other != action && bindingFor(other) == binding
        }
    }
}
