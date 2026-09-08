package com.foxhole.guard.ui.cli

import android.os.Build
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foxhole.core.model.AccentColor
import com.foxhole.core.model.PanelAppearance
import com.foxhole.core.model.ThemeMode
import com.foxhole.guard.R

internal val CliNeonBlue = Color(0xFF2FD9F2)

internal val CliDataBlue = Color(0xFF58A6FF)

internal val CliAutoGreen = Color(0xFF7FB34A)

@Immutable
data class CliStatusColors(
    val success: Color = CliAutoGreen,
    val information: Color = CliNeonBlue,
    val data: Color = CliDataBlue,
    val warning: Color = Color(0xFFFFD447),
    val attention: Color = Color(0xFFFF9A3D),
    val error: Color = Color(0xFFE0562A),
)

@Immutable
data class CliChannelColors(
    val vpn: Color = CliAutoGreen,
    val tor: Color = Color(0xFFFF7A1A),
    val i2p: Color = Color(0xFFF45BC8),
    val firewall: Color = CliNeonBlue,
    val dns: Color = CliDataBlue,
)

@Immutable
data class CliMapColors(
    val water: Color = Color(0xFF1F1A12),
    val land: Color = Color(0xFF3F3731),
    val coast: Color = Color(0xFFA85210),
    val route: Color = CliDataBlue,
    val grid: Color = Color(0xFF423625),
    val marker: Color = Color(0xFFF0E6D6),
)

@Immutable
data class CliColors(
    val bg: Color = Color(0xFF0B0A08),
    val panel: Color = Color(0xFF15120D),
    val panelAlt: Color = Color(0xFF1F1A12),
    val border: Color = Color(0xFF4A3C29),
    val borderBright: Color = Color(0xFF806548),
    val fg: Color = Color(0xFFF0E6D6),
    val dim: Color = Color(0xFFB49A76),
    val faint: Color = Color(0xFF8B7A5E),
    val accent: Color = CliAutoGreen,
    val accentBright: Color = Color(0xFFA5CD7F),
    val accentDim: Color = Color(0xFF5B8135),
    val onAccent: Color = Color(0xFF150A02),
    val status: CliStatusColors = CliStatusColors(),
    val channel: CliChannelColors = CliChannelColors(),
    val map: CliMapColors = CliMapColors(),
) {
    val ok: Color get() = status.success
    val info: Color get() = status.information
    val data: Color get() = status.data
    val warn: Color get() = status.warning
    val alert: Color get() = status.attention
    val err: Color get() = status.error
    val vpn: Color get() = channel.vpn
    val tor: Color get() = channel.tor
    val i2p: Color get() = channel.i2p
    val firewall: Color get() = channel.firewall
    val dnsFilter: Color get() = channel.dns
}

val LocalCliColors = staticCompositionLocalOf { CliColors() }

val LocalCliPanelAppearance = staticCompositionLocalOf { PanelAppearance.STANDARD }

val LocalCliDynamicColors = staticCompositionLocalOf { false }

object CliMotion {
    const val DurationShort = 100

    const val DurationQuick = 200

    const val DurationMedium = 300

    const val DurationEmphasis = 400

    val EasingStandard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    val EasingEnter: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    val EasingExit: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    fun <T> enter(durationMillis: Int = DurationMedium): FiniteAnimationSpec<T> =
        tween(durationMillis = durationMillis, easing = EasingEnter)

    fun <T> exit(durationMillis: Int = DurationShort): FiniteAnimationSpec<T> =
        tween(durationMillis = durationMillis, easing = EasingExit)

    fun <T> standard(durationMillis: Int = DurationQuick): FiniteAnimationSpec<T> =
        tween(durationMillis = durationMillis, easing = EasingStandard)

    fun <T> press(): SpringSpec<T> =
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)

    fun <T> settle(): SpringSpec<T> =
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

    fun <T> emphasis(): SpringSpec<T> =
        spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)
}

private const val CLI_TYPE_SCALE = 1.1f

internal const val CLI_UNIFIED_METRIC_SCALE = 0.92f

object CliIconSize {
    val glyph = (12 * CLI_TYPE_SCALE).dp

    val note = (14 * CLI_TYPE_SCALE).dp

    val row = (16 * CLI_TYPE_SCALE).dp
}

fun cliScaledSp(baseSp: Float): TextUnit = (baseSp * CLI_TYPE_SCALE).sp

fun cliScaledDp(baseDp: Float): Dp = (baseDp * CLI_TYPE_SCALE).dp

object CliSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
}

object CliRadius {
    val hairline = 1.dp
    val pixel = 2.dp
    val indicator = 4.dp
    val control = 6.dp
    val panel = 8.dp
    val modal = 12.dp
    val sheet = 24.dp
}

private val Tiny5Family = FontFamily(Font(R.font.tiny5_regular))

@Immutable
data class CliTypography(
    val body: TextStyle,
    val small: TextStyle,
    val title: TextStyle,
    val display: TextStyle,
    val button: TextStyle,
)

private val JetBrainsMonoBoldFamily = FontFamily(Font(R.font.jetbrains_mono_bold, FontWeight.Bold))

private fun cliUnifiedSp(baseSp: Int): TextUnit =
    (baseSp * CLI_TYPE_SCALE * CLI_UNIFIED_METRIC_SCALE).sp

private val CliUnifiedTypography = CliTypography(
    body = TextStyle(
        fontFamily = JetBrainsMonoBoldFamily,
        fontWeight = FontWeight.Bold,
        fontSize = cliUnifiedSp(15),
        lineHeight = cliUnifiedSp(19),
    ),
    small = TextStyle(
        fontFamily = JetBrainsMonoBoldFamily,
        fontWeight = FontWeight.Bold,
        fontSize = cliUnifiedSp(13),
        lineHeight = cliUnifiedSp(16),
    ),
    title = TextStyle(
        fontFamily = JetBrainsMonoBoldFamily,
        fontWeight = FontWeight.Bold,
        fontSize = cliUnifiedSp(14),
        lineHeight = cliUnifiedSp(21),
    ),
    display = TextStyle(
        fontFamily = JetBrainsMonoBoldFamily,
        fontWeight = FontWeight.Bold,
        fontSize = cliUnifiedSp(16),
        lineHeight = cliUnifiedSp(21),
    ),
    button = TextStyle(
        fontFamily = JetBrainsMonoBoldFamily,
        fontWeight = FontWeight.Bold,
        fontSize = cliUnifiedSp(15),
        lineHeight = cliUnifiedSp(20),
    ),
)

private val CliPixelArtTypography = CliUnifiedTypography.copy(
    button = TextStyle(
        fontFamily = Tiny5Family,
        fontSize = cliPixelFontSizeForMonoSp(cliUnifiedSp(15).value),
        lineHeight = cliUnifiedSp(20),
    ),
)

private val CliHeadingLineHeight = cliUnifiedSp(23)

private val CliHeadingTitle = TextStyle(
    fontFamily = Tiny5Family,
    fontSize = cliPixelHeadingSp(14),
    lineHeight = CliHeadingLineHeight,
)

private val CliHeadingDisplay = TextStyle(
    fontFamily = Tiny5Family,
    fontSize = cliPixelHeadingSp(16),
    lineHeight = CliHeadingLineHeight,
)

private val CliMonoHeadingTitle = CliUnifiedTypography.title.copy(
    lineHeight = CliHeadingLineHeight,
)

private val CliMonoHeadingDisplay = CliUnifiedTypography.display.copy(
    lineHeight = CliHeadingLineHeight,
)

private fun cliPixelHeadingSp(baseSp: Int): TextUnit =
    cliPixelFontSizeForMonoSp(baseSp * CLI_TYPE_SCALE * CLI_UNIFIED_METRIC_SCALE)

internal fun cliPixelFontSizeForMonoSp(monoFontSizeSp: Float): TextUnit =
    (monoFontSizeSp * CLI_PIXEL_FONT_SIZE_SCALE).sp

internal fun cliFontSizeForMode(
    monoFontSize: TextUnit,
    pixelArtEnabled: Boolean,
): TextUnit = if (pixelArtEnabled) {
    cliPixelFontSizeForMonoSp(monoFontSize.value)
} else {
    monoFontSize
}

internal const val CLI_PIXEL_FONT_CAP_HEIGHT_RATIO = 640f / 1024f
internal const val CLI_MONO_FONT_CAP_HEIGHT_RATIO = 730f / 1000f
internal const val CLI_PIXEL_FONT_SIZE_SCALE =
    CLI_MONO_FONT_CAP_HEIGHT_RATIO / CLI_PIXEL_FONT_CAP_HEIGHT_RATIO

internal fun cliTypography(pixelArtEnabled: Boolean = true): CliTypography =
    if (pixelArtEnabled) CliPixelArtTypography else CliUnifiedTypography

internal val CLI_ICON_OPTICAL_OFFSET = 0.dp

@Composable
@ReadOnlyComposable
fun cliMetricSp(baseSp: Float): TextUnit =
    (baseSp * CLI_TYPE_SCALE * CLI_UNIFIED_METRIC_SCALE).sp

@Composable
@ReadOnlyComposable
fun cliMetricDp(baseDp: Float): Dp =
    (baseDp * CLI_TYPE_SCALE * CLI_UNIFIED_METRIC_SCALE).dp

val LocalCliType = staticCompositionLocalOf { CliPixelArtTypography }

val LocalCliPixelArtEnabled = staticCompositionLocalOf { true }

@Composable
@ReadOnlyComposable
fun cliDisplayStyle(text: String): TextStyle =
    cliDisplayStyleFor(text, LocalCliPixelArtEnabled.current)

@Composable
@ReadOnlyComposable
fun cliScreenTitleStyle(text: String): TextStyle =
    cliScreenTitleStyleFor(text, LocalCliPixelArtEnabled.current)

internal fun cliScreenTitleStyleFor(
    @Suppress("UNUSED_PARAMETER") text: String,
    pixelArtEnabled: Boolean = true,
): TextStyle =
    if (pixelArtEnabled) CliHeadingDisplay else CliMonoHeadingDisplay

internal fun cliDisplayStyleFor(
    @Suppress("UNUSED_PARAMETER") text: String,
    pixelArtEnabled: Boolean = true,
): TextStyle =
    if (pixelArtEnabled) CliHeadingDisplay else CliMonoHeadingDisplay

@Composable
@ReadOnlyComposable
fun cliTitleStyle(text: String): TextStyle =
    cliTitleStyleFor(text, LocalCliPixelArtEnabled.current)

internal fun cliTitleStyleFor(
    @Suppress("UNUSED_PARAMETER") text: String,
    pixelArtEnabled: Boolean = true,
): TextStyle =
    if (pixelArtEnabled) CliHeadingTitle else CliMonoHeadingTitle

@Composable
@ReadOnlyComposable
fun cliPanelTitleStyle(text: String): TextStyle =
    cliPanelTitleStyleFor(text, LocalCliPixelArtEnabled.current)

internal fun cliPanelTitleStyleFor(
    @Suppress("UNUSED_PARAMETER") text: String,
    pixelArtEnabled: Boolean = true,
): TextStyle =
    if (pixelArtEnabled) CliHeadingTitle else CliMonoHeadingTitle

@Composable
@ReadOnlyComposable
fun cliCaptionSpanStyle(text: String): SpanStyle =
    cliTitleStyle(text).let { style ->
        SpanStyle(
            fontFamily = style.fontFamily,
            fontSize = style.fontSize,
        )
    }

@Composable
@ReadOnlyComposable
fun cliLabelText(text: String): String =
    cliTitleCaseLabel(text)

internal fun cliTitleCaseLabel(text: String): String =
    text.replaceFirstChar { char -> char.uppercaseChar() }

internal fun cliHeadingText(text: String): String =
    text.uppercase()

@Composable
@ReadOnlyComposable
fun cliRowTextStyle(): TextStyle =
    LocalCliType.current.body

@Composable
@ReadOnlyComposable
fun cliCaptionTextStyle(): TextStyle =
    LocalCliType.current.body

internal fun cliHeadingOpticalOffsetFor(
    @Suppress("UNUSED_PARAMETER") text: String,
    @Suppress("UNUSED_PARAMETER") pixelArtEnabled: Boolean = true,
): Dp = CLI_HEADING_LIFT

internal fun cliPanelHeadingOpticalOffsetFor(
    @Suppress("UNUSED_PARAMETER") text: String,
    @Suppress("UNUSED_PARAMETER") pixelArtEnabled: Boolean = true,
): Dp = CLI_PANEL_HEADING_LIFT

internal fun cliHeadingGlyphOpticalOffsetFor(
    @Suppress("UNUSED_PARAMETER") text: String,
    @Suppress("UNUSED_PARAMETER") pixelArtEnabled: Boolean = true,
): Dp = CLI_HEADING_GLYPH_LIFT

internal fun cliScreenHeadingOpticalOffsetFor(
    @Suppress("UNUSED_PARAMETER") text: String,
    @Suppress("UNUSED_PARAMETER") pixelArtEnabled: Boolean = true,
): Dp = CLI_SCREEN_HEADING_LIFT

internal val CLI_HEADING_LIFT = (-2).dp

internal val CLI_PANEL_HEADING_LIFT = (-2).dp

internal val CLI_SCREEN_HEADING_LIFT = (-4).dp

internal val CLI_HEADING_GLYPH_LIFT = (-1).dp

internal val CLI_FIRST_LINE_GLYPH_DROP = 2.dp

object CliType {
    val body: TextStyle
        @Composable @ReadOnlyComposable
        get() = LocalCliType.current.body
    val small: TextStyle
        @Composable @ReadOnlyComposable
        get() = LocalCliType.current.small
    val title: TextStyle
        @Composable @ReadOnlyComposable
        get() = LocalCliType.current.title
    val display: TextStyle
        @Composable @ReadOnlyComposable
        get() = LocalCliType.current.display
    val button: TextStyle
        @Composable @ReadOnlyComposable
        get() = LocalCliType.current.button
}

internal val CliWarmDarkColors = CliColors().withAccentHue(CliAutoGreen, light = false)

internal val CliOledDarkColors = CliWarmDarkColors.copy(
    bg = Color.Black,
    panel = Color.Black,
    panelAlt = Color.Black,
    map = CliWarmDarkColors.map.copy(
        water = Color.Black,
        land = Color.Black,
    ),
)

internal val CliLightColors = CliColors(
    bg = Color(0xFFF2E8D5),
    panel = Color(0xFFFFF8EA),
    panelAlt = Color(0xFFE7D8BE),
    border = Color(0xFFB7A17D),
    borderBright = Color(0xFF806542),
    fg = Color(0xFF2A2118),
    dim = Color(0xFF665440),
    faint = Color(0xFF756149),
    accent = Color(0xFF27723B),
    onAccent = Color(0xFFFFF8EA),
    status = CliStatusColors(
        success = Color(0xFF27723B),
        information = Color(0xFF0E7082),
        data = Color(0xFF175CA8),
        warning = Color(0xFF996600),
        attention = Color(0xFFA44508),
        error = Color(0xFFB83224),
    ),
    channel = CliChannelColors(
        vpn = Color(0xFF27723B),
        tor = Color(0xFFA94508),
        i2p = Color(0xFF9D286F),
        firewall = Color(0xFF087487),
        dns = Color(0xFF175CA8),
    ),
    map = CliMapColors(
        water = Color(0xFFE7D8BE),
        land = Color(0xFFBBA991),
        coast = Color(0xFFA94508),
        route = Color(0xFF175CA8),
        grid = Color(0xFFB7A17D),
        marker = Color(0xFF2A2118),
    ),
).withAccentHue(Color(0xFF27723B), light = true)

@Composable
@ReadOnlyComposable
fun cliResolvedThemeMode(themeMode: ThemeMode): ThemeMode =
    when (themeMode) {
        ThemeMode.SYSTEM -> if (isSystemInDarkTheme()) ThemeMode.DARK else ThemeMode.LIGHT
        ThemeMode.DARK -> ThemeMode.DARK
        ThemeMode.OLED -> ThemeMode.OLED
        ThemeMode.LIGHT -> ThemeMode.LIGHT
    }

internal fun cliPanelAppearanceFor(themeMode: ThemeMode): PanelAppearance =
    when (themeMode) {
        ThemeMode.SYSTEM -> PanelAppearance.AUTO
        ThemeMode.DARK -> PanelAppearance.STANDARD
        ThemeMode.OLED -> PanelAppearance.STANDARD
        ThemeMode.LIGHT -> PanelAppearance.LIGHT
    }

private val DarkAccentHues = mapOf(
    AccentColor.ORANGE to Color(0xFFF07A14),
    AccentColor.GREEN to CliAutoGreen,
    AccentColor.LIME to Color(0xFFA3E635),
    AccentColor.BLUE to CliDataBlue,
    AccentColor.PINK to Color(0xFFFF5FCF),
    AccentColor.CYAN to CliNeonBlue,
    AccentColor.WHITE to Color.White,
)

private val LightAccentHues = mapOf(
    AccentColor.ORANGE to Color(0xFFA94E00),
    AccentColor.GREEN to Color(0xFF27723B),
    AccentColor.LIME to Color(0xFF46740C),
    AccentColor.BLUE to Color(0xFF175CA8),
    AccentColor.PINK to Color(0xFF9D286F),
    AccentColor.CYAN to Color(0xFF0C708C),
    AccentColor.WHITE to Color(0xFF303842),
)

private fun CliColors.withAccentHue(hue: Color, light: Boolean): CliColors =
    if (light) {
        copy(
            accent = hue,
            accentBright = lerp(hue, Color.Black, 0.18f),
            accentDim = lerp(hue, Color.White, 0.35f),
        )
    } else {
        copy(
            accent = hue,
            accentBright = lerp(hue, Color.White, 0.30f),
            accentDim = lerp(hue, Color.Black, 0.28f),
        )
    }

internal fun cliColorsFor(
    resolvedThemeMode: ThemeMode,
    accent: AccentColor = AccentColor.AUTO,
    monochromeEnabled: Boolean = false,
): CliColors {
    require(resolvedThemeMode != ThemeMode.SYSTEM) { "theme mode must be resolved before selecting fixed colors" }
    val light = resolvedThemeMode == ThemeMode.LIGHT
    if (monochromeEnabled) return cliMonochromeColors(light)
    val base = when (resolvedThemeMode) {
        ThemeMode.SYSTEM -> error("theme mode must be resolved before selecting fixed colors")
        ThemeMode.DARK -> CliWarmDarkColors
        ThemeMode.OLED -> CliOledDarkColors
        ThemeMode.LIGHT -> CliLightColors
    }
    val resolvedAccent = if (accent == AccentColor.AUTO) AccentColor.GREEN else accent
    val hues = if (light) LightAccentHues else DarkAccentHues
    val hue = hues.getValue(resolvedAccent)
    return base.withAccentHue(hue, light)
}

internal fun cliAccentSwatch(
    resolvedThemeMode: ThemeMode,
    accent: AccentColor,
): Color = cliColorsFor(resolvedThemeMode, accent).accent

internal fun cliColorsFromDynamicScheme(
    scheme: ColorScheme,
    light: Boolean,
): CliColors {
    val resolvedMode = if (light) ThemeMode.LIGHT else ThemeMode.DARK
    val base = cliColorsFor(resolvedMode)
    return base.copy(
        bg = scheme.background,
        panel = scheme.surface,
        panelAlt = scheme.surfaceVariant,
        border = scheme.outline,
        borderBright = lerp(scheme.outline, scheme.onSurface, if (light) 0.25f else 0.45f),
        fg = scheme.onBackground,
        dim = scheme.onSurfaceVariant,
        faint = lerp(scheme.onSurfaceVariant, scheme.surface, if (light) 0.28f else 0.42f),
        status = base.status.copy(
            information = scheme.secondary,
            data = scheme.tertiary,
            error = scheme.error,
        ),
        channel = base.channel.copy(
            firewall = scheme.secondary,
            dns = scheme.tertiary,
        ),
        map = base.map.copy(
            water = scheme.surface,
            land = lerp(scheme.surface, scheme.secondary, 0.10f),
            coast = lerp(scheme.surface, scheme.secondary, 0.45f),
            route = scheme.primary,
            grid = scheme.onSurfaceVariant,
            marker = scheme.onSurface,
        ),
        accent = scheme.primary,
        accentBright = scheme.primary,
        accentDim = scheme.primaryContainer,
        onAccent = scheme.onPrimary,
    )
}

private fun cliSchemeFor(colors: CliColors, light: Boolean) = if (light) {
    lightColorScheme(
        primary = colors.accent,
        onPrimary = colors.onAccent,
        secondary = colors.info,
        background = colors.bg,
        onBackground = colors.fg,
        surface = colors.panel,
        onSurface = colors.fg,
        surfaceVariant = colors.panelAlt,
        onSurfaceVariant = colors.dim,
        outline = colors.border,
        error = colors.err,
    )
} else {
    darkColorScheme(
        primary = colors.accent,
        onPrimary = colors.onAccent,
        secondary = colors.info,
        background = colors.bg,
        onBackground = colors.fg,
        surface = colors.panel,
        onSurface = colors.fg,
        surfaceVariant = colors.panelAlt,
        onSurfaceVariant = colors.dim,
        outline = colors.border,
        error = colors.err,
    )
}

@Composable
fun CliTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    accentColor: AccentColor = AccentColor.AUTO,
    pixelArtEnabled: Boolean = true,
    monochromeEnabled: Boolean = false,
    content: @Composable () -> Unit,
) {
    val resolvedThemeMode = cliResolvedThemeMode(themeMode)
    val resolvedAppearance = cliPanelAppearanceFor(themeMode)
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val dynamicPalette = !monochromeEnabled && themeMode == ThemeMode.SYSTEM &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val dynamicScheme = remember(dynamicPalette, systemDark, context) {
        if (dynamicPalette) {
            if (systemDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            null
        }
    }
    val light = resolvedThemeMode == ThemeMode.LIGHT
    val colors = remember(resolvedThemeMode, accentColor, dynamicScheme, monochromeEnabled) {
        dynamicScheme?.let { scheme ->
            cliColorsFromDynamicScheme(scheme, light)
        } ?: cliColorsFor(resolvedThemeMode, accentColor, monochromeEnabled)
    }
    val scheme = dynamicScheme ?: remember(resolvedThemeMode, accentColor, colors) {
        cliSchemeFor(colors, light)
    }
    CompositionLocalProvider(
        LocalCliMonochrome provides monochromeEnabled,
        LocalCliColors provides colors,
        LocalCliPanelAppearance provides resolvedAppearance,
        LocalCliDynamicColors provides (dynamicScheme != null),
        LocalCliType provides cliTypography(pixelArtEnabled),
        LocalCliPixelArtEnabled provides pixelArtEnabled,
    ) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
