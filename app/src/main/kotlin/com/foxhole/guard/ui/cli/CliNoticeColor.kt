package com.foxhole.guard.ui.cli

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.foxhole.guard.ui.FoxholeBannerTone

internal fun cliNoticeColor(tone: FoxholeBannerTone, colors: CliColors): Color {
    val light = colors.panel.luminance() > 0.5f
    return when (tone) {
        FoxholeBannerTone.SUCCESS -> if (light) Color(0xFF1B5E20) else Color(0xFF81C784)
        FoxholeBannerTone.INFO -> if (light) Color(0xFF154E8C) else CliDataBlue
        FoxholeBannerTone.WARNING -> if (light) Color(0xFF873700) else Color(0xFFFFAD66)
        FoxholeBannerTone.ERROR -> if (light) Color(0xFFA71D18) else Color(0xFFFF6B70)
    }
}
