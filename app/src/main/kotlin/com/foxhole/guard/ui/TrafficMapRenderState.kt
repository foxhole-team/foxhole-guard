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
        // Only show the loading preloader before there is any map data (cold start). Once the map
        // already has traffic to draw, keep it RENDERED through a refresh so navigating away and
        // back (e.g. Settings -> Dashboard) while connected does not flash the preloader again.
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
