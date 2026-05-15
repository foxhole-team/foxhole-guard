package com.foxhole.beta.core.statistics

import org.junit.Assert.assertEquals
import org.junit.Test

class StatisticsScaleTest {
    @Test
    fun `traffic scale uses kb tiers for small traffic`() {
        assertEquals(1_024L, niceBytesScale(300L))
        assertEquals(10L * 1_024L, niceBytesScale(9_000L))
        assertEquals(100L * 1_024L, niceBytesScale(80_000L))
    }

    @Test
    fun `traffic scale uses nearest configured tier`() {
        assertEquals(1024L * 1024L, niceBytesScale(500_000L))
        assertEquals(10L * 1024L * 1024L, niceBytesScale(2L * 1024L * 1024L))
        assertEquals(1024L * 1024L * 1024L, niceBytesScale(900L * 1024L * 1024L))
    }
}
