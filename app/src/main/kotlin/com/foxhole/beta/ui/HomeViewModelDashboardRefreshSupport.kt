package com.foxhole.beta.ui

import android.os.SystemClock
import com.foxhole.beta.core.model.IpInfo
import com.foxhole.beta.vpn.FoxholeVpnService

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
    val state = controlUiState.value
    val torRouteVisible =
        state.connection.profileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID ||
            state.settings.privacyRoute.enabled
    val accepted =
        torRouteVisible &&
            (!state.torOperation.active || state.torOperation.canAcceptTorIp(info))
    if (accepted) {
        torIpInfoMutable.value = info
        if (state.torOperation.active) {
            emitTorConnectedBanner(info)
            clearTorOperation()
        }
    }
    return accepted
}
