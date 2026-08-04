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
    var protocolMetricsRefreshJob: Job? = null
    var protocolMetricsRestoreOnCancel: Boolean = true
    var profileLatencyRefreshJob: Job? = null
    var postConnectLatencyRefreshJob: Job? = null
}
