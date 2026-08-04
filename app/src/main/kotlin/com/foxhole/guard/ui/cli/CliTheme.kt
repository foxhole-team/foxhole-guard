package com.foxhole.guard.ui.cli

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foxhole.guard.R

/**
 * The single palette; there is no appearance setting. VPN is cyan, TOR is orange.
 *
 * Contrast against black is the constraint: text tones ≥ 7:1, [faint] ≥ 5:1,
 * [border]/[borderBright] ≥ 1.5:1/3:1. Earlier, darker values sank into black on budget
 * panels.
 */
@Immutable
data class CliColors(
    val bg: Color = Color(0xFF000000),
    val panel: Color = Color(0xFF0D1117),
    val panelAlt: Color = Color(0xFF151C29),
    val border: Color = Color(0xFF2C374A),
    val borderBright: Color = Color(0xFF43536E),
    val fg: Color = Color(0xFFE8E4DC),
    val dim: Color = Color(0xFF9CA3B0),
    val faint: Color = Color(0xFF667081),
    val accent: Color = Color(0xFFF07A14),
    val accentBright: Color = Color(0xFFFFA23D),
    val accentDim: Color = Color(0xFFC05808),
    val onAccent: Color = Color(0xFF150A02),
    val ok: Color = Color(0xFF6FE0B8),
    val vpn: Color = Color(0xFF2FD9F2),
    val tor: Color = Color(0xFFFF8A1E),
    val info: Color = Color(0xFF4D9DE8),
    val warn: Color = Color(0xFFFFC94D),
    val err: Color = Color(0xFFFF6161),
)

val LocalCliColors = staticCompositionLocalOf { CliColors() }

/**
 * The 4dp spacing grid. Ad-hoc 14/18/20/24dp are deliberately excluded — everything is
 * pinned to these four tokens. Two pixel-polish values stay off-grid on purpose and must
 * not become tokens: 6dp icon-to-text inside rows, 10dp vertical rhythm in CliPanel's
 * body. Rounding them to 4/8dp changes a tuned visual.
 */
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
// for the "FOXHOLE GUARD" brand and screen titles. Latin-only, so Press Start 2P picks up
// Cyrillic per glyph: equally bold and pixelated, keeping non-Latin titles in the style of
// Latin ones (the thin LanaPixel broke the display grammar). LanaPixel remains the last
// fallback for glyphs in neither set.
private val SilkscreenFamily = FontFamily(
    Font(R.font.silkscreen_bold),
    Font(R.font.press_start_2p),
    Font(R.font.lanapixel),
)

/** The five text roles; [title] is fixed-width (ASCII art, PIN). */
@Immutable
data class CliTypography(
    val body: TextStyle,
    val small: TextStyle,
    val title: TextStyle,
    val display: TextStyle,
    val button: TextStyle,
)

// No synthetic weights: fake-bold smears pixel grid fonts. The line heights hold the
// row grid and 48dp targets.
private val CliPixelTypography = CliTypography(
    body = TextStyle(
        fontFamily = LanaPixelFamily,
        fontSize = 15.sp,
        lineHeight = 19.sp,
    ),
    small = TextStyle(
        fontFamily = LanaPixelFamily,
        fontSize = 13.sp,
        lineHeight = 16.sp,
    ),
    // Press Start 2P fills the em box: 14sp reads like 17-18sp of an ordinary face.
    title = TextStyle(
        fontFamily = PressStart2PFamily,
        fontSize = 14.sp,
        lineHeight = 21.sp,
    ),
    // Silkscreen is an 8px grid, so keep to multiples: 16sp.
    display = TextStyle(
        fontFamily = SilkscreenFamily,
        fontSize = 16.sp,
        lineHeight = 21.sp,
    ),
    button = TextStyle(
        fontFamily = LanaPixelFamily,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
)

val LocalCliType = staticCompositionLocalOf { CliPixelTypography }

private val CyrillicRange = 'Ѐ'..'ӿ'

/**
 * Display style chosen per title text. Silkscreen is Latin-only and Android does *not* fall
 * back per glyph mid-FontFamily (a style takes the first matching face), so a Cyrillic title
 * moves wholesale to Press Start 2P. 12sp because PS2P fills the em box and reads a step
 * larger than nominal — visually ≈ 15-16sp of Silkscreen.
 */
@Composable
@ReadOnlyComposable
fun cliDisplayStyle(text: String): TextStyle {
    val display = LocalCliType.current.display
    return if (text.any { char -> char in CyrillicRange }) {
        display.copy(fontFamily = PressStart2PFamily, fontSize = 12.sp)
    } else {
        display
    }
}

/**
 * Panel caption span. Cyrillic drops to PS2P at 10sp so the caption does not outgrow the
 * small text beside it.
 */
@Composable
@ReadOnlyComposable
fun cliCaptionSpanStyle(text: String): SpanStyle =
    if (text.any { char -> char in CyrillicRange }) {
        SpanStyle(fontFamily = PressStart2PFamily, fontSize = 10.sp)
    } else {
        SpanStyle(fontFamily = LocalCliType.current.display.fontFamily)
    }

/** Facade over [LocalCliType] so `CliType.body` call sites read ambient typography. */
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

// The palette never varies, so it and the M3 scheme are hoisted to top-level vals rather
// than reallocated on every CliTheme composition.
private val CliCanonColors = CliColors()

// Minimal M3 scheme so reused Material components (UnlockScreen, text fields) stay legible
// on the terminal palette.
private val CliCanonScheme = darkColorScheme(
    primary = CliCanonColors.accent,
    onPrimary = CliCanonColors.onAccent,
    secondary = CliCanonColors.info,
    background = CliCanonColors.bg,
    onBackground = CliCanonColors.fg,
    surface = CliCanonColors.panel,
    onSurface = CliCanonColors.fg,
    surfaceVariant = CliCanonColors.panelAlt,
    onSurfaceVariant = CliCanonColors.dim,
    outline = CliCanonColors.border,
    error = CliCanonColors.err,
)

@Composable
fun CliTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalCliColors provides CliCanonColors,
        LocalCliType provides CliPixelTypography,
    ) {
        MaterialTheme(colorScheme = CliCanonScheme, content = content)
    }
}
