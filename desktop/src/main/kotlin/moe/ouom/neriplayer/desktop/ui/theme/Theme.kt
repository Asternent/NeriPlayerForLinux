package moe.ouom.neriplayer.desktop.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
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

const val DEFAULT_SEED_COLOR = "0061A4"

val PRESET_SEED_COLORS = listOf(
    "0061A4", "6750A4", "B3261E", "C425A8", "00897B", "388E3C", "FBC02D", "E65100",
)

val PALETTE_STYLES = listOf(
    "TonalSpot", "Neutral", "Vibrant", "Expressive", "Rainbow", "FruitSalad", "Monochrome", "Fidelity", "Content",
)

val COLOR_SPECS = listOf("SPEC_2021", "SPEC_2025")

fun paletteStyleLabel(style: String): String = when (style) {
    "TonalSpot" -> "柔和色点"
    "Neutral" -> "低饱和"
    "Vibrant" -> "鲜明"
    "Expressive" -> "表现力"
    "Rainbow" -> "彩虹"
    "FruitSalad" -> "果味"
    "Monochrome" -> "单色"
    "Fidelity" -> "忠实取色"
    "Content" -> "内容色"
    else -> style
}

fun paletteStyleDescription(style: String): String = when (style) {
    "TonalSpot" -> "接近 Android 默认动态色，平衡耐看。"
    "Neutral" -> "减少彩度，页面更克制。"
    "Vibrant" -> "主色更亮更突出。"
    "Expressive" -> "色相更跳跃，适合个性化主题。"
    "Rainbow" -> "色相分布更宽，色彩更丰富。"
    "FruitSalad" -> "互补色更强，风格活泼。"
    "Monochrome" -> "接近黑白灰，弱化主题色。"
    "Fidelity" -> "更贴近你选择的种子色。"
    "Content" -> "沿用内容本身的取色。"
    else -> ""
}

/** 归一化种子色：去掉 # 前缀并转大写，非法值回退默认色。 */
fun sanitizeSeedColorHex(value: String?): String {
    val normalized = value?.trim()?.removePrefix("#")?.uppercase().orEmpty()
    return if (normalized.length == 6 && normalized.all { it in "0123456789ABCDEF" }) {
        normalized
    } else {
        DEFAULT_SEED_COLOR
    }
}

fun seedColorArgb(hex: String): Int = (0xFF000000.toInt()) or sanitizeSeedColorHex(hex).toLong(16).toInt()

fun colorFromHex(hex: String): Color = Color(seedColorArgb(hex))

private val dynamicColors = MaterialDynamicColors()

fun buildDynamicScheme(
    seedArgb: Int,
    isDark: Boolean,
    variant: Variant,
    specVersion: ColorSpec.SpecVersion,
): DynamicScheme {
    val hct = Hct.fromInt(seedArgb)
    val platform = DynamicScheme.Platform.PHONE
    val contrast = 0.0
    return when (variant) {
        Variant.TONAL_SPOT -> SchemeTonalSpot(hct, isDark, contrast, specVersion, platform)
        Variant.NEUTRAL -> SchemeNeutral(hct, isDark, contrast, specVersion, platform)
        Variant.VIBRANT -> SchemeVibrant(hct, isDark, contrast, specVersion, platform)
        Variant.EXPRESSIVE -> SchemeExpressive(hct, isDark, contrast, specVersion, platform)
        Variant.RAINBOW -> SchemeRainbow(hct, isDark, contrast, specVersion, platform)
        Variant.FRUIT_SALAD -> SchemeFruitSalad(hct, isDark, contrast, specVersion, platform)
        Variant.MONOCHROME -> SchemeMonochrome(hct, isDark, contrast, specVersion, platform)
        Variant.FIDELITY -> SchemeFidelity(hct, isDark, contrast, specVersion, platform)
        Variant.CONTENT -> SchemeContent(hct, isDark, contrast, specVersion, platform)
        else -> SchemeTonalSpot(hct, isDark, contrast, specVersion, platform)
    }
}

fun variantOf(style: String): Variant = when (style) {
    "Neutral" -> Variant.NEUTRAL
    "Vibrant" -> Variant.VIBRANT
    "Expressive" -> Variant.EXPRESSIVE
    "Rainbow" -> Variant.RAINBOW
    "FruitSalad" -> Variant.FRUIT_SALAD
    "Monochrome" -> Variant.MONOCHROME
    "Fidelity" -> Variant.FIDELITY
    "Content" -> Variant.CONTENT
    else -> Variant.TONAL_SPOT
}

fun specVersionOf(value: String): ColorSpec.SpecVersion =
    if (value == "SPEC_2025") ColorSpec.SpecVersion.SPEC_2025 else ColorSpec.SpecVersion.SPEC_2021

private fun DynamicScheme.color(role: DynamicColor): Color = Color(role.getArgb(this))

fun DynamicScheme.toColorScheme(): ColorScheme = ColorScheme(
    primary = color(dynamicColors.primary()),
    onPrimary = color(dynamicColors.onPrimary()),
    primaryContainer = color(dynamicColors.primaryContainer()),
    onPrimaryContainer = color(dynamicColors.onPrimaryContainer()),
    inversePrimary = color(dynamicColors.inversePrimary()),
    secondary = color(dynamicColors.secondary()),
    onSecondary = color(dynamicColors.onSecondary()),
    secondaryContainer = color(dynamicColors.secondaryContainer()),
    onSecondaryContainer = color(dynamicColors.onSecondaryContainer()),
    tertiary = color(dynamicColors.tertiary()),
    onTertiary = color(dynamicColors.onTertiary()),
    tertiaryContainer = color(dynamicColors.tertiaryContainer()),
    onTertiaryContainer = color(dynamicColors.onTertiaryContainer()),
    background = color(dynamicColors.background()),
    onBackground = color(dynamicColors.onBackground()),
    surface = color(dynamicColors.surface()),
    onSurface = color(dynamicColors.onSurface()),
    surfaceVariant = color(dynamicColors.surfaceVariant()),
    onSurfaceVariant = color(dynamicColors.onSurfaceVariant()),
    surfaceTint = color(dynamicColors.surfaceTint()),
    inverseSurface = color(dynamicColors.inverseSurface()),
    inverseOnSurface = color(dynamicColors.inverseOnSurface()),
    error = color(dynamicColors.error()),
    onError = color(dynamicColors.onError()),
    errorContainer = color(dynamicColors.errorContainer()),
    onErrorContainer = color(dynamicColors.onErrorContainer()),
    outline = color(dynamicColors.outline()),
    outlineVariant = color(dynamicColors.outlineVariant()),
    scrim = color(dynamicColors.scrim()),
    surfaceBright = color(dynamicColors.surfaceBright()),
    surfaceDim = color(dynamicColors.surfaceDim()),
    surfaceContainer = color(dynamicColors.surfaceContainer()),
    surfaceContainerHigh = color(dynamicColors.surfaceContainerHigh()),
    surfaceContainerHighest = color(dynamicColors.surfaceContainerHighest()),
    surfaceContainerLow = color(dynamicColors.surfaceContainerLow()),
    surfaceContainerLowest = color(dynamicColors.surfaceContainerLowest()),
    primaryFixed = color(dynamicColors.primaryFixed()),
    primaryFixedDim = color(dynamicColors.primaryFixedDim()),
    onPrimaryFixed = color(dynamicColors.onPrimaryFixed()),
    onPrimaryFixedVariant = color(dynamicColors.onPrimaryFixedVariant()),
    secondaryFixed = color(dynamicColors.secondaryFixed()),
    secondaryFixedDim = color(dynamicColors.secondaryFixedDim()),
    onSecondaryFixed = color(dynamicColors.onSecondaryFixed()),
    onSecondaryFixedVariant = color(dynamicColors.onSecondaryFixedVariant()),
    tertiaryFixed = color(dynamicColors.tertiaryFixed()),
    tertiaryFixedDim = color(dynamicColors.tertiaryFixedDim()),
    onTertiaryFixed = color(dynamicColors.onTertiaryFixed()),
    onTertiaryFixedVariant = color(dynamicColors.onTertiaryFixedVariant()),
)

@Composable
private fun animateThemeColor(target: Color, label: String): Color {
    if (target == Color.Unspecified) return target
    val animated by animateColorAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        label = label,
    )
    return animated
}

@Composable
private fun animateScheme(target: ColorScheme): ColorScheme = target.copy(
    primary = animateThemeColor(target.primary, "primary"),
    onPrimary = animateThemeColor(target.onPrimary, "onPrimary"),
    primaryContainer = animateThemeColor(target.primaryContainer, "primaryContainer"),
    onPrimaryContainer = animateThemeColor(target.onPrimaryContainer, "onPrimaryContainer"),
    inversePrimary = animateThemeColor(target.inversePrimary, "inversePrimary"),
    secondary = animateThemeColor(target.secondary, "secondary"),
    onSecondary = animateThemeColor(target.onSecondary, "onSecondary"),
    secondaryContainer = animateThemeColor(target.secondaryContainer, "secondaryContainer"),
    onSecondaryContainer = animateThemeColor(target.onSecondaryContainer, "onSecondaryContainer"),
    tertiary = animateThemeColor(target.tertiary, "tertiary"),
    onTertiary = animateThemeColor(target.onTertiary, "onTertiary"),
    tertiaryContainer = animateThemeColor(target.tertiaryContainer, "tertiaryContainer"),
    onTertiaryContainer = animateThemeColor(target.onTertiaryContainer, "onTertiaryContainer"),
    background = animateThemeColor(target.background, "background"),
    onBackground = animateThemeColor(target.onBackground, "onBackground"),
    surface = animateThemeColor(target.surface, "surface"),
    onSurface = animateThemeColor(target.onSurface, "onSurface"),
    surfaceVariant = animateThemeColor(target.surfaceVariant, "surfaceVariant"),
    onSurfaceVariant = animateThemeColor(target.onSurfaceVariant, "onSurfaceVariant"),
    surfaceTint = animateThemeColor(target.surfaceTint, "surfaceTint"),
    inverseSurface = animateThemeColor(target.inverseSurface, "inverseSurface"),
    inverseOnSurface = animateThemeColor(target.inverseOnSurface, "inverseOnSurface"),
    error = animateThemeColor(target.error, "error"),
    onError = animateThemeColor(target.onError, "onError"),
    errorContainer = animateThemeColor(target.errorContainer, "errorContainer"),
    onErrorContainer = animateThemeColor(target.onErrorContainer, "onErrorContainer"),
    outline = animateThemeColor(target.outline, "outline"),
    outlineVariant = animateThemeColor(target.outlineVariant, "outlineVariant"),
    scrim = animateThemeColor(target.scrim, "scrim"),
    surfaceBright = animateThemeColor(target.surfaceBright, "surfaceBright"),
    surfaceDim = animateThemeColor(target.surfaceDim, "surfaceDim"),
    surfaceContainer = animateThemeColor(target.surfaceContainer, "surfaceContainer"),
    surfaceContainerHigh = animateThemeColor(target.surfaceContainerHigh, "surfaceContainerHigh"),
    surfaceContainerHighest = animateThemeColor(target.surfaceContainerHighest, "surfaceContainerHighest"),
    surfaceContainerLow = animateThemeColor(target.surfaceContainerLow, "surfaceContainerLow"),
    surfaceContainerLowest = animateThemeColor(target.surfaceContainerLowest, "surfaceContainerLowest"),
)

@Composable
fun NeriTheme(
    seedColorHex: String,
    isDark: Boolean,
    paletteStyle: String,
    colorSpec: String,
    content: @Composable () -> Unit,
) {
    val target = remember(seedColorHex, isDark, paletteStyle, colorSpec) {
        val scheme = buildDynamicScheme(
            seedArgb = seedColorArgb(seedColorHex),
            isDark = isDark,
            variant = variantOf(paletteStyle),
            specVersion = specVersionOf(colorSpec),
        )
        scheme.toColorScheme()
    }
    val animated = animateScheme(target)
    MaterialTheme(
        colorScheme = animated,
        typography = Typography(),
        content = content,
    )
}

@Suppress("unused")
private val fallbackLight = lightColorScheme()

@Suppress("unused")
private val fallbackDark = darkColorScheme()
