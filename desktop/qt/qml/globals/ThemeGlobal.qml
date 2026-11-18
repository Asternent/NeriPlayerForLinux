import QtQuick

// 主题与设计令牌（Material 3）。颜色默认值是一套 M3 深色基线，
// 后端连上后会通过 "theme" 事件下发由 Material Color Utilities 算出的真实调色板。
QtObject {
    id: theme

    property bool isDark: true
    property var roles: ({
        "primary": "#d0bcff",
        "onPrimary": "#381e72",
        "primaryContainer": "#4f378b",
        "onPrimaryContainer": "#eaddff",
        "secondary": "#ccc2dc",
        "onSecondary": "#332d41",
        "secondaryContainer": "#4a4458",
        "onSecondaryContainer": "#e8def8",
        "tertiary": "#efb8c8",
        "onTertiary": "#492532",
        "tertiaryContainer": "#633b48",
        "onTertiaryContainer": "#ffd8e4",
        "error": "#f2b8b5",
        "onError": "#601410",
        "errorContainer": "#8c1d18",
        "onErrorContainer": "#f9dedc",
        "background": "#141218",
        "onBackground": "#e6e0e9",
        "surface": "#141218",
        "onSurface": "#e6e0e9",
        "surfaceVariant": "#49454f",
        "onSurfaceVariant": "#cac4d0",
        "surfaceTint": "#d0bcff",
        "inverseSurface": "#e6e0e9",
        "inverseOnSurface": "#322f35",
        "inversePrimary": "#6750a4",
        "outline": "#938f99",
        "outlineVariant": "#49454f",
        "scrim": "#000000",
        "surfaceDim": "#141218",
        "surfaceBright": "#3b383e",
        "surfaceContainerLowest": "#0f0d13",
        "surfaceContainerLow": "#1d1b20",
        "surfaceContainer": "#211f26",
        "surfaceContainerHigh": "#2b292f",
        "surfaceContainerHighest": "#36343b",
        "primaryFixed": "#eaddff",
        "primaryFixedDim": "#d0bcff",
        "onPrimaryFixed": "#21005d",
        "onPrimaryFixedVariant": "#4f378b",
        "secondaryFixed": "#e8def8",
        "secondaryFixedDim": "#ccc2dc",
        "onSecondaryFixed": "#1d192b",
        "onSecondaryFixedVariant": "#4a4458",
        "tertiaryFixed": "#ffd8e4",
        "tertiaryFixedDim": "#efb8c8",
        "onTertiaryFixed": "#31111d",
        "onTertiaryFixedVariant": "#633b48"
    })

    function c(name) {
        return roles[name] !== undefined ? roles[name] : "#ff00ff"
    }

    // ---- 常用颜色（便于直接绑定，带 420ms 过渡动画） ----
    readonly property color primary: c("primary")
    readonly property color onPrimaryColor: c("onPrimary")
    readonly property color primaryContainer: c("primaryContainer")
    readonly property color onPrimaryContainerColor: c("onPrimaryContainer")
    readonly property color secondary: c("secondary")
    readonly property color onSecondaryColor: c("onSecondary")
    readonly property color secondaryContainer: c("secondaryContainer")
    readonly property color onSecondaryContainerColor: c("onSecondaryContainer")
    readonly property color tertiary: c("tertiary")
    readonly property color onTertiaryColor: c("onTertiary")
    readonly property color tertiaryContainer: c("tertiaryContainer")
    readonly property color onTertiaryContainerColor: c("onTertiaryContainer")
    readonly property color error: c("error")
    readonly property color onErrorColor: c("onError")
    readonly property color errorContainer: c("errorContainer")
    readonly property color onErrorContainerColor: c("onErrorContainer")
    readonly property color background: c("background")
    readonly property color onBackgroundColor: c("onBackground")
    readonly property color surface: c("surface")
    readonly property color onSurfaceColor: c("onSurface")
    readonly property color surfaceVariant: c("surfaceVariant")
    readonly property color onSurfaceVariantColor: c("onSurfaceVariant")
    readonly property color surfaceTint: c("surfaceTint")
    readonly property color inverseSurface: c("inverseSurface")
    readonly property color inverseOnSurface: c("inverseOnSurface")
    readonly property color inversePrimary: c("inversePrimary")
    readonly property color outline: c("outline")
    readonly property color outlineVariant: c("outlineVariant")
    readonly property color scrim: c("scrim")
    readonly property color surfaceDim: c("surfaceDim")
    readonly property color surfaceBright: c("surfaceBright")
    readonly property color surfaceContainerLowest: c("surfaceContainerLowest")
    readonly property color surfaceContainerLow: c("surfaceContainerLow")
    readonly property color surfaceContainer: c("surfaceContainer")
    readonly property color surfaceContainerHigh: c("surfaceContainerHigh")
    readonly property color surfaceContainerHighest: c("surfaceContainerHighest")

    // ---- 排版（Material 3 默认 Typography，单位：逻辑像素） ----
    readonly property string fontFamily: "Noto Sans CJK SC"
    property FontLoader iconFontLoader: FontLoader {
        source: Qt.resolvedUrl("../resources/fonts/MaterialSymbolsOutlined.ttf")
    }
    readonly property string iconFontFamily: iconFontLoader.name !== "" ? iconFontLoader.name : "Material Symbols Outlined"

    readonly property var typography: ({
        "displayLarge": { size: 57, lineHeight: 64, weight: Font.Normal, letterSpacing: 0.0 },
        "displayMedium": { size: 45, lineHeight: 52, weight: Font.Normal, letterSpacing: 0.0 },
        "displaySmall": { size: 36, lineHeight: 44, weight: Font.Normal, letterSpacing: 0.0 },
        "headlineLarge": { size: 32, lineHeight: 40, weight: Font.Normal, letterSpacing: 0.0 },
        "headlineMedium": { size: 28, lineHeight: 36, weight: Font.Normal, letterSpacing: 0.0 },
        "headlineSmall": { size: 24, lineHeight: 32, weight: Font.Normal, letterSpacing: 0.0 },
        "titleLarge": { size: 22, lineHeight: 28, weight: Font.Normal, letterSpacing: 0.0 },
        "titleMedium": { size: 16, lineHeight: 24, weight: Font.Medium, letterSpacing: 0.15 },
        "titleSmall": { size: 14, lineHeight: 20, weight: Font.Medium, letterSpacing: 0.1 },
        "bodyLarge": { size: 16, lineHeight: 24, weight: Font.Normal, letterSpacing: 0.5 },
        "bodyMedium": { size: 14, lineHeight: 20, weight: Font.Normal, letterSpacing: 0.25 },
        "bodySmall": { size: 12, lineHeight: 16, weight: Font.Normal, letterSpacing: 0.4 },
        "labelLarge": { size: 14, lineHeight: 20, weight: Font.Medium, letterSpacing: 0.1 },
        "labelMedium": { size: 12, lineHeight: 16, weight: Font.Medium, letterSpacing: 0.5 },
        "labelSmall": { size: 11, lineHeight: 16, weight: Font.Medium, letterSpacing: 0.5 }
    })

    function textStyle(role) {
        const style = typography[role]
        return style !== undefined ? style : typography["bodyMedium"]
    }

    function fontFor(role) {
        const style = textStyle(role)
        return Qt.font({
            "family": fontFamily,
            "pixelSize": style.size,
            "weight": style.weight,
            "letterSpacing": style.letterSpacing
        })
    }

    // ---- 间距 ----
    readonly property real spacingXs: 4
    readonly property real spacingSm: 8
    readonly property real spacingMd: 12
    readonly property real spacingLg: 16
    readonly property real spacingXl: 24
    readonly property real spacingXxl: 32
    readonly property real spacingHuge: 48

    // ---- 圆角（M3 shape） ----
    readonly property real radiusXs: 4
    readonly property real radiusSm: 8
    readonly property real radiusMd: 12
    readonly property real radiusLg: 16
    readonly property real radiusXl: 20
    readonly property real radiusXxl: 28
    readonly property real radiusFull: 999

    // ---- 动效 ----
    readonly property int motionShort: 120
    readonly property int motionMedium: 220
    readonly property int motionLong: 420
    readonly property int easingStandard: Easing.OutCubic
    readonly property int easingEmphasized: Easing.OutBack

    // ---- 布局 ----
    readonly property real wideBreakpoint: 900
    readonly property real navRailWidth: 232
    readonly property real navRailCompactWidth: 88
    readonly property real miniPlayerHeight: 88
    readonly property real topBarHeight: 64

    // onSurface 等角色用 xxxColor 命名（见上），这里保留与 Kotlin 同名的访问入口，避免页面里到处写 c()
    function role(name) { return c(name) }

    function withAlpha(color, alpha) {
        return Qt.rgba(color.r, color.g, color.b, alpha)
    }

    function mix(a, b, t) {
        return Qt.rgba(a.r + (b.r - a.r) * t,
                       a.g + (b.g - a.g) * t,
                       a.b + (b.b - a.b) * t,
                       a.a + (b.a - a.a) * t)
    }

    /** 应用后端下发的主题快照（app.bootstrap 与 "theme" 事件共用同一形状）。 */
    function applyTheme(data) {
        if (!data)
            return
        if (data.roles !== undefined && data.roles !== null)
            roles = data.roles
        if (data.isDark !== undefined)
            isDark = data.isDark
        if (data.dark !== undefined)
            isDark = data.dark
    }

    // ---- 后端主题事件 ----
    property Connections backendConnection: Connections {
        target: Backend
        function onEvent(name, data) {
            if (name === "theme")
                theme.applyTheme(data)
        }
    }
}
