package com.foxhole.guard.ui.cli

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

val LocalCliMonochrome = staticCompositionLocalOf { false }

internal fun cliMonochromeColors(light: Boolean): CliColors {
    val foreground = if (light) Color(0xFF303842) else Color.White
    val secondary = if (light) Color(0xFF626B71) else Color(0xFFB5B5B5)
    val muted = if (light) Color(0xFF495057) else Color(0xFF777777)
    val background = if (light) Color(0xFFCBCACA) else Color.Black
    val panel = if (light) Color(0xFFDAD9D8) else Color(0xFF0A0A0A)
    return CliColors(
        bg = background,
        panel = panel,
        panelAlt = if (light) Color(0xFFC4C3C2) else Color(0xFF1C1C1C),
        border = if (light) Color(0xFF626B71) else Color(0xFF242424),
        borderBright = if (light) muted else Color(0xFF555555),
        fg = foreground,
        dim = muted,
        faint = muted,
        accent = foreground,
        accentBright = foreground,
        accentDim = secondary,
        onAccent = if (light) Color(0xFFEEECEA) else Color.Black,
        status = CliStatusColors(foreground, secondary, secondary, secondary, foreground, foreground),
        channel = CliChannelColors(foreground, secondary, muted, foreground, secondary),
        map = CliMapColors(background, panel, secondary, foreground, muted, foreground),
    )
}
