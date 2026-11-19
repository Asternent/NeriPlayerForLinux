package moe.ouom.neriplayer.desktop.backend

import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.dynamiccolor.DynamicColor
import com.materialkolor.dynamiccolor.MaterialDynamicColors
import com.materialkolor.hct.Hct
import com.materialkolor.scheme.DynamicScheme
import com.materialkolor.scheme.SchemeContent
import com.materialkolor.scheme.SchemeExpressive
import com.materialkolor.scheme.SchemeFidelity
import com.materialkolor.scheme.SchemeFruitSalad
import com.materialkolor.scheme.SchemeMonochrome
import com.materialkolor.scheme.SchemeNeutral
import com.materialkolor.scheme.SchemeRainbow
import com.materialkolor.scheme.SchemeTonalSpot
import com.materialkolor.scheme.SchemeVibrant
import com.materialkolor.scheme.Variant

/**
 * Material 3 调色板计算（Qt 前端不实现 HCT，直接消费这里算好的 49 个色值）。
 * 逻辑与 Compose 版 ui/theme/Theme.kt 保持一致，只是把结果输出成 "#RRGGBB" 字符串。
 */
object ThemePalette {

    const val DEFAULT_SEED_COLOR = "0061A4"

    private val dynamicColors = MaterialDynamicColors()

    fun sanitizeSeedColor(value: String?): String {
        val normalized = value?.trim()?.removePrefix("#")?.uppercase().orEmpty()
        return if (normalized.length == 6 && normalized.all { it in "0123456789ABCDEF" }) {
            normalized
        } else {
            DEFAULT_SEED_COLOR
        }
    }

    fun compute(
        seedHex: String,
        isDark: Boolean,
        paletteStyle: String,
        colorSpec: String,
    ): Map<String, String> {
        val seed = sanitizeSeedColor(seedHex)
        val seedArgb = (0xFF000000.toInt()) or seed.toLong(16).toInt()
        val hct = Hct.fromInt(seedArgb)
        val contrast = 0.0
        val specVersion = if (colorSpec == "SPEC_2025") {
            ColorSpec.SpecVersion.SPEC_2025
        } else {
            ColorSpec.SpecVersion.SPEC_2021
        }
        val platform = DynamicScheme.Platform.PHONE
        val scheme: DynamicScheme = when (paletteStyle) {
            "Neutral" -> SchemeNeutral(hct, isDark, contrast, specVersion, platform)
            "Vibrant" -> SchemeVibrant(hct, isDark, contrast, specVersion, platform)
            "Expressive" -> SchemeExpressive(hct, isDark, contrast, specVersion, platform)
            "Rainbow" -> SchemeRainbow(hct, isDark, contrast, specVersion, platform)
            "FruitSalad" -> SchemeFruitSalad(hct, isDark, contrast, specVersion, platform)
            "Monochrome" -> SchemeMonochrome(hct, isDark, contrast, specVersion, platform)
            "Fidelity" -> SchemeFidelity(hct, isDark, contrast, specVersion, platform)
            "Content" -> SchemeContent(hct, isDark, contrast, specVersion, platform)
            else -> SchemeTonalSpot(hct, isDark, contrast, specVersion, platform)
        }

        fun DynamicColor.hex(): String = String.format("#%06X", getArgb(scheme) and 0xFFFFFF)

        return linkedMapOf(
            "primary" to dynamicColors.primary().hex(),
            "onPrimary" to dynamicColors.onPrimary().hex(),
            "primaryContainer" to dynamicColors.primaryContainer().hex(),
            "onPrimaryContainer" to dynamicColors.onPrimaryContainer().hex(),
            "inversePrimary" to dynamicColors.inversePrimary().hex(),
            "secondary" to dynamicColors.secondary().hex(),
            "onSecondary" to dynamicColors.onSecondary().hex(),
            "secondaryContainer" to dynamicColors.secondaryContainer().hex(),
            "onSecondaryContainer" to dynamicColors.onSecondaryContainer().hex(),
            "tertiary" to dynamicColors.tertiary().hex(),
            "onTertiary" to dynamicColors.onTertiary().hex(),
            "tertiaryContainer" to dynamicColors.tertiaryContainer().hex(),
            "onTertiaryContainer" to dynamicColors.onTertiaryContainer().hex(),
            "background" to dynamicColors.background().hex(),
            "onBackground" to dynamicColors.onBackground().hex(),
            "surface" to dynamicColors.surface().hex(),
            "onSurface" to dynamicColors.onSurface().hex(),
            "surfaceVariant" to dynamicColors.surfaceVariant().hex(),
            "onSurfaceVariant" to dynamicColors.onSurfaceVariant().hex(),
            "surfaceTint" to dynamicColors.surfaceTint().hex(),
            "inverseSurface" to dynamicColors.inverseSurface().hex(),
            "inverseOnSurface" to dynamicColors.inverseOnSurface().hex(),
            "error" to dynamicColors.error().hex(),
            "onError" to dynamicColors.onError().hex(),
            "errorContainer" to dynamicColors.errorContainer().hex(),
            "onErrorContainer" to dynamicColors.onErrorContainer().hex(),
            "outline" to dynamicColors.outline().hex(),
            "outlineVariant" to dynamicColors.outlineVariant().hex(),
            "scrim" to dynamicColors.scrim().hex(),
            "surfaceBright" to dynamicColors.surfaceBright().hex(),
            "surfaceDim" to dynamicColors.surfaceDim().hex(),
            "surfaceContainer" to dynamicColors.surfaceContainer().hex(),
            "surfaceContainerHigh" to dynamicColors.surfaceContainerHigh().hex(),
            "surfaceContainerHighest" to dynamicColors.surfaceContainerHighest().hex(),
            "surfaceContainerLow" to dynamicColors.surfaceContainerLow().hex(),
            "surfaceContainerLowest" to dynamicColors.surfaceContainerLowest().hex(),
            "primaryFixed" to dynamicColors.primaryFixed().hex(),
            "primaryFixedDim" to dynamicColors.primaryFixedDim().hex(),
            "onPrimaryFixed" to dynamicColors.onPrimaryFixed().hex(),
            "onPrimaryFixedVariant" to dynamicColors.onPrimaryFixedVariant().hex(),
            "secondaryFixed" to dynamicColors.secondaryFixed().hex(),
            "secondaryFixedDim" to dynamicColors.secondaryFixedDim().hex(),
            "onSecondaryFixed" to dynamicColors.onSecondaryFixed().hex(),
            "onSecondaryFixedVariant" to dynamicColors.onSecondaryFixedVariant().hex(),
            "tertiaryFixed" to dynamicColors.tertiaryFixed().hex(),
            "tertiaryFixedDim" to dynamicColors.tertiaryFixedDim().hex(),
            "onTertiaryFixed" to dynamicColors.onTertiaryFixed().hex(),
            "onTertiaryFixedVariant" to dynamicColors.onTertiaryFixedVariant().hex(),
        )
    }

    /** 校验并返回 Variant 名称列表（设置页要展示的 9 种调色风格）。 */
    val PALETTE_STYLES = listOf(
        "TonalSpot", "Neutral", "Vibrant", "Expressive", "Rainbow",
        "FruitSalad", "Monochrome", "Fidelity", "Content",
    )

    val COLOR_SPECS = listOf("SPEC_2021", "SPEC_2025")
}
