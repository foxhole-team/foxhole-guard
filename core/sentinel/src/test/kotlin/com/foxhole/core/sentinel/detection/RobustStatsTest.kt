package com.foxhole.core.sentinel.detection

import org.junit.Assert.assertEquals
import org.junit.Test

class RobustStatsTest {
    @Test
    fun `median handles empty odd and even inputs`() {
        assertEquals(0.0, RobustStats.median(emptyList()), 1e-9)
        assertEquals(3.0, RobustStats.median(listOf(5.0, 1.0, 3.0)), 1e-9)
        assertEquals(2.5, RobustStats.median(listOf(4.0, 1.0, 2.0, 3.0)), 1e-9)
    }

    @Test
    fun `mad is floored so constant histories cannot explode z`() {
        val constant = List(14) { 100.0 }
        assertEquals(RobustStats.DEFAULT_MAD_FLOOR, RobustStats.mad(constant, 100.0), 1e-9)
    }

    @Test
    fun `mad floor is configurable for small-scale metrics`() {
        val constant = List(14) { 0.5 }
        assertEquals(0.01, RobustStats.mad(constant, 0.5, floor = 0.01), 1e-9)
    }

    @Test
    fun `robust z is zero until the history has enough samples`() {
        val shortHistory = List(RobustStats.MIN_SAMPLES - 1) { 100.0 }
        assertEquals(0.0, RobustStats.robustZ(1_000_000.0, shortHistory), 1e-9)

        val fullHistory = List(RobustStats.MIN_SAMPLES) { 100.0 }
        val z = RobustStats.robustZ(1_000_000.0, fullHistory)
        assertEquals(0.6745 * (1_000_000.0 - 100.0), z, 1e-6)
    }

    @Test
    fun `robust z from persisted stats matches the raw formula`() {
        assertEquals(
            0.6745 * (150.0 - 100.0) / 10.0,
            RobustStats.robustZFromStats(value = 150.0, median = 100.0, mad = 10.0),
            1e-9,
        )
    }
}
