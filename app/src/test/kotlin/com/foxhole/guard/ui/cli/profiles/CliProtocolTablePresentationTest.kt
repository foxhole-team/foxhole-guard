package com.foxhole.guard.ui.cli.profiles

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Контракт таблицы протоколов смарт-профиля: колонка S (приоритет состояний и скобочный алфавит)
 * и формат ячеек T/P/L. Оба маппера чистые, поэтому регресс ловится без Compose-рантайма.
 */
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
