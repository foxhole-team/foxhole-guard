package com.foxhole.beta.ui

internal fun buildStatisticsDashboardUiState(
    state: SettingsRouteUiState,
    trafficMapState: com.foxhole.beta.core.model.TrafficMapUiState,
): StatisticsDashboardUiState {
    val retention = state.settings.statistics.retention
    val nowMs = System.currentTimeMillis()
    val statistics = statisticsUiState(state = state, retention = retention)
    val appRows =
        appTrafficRows(
            samples = state.appTrafficWindows,
            installedApps = state.installedApps,
            anomalyEvents = state.anomalyEvents,
            retention = retention,
            nowMs = nowMs,
        )
    val countryRows =
        countryTrafficRows(
            trafficWindows = state.trafficWindows,
            liveDestinations = trafficMapState.destinations,
        )
    val dnsSummary =
        dnsProtectionSummary(
            trafficWindows = state.trafficWindows,
            appRows = appRows,
            retention = retention,
            dnsSettings = state.settings.dns,
            nowMs = nowMs,
        )
    val appChanges =
        installedAppChangesForRetention(
            changes = state.settings.installedAppInventoryAudit.recentChanges,
            retention = retention,
            nowMs = nowMs,
        )
    return StatisticsDashboardUiState(
        nowMs = nowMs,
        statistics = statistics,
        appRows = appRows,
        countryRows = countryRows,
        dnsSummary = dnsSummary,
        appChanges = appChanges,
    )
}
