package com.foxhole.guard.ui.cli

import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.foxhole.core.model.ThemeMode
import com.foxhole.guard.ui.FoxholeBannerTone
import org.junit.Assert.assertTrue
import org.junit.Test

class CliNoticeColorTest {
    @Test
    fun `notice text stays readable in color and monochrome themes`() {
        listOf(ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.OLED).forEach { theme ->
            listOf(false, true).forEach { monochrome ->
                val colors = cliColorsFor(theme, monochromeEnabled = monochrome)
                FoxholeBannerTone.entries.forEach { tone ->
                    val text = cliNoticeColor(tone, colors)
                    val background = lerp(colors.panel, text, 0.08f)
                    val a = text.luminance()
                    val b = background.luminance()
                    val contrast = (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)
                    assertTrue("$theme/$monochrome/$tone contrast=$contrast", contrast >= 4.5f)
                }
            }
        }
    }

    @Test
    fun `monochrome notices keep green blue orange and red semantic colors`() {
        listOf(ThemeMode.LIGHT, ThemeMode.DARK).forEach { theme ->
            val colors = cliColorsFor(theme, monochromeEnabled = true)
            val success = cliNoticeColor(FoxholeBannerTone.SUCCESS, colors)
            val info = cliNoticeColor(FoxholeBannerTone.INFO, colors)
            val warning = cliNoticeColor(FoxholeBannerTone.WARNING, colors)
            val error = cliNoticeColor(FoxholeBannerTone.ERROR, colors)
            assertTrue(success.green > success.red && success.green > success.blue)
            assertTrue(info.blue > info.red && info.blue > info.green)
            assertTrue(warning.red > warning.green && warning.green > warning.blue)
            assertTrue(error.red > error.green && error.red > error.blue)
        }
    }
}
