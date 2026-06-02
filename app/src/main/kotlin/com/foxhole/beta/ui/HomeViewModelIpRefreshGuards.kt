package com.foxhole.beta.ui

import com.foxhole.beta.core.model.ConnectionSnapshot
import com.foxhole.beta.core.model.IpInfo
import com.foxhole.beta.vpn.FoxholeVpnService

internal fun shouldPublishDeviceIpInfoFromDashboardRefresh(target: IpInfoRefreshTarget): Boolean =
    when (target) {
        IpInfoRefreshTarget.UPSTREAM,
        IpInfoRefreshTarget.LOCAL_GUARD,
        -> true
        IpInfoRefreshTarget.TOR,
        IpInfoRefreshTarget.VPN_BOUND,
        IpInfoRefreshTarget.PROXY,
        -> false
    }

internal fun ConnectionSnapshot.shouldRefreshDashboardConnectionMetrics(): Boolean =
    state in HomeViewModel.ACTIVE_CONNECTION_STATES &&
        profileId != FoxholeVpnService.LOCAL_GUARD_PROFILE_ID &&
        profileId != FoxholeVpnService.TOR_ONLY_PROFILE_ID

internal fun ConnectionSnapshot.shouldReportManualDashboardIpRefreshFailures(): Boolean =
    profileId != FoxholeVpnService.LOCAL_GUARD_PROFILE_ID &&
        profileId != FoxholeVpnService.TOR_ONLY_PROFILE_ID

internal fun shouldPublishDashboardIpRefresh(
    startedTarget: IpInfoRefreshTarget,
    currentTarget: IpInfoRefreshTarget,
    reason: IpInfoRefreshReason,
): Boolean =
    reason == IpInfoRefreshReason.TOR_ROUTE ||
        startedTarget == currentTarget

internal fun shouldPublishTorIpInfoForDashboardRefresh(
    target: IpInfoRefreshTarget,
    reason: IpInfoRefreshReason,
): Boolean =
    target == IpInfoRefreshTarget.TOR ||
        reason == IpInfoRefreshReason.TOR_ROUTE

internal fun HomeViewModel.canAcceptTorRouteIpRefresh(info: IpInfo): Boolean {
    val state = controlUiState.value
    val torRouteVisible =
        state.connection.profileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID ||
            state.settings.privacyRoute.enabled
    return torRouteVisible &&
        (!state.torOperation.active || state.torOperation.canAcceptTorIp(info))
}
