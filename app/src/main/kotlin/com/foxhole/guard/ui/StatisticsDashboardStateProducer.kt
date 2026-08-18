package com.foxhole.guard.ui

import com.foxhole.core.model.effectiveRetention
import com.foxhole.core.model.toStatisticsDisplayRetention

internal fun retainedStatisticsDashboard(
    last: StatisticsDashboardUiState?,
    nowMs: Long,
): StatisticsDashboardUiState =
    last?.takeIf { snapshot -> snapshot.ready && nowMs - snapshot.nowMs <= STATISTICS_DASHBOARD_RETAIN_MS }
        ?: StatisticsDashboardUiState()

internal const val STATISTICS_DASHBOARD_RETAIN_MS = 5L * 60L * 1000L

internal const val STATISTICS_UI_NOW_BUCKET_MS = 60_000L

internal fun Long.toStatisticsUiNowBucket(): Long = (this / STATISTICS_UI_NOW_BUCKET_MS) * STATISTICS_UI_NOW_BUCKET_MS

internal fun buildStatisticsDashboardUiState(
    state: StatisticsRouteUiState,
    liveDestinations: List<com.foxhole.core.model.TrafficMapPoint>,
    nowMs: Long,
    usageAccessGranted: Boolean = true,
): StatisticsDashboardUiState {
    val retention = state.settings.statistics.effectiveRetention().toStatisticsDisplayRetention()
    val statistics =
        statisticsUiState(
            state = state,
            retention = retention,
            usageAccessGranted = usageAccessGranted,
        )
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
            liveDestinations = liveDestinations,
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
