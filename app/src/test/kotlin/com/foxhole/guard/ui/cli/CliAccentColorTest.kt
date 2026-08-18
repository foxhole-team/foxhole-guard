package com.foxhole.guard.ui.cli

import androidx.compose.ui.graphics.Color
import com.foxhole.core.model.AccentColor
import com.foxhole.core.model.PanelAppearance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.pow

class CliAccentColorTest {

    private val palettes = listOf(PanelAppearance.STANDARD, PanelAppearance.LIGHT)
    private val fixedAccents = AccentColor.entries.filterNot { it == AccentColor.AUTO }

    @Test
    fun `orange returns the canonical palettes unchanged`() {
        assertEquals(cliColorsFor(PanelAppearance.STANDARD), cliColorsFor(PanelAppearance.STANDARD, AccentColor.ORANGE))
        assertEquals(cliColorsFor(PanelAppearance.LIGHT), cliColorsFor(PanelAppearance.LIGHT, AccentColor.ORANGE))
    }

    @Test
    fun `accents swap only the accent family`() {
        palettes.forEach { palette ->
            val base = cliColorsFor(palette)
            fixedAccents.filter { it != AccentColor.ORANGE }.forEach { accent ->
                val accented = cliColorsFor(palette, accent)
                assertNotEquals("$palette/$accent accent unchanged", base.accent, accented.accent)
                assertEquals(base.bg, accented.bg)
                assertEquals(base.fg, accented.fg)
                assertEquals(base.vpn, accented.vpn)
                assertEquals(base.tor, accented.tor)
                assertEquals(base.i2p, accented.i2p)
                assertEquals(base.firewall, accented.firewall)
                assertEquals(base.dnsFilter, accented.dnsFilter)
                assertEquals(base.onAccent, accented.onAccent)
            }
        }
    }

    @Test
    fun `every accent hue is distinct within a palette`() {
        palettes.forEach { palette ->
            val hues = fixedAccents.map { accent -> cliColorsFor(palette, accent).accent }
            assertEquals("$palette accents must stay distinct", hues.size, hues.toSet().size)
        }
    }

    @Test
    fun `every accent holds four and a half to one against its background`() {
        palettes.forEach { palette ->
            val bg = cliColorsFor(palette).bg
            AccentColor.entries.forEach { accent ->
                val hue = cliColorsFor(palette, accent).accent
                val ratio = contrast(hue, bg)
                assertTrue("$palette/$accent contrast %.2f below 4.5".format(ratio), ratio >= 4.5)
            }
        }
    }

    @Test
    fun `swatch matches the accent the palette would apply`() {
        palettes.forEach { palette ->
            AccentColor.entries.forEach { accent ->
                assertEquals(cliColorsFor(palette, accent).accent, cliAccentSwatch(palette, accent))
            }
        }
    }

    @Test
    fun `auto accent is delegated to the Android dynamic scheme`() {
        val theme = source("CliTheme.kt")
        assertTrue(theme.contains("dynamicLightColorScheme(context)"))
        assertTrue(theme.contains("dynamicDarkColorScheme(context)"))
        assertTrue(theme.contains("accent == AccentColor.AUTO"))
    }

    @Test
    fun `dns filter is the blue family in both palettes`() {
        assertEquals(CliNoteBlue, cliColorsFor(PanelAppearance.STANDARD).dnsFilter)
        assertEquals(cliColorsFor(PanelAppearance.LIGHT).note, cliColorsFor(PanelAppearance.LIGHT).dnsFilter)
    }

    @Test
    fun `retro enabled controls follow the selected accent instead of legacy green`() {
        val rows = source("components/CliRows.kt")
        val checkGlyph =
            rows.substringAfter("internal fun CliCheckGlyph(")
                .substringBefore("internal val ROW_VALUE")
        val toggleRow =
            rows.substringAfter("internal fun CliToggleRow(")
                .substringBefore("internal fun CliRowInfoGlyph")
        val module = source("settings/CliModuleBlock.kt")

        assertTrue(checkGlyph.contains("color = if (checked) colors.accent else colors.faint"))
        assertTrue(toggleRow.contains("tint = if (checked) colors.accent else colors.dim"))
        assertFalse(checkGlyph.contains("colors.ok"))
        assertFalse(toggleRow.contains("colors.ok"))
        assertTrue(module.contains("val settingsActionColor = colors.accent"))
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

    private fun source(relative: String): String =
        listOf(
            File("src/main/kotlin/com/foxhole/guard/ui/cli/$relative"),
            File("app/src/main/kotlin/com/foxhole/guard/ui/cli/$relative"),
            File("../app/src/main/kotlin/com/foxhole/guard/ui/cli/$relative"),
        ).first(File::isFile).readText()
}
