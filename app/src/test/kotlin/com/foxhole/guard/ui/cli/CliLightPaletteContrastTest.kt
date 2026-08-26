package com.foxhole.guard.ui.cli

import androidx.compose.ui.graphics.Color
import com.foxhole.core.model.AccentColor
import com.foxhole.core.model.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class CliLightPaletteContrastTest {
    private val fixedPalettes = ThemeMode.entries.filterNot { it == ThemeMode.SYSTEM }

    @Test
    fun `fixed styles use the warm dark oled and light reference palettes`() {
        val dark = cliColorsFor(ThemeMode.DARK, AccentColor.ORANGE)
        val oled = cliColorsFor(ThemeMode.OLED, AccentColor.ORANGE)
        val light = cliColorsFor(ThemeMode.LIGHT, AccentColor.ORANGE)

        assertTokens(
            dark,
            bg = 0xFF0B0A08,
            panel = 0xFF15120D,
            panelAlt = 0xFF1F1A12,
            fg = 0xFFF0E6D6,
            dim = 0xFFB49A76,
            faint = 0xFF8B7A5E,
        )
        assertTokens(
            oled,
            bg = 0xFF000000,
            panel = 0xFF000000,
            panelAlt = 0xFF000000,
            fg = 0xFFF0E6D6,
            dim = 0xFFB49A76,
            faint = 0xFF8B7A5E,
        )
        assertTokens(
            light,
            bg = 0xFFF2E8D5,
            panel = 0xFFFFF8EA,
            panelAlt = 0xFFE7D8BE,
            fg = 0xFF2A2118,
            dim = 0xFF665440,
            faint = 0xFF756149,
        )
    }

    @Test
    fun `warm dark palette matches the reference tokens`() {
        assertTokens(
            CliWarmDarkColors,
            bg = 0xFF0B0A08,
            panel = 0xFF15120D,
            panelAlt = 0xFF1F1A12,
            fg = 0xFFF0E6D6,
            dim = 0xFFB49A76,
            faint = 0xFF8B7A5E,
        )
        assertEquals(Color(0xFF7FB34A), CliWarmDarkColors.accent)
        assertEquals(CliWarmDarkColors.status.success, CliWarmDarkColors.accent)
        assertEquals(CliWarmDarkColors.channel.vpn, CliWarmDarkColors.accent)
    }

    @Test
    fun `oled copies warm dark tokens and changes only solid fills to black`() {
        val expected = CliWarmDarkColors.copy(
            bg = Color.Black,
            panel = Color.Black,
            panelAlt = Color.Black,
            map = CliWarmDarkColors.map.copy(
                water = Color.Black,
                land = Color.Black,
            ),
        )

        assertEquals(expected, CliOledDarkColors)
    }

    @Test
    fun `all fixed text roles remain readable`() {
        fixedPalettes.forEach { mode ->
            val colors = cliColorsFor(mode)
            val name = mode.name

            assertContrastAtLeast(12.0, colors.fg, colors.bg, "$name/fg")
            assertContrastAtLeast(5.5, colors.dim, colors.bg, "$name/dim")
            assertContrastAtLeast(4.5, colors.faint, colors.bg, "$name/faint")
        }
    }

    @Test
    fun `status and channel roles hold text contrast on panels`() {
        fixedPalettes.forEach { mode ->
            val colors = cliColorsFor(mode)
            assertEquals("$mode one green", colors.status.success, colors.channel.vpn)
            val roles =
                listOf(
                    "success" to colors.status.success,
                    "information" to colors.status.information,
                    "data" to colors.status.data,
                    "warning" to colors.status.warning,
                    "attention" to colors.status.attention,
                    "error" to colors.status.error,
                    "vpn" to colors.channel.vpn,
                    "tor" to colors.channel.tor,
                    "i2p" to colors.channel.i2p,
                    "firewall" to colors.channel.firewall,
                    "dns" to colors.channel.dns,
                )
            roles.forEach { (role, color) ->
                assertContrastAtLeast(4.5, color, colors.panel, "$mode/$role")
            }
        }
    }

    @Test
    fun `fixed palette borders remain visible without outlining every component`() {
        fixedPalettes.forEach { mode ->
            val colors = cliColorsFor(mode)
            assertContrastAtLeast(1.5, colors.border, colors.bg, "$mode/border")
            assertContrastAtLeast(3.0, colors.borderBright, colors.bg, "$mode/borderBright")
        }
    }

    private fun assertTokens(
        colors: CliColors,
        bg: Long,
        panel: Long,
        panelAlt: Long,
        fg: Long,
        dim: Long,
        faint: Long,
    ) {
        assertEquals(Color(bg), colors.bg)
        assertEquals(Color(panel), colors.panel)
        assertEquals(Color(panelAlt), colors.panelAlt)
        assertEquals(Color(fg), colors.fg)
        assertEquals(Color(dim), colors.dim)
        assertEquals(Color(faint), colors.faint)
    }

    private fun assertContrastAtLeast(expected: Double, a: Color, b: Color, name: String) {
        val actual = contrast(a, b)
        assertTrue(
            "$name contrast %.2f is below $expected".format(actual),
            actual >= expected,
        )
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun luminance(color: Color): Double {
        fun channel(value: Float): Double {
            val v = value.toDouble()
            return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color.red) +
            0.7152 * channel(color.green) +
            0.0722 * channel(color.blue)
    }
}
