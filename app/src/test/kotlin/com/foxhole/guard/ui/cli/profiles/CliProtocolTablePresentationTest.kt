package com.foxhole.guard.ui.cli.profiles

import com.foxhole.guard.ui.cli.CliColors
import com.foxhole.guard.ui.cli.components.CLI_CONNECT_NORMAL_MAX_MS
import com.foxhole.guard.ui.cli.components.CliLatencyKind
import com.foxhole.guard.ui.cli.components.CliLatencyTone
import com.foxhole.guard.ui.cli.components.cliLatencyTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CliProtocolTablePresentationTest {

    @Test
    fun `testing wins over every other state`() {
        val status = status(testing = true, down = true, enabled = false, active = true, measured = false)

        assertEquals(CliProtocolStatus.TESTING, status)
    }

    @Test
    fun `down wins over disabled and active`() {
        val status = status(down = true, enabled = false, active = true, measured = false)

        assertEquals(CliProtocolStatus.DOWN, status)
    }

    @Test
    fun `disabled wins over active`() {
        val status = status(enabled = false, active = true)

        assertEquals(CliProtocolStatus.OFF, status)
    }

    @Test
    fun `active protocol reads as active even before it was ever measured`() {
        val status = status(active = true, measured = false)

        assertEquals(CliProtocolStatus.ACTIVE, status)
    }

    @Test
    fun `enabled protocol without a measurement is untested`() {
        val status = status(measured = false)

        assertEquals(CliProtocolStatus.UNTESTED, status)
    }

    @Test
    fun `enabled measured protocol is ready`() {
        val status = status()

        assertEquals(CliProtocolStatus.READY, status)
    }

    @Test
    fun `status glyphs stay in the bracket alphabet of the rest of the ui`() {
        assertEquals("[~]", CliProtocolStatus.TESTING.glyph)
        assertEquals("[!]", CliProtocolStatus.DOWN.glyph)
        assertEquals("[ ]", CliProtocolStatus.OFF.glyph)
        assertEquals("[x]", CliProtocolStatus.ACTIVE.glyph)
        assertEquals("[?]", CliProtocolStatus.UNTESTED.glyph)
        assertEquals("[x]", CliProtocolStatus.READY.glyph)
    }

    @Test
    fun `every status glyph is three characters wide`() {
        CliProtocolStatus.entries.forEach { status ->
            assertEquals(status.name, 3, status.glyph.length)
        }
    }

    @Test
    fun `metric cell prints milliseconds when the value is known`() {
        assertEquals("1234", cliProtocolMetricCell(CliProtocolStatus.READY, 1234L))
        assertEquals("0", cliProtocolMetricCell(CliProtocolStatus.ACTIVE, 0L))
    }

    @Test
    fun `metric cell falls back to a dash without a value`() {
        assertEquals("—", cliProtocolMetricCell(CliProtocolStatus.READY, null))
        assertEquals("—", cliProtocolMetricCell(CliProtocolStatus.UNTESTED, null))
        assertEquals("—", cliProtocolMetricCell(CliProtocolStatus.OFF, null))
    }

    @Test
    fun `testing and down override any remembered value in the metric cells`() {
        assertEquals("…", cliProtocolMetricCell(CliProtocolStatus.TESTING, 42L))
        assertEquals("—", cliProtocolMetricCell(CliProtocolStatus.DOWN, 42L))
    }

    @Test
    fun `two seconds is green only for the T connect column`() {
        assertEquals(
            CliLatencyTone.NORMAL,
            cliLatencyTone(CLI_CONNECT_NORMAL_MAX_MS, CliLatencyKind.CONNECT),
        )
        assertEquals(
            CliLatencyTone.DEGRADED,
            cliLatencyTone(CLI_CONNECT_NORMAL_MAX_MS + 1L, CliLatencyKind.CONNECT),
        )
        assertEquals(CliLatencyTone.POOR, cliLatencyTone(2_000L, CliLatencyKind.PROTOCOL))
        assertEquals(CliLatencyTone.POOR, cliLatencyTone(2_000L, CliLatencyKind.HOME))
        assertEquals(CliLatencyTone.UNAVAILABLE, cliLatencyTone(null, CliLatencyKind.CONNECT))
        assertEquals(CliLatencyTone.UNAVAILABLE, cliLatencyTone(-1L, CliLatencyKind.CONNECT))
    }

    @Test
    fun `protocol metric colors use status semantics and preserve state overrides`() {
        val colors = CliColors()

        assertEquals(
            colors.err,
            protocolMetricColor(
                CLI_CONNECT_NORMAL_MAX_MS,
                CliProtocolStatus.DOWN,
                CliLatencyKind.CONNECT,
                colors,
            ),
        )
        assertEquals(
            colors.warn,
            protocolMetricColor(
                CLI_CONNECT_NORMAL_MAX_MS,
                CliProtocolStatus.TESTING,
                CliLatencyKind.CONNECT,
                colors,
            ),
        )
        assertEquals(
            colors.ok,
            protocolMetricColor(
                CLI_CONNECT_NORMAL_MAX_MS,
                CliProtocolStatus.READY,
                CliLatencyKind.CONNECT,
                colors,
            ),
        )
        assertEquals(
            colors.err,
            protocolMetricColor(2_000L, CliProtocolStatus.READY, CliLatencyKind.PROTOCOL, colors),
        )
        val elevated =
            protocolMetricColor(301L, CliProtocolStatus.READY, CliLatencyKind.PROTOCOL, colors)
        assertEquals(colors.alert, elevated)
        assertNotEquals(colors.tor, elevated)
    }

    private fun status(
        testing: Boolean = false,
        down: Boolean = false,
        enabled: Boolean = true,
        active: Boolean = false,
        measured: Boolean = true,
    ): CliProtocolStatus = cliProtocolStatus(
        testing = testing,
        down = down,
        enabled = enabled,
        active = active,
        measured = measured,
    )
}

class CliProtocolColumnWidthTest {

    @Test
    fun `short values stretch to the cap when the even share exceeds it`() {
        val width = cliProtocolMetricColumnWidthPx(
            measuredMaxPx = 40,
            hostWidthPx = 720,
            statusPx = 96,
            spacingPx = 8,
        )

        assertEquals(108, width)
    }

    @Test
    fun `even share below the cap is used as is`() {
        val width = cliProtocolMetricColumnWidthPx(
            measuredMaxPx = 40,
            hostWidthPx = 1000,
            statusPx = 300,
            spacingPx = 25,
        )

        assertEquals(83, width)
    }

    @Test
    fun `column never shrinks below the measured content`() {
        val width = cliProtocolMetricColumnWidthPx(
            measuredMaxPx = 90,
            hostWidthPx = 1000,
            statusPx = 300,
            spacingPx = 25,
        )

        assertEquals(90, width)
    }

    @Test
    fun `content wider than the cap is truncated to the cap`() {
        val width = cliProtocolMetricColumnWidthPx(
            measuredMaxPx = 400,
            hostWidthPx = 720,
            statusPx = 96,
            spacingPx = 8,
        )

        assertEquals(108, width)
    }

    @Test
    fun `three columns plus status always leave the name its reserve`() {
        val hostPx = 720
        val statusPx = 96
        val spacingPx = 8

        val width = cliProtocolMetricColumnWidthPx(
            measuredMaxPx = 40,
            hostWidthPx = hostPx,
            statusPx = statusPx,
            spacingPx = spacingPx,
        )

        val nameLeftover = hostPx - 3 * width - statusPx - 4 * spacingPx
        assertEquals(true, nameLeftover >= (hostPx * 0.35f).toInt())
    }
}
