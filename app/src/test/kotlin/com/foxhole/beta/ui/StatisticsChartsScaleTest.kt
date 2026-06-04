package com.foxhole.beta.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class StatisticsChartsScaleTest {
    @Test
    fun `timeline traffic scale uses adaptive steps below one hundred MB`() {
        assertEquals(100_000L, niceTrafficScale(1L))
        assertEquals(90_000_000L, niceTrafficScale(80L * 1024L * 1024L))
        assertEquals(200_000_000L, niceTrafficScale(101_000_000L))
        assertEquals(500_000_000L, niceTrafficScale(499_999_999L))
    }

    @Test
    fun `timeline traffic ticks adapt below one hundred MB and stay coarse above it`() {
        assertEquals(
            listOf(0L, 10_000_000L, 20_000_000L, 30_000_000L, 40_000_000L, 50_000_000L),
            timelineTrafficTickValues(50_000_000L),
        )
        assertEquals(
            listOf(0L, 100_000_000L, 200_000_000L, 300_000_000L, 400_000_000L, 500_000_000L),
            timelineTrafficTickValues(500_000_000L),
        )
    }
}
