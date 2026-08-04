package com.foxhole.guard.ui

import android.os.SystemClock
import com.foxhole.core.model.IpInfo

internal fun HomeViewModel.scheduleForegroundDashboardRefreshIfStale() {
    val now = SystemClock.elapsedRealtime()
    if (now - lastForegroundDashboardRefreshElapsedMs < HomeViewModel.FOREGROUND_DASHBOARD_REFRESH_MIN_INTERVAL_MS) {
        container.diagnosticsLogger.record("ip", "foreground dashboard refresh skipped: throttled")
        if (
            profileLatencyRefreshJob == null &&
            container.connectionController.snapshot.value.shouldRefreshDashboardConnectionMetrics()
        ) {
            scheduleActiveProfileLatencyRefresh(showLoading = false, refreshImmediately = true)
        }
        return
    }
    lastForegroundDashboardRefreshElapsedMs = now
    scheduleConnectedIpRefresh(reason = IpInfoRefreshReason.FOREGROUND, clearExistingIp = false)
    if (container.connectionController.snapshot.value.shouldRefreshDashboardConnectionMetrics()) {
        scheduleActiveProfileLatencyRefresh(showLoading = false, refreshImmediately = true)
    }
}

internal fun HomeTorOperationUiState.canAcceptTorIp(ipInfo: IpInfo): Boolean {
    val readyToPublish = !active || ipInfo.fetchedAt >= startedAt
    val currentIpAddress = primaryVisibleIp(ipInfo)
    return readyToPublish && (startedIpAddress == null || currentIpAddress != startedIpAddress)
}

internal suspend fun HomeViewModel.publishTorIpInfoFromDashboardRefresh(info: IpInfo): Boolean {
    val published = publishTorRouteExit(info)
    // Same claim discipline as maybeFinishTorOperation: check the source-of-truth mutable
    // (the derived UI state lags a frame) and clear before the suspending emit, so the racing
    // completion path can never also emit — one Tor start produces exactly one connected banner.
    if (published && torOperationMutable.value.active) {
        clearTorOperation()
        emitTorConnectedBanner(info)
    }
    return published
}
