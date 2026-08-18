package com.foxhole.guard.ui

import com.foxhole.core.model.TrafficMapUiState

internal fun trafficMapVisibleStatusRefreshing(
    statusRefreshing: Boolean,
    waitingForConnection: Boolean,
): Boolean = statusRefreshing && waitingForConnection

internal fun trafficMapDashboardRenderState(
    state: TrafficMapUiState,
    loading: Boolean,
    mapDataAvailable: Boolean = true,
): TrafficMapDashboardRenderState {
    val hasTrafficSummary = state.destinations.isNotEmpty() || state.unknownCountryBytes > 0L
    return when {
        !mapDataAvailable -> TrafficMapDashboardRenderState.ERROR
        !state.isAvailable && !hasTrafficSummary -> TrafficMapDashboardRenderState.DISABLED
        loading && !hasTrafficSummary -> TrafficMapDashboardRenderState.LOADING
        !hasTrafficSummary -> TrafficMapDashboardRenderState.EMPTY
        else -> TrafficMapDashboardRenderState.RENDERED
    }
}

internal fun TrafficMapDashboardRenderState.showsDashboardPreview(): Boolean =
    this == TrafficMapDashboardRenderState.LOADING ||
        this == TrafficMapDashboardRenderState.DISABLED ||
        this == TrafficMapDashboardRenderState.EMPTY ||
        this == TrafficMapDashboardRenderState.RENDERED
