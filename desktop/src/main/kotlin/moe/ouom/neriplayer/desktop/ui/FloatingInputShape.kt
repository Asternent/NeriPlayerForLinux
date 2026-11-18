package moe.ouom.neriplayer.desktop.ui

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure

/**
 * 悬浮歌词窗口的鼠标穿透（X11 SHAPE 扩展）。
 *
 * 锁定时把窗口的 input shape 设成「控制条那一条矩形」，歌词区不接收鼠标事件，
 * 点击/滚轮会落到下层窗口；解锁时清空 shape，整个窗口恢复可交互。
 * 只在 X11/XWayland 下生效，纯 Wayland 或 SHAPE 不可用时静默降级（全部可交互）。
 */
internal object FloatingInputShape {
    private val x11: X11Lib? = runCatching { Native.load("X11", X11Lib::class.java) }.getOrNull()

    /**
     * @param handle AWT 窗口的 native handle（X11 下即 XID）；<=0 表示不可用。
     * @param controlRect 允许接收鼠标的区域，单位是窗口内像素；null 表示恢复全窗口可交互。
     */
    fun setShape(handle: Long, controlRect: Rect? = null) {
        val lib = x11 ?: return
        val display = lib.XOpenDisplay(null) ?: return
        try {
            val rects = if (controlRect != null) {
                arrayOf(
                    XRectangle().apply {
                        x = controlRect.x.toShort()
                        y = controlRect.y.toShort()
                        width = controlRect.width.toShort()
                        height = controlRect.height.toShort()
                    },
                )
            } else {
                emptyArray()
            }
            // shapeKind=2 (ShapeInput), op=0 (ShapeSet), ordering=0 (Unsorted)
            lib.XShapeCombineRectangles(
                display, handle, 2, 0, 0,
                rects, rects.size, 0, 0,
            )
            lib.XFlush(display)
        } catch (_: Throwable) {
            // 穿透失败不影响悬浮歌词的基本功能，静默降级
        } finally {
            runCatching { lib.XCloseDisplay(display) }
        }
    }

    data class Rect(val x: Int, val y: Int, val width: Int, val height: Int)

    @Structure.FieldOrder("x", "y", "width", "height")
    internal class XRectangle : Structure() {
        @JvmField var x: Short = 0
        @JvmField var y: Short = 0
        @JvmField var width: Short = 0
        @JvmField var height: Short = 0
    }

    private interface X11Lib : Library {
        fun XOpenDisplay(name: String?): Pointer?
        fun XCloseDisplay(display: Pointer): Int
        fun XFlush(display: Pointer): Int
        fun XShapeCombineRectangles(
            display: Pointer,
            window: Long,
            shapeKind: Int,
            xOffset: Int,
            yOffset: Int,
            rectangles: Array<XRectangle>?,
            numRects: Int,
            op: Int,
            ordering: Int,
        )
    }
}
