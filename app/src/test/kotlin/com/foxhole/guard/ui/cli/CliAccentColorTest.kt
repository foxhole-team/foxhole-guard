package com.foxhole.guard.ui.cli

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import com.foxhole.core.model.AccentColor
import com.foxhole.core.model.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.pow

class CliAccentColorTest {
    private val fixedPalettes = ThemeMode.entries.filterNot { it == ThemeMode.SYSTEM }
    private val fixedAccents = AccentColor.entries.filterNot { it == AccentColor.AUTO }

    @Test
    fun `automatic fixed accent is the one green family`() {
        fixedPalettes.forEach { mode ->
            assertEquals(
                mode.name,
                cliColorsFor(mode, AccentColor.GREEN),
                cliColorsFor(mode, AccentColor.AUTO),
            )
        }
    }

    @Test
    fun `manual accents swap only the accent family`() {
        fixedPalettes.forEach { mode ->
            val base = cliColorsFor(mode)
            fixedAccents.filterNot { it == AccentColor.GREEN }.forEach { accent ->
                val accented = cliColorsFor(mode, accent)
                assertNotEquals("$mode/$accent accent unchanged", base.accent, accented.accent)
                assertEquals(base.bg, accented.bg)
                assertEquals(base.fg, accented.fg)
                assertEquals(base.status, accented.status)
                assertEquals(base.channel, accented.channel)
                assertEquals(base.map, accented.map)
                assertEquals(base.onAccent, accented.onAccent)
            }
        }
    }

    @Test
    fun `every manual accent is distinct and readable within a fixed palette`() {
        fixedPalettes.forEach { mode ->
            val palette = cliColorsFor(mode)
            val hues = fixedAccents.map { accent -> cliColorsFor(mode, accent).accent }
            assertEquals("$mode accents must stay distinct", hues.size, hues.toSet().size)
            fixedAccents.forEach { accent ->
                val ratio = contrast(cliColorsFor(mode, accent).accent, palette.bg)
                assertTrue(
                    "$mode/$accent contrast %.2f below 4.5".format(ratio),
                    ratio >= 4.5,
                )
            }
        }
    }

    @Test
    fun `system auto keeps exact Material You primary and dynamic surfaces`() {
        val scheme =
            darkColorScheme(
                primary = Color(0xFFABCDEF),
                onPrimary = Color(0xFF102030),
                primaryContainer = Color(0xFF234567),
                background = Color(0xFF010203),
                surface = Color(0xFF040506),
                surfaceVariant = Color(0xFF070809),
                secondary = Color(0xFF91A2B3),
                tertiary = Color(0xFFC4D5E6),
                error = Color(0xFFFEDCBA),
            )

        val colors =
            cliColorsFromDynamicScheme(
                scheme = scheme,
                light = false,
            )

        assertEquals(scheme.background, colors.bg)
        assertEquals(scheme.surface, colors.panel)
        assertEquals(scheme.surfaceVariant, colors.panelAlt)
        assertEquals(scheme.primary, colors.accent)
        assertEquals(scheme.onPrimary, colors.onAccent)
        assertEquals(scheme.error, colors.status.error)
        assertEquals(CliWarmDarkColors.status.success, colors.status.success)
        assertEquals(CliWarmDarkColors.channel.vpn, colors.channel.vpn)
    }

    @Test
    fun `system manual accent cannot replace Material You primary`() {
        val scheme =
            darkColorScheme(
                primary = Color(0xFFABCDEF),
                background = Color(0xFF010203),
                surface = Color(0xFF040506),
            )
        val colors =
            cliColorsFromDynamicScheme(
                scheme = scheme,
                light = false,
            )

        assertEquals(scheme.background, colors.bg)
        assertEquals(scheme.surface, colors.panel)
        assertEquals(scheme.primary, colors.accent)
    }

    @Test
    fun `swatch matches the fixed accent the selected style would apply`() {
        fixedPalettes.forEach { mode ->
            fixedAccents.forEach { accent ->
                assertEquals(
                    cliColorsFor(mode, accent).accent,
                    cliAccentSwatch(mode, accent),
                )
            }
        }
    }

    @Test
    fun `dns and data use one blue semantic family in fixed palettes`() {
        fixedPalettes.forEach { mode ->
            val colors = cliColorsFor(mode)
            assertEquals(mode.name, colors.status.data, colors.channel.dns)
        }
    }

    @Test
    fun `enabled controls follow the selected accent instead of status success`() {
        val rows = source("components/CliRows.kt")
        val checkGlyph =
            rows.substringAfter("internal fun CliCheckGlyph(")
                .substringBefore("internal val ROW_VALUE")
        val toggleRow =
            rows.substringAfter("internal fun CliToggleRow(")
                .substringBefore("internal fun CliRowInfoGlyph")
        val module = source("settings/CliModuleBlock.kt")

        assertTrue(checkGlyph.contains("checkedTrackColor = colors.accent"))
        assertTrue(checkGlyph.contains("checkedBorderColor = colors.accent"))
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
