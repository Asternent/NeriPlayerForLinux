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
 * 可绑定快捷键的动作。
 *
 * 每个动作有两套独立绑定：
 * - **应用内**（[defaultLocalBinding]）：窗口有焦点时生效；[systemKey] 为 true 的是基础操作
 *   （如空格播放暂停、Esc 返回），界面里只读展示、不允许改。
 * - **全局**（[globalSupported]）：应用在后台也能触发，逐个动作单独设置，互不影响。
 *
 * 页面导航类不提供全局绑定：注册到桌面会抢走其它应用的按键。
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
        defaultLocalBinding = ShortcutBinding.bound(Key.Spacebar),
        defaultGlobalBinding = ShortcutBinding.bound(Key.Spacebar, ctrl = true, shift = true),
    ),
    NEXT_SONG(
        "next_song", "下一首", "播放控制",
        systemKey = false, globalSupported = true,
        defaultLocalBinding = ShortcutBinding.bound(Key.MediaNext),
        defaultGlobalBinding = ShortcutBinding.bound(Key.DirectionRight, ctrl = true, shift = true),
    ),
    PREVIOUS_SONG(
        "previous_song", "上一首", "播放控制",
        systemKey = false, globalSupported = true,
        defaultLocalBinding = ShortcutBinding.bound(Key.MediaPrevious),
        defaultGlobalBinding = ShortcutBinding.bound(Key.DirectionLeft, ctrl = true, shift = true),
    ),
    VOLUME_UP(
        "volume_up", "音量 +", "播放控制",
        systemKey = false, globalSupported = true,
        defaultLocalBinding = ShortcutBinding.bound(Key.DirectionUp, ctrl = true),
        defaultGlobalBinding = ShortcutBinding.NONE,
    ),
    VOLUME_DOWN(
        "volume_down", "音量 −", "播放控制",
        systemKey = false, globalSupported = true,
        defaultLocalBinding = ShortcutBinding.bound(Key.DirectionDown, ctrl = true),
        defaultGlobalBinding = ShortcutBinding.NONE,
    ),
    SEEK_FORWARD(
        "seek_forward", "快进 5 秒", "播放控制",
        systemKey = false, globalSupported = false,
        defaultLocalBinding = ShortcutBinding.bound(Key.DirectionRight, ctrl = true),
        defaultGlobalBinding = ShortcutBinding.NONE,
    ),
    SEEK_BACKWARD(
        "seek_backward", "后退 5 秒", "播放控制",
        systemKey = false, globalSupported = false,
        defaultLocalBinding = ShortcutBinding.bound(Key.DirectionLeft, ctrl = true),
        defaultGlobalBinding = ShortcutBinding.NONE,
    ),
    TOGGLE_FLOATING_LYRICS(
        "toggle_floating_lyrics", "开关悬浮歌词", "功能与窗口",
        systemKey = false, globalSupported = true,
        defaultLocalBinding = ShortcutBinding.bound(Key.L, ctrl = true),
        defaultGlobalBinding = ShortcutBinding.bound(Key.L, ctrl = true, alt = true),
    ),
    TOGGLE_FLOATING_LYRICS_LOCK(
        "toggle_floating_lyrics_lock", "固定 / 取消固定歌词位置", "功能与窗口",
        systemKey = false, globalSupported = true,
        defaultLocalBinding = ShortcutBinding.bound(Key.L, ctrl = true, alt = true),
        defaultGlobalBinding = ShortcutBinding.bound(Key.L, ctrl = true, alt = true, shift = true),
    ),
    OPEN_NOW_PLAYING(
        "open_now_playing", "打开播放页", "功能与窗口",
        systemKey = false, globalSupported = true,
        defaultLocalBinding = ShortcutBinding.bound(Key.P, ctrl = true),
        defaultGlobalBinding = ShortcutBinding.bound(Key.P, ctrl = true, alt = true),
    ),
    TOGGLE_TRAY_PANEL(
        "toggle_tray_panel", "打开 / 关闭控制面板", "功能与窗口",
        systemKey = false, globalSupported = true,
        defaultLocalBinding = ShortcutBinding.bound(Key.M, ctrl = true),
        defaultGlobalBinding = ShortcutBinding.bound(Key.M, ctrl = true, alt = true),
    ),
    TOGGLE_DOWNLOADS(
        "toggle_downloads", "打开下载管理", "功能与窗口",
        systemKey = false, globalSupported = false,
        defaultLocalBinding = ShortcutBinding.bound(Key.D, ctrl = true),
        defaultGlobalBinding = ShortcutBinding.NONE,
    ),
    NAV_BACK(
        "nav_back", "返回上一页", "页面导航",
        systemKey = true, globalSupported = false,
        defaultLocalBinding = ShortcutBinding.bound(Key.Escape),
        defaultGlobalBinding = ShortcutBinding.NONE,
    ),
    NAV_HOME(
        "nav_home", "回到首页", "页面导航",
        systemKey = false, globalSupported = false,
        defaultLocalBinding = ShortcutBinding.bound(Key.H, ctrl = true),
        defaultGlobalBinding = ShortcutBinding.NONE,
    ),
    NAV_EXPLORE(
        "nav_explore", "切换到探索", "页面导航",
        systemKey = false, globalSupported = false,
        defaultLocalBinding = ShortcutBinding.bound(Key.N, ctrl = true),
        defaultGlobalBinding = ShortcutBinding.NONE,
    ),
    NAV_LIBRARY(
        "nav_library", "切换到媒体库", "页面导航",
        systemKey = false, globalSupported = false,
        defaultLocalBinding = ShortcutBinding.bound(Key.J, ctrl = true),
        defaultGlobalBinding = ShortcutBinding.NONE,
    ),
    NAV_SETTINGS(
        "nav_settings", "切换到设置", "页面导航",
        systemKey = false, globalSupported = false,
        defaultLocalBinding = ShortcutBinding.bound(Key.S, ctrl = true),
        defaultGlobalBinding = ShortcutBinding.NONE,
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

    /**
     * 归一化的键码：只保留低 32 位。Compose 桌面端 Key 是 (keyLocation<<32)|keyCode 的
     * 打包值，左右 Ctrl 等区分 location，比较时 location 位不应参与，否则左右键互换就配不上。
     */
    val normalizedKeyCode: Long get() = keyCode and 0xFFFFFFFFL

    fun matches(event: KeyEvent): Boolean {
        if (!isBound) return false
        if (event.key.keyCode.and(0xFFFFFFFFL) != normalizedKeyCode) return false
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
            add(keyDisplayName(normalizedKeyCode))
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

        /**
         * 从按键事件生成绑定。
         *
         * 按下 Ctrl/Shift/Alt/Meta 本身也会产生 KeyDown 事件，必须排除，
         * 否则录制组合键时只会记下最先按下的修饰键。
         */
        fun fromEvent(event: KeyEvent): ShortcutBinding? {
            val code = event.key.keyCode and 0xFFFFFFFFL
            if (code == 0L) return null
            if (isModifierKey(code)) return null
            if (keyDisplayName(code).isEmpty()) return null
            return ShortcutBinding(
                keyCode = code,
                ctrl = event.isCtrlPressed,
                alt = event.isAltPressed,
                shift = event.isShiftPressed,
                meta = event.isMetaPressed,
            )
        }

        /** 是否为纯修饰键（左右 Ctrl/Shift/Alt/Meta 与锁定键）。 */
        fun isModifierKey(keyCode: Long): Boolean = keyCode and 0xFFFFFFFFL in MODIFIER_KEYS

        private val MODIFIER_KEYS: Set<Long> = setOf(
            (Key.CtrlLeft.keyCode and 0xFFFFFFFFL), (Key.CtrlRight.keyCode and 0xFFFFFFFFL),
            (Key.ShiftLeft.keyCode and 0xFFFFFFFFL), (Key.ShiftRight.keyCode and 0xFFFFFFFFL),
            (Key.AltLeft.keyCode and 0xFFFFFFFFL), (Key.AltRight.keyCode and 0xFFFFFFFFL),
            (Key.MetaLeft.keyCode and 0xFFFFFFFFL), (Key.MetaRight.keyCode and 0xFFFFFFFFL),
            (Key.CapsLock.keyCode and 0xFFFFFFFFL), (Key.NumLock.keyCode and 0xFFFFFFFFL),
        )
    }
}

/** Compose keyCode 到显示名的映射（覆盖常用键；未知键回退为 Key.toString）。
 * 入参做低 32 位归一化：Compose Key 值是 (keyLocation<<32)|keyCode 打包格式，
 * 显示名只看键码本身，避免左右键区分位造成误判。 */
fun keyDisplayName(rawKeyCode: Long): String {
    val keyCode = rawKeyCode and 0xFFFFFFFFL
    return when (keyCode) {
        0L -> ""
        Key.Spacebar.keyCode and 0xFFFFFFFFL -> "Space"
        Key.Enter.keyCode and 0xFFFFFFFFL -> "Enter"
        Key.Escape.keyCode and 0xFFFFFFFFL -> "Esc"
        Key.Tab.keyCode and 0xFFFFFFFFL -> "Tab"
        Key.Backspace.keyCode and 0xFFFFFFFFL -> "Backspace"
        Key.Delete.keyCode and 0xFFFFFFFFL -> "Delete"
        Key.DirectionUp.keyCode and 0xFFFFFFFFL -> "↑"
        Key.DirectionDown.keyCode and 0xFFFFFFFFL -> "↓"
        Key.DirectionLeft.keyCode and 0xFFFFFFFFL -> "←"
        Key.DirectionRight.keyCode and 0xFFFFFFFFL -> "→"
        Key.MediaPrevious.keyCode and 0xFFFFFFFFL -> "媒体上一首"
        Key.MediaNext.keyCode and 0xFFFFFFFFL -> "媒体下一首"
        Key.MediaStop.keyCode and 0xFFFFFFFFL -> "媒体停止"
        Key.MediaPlay.keyCode and 0xFFFFFFFFL -> "媒体播放"
        Key.MediaPause.keyCode and 0xFFFFFFFFL -> "媒体暂停"
        Key.MediaPlayPause.keyCode and 0xFFFFFFFFL -> "媒体播放/暂停"
    else -> {
        val text = runCatching { Key(keyCode).toString() }.getOrNull().orEmpty()
        // Compose 对字母键返回 "Key: P" 这种格式，去掉前缀便于显示
        when {
            text.startsWith("Key: ") && text.length > 5 -> text.substring(5)
            text.startsWith("NumPad") && text.length > 6 -> "Num" + text.substring(6)
            else -> text
        }
    }
    }
}

/**
 * 快捷键配置：应用内与全局两套绑定分开存。
 * 未出现的动作回落各自的默认值（全局默认为空）。
 */
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

    /** 删除应用内覆盖，回落默认值。 */
    fun clearLocalOverride(action: ShortcutAction): ShortcutSettings =
        copy(local = local - action.id)

    /** 清空某个动作的全局绑定。 */
    fun clearGlobal(action: ShortcutAction): ShortcutSettings =
        copy(global = global - action.id)

    /** 应用内绑定是否与其它动作冲突。 */
    fun localConflict(action: ShortcutAction, binding: ShortcutBinding): ShortcutAction? {
        if (!binding.isBound) return null
        return ShortcutAction.entries.firstOrNull { other ->
            other != action && localBindingFor(other) == binding
        }
    }

    /** 全局绑定是否与其它动作冲突。 */
    fun globalConflict(action: ShortcutAction, binding: ShortcutBinding): ShortcutAction? {
        if (!binding.isBound) return null
        return ShortcutAction.entries.firstOrNull { other ->
            other != action && globalBindingFor(other) == binding
        }
    }

    /** 现存的全局绑定（仅 globalSupported 的动作），供注册使用。 */
    fun activeGlobalBindings(): List<Pair<ShortcutAction, ShortcutBinding>> =
        ShortcutAction.entries
            .filter { it.globalSupported }
            .map { it to globalBindingFor(it) }
            .filter { it.second.isBound }
}
