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
import com.foxhole.core.model.VisualStyle
import com.foxhole.guard.R

internal val CliNeonBlue = Color(0xFF2FD9F2)

internal val CliNoteBlue = Color(0xFF58A6FF)

@Immutable
data class CliColors(
    val bg: Color = Color(0xFF000000),
    val panel: Color = Color(0xFF0D1117),
    val panelAlt: Color = Color(0xFF151C29),
    val border: Color = Color(0xFF2C374A),
    val borderBright: Color = Color(0xFF4B5A74),
    val fg: Color = Color(0xFFE8E4DC),
    val dim: Color = Color(0xFF9CA3B0),
    val faint: Color = Color(0xFF747D8C),
    val accent: Color = Color(0xFFF07A14),
    val accentBright: Color = Color(0xFFFFA23D),
    val accentDim: Color = Color(0xFFC05808),
    val onAccent: Color = Color(0xFF150A02),
    val ok: Color = Color(0xFF6FE0B8),
    val vpn: Color = Color(0xFF3FD96A),
    val tor: Color = Color(0xFFFF8A1E),
    val i2p: Color = Color(0xFFFF5FCF),
    val firewall: Color = CliNeonBlue,
    val dnsFilter: Color = CliNoteBlue,
    val info: Color = CliNeonBlue,
    val note: Color = CliNoteBlue,
    val warn: Color = Color(0xFFFFC94D),
    val alert: Color = Color(0xFFFF9A3D),
    val err: Color = Color(0xFFFF6161),
)

val LocalCliColors = staticCompositionLocalOf { CliColors() }

val LocalCliPanelAppearance = staticCompositionLocalOf { PanelAppearance.STANDARD }

val LocalCliVisualStyle = staticCompositionLocalOf { VisualStyle.PIXEL }

val LocalCliDynamicColors = staticCompositionLocalOf { false }

/**
 * The app's motion vocabulary: durations, easings and the three ready-made specs everything
 * animated is built from. One place, so the profile selector, the profile editor and the shared
 * components cannot each invent their own timing.
 *
 * The numbers are the Material 3 motion tokens, not taste. Durations are the M3 duration scale
 * (short2/short4/medium2/medium4); the easings are the M3 easing set as cubic-beziers, taken from
 * the Material Components for Android motion attributes:
 * `motionEasingStandardInterpolator` = (0.2, 0, 0, 1),
 * `motionEasingEmphasizedDecelerateInterpolator` = (0.05, 0.7, 0.1, 1),
 * `motionEasingEmphasizedAccelerateInterpolator` = (0.3, 0, 0.8, 0.15).
 *
 * Two rules follow from the shape of those curves, and they are the whole answer to "the
 * animations start abruptly":
 *
 * 1. **Nothing uses a default tween.** Compose's `tween()` defaults to FastOutSlowIn, which leaves
 *    the rest position at speed; over the 90–150 ms these controls were using, the eased head of
 *    the curve is too short to read and the motion registers as a jump. Enter decelerates
 *    ([EasingEnter], effectively zero initial acceleration), exit accelerates ([EasingExit]).
 * 2. **Anything a finger can retarget is a spring, not a clock.** A spring carries velocity across
 *    a target change, so a second tap mid-flight bends the motion instead of restarting it from
 *    zero — restarting from zero is itself an abrupt start. Use [press], [settle] or [emphasis]
 *    for press, selection and swap; keep the tweens for one-shot fades that cannot be interrupted.
 */
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

val LocalCliMetricScale = staticCompositionLocalOf { 1f }

const val CLI_MODERN_METRIC_SCALE = 0.92f

private const val CLI_TYPE_SCALE = 1.1f

private class CliTypeStep(baseSizeSp: Int, baseLineHeightSp: Int) {
    val size = (baseSizeSp * CLI_TYPE_SCALE).sp
    val lineHeight = (baseLineHeightSp * CLI_TYPE_SCALE).sp

    val plainSize = (baseSizeSp * CLI_TYPE_SCALE * CLI_MODERN_METRIC_SCALE).sp
    val plainLineHeight = (baseLineHeightSp * CLI_TYPE_SCALE * CLI_MODERN_METRIC_SCALE).sp
}

private val CliTypeBody = CliTypeStep(15, 19)
private val CliTypeSmall = CliTypeStep(13, 16)
private val CliTypeTitle = CliTypeStep(14, 21)
private val CliTypeDisplay = CliTypeStep(16, 21)
private val CliTypeButton = CliTypeStep(15, 20)

private val CliCyrillicDisplaySize = (12 * CLI_TYPE_SCALE).sp
private val CliCyrillicCaptionSize = (10 * CLI_TYPE_SCALE).sp

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

// LanaPixel (OFL 1.1, (c) 2020 eishiya; subset: Latin + Cyrillic + punctuation + arrows).
// An 11px design, legible from 13-15sp; carries body/small/button.
private val LanaPixelFamily = FontFamily(Font(R.font.lanapixel))

// Press Start 2P (OFL 1.1, (c) 2012 The Press Start 2P Project Authors) — arcade caps for
// fixed-width contexts (ASCII art, PIN dots) where every glyph shares one advance.
// LanaPixel is second in the chain as the per-glyph fallback outside the arcade set.
private val PressStart2PFamily = FontFamily(
    Font(R.font.press_start_2p),
    Font(R.font.lanapixel),
)

// Silkscreen Bold (OFL 1.1, (c) 2001 The Silkscreen Project Authors) — the display face
// for the "FoxHole Guard" brand and screen titles. Latin-only, so Press Start 2P picks up
// Cyrillic per glyph: equally bold and pixelated, keeping non-Latin titles in the style of
// Latin ones (the thin LanaPixel broke the display grammar). LanaPixel remains the last
// fallback for glyphs in neither set.
private val SilkscreenFamily = FontFamily(
    Font(R.font.silkscreen_bold),
    Font(R.font.press_start_2p),
    Font(R.font.lanapixel),
)

@Immutable
data class CliTypography(
    val body: TextStyle,
    val small: TextStyle,
    val title: TextStyle,
    val display: TextStyle,
    val button: TextStyle,
)

private val InterFamily = FontFamily(Font(R.font.inter_regular))

private val InterMediumFamily = FontFamily(Font(R.font.inter_medium, FontWeight.Medium))

private val InterSemiBoldFamily = FontFamily(Font(R.font.inter_semibold, FontWeight.SemiBold))

private val JetBrainsMonoBoldFamily = FontFamily(Font(R.font.jetbrains_mono_bold, FontWeight.Bold))

private val CliPixelTypography = CliTypography(
    body = TextStyle(
        fontFamily = LanaPixelFamily,
        fontSize = CliTypeBody.size,
        lineHeight = CliTypeBody.lineHeight,
    ),
    small = TextStyle(
        fontFamily = LanaPixelFamily,
        fontSize = CliTypeSmall.size,
        lineHeight = CliTypeSmall.lineHeight,
    ),
    title = TextStyle(
        fontFamily = PressStart2PFamily,
        fontSize = CliTypeTitle.size,
        lineHeight = CliTypeTitle.lineHeight,
    ),
    display = TextStyle(
        fontFamily = SilkscreenFamily,
        fontSize = CliTypeDisplay.size,
        lineHeight = CliTypeDisplay.lineHeight,
    ),
    button = TextStyle(
        fontFamily = LanaPixelFamily,
        fontSize = CliTypeButton.size,
        lineHeight = CliTypeButton.lineHeight,
    ),
)

private val CliPlainTypography = CliTypography(
    body = TextStyle(
        fontFamily = InterFamily,
        fontSize = CliTypeBody.plainSize,
        lineHeight = CliTypeBody.plainLineHeight,
    ),
    small = TextStyle(
        fontFamily = InterFamily,
        fontSize = CliTypeSmall.plainSize,
        lineHeight = CliTypeSmall.plainLineHeight,
    ),
    title = TextStyle(
        fontFamily = JetBrainsMonoBoldFamily,
        fontWeight = FontWeight.Bold,
        fontSize = CliTypeTitle.plainSize,
        lineHeight = CliTypeTitle.plainLineHeight,
    ),
    display = TextStyle(
        fontFamily = InterSemiBoldFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = CliTypeDisplay.plainSize,
        lineHeight = CliTypeDisplay.plainLineHeight,
    ),
    button = TextStyle(
        fontFamily = InterMediumFamily,
        fontWeight = FontWeight.Medium,
        fontSize = CliTypeButton.plainSize,
        lineHeight = CliTypeButton.plainLineHeight,
    ),
)

internal fun cliTypographyFor(style: VisualStyle): CliTypography = when (style) {
    VisualStyle.PIXEL -> CliPixelTypography
    VisualStyle.PLAIN -> CliPlainTypography
}

internal fun cliMetricScaleFor(style: VisualStyle): Float = when (style) {
    VisualStyle.PIXEL -> 1f
    VisualStyle.PLAIN -> CLI_MODERN_METRIC_SCALE
}

@Composable
@ReadOnlyComposable
fun cliMetricSp(baseSp: Float): TextUnit = (baseSp * CLI_TYPE_SCALE * LocalCliMetricScale.current).sp

@Composable
@ReadOnlyComposable
fun cliMetricDp(baseDp: Float): Dp = (baseDp * CLI_TYPE_SCALE * LocalCliMetricScale.current).dp

val LocalCliType = staticCompositionLocalOf { CliPixelTypography }

private val CyrillicRange = 'Ѐ'..'ӿ'

/**
 * Android does not fall back per glyph within a FontFamily style, so in the pixel set a
 * Cyrillic title moves wholesale to Press Start 2P (Silkscreen is Latin-only). Inter carries
 * Cyrillic natively, so the plain set never switches.
 */
@Composable
@ReadOnlyComposable
fun cliDisplayStyle(text: String): TextStyle {
    val display = LocalCliType.current.display
    return if (LocalCliVisualStyle.current == VisualStyle.PIXEL &&
        text.any { char -> char in CyrillicRange }
    ) {
        display.copy(fontFamily = PressStart2PFamily, fontSize = CliCyrillicDisplaySize)
    } else {
        display
    }
}

@Composable
@ReadOnlyComposable
fun cliCaptionSpanStyle(text: String): SpanStyle =
    if (LocalCliVisualStyle.current == VisualStyle.PIXEL &&
        text.any { char -> char in CyrillicRange }
    ) {
        SpanStyle(fontFamily = PressStart2PFamily, fontSize = CliCyrillicCaptionSize)
    } else {
        SpanStyle(fontFamily = LocalCliType.current.display.fontFamily)
    }

@Composable
@ReadOnlyComposable
fun cliLabelText(text: String): String =
    if (LocalCliVisualStyle.current == VisualStyle.PLAIN) {
        text.replaceFirstChar { char -> char.uppercaseChar() }
    } else {
        text
    }

@Composable
@ReadOnlyComposable
fun cliRowTextStyle(): TextStyle =
    if (LocalCliVisualStyle.current == VisualStyle.PLAIN) {
        LocalCliType.current.small
    } else {
        LocalCliType.current.body
    }

@Composable
@ReadOnlyComposable
fun cliCaptionTextStyle(): TextStyle =
    if (LocalCliVisualStyle.current == VisualStyle.PLAIN) {
        LocalCliType.current.body
    } else {
        LocalCliType.current.small
    }

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

private val CliCanonColors = CliColors()

internal val CliLightColors = CliColors(
    bg = Color(0xFFF4F1EA),
    panel = Color(0xFFFDFCF9),
    panelAlt = Color(0xFFE9E4D8),
    border = Color(0xFFB8B0A0),
    borderBright = Color(0xFF8E8674),
    fg = Color(0xFF1F2328),
    dim = Color(0xFF475059),
    faint = Color(0xFF60696F),
    accent = Color(0xFFAB5000),
    accentBright = Color(0xFF8F4300),
    accentDim = Color(0xFFD98A3D),
    onAccent = Color(0xFFFFF8F0),
    ok = Color(0xFF167445),
    vpn = Color(0xFF117434),
    tor = Color(0xFFC2410C),
    i2p = Color(0xFFA82E7D),
    firewall = Color(0xFF0E7490),
    dnsFilter = Color(0xFF0969DA),
    info = Color(0xFF0E7490),
    note = Color(0xFF0969DA),
    warn = Color(0xFF8F5F00),
    alert = Color(0xFFB94A00),
    err = Color(0xFFCF222E),
)

@Composable
@ReadOnlyComposable
fun cliResolvedPanelAppearance(appearance: PanelAppearance): PanelAppearance =
    if (appearance == PanelAppearance.AUTO) {
        if (isSystemInDarkTheme()) PanelAppearance.STANDARD else PanelAppearance.LIGHT
    } else {
        appearance
    }

private val DarkAccentHues = mapOf(
    AccentColor.GREEN to Color(0xFF3FD96A),
    AccentColor.LIME to Color(0xFFA3E635),
    AccentColor.BLUE to CliNoteBlue,
    AccentColor.PINK to Color(0xFFFF5FCF),
    AccentColor.CYAN to CliNeonBlue,
)

private val LightAccentHues = mapOf(
    AccentColor.GREEN to Color(0xFF117434),
    AccentColor.LIME to Color(0xFF4A780E),
    AccentColor.BLUE to Color(0xFF0969DA),
    AccentColor.PINK to Color(0xFFA82E7D),
    AccentColor.CYAN to Color(0xFF0E7490),
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
    appearance: PanelAppearance,
    accent: AccentColor = AccentColor.ORANGE,
): CliColors {
    val light = appearance == PanelAppearance.LIGHT
    val base = if (light) CliLightColors else CliCanonColors
    val hue = (if (light) LightAccentHues else DarkAccentHues)[accent] ?: return base
    return base.withAccentHue(hue, light)
}

internal fun cliAccentSwatch(appearance: PanelAppearance, accent: AccentColor): Color =
    cliColorsFor(appearance, accent).accent

@Composable
fun cliDynamicAccentOrNull(): Color? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    return remember(context, dark) {
        (if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)).primary
    }
}

private fun cliColorsFromDynamicScheme(
    scheme: ColorScheme,
    accent: AccentColor,
    light: Boolean,
): CliColors {
    val base = if (light) CliLightColors else CliCanonColors
    val dynamic = base.copy(
        bg = scheme.background,
        panel = scheme.surface,
        panelAlt = scheme.surfaceVariant,
        border = scheme.outline,
        borderBright = lerp(scheme.outline, scheme.onSurface, if (light) 0.25f else 0.45f),
        fg = scheme.onBackground,
        dim = scheme.onSurfaceVariant,
        faint = lerp(scheme.onSurfaceVariant, scheme.surface, if (light) 0.28f else 0.42f),
        info = scheme.secondary,
        note = scheme.tertiary,
        firewall = scheme.secondary,
        dnsFilter = scheme.tertiary,
        warn = if (light) base.warn else lerp(base.warn, scheme.onSurface, 0.12f),
        err = scheme.error,
        onAccent = scheme.onPrimary,
    )
    return if (accent == AccentColor.AUTO) {
        dynamic.withAccentHue(scheme.primary, light)
    } else {
        val hues = if (light) LightAccentHues else DarkAccentHues
        dynamic.withAccentHue(hues[accent] ?: dynamic.accent, light)
    }
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
    panelAppearance: PanelAppearance = PanelAppearance.STANDARD,
    visualStyle: VisualStyle = VisualStyle.PIXEL,
    accentColor: AccentColor = AccentColor.AUTO,
    content: @Composable () -> Unit,
) {
    val resolved = cliResolvedPanelAppearance(panelAppearance)
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val dynamicPalette = panelAppearance == PanelAppearance.AUTO &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val dynamicScheme = remember(dynamicPalette, systemDark, context) {
        if (dynamicPalette) {
            if (systemDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            null
        }
    }
    val light = resolved == PanelAppearance.LIGHT
    val dynamicAccent = remember(accentColor, systemDark, context) {
        if (accentColor == AccentColor.AUTO && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val scheme = if (systemDark) {
                dynamicDarkColorScheme(context)
            } else {
                dynamicLightColorScheme(context)
            }
            scheme.primary
        } else {
            null
        }
    }
    val colors = remember(resolved, accentColor, dynamicScheme, dynamicAccent) {
        dynamicScheme?.let { scheme -> cliColorsFromDynamicScheme(scheme, accentColor, light) }
            ?: cliColorsFor(resolved, accentColor).let { base ->
                if (dynamicAccent != null) base.withAccentHue(dynamicAccent, light) else base
            }
    }
    val scheme = dynamicScheme ?: remember(resolved, accentColor, colors) {
        cliSchemeFor(colors, light = light)
    }
    CompositionLocalProvider(
        LocalCliColors provides colors,
        LocalCliPanelAppearance provides resolved,
        LocalCliVisualStyle provides visualStyle,
        LocalCliDynamicColors provides (dynamicScheme != null),
        LocalCliType provides cliTypographyFor(visualStyle),
        LocalCliMetricScale provides cliMetricScaleFor(visualStyle),
    ) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
