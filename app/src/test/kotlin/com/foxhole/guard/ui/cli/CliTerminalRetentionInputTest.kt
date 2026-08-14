package com.foxhole.guard.ui.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * «Своё значение» ретенции терминала: вводится в часах или в днях, и ни ноль, ни пустое поле, ни
 * огромное число не должны дать значение, с которым журнал ведёт себя странно. Ноль опаснее всего:
 * из ретенции считается граница отсечения, и нулевой срок стирал бы строку в момент записи.
 */
class CliTerminalRetentionInputTest {

    @Test
    fun `zero and empty entries are refused, not silently rounded up`() {
        listOf("", "   ", "0", "00", "abc", "-5").forEach { raw ->
            CliRetentionUnit.entries.forEach { unit ->
                assertNull(
                    "entry '$raw' in $unit must not be accepted",
                    CliTerminalPrefs.hoursFromInput(raw, unit),
                )
            }
        }
    }

    @Test
    fun `days are stored as hours`() {
        assertEquals(24, CliTerminalPrefs.hoursFromInput("1", CliRetentionUnit.DAYS))
        assertEquals(72, CliTerminalPrefs.hoursFromInput("3", CliRetentionUnit.DAYS))
        assertEquals(7, CliTerminalPrefs.hoursFromInput("7", CliRetentionUnit.HOURS))
    }

    @Test
    fun `an entry above the ceiling clamps instead of overflowing`() {
        // 999 дней — самое большое, что помещается в поле; в часах это далеко за Int-границу
        // ретенции, и клампиться должно к потолку, а не переполняться в отрицательное.
        assertEquals(CliTerminalPrefs.MAX_HOURS, CliTerminalPrefs.hoursFromInput("999", CliRetentionUnit.DAYS))
        assertEquals(CliTerminalPrefs.MAX_HOURS, CliTerminalPrefs.hoursFromInput("999", CliRetentionUnit.HOURS))
        assertEquals(CliTerminalPrefs.MAX_HOURS, CliTerminalPrefs.hoursFromInput("999999999999", CliRetentionUnit.DAYS))
    }

    @Test
    fun `normalization is the single guard on a stored value`() {
        assertEquals(CliTerminalPrefs.MIN_HOURS, CliTerminalPrefs.normalizedHours(0))
        assertEquals(CliTerminalPrefs.MIN_HOURS, CliTerminalPrefs.normalizedHours(-100))
        assertEquals(CliTerminalPrefs.MAX_HOURS, CliTerminalPrefs.normalizedHours(Int.MAX_VALUE))
        assertEquals(12, CliTerminalPrefs.normalizedHours(12))
    }

    @Test
    fun `whole days read as days and everything else as hours`() {
        assertEquals("6h", CliTerminalPrefs.retentionLabel(6))
        assertEquals("1d", CliTerminalPrefs.retentionLabel(24))
        assertEquals("2d", CliTerminalPrefs.retentionLabel(48))
        assertEquals("30d", CliTerminalPrefs.retentionLabel(720))
        assertEquals("36h", CliTerminalPrefs.retentionLabel(36))
        assertEquals("1h", CliTerminalPrefs.retentionLabel(0))
    }
}
