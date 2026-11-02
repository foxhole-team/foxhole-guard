package com.foxhole.guard.ui.cli

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.foxhole.guard.ui.FoxholeBannerTone

internal fun cliNoticeColor(tone: FoxholeBannerTone, colors: CliColors): Color {
    val light = colors.panel.luminance() > 0.5f
    return when (tone) {
        FoxholeBannerTone.SUCCESS -> if (light) Color(0xFF1B5E20) else Color(0xFF81C784)
        FoxholeBannerTone.INFO -> cliNoticeInfoColor(colors)
        FoxholeBannerTone.WARNING -> if (light) Color(0xFF873700) else Color(0xFFFFAD66)
        FoxholeBannerTone.ERROR -> if (light) Color(0xFFA71D18) else Color(0xFFFF6B70)
    }
}

private fun cliNoticeInfoColor(colors: CliColors): Color {
    val help = colors.info
    val background = lerp(colors.panel, help, 0.08f)
    val a = help.luminance()
    val b = background.luminance()
    val contrast = (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)
    return if (contrast >= 4.5f) help else colors.fg
}
