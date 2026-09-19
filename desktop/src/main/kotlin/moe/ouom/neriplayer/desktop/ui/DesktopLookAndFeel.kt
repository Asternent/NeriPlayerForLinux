package moe.ouom.neriplayer.desktop.ui

import java.awt.Font
import java.awt.GraphicsEnvironment
import javax.swing.UIManager

/**
 * 桌面端外观初始化。
 *
 * Compose Desktop 本身是自绘的，但选择目录、确认框等系统对话框仍然是 Swing/AWT 组件，
 * 默认会退化成灰底小字。这里跟随桌面主题，并把菜单、对话框字体统一成系统中文字体，
 * 让这些窗口与主题化的应用界面不至于割裂。
 */
fun setupDesktopLookAndFeel() {
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }

    val font = preferredUiFont()
    listOf(
        "Menu.font",
        "MenuItem.font",
        "CheckBoxMenuItem.font",
        "RadioButtonMenuItem.font",
        "PopupMenu.font",
        "Label.font",
        "Button.font",
        "TextArea.font",
        "TextField.font",
        "List.font",
        "Table.font",
        "Tree.font",
        "TitledBorder.font",
        "OptionPane.messageFont",
        "OptionPane.buttonFont",
    ).forEach { key -> UIManager.put(key, font) }

    // 菜单高亮色跟随应用主色，避免系统默认的蓝色与主题冲突
    runCatching {
        val accent = java.awt.Color(0x4F, 0x8E, 0xF7)
        UIManager.put("MenuItem.selectionBackground", accent)
        UIManager.put("MenuItem.selectionForeground", java.awt.Color.WHITE)
        UIManager.put("Menu.selectionBackground", accent)
        UIManager.put("Menu.selectionForeground", java.awt.Color.WHITE)
    }
}

/** 挑选一个可用的中文字体；找不到时退回逻辑字体（由 fontconfig 做字形回退）。 */
private fun preferredUiFont(): Font {
    val candidates = listOf(
        "Noto Sans CJK SC",
        "Source Han Sans SC",
        "Microsoft YaHei",
        "WenQuanYi Micro Hei",
        "Sarasa Gothic SC",
        "PingFang SC",
        "DejaVu Sans",
    )
    val available = runCatching {
        GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet()
    }.getOrDefault(emptySet())
    val family = candidates.firstOrNull { it in available } ?: Font.SANS_SERIF
    return Font(family, Font.PLAIN, 14)
}
