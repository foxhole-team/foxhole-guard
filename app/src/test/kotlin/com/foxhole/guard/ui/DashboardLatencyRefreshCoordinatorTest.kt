package com.foxhole.guard.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardLatencyRefreshCoordinatorTest {
    @Test
    fun `presentation can be shown only by an acquired current lease`() {
        val state = HomeProtocolMetricsState()
        val stale = state.beginDashboardLatencyRefresh()
        val current = state.beginDashboardLatencyRefresh()

        assertFalse(state.acquireDashboardLoading(stale, startedAtMs = 10L))
        assertFalse(state.dashboardConnectionMetricsLoadingMutable.value)
        assertTrue(state.acquireDashboardLoading(current, startedAtMs = 20L))
        assertTrue(state.dashboardConnectionMetricsLoadingMutable.value)
        assertEquals(20L, state.dashboardConnectionMetricsLoadingStartedAtMs)

        assertFalse(state.releaseDashboardLoading(stale))
        assertTrue(state.dashboardConnectionMetricsLoadingMutable.value)
        assertTrue(state.releaseDashboardLoading(current))
        assertFalse(state.dashboardConnectionMetricsLoadingMutable.value)
        assertEquals(0L, state.dashboardConnectionMetricsLoadingStartedAtMs)
    }

    @Test
    fun `only current generation can acquire the loading lease`() {
        val coordinator = DashboardLatencyRefreshCoordinator()
        val stale = coordinator.beginGeneration()
        val current = coordinator.beginGeneration()

        assertFalse(coordinator.acquireLoading(stale))
        assertTrue(coordinator.acquireLoading(current))
        assertTrue(coordinator.isLoadingOwnedBy(current))
    }

    @Test
    fun `stale completion cannot clear the next probe loading lease`() {
        val coordinator = DashboardLatencyRefreshCoordinator()
        val stale = coordinator.beginGeneration()
        assertTrue(coordinator.acquireLoading(stale))

        val current = coordinator.beginGeneration()
        assertTrue(coordinator.acquireLoading(current))

        assertFalse(coordinator.releaseLoading(stale))
        assertTrue(coordinator.isLoadingOwnedBy(current))
        assertTrue(coordinator.releaseLoading(current))
        assertFalse(coordinator.isLoadingOwnedBy(current))
    }

    @Test
    fun `invalidate fences both pending and loading work`() {
        val coordinator = DashboardLatencyRefreshCoordinator()
        val stale = coordinator.beginGeneration()
        assertTrue(coordinator.acquireLoading(stale))

        coordinator.invalidate()

        assertFalse(coordinator.isCurrent(stale))
        assertFalse(coordinator.isLoadingOwnedBy(stale))
        assertFalse(coordinator.releaseLoading(stale))
    }
}
