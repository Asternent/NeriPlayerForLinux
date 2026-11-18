package moe.ouom.neriplayer.desktop.ui

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

/**
 * 悬浮歌词窗口的鼠标穿透（XFixes 设置 ShapeInput 区域）。
 *
 * 锁定时把窗口的 input region 设成空区域：整个窗口不接收鼠标事件，点击落到下层窗口；
 * 解锁时传 null region 恢复整窗可交互。
 * 只在 X11/XWayland 下生效，XFixes 不可用或非 X11 时静默降级。
 */
internal object FloatingInputShape {
    private val x11: X11Lib? = runCatching { Native.load("X11", X11Lib::class.java) }.getOrNull()
    private val xfixes: XFixesLib? = runCatching { Native.load("Xfixes", XFixesLib::class.java) }.getOrNull()

    /** @param passThrough true 表示整窗穿透；false 表示恢复可交互。 */
    fun setPassThrough(handle: Long, passThrough: Boolean) {
        val lib11 = x11 ?: return
        val libFixes = xfixes ?: return
        if (handle <= 0) return
        val display = lib11.XOpenDisplay(null) ?: return
        try {
            val region = if (passThrough) {
                // 空 region（不添加任何矩形）= 窗口不接收任何输入
                libFixes.XFixesCreateRegion(display, null, 0)
            } else {
                null
            }
            // shapeKind: ShapeInput = 2；region=null 恢复默认（整窗可交互）
            libFixes.XFixesSetWindowShapeRegion(display, handle, 2, 0, 0, region)
            if (region != null) libFixes.XFixesDestroyRegion(display, region)
            lib11.XFlush(display)
        } catch (_: Throwable) {
            // 穿透失败不影响基本功能，静默降级
        } finally {
            runCatching { lib11.XCloseDisplay(display) }
        }
    }

    private interface X11Lib : Library {
        fun XOpenDisplay(name: String?): Pointer?
        fun XCloseDisplay(display: Pointer): Int
        fun XFlush(display: Pointer): Int
    }

    private interface XFixesLib : Library {
        fun XFixesCreateRegion(display: Pointer, rectangles: Pointer?, nrectangles: Int): Pointer
        fun XFixesDestroyRegion(display: Pointer, region: Pointer)
        fun XFixesSetWindowShapeRegion(
            display: Pointer,
            window: Long,
            shapeKind: Int,
            xOffset: Int,
            yOffset: Int,
            region: Pointer?,
        )
    }
}
