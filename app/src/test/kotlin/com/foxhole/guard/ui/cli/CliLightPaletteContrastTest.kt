package com.foxhole.guard.ui.cli

import androidx.compose.ui.graphics.Color
import com.foxhole.core.model.PanelAppearance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class CliLightPaletteContrastTest {

    private val colors = cliColorsFor(PanelAppearance.LIGHT)

    @Test
    fun `standard and dark appearances share the canon dark palette`() {
        assertEquals(cliColorsFor(PanelAppearance.STANDARD), cliColorsFor(PanelAppearance.DARK))
        assertTrue(cliColorsFor(PanelAppearance.LIGHT) != cliColorsFor(PanelAppearance.STANDARD))
    }

    @Test
    fun `text tones hold seven to one`() {
        assertContrastAtLeast(7.0, colors.fg, colors.bg, "fg")
        assertContrastAtLeast(7.0, colors.dim, colors.bg, "dim")
    }

    @Test
    fun `faint holds four and a half to one`() {
        assertContrastAtLeast(4.5, colors.faint, colors.bg, "faint")
    }

    @Test
    fun `semantic accents hold four and a half to one`() {
        listOf(
            "accent" to colors.accent,
            "accentBright" to colors.accentBright,
            "ok" to colors.ok,
            "vpn" to colors.vpn,
            "tor" to colors.tor,
            "i2p" to colors.i2p,
            "firewall" to colors.firewall,
            "dnsFilter" to colors.dnsFilter,
            "info" to colors.info,
            "note" to colors.note,
            "warn" to colors.warn,
            "err" to colors.err,
        ).forEach { (name, color) -> assertContrastAtLeast(4.5, color, colors.bg, name) }
    }

    @Test
    fun `borders stay visible`() {
        assertContrastAtLeast(1.5, colors.border, colors.bg, "border")
        assertContrastAtLeast(3.0, colors.borderBright, colors.bg, "borderBright")
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
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
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
