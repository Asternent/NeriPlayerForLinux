package moe.ouom.neriplayer.desktop.core

import java.awt.Toolkit
import java.util.concurrent.TimeUnit

/**
 * 界面缩放（HiDPI 适配）。
 *
 * Compose Desktop 在 Linux 上按 density = 1.0 渲染，并不读取桌面的 Xft.dpi：
 * 桌面开了 200% 缩放时（GNOME 高分屏上常见的 `Xft.dpi: 192`），整个界面只有一半大小。
 * 这里按桌面自己的规则推导缩放比例，应用到所有窗口的内容上。
 */
object UiScale {

    /** 设置里用 0 表示「跟随系统」。 */
    const val SYSTEM: Float = 0f

    const val MIN: Float = 1f
    const val MAX: Float = 3f

    /** 可选的界面缩放比例（0 = 跟随系统）。 */
    val PRESETS: List<Pair<Float, String>> = listOf(
        SYSTEM to "跟随系统",
        1f to "100%",
        1.25f to "125%",
        1.5f to "150%",
        1.75f to "175%",
        2f to "200%",
        2.5f to "250%",
    )

    private var detectedSource: String = "默认"

    private val detected: Float by lazy { detect() }

    /** 桌面缩放的来源说明，用于设置页展示。 */
    val source: String get() = detectedSource

    fun systemScale(): Float = detected

    /** 把设置项换算成实际生效的缩放比例。 */
    fun resolve(setting: Float): Float =
        if (setting <= 0f) systemScale() else setting.coerceIn(MIN, MAX)

    private fun detect(): Float {
        // 各来源分别算一个候选值，取其中最大的：Skiko 有时会把 sun.java2d.uiScale 写成 1.0，
        // 只按第一个命中的来源判断会被它带偏。
        val candidates = mutableListOf<Pair<Float, String>>()

        // 显式覆盖优先（调试、Wayland 下没有 Xft.dpi 时手动指定）
        System.getenv("NERIPLAYER_UI_SCALE")?.trim()?.toFloatOrNull()?.let { value ->
            if (value > 0f) {
                detectedSource = "环境变量 NERIPLAYER_UI_SCALE"
                return value.coerceIn(MIN, MAX)
            }
        }
        System.getProperty("sun.java2d.uiScale")?.trim()?.toFloatOrNull()?.let { value ->
            if (value > 1.001f) candidates += value to "JVM 参数 sun.java2d.uiScale"
        }
        System.getenv("GDK_SCALE")?.trim()?.toFloatOrNull()?.let { value ->
            if (value > 1.001f) candidates += value to "环境变量 GDK_SCALE"
        }
        System.getenv("QT_SCALE_FACTOR")?.trim()?.toFloatOrNull()?.let { value ->
            if (value > 1.001f) candidates += value to "环境变量 QT_SCALE_FACTOR"
        }
        // 桌面通过 XSETTINGS 下发的 Xft.dpi（GNOME / KDE 都走这条）
        xftDpiFromDesktopProperty()?.let { dpi ->
            if (dpi > 96) candidates += (dpi.toFloat() / 96f) to "桌面 Xft.dpi=$dpi"
        }
        // 兜底：直接读 X 资源
        xftDpiFromXrdb()?.let { dpi ->
            if (dpi > 96) candidates += (dpi.toFloat() / 96f) to "X 资源 Xft.dpi=$dpi"
        }
        // 平台自身如果已经缩放过（例如 Windows / macOS），这里也算一个候选
        val platform = runCatching {
            java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                .defaultScreenDevice.defaultConfiguration.defaultTransform.scaleX.toFloat()
        }.getOrDefault(1f)
        if (platform > 1.001f) candidates += platform to "平台缩放 ${platform}"

        val best = candidates.maxByOrNull { it.first }
        if (best == null) {
            detectedSource = "未检测到桌面缩放"
            return 1f
        }
        detectedSource = best.second
        return best.first.coerceIn(MIN, MAX)
    }

    private fun xftDpiFromDesktopProperty(): Int? = runCatching {
        // GNOME 把 Xft.dpi 放大 1024 倍后放在这个桌面属性里
        val raw = Toolkit.getDefaultToolkit().getDesktopProperty("gnome.Xft/DPI") as? Int
        raw?.takeIf { it > 0 }?.div(1024)
    }.getOrNull()

    private fun xftDpiFromXrdb(): Int? = runCatching {
        if (System.getenv("DISPLAY").isNullOrBlank()) return null
        val process = ProcessBuilder("xrdb", "-query").redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor(1, TimeUnit.SECONDS)
        Regex("Xft\\.dpi:\\s*([0-9]+(?:\\.[0-9]+)?)").find(text)
            ?.groupValues?.get(1)?.toFloatOrNull()?.toInt()
    }.getOrNull()
}
