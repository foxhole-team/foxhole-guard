package com.foxhole.guard.ui

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Per-protocol metrics state: latency/ping/down caches keyed by profile option, the smart-profile
 * refresh bookkeeping and the dashboard metrics-loading flag. HomeViewModel exposes same-named
 * aliases so the Support-file call sites read unchanged.
 */
internal class HomeProtocolMetricsState {
    val profileOptionLatenciesMutable = MutableStateFlow<Map<ProfileOptionLatencyKey, Long>>(emptyMap())
    val profileOptionDownMutable = MutableStateFlow<Set<ProfileOptionLatencyKey>>(emptySet())
    val profileOptionLatencyUnavailableMutable = MutableStateFlow<Set<ProfileOptionLatencyKey>>(emptySet())
    val profileOptionServerPingsMutable =
        MutableStateFlow<Map<ProfileOptionLatencyKey, ProfileOptionServerPingState>>(
            emptyMap(),
        )
    val profileOptionTunnelPingsMutable =
        MutableStateFlow<Map<ProfileOptionLatencyKey, ProfileOptionTunnelPingState>>(
            emptyMap(),
        )
    val profileOptionMetricsUpdatedAtMutable = MutableStateFlow<Map<ProfileOptionLatencyKey, Long>>(emptyMap())
    val protocolMetricsRefreshingProfileIdsMutable = MutableStateFlow<Set<Long>>(emptySet())
    val protocolMetricsRefreshingOptionIdByProfileIdMutable = MutableStateFlow<Map<Long, String>>(emptyMap())
    val recommendedProtocolMutable = MutableStateFlow<ProtocolRecommendationState?>(null)
    val dashboardConnectionMetricsLoadingMutable = MutableStateFlow(false)
    var dashboardConnectionMetricsLoadingStartedAtMs = 0L
    private val dashboardLatencyRefreshCoordinator = DashboardLatencyRefreshCoordinator()
    var protocolMetricsRefreshJob: Job? = null
    var protocolMetricsRestoreOnCancel: Boolean = true
    var profileLatencyRefreshJob: Job? = null
    var profileLatencyRefreshGeneration: Long? = null
    var postConnectLatencyRefreshJob: Job? = null
    var postConnectLatencyRefreshGeneration: Long? = null

    fun markRefreshStarted(profileId: Long) {
        protocolMetricsRefreshingProfileIdsMutable.value += profileId
    }

    fun clearRefreshPresentation() {
        protocolMetricsRefreshingProfileIdsMutable.value = emptySet()
        protocolMetricsRefreshingOptionIdByProfileIdMutable.value = emptyMap()
        invalidateDashboardLatencyRefresh()
    }

    fun beginDashboardLatencyRefresh(): Long {
        val generation = dashboardLatencyRefreshCoordinator.beginGeneration()
        clearDashboardLoadingPresentation()
        return generation
    }

    fun isCurrentDashboardLatencyRefresh(generation: Long): Boolean =
        dashboardLatencyRefreshCoordinator.isCurrent(generation)

    fun acquireDashboardLoading(
        generation: Long,
        startedAtMs: Long,
    ): Boolean {
        if (!dashboardLatencyRefreshCoordinator.acquireLoading(generation)) {
            return false
        }
        dashboardConnectionMetricsLoadingMutable.value = true
        dashboardConnectionMetricsLoadingStartedAtMs = startedAtMs
        return true
    }

    fun releaseDashboardLoading(generation: Long): Boolean {
        if (!dashboardLatencyRefreshCoordinator.releaseLoading(generation)) {
            return false
        }
        clearDashboardLoadingPresentation()
        return true
    }

    fun isDashboardLoadingOwnedBy(generation: Long): Boolean =
        dashboardLatencyRefreshCoordinator.isLoadingOwnedBy(generation)

    fun invalidateDashboardLatencyRefresh() {
        dashboardLatencyRefreshCoordinator.invalidate()
        clearDashboardLoadingPresentation()
    }

    private fun clearDashboardLoadingPresentation() {
        dashboardConnectionMetricsLoadingMutable.value = false
        dashboardConnectionMetricsLoadingStartedAtMs = 0L
    }
}

/**
 * Generation fence for one connected-route latency probe.
 *
 * A pending IP lookup and a probe from the previous route may finish after a reconnect or network
 * handover. Only the current generation can acquire/release the dashboard loading presentation, so
 * that stale completion can neither leave a spinner behind nor clear the next real probe's one.
 */
internal class DashboardLatencyRefreshCoordinator {
    private var generation = 0L
    private var loadingGeneration: Long? = null

    fun beginGeneration(): Long {
        generation += 1L
        loadingGeneration = null
        return generation
    }

    fun invalidate(): Long = beginGeneration()

    fun isCurrent(candidate: Long): Boolean = candidate == generation

    fun acquireLoading(candidate: Long): Boolean {
        if (!isCurrent(candidate)) {
            return false
        }
        loadingGeneration = candidate
        return true
    }

    fun releaseLoading(candidate: Long): Boolean {
        if (loadingGeneration != candidate) {
            return false
        }
        loadingGeneration = null
        return true
    }

    fun isLoadingOwnedBy(candidate: Long): Boolean = loadingGeneration == candidate
}
