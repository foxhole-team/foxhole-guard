package com.foxhole.guard.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class StatisticsDashboardRetentionTest {
    @Test
    fun `fresh snapshot is reused while the route is hidden`() {
        val built = StatisticsDashboardUiState(ready = true, nowMs = 1_000_000L)
        val retained = retainedStatisticsDashboard(last = built, nowMs = 1_000_000L + 60_000L)
        assertSame(built, retained)
    }

    @Test
    fun `stale snapshot falls back to the preloader state`() {
        val built = StatisticsDashboardUiState(ready = true, nowMs = 1_000_000L)
        val retained =
            retainedStatisticsDashboard(
                last = built,
                nowMs = 1_000_000L + STATISTICS_DASHBOARD_RETAIN_MS + 1L,
            )
        assertFalse(retained.ready)
    }

    @Test
    fun `missing or never-built snapshots stay on the preloader`() {
        assertFalse(retainedStatisticsDashboard(last = null, nowMs = 0L).ready)
        val unready = StatisticsDashboardUiState(ready = false, nowMs = 0L)
        assertFalse(retainedStatisticsDashboard(last = unready, nowMs = 0L).ready)
    }
}
