package com.foxhole.guard.ui.cli

import com.foxhole.core.model.VisualStyle
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.components.cliLinIconRes
import com.foxhole.guard.ui.cli.home.CliLineTone
import com.foxhole.guard.ui.cli.home.CliTerminalLine
import com.foxhole.guard.ui.cli.home.cliLineToneIcon
import com.foxhole.guard.ui.cli.home.cliTerminalLineIcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CliVisualStyleTest {

    private val pixelIcons = listOf(
        R.drawable.pix_add,
        R.drawable.pix_apps,
        R.drawable.pix_arrow_down,
        R.drawable.pix_arrow_right,
        R.drawable.pix_check,
        R.drawable.pix_clock,
        R.drawable.pix_copy,
        R.drawable.pix_cross,
        R.drawable.pix_device,
        R.drawable.pix_dns,
        R.drawable.pix_edit,
        R.drawable.pix_export,
        R.drawable.pix_fire,
        R.drawable.pix_forbidden,
        R.drawable.pix_globe,
        R.drawable.pix_home,
        R.drawable.pix_import,
        R.drawable.pix_incognito,
        R.drawable.pix_info,
        R.drawable.pix_journal,
        R.drawable.pix_link,
        R.drawable.pix_lock,
        R.drawable.pix_map,
        R.drawable.pix_power,
        R.drawable.pix_profiles,
        R.drawable.pix_qr,
        R.drawable.pix_restart,
        R.drawable.pix_settings,
        R.drawable.pix_shield,
        R.drawable.pix_star,
        R.drawable.pix_stats,
        R.drawable.pix_status,
        R.drawable.pix_tor,
        R.drawable.pix_trash,
        R.drawable.pix_up,
        R.drawable.pix_update,
        R.drawable.pix_webapps,
        R.drawable.widget_refresh_pixel,
    )

    @Test
    fun `every pixel glyph has a distinct plain counterpart`() {
        val mapped = pixelIcons.map { id -> cliLinIconRes(id) }
        pixelIcons.zip(mapped).forEach { (pixel, lin) ->
            assertNotEquals("pixel glyph $pixel has no plain counterpart", pixel, lin)
        }
        val pixOnly = pixelIcons - R.drawable.widget_refresh_pixel
        val pixMapped = pixOnly.map { id -> cliLinIconRes(id) }
        assertEquals("plain glyphs must stay distinct", pixMapped.size, pixMapped.toSet().size)
    }

    @Test
    fun `unmapped drawables fall back to themselves`() {
        assertEquals(R.drawable.widget_frame, cliLinIconRes(R.drawable.widget_frame))
    }

    @Test
    fun `plain typography swaps families and takes one uniform size notch`() {
        val pixel = cliTypographyFor(VisualStyle.PIXEL)
        val plain = cliTypographyFor(VisualStyle.PLAIN)
        assertEquals(16.5f, pixel.body.fontSize.value, TYPE_NOTCH_TOLERANCE)
        assertEquals(16.5f * CLI_MODERN_METRIC_SCALE, plain.body.fontSize.value, TYPE_NOTCH_TOLERANCE)
        listOf(
            pixel.body to plain.body,
            pixel.small to plain.small,
            pixel.title to plain.title,
            pixel.display to plain.display,
            pixel.button to plain.button,
        ).forEach { (pixelStyle, plainStyle) ->
            assertNotEquals(pixelStyle.fontFamily, plainStyle.fontFamily)
            assertEquals(
                pixelStyle.fontSize.value * CLI_MODERN_METRIC_SCALE,
                plainStyle.fontSize.value,
                TYPE_NOTCH_TOLERANCE,
            )
            assertEquals(
                pixelStyle.lineHeight.value * CLI_MODERN_METRIC_SCALE,
                plainStyle.lineHeight.value,
                TYPE_NOTCH_TOLERANCE,
            )
        }
    }

    @Test
    fun `every terminal tone carries an event glyph except the accent brand line`() {
        CliLineTone.entries.forEach { tone ->
            val icon = cliLineToneIcon(tone, prompt = false)
            if (tone == CliLineTone.ACCENT) {
                assertNull(icon)
            } else {
                assertTrue("tone $tone has no glyph", icon != null && icon != 0)
            }
        }
    }

    @Test
    fun `prompt lines keep the bare action marker`() {
        assertNull(cliLineToneIcon(CliLineTone.PLAIN, prompt = true))
    }

    @Test
    fun `the welcome version line reuses the quick settings fox`() {
        val line = CliTerminalLine(
            timestampMs = 0L,
            text = "FoxHole Guard · v0.0.2",
            tone = CliLineTone.ACCENT,
        )

        assertEquals(R.drawable.ic_qs_tile, cliTerminalLineIcon(line))
    }

    @Test
    fun `core tones map to their core glyphs`() {
        assertEquals(R.drawable.pix_shield, cliLineToneIcon(CliLineTone.VPN, prompt = false))
        assertEquals(R.drawable.pix_tor, cliLineToneIcon(CliLineTone.TOR, prompt = false))
        assertEquals(R.drawable.pix_incognito, cliLineToneIcon(CliLineTone.I2P, prompt = false))
        assertEquals(R.drawable.pix_fire, cliLineToneIcon(CliLineTone.FIREWALL, prompt = false))
        assertEquals(R.drawable.pix_dns, cliLineToneIcon(CliLineTone.DNS_FILTER, prompt = false))
        assertEquals(R.drawable.pix_check, cliLineToneIcon(CliLineTone.OK, prompt = false))
        assertEquals(R.drawable.pix_cross, cliLineToneIcon(CliLineTone.ERR, prompt = false))
        assertEquals(R.drawable.pix_info, cliLineToneIcon(CliLineTone.INFO, prompt = false))
    }

    private companion object {
        const val TYPE_NOTCH_TOLERANCE = 0.01f
    }
}
