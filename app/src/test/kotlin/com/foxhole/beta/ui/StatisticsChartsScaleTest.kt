package com.foxhole.beta.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class StatisticsChartsScaleTest {
    @Test
    fun `timeline traffic scale uses decimal 100 MB steps`() {
        assertEquals(100_000_000L, niceTrafficScale(1L))
        assertEquals(100_000_000L, niceTrafficScale(80L * 1024L * 1024L))
        assertEquals(200_000_000L, niceTrafficScale(101_000_000L))
        assertEquals(500_000_000L, niceTrafficScale(499_999_999L))
    }

    @Test
    fun `timeline traffic ticks stay on 100 MB multiples`() {
        assertEquals(
            listOf(0L, 100_000_000L),
            timelineTrafficTickValues(100_000_000L),
        )
        assertEquals(
            listOf(0L, 100_000_000L, 200_000_000L, 300_000_000L, 400_000_000L, 500_000_000L),
            timelineTrafficTickValues(500_000_000L),
        )
    }
}
