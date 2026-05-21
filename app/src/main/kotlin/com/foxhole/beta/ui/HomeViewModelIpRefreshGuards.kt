package com.foxhole.beta.ui

import com.foxhole.beta.core.model.ConnectionSnapshot
import com.foxhole.beta.core.model.IpInfo
import com.foxhole.beta.core.model.TrafficMode
import com.foxhole.beta.vpn.FoxholeVpnService

internal fun ConnectionSnapshot.shouldPublishDeviceIpInfoFromDashboardRefresh(): Boolean =
    state !in HomeViewModel.ACTIVE_CONNECTION_STATES ||
        trafficMode != TrafficMode.TUNNEL ||
        profileId == FoxholeVpnService.LOCAL_GUARD_PROFILE_ID

internal fun shouldPublishDashboardIpRefresh(
    startedTarget: IpInfoRefreshTarget,
    currentTarget: IpInfoRefreshTarget,
    reason: IpInfoRefreshReason,
): Boolean =
    reason == IpInfoRefreshReason.TOR_ROUTE ||
        startedTarget == currentTarget

internal fun HomeViewModel.canAcceptTorRouteIpRefresh(info: IpInfo): Boolean {
    val state = uiState.value
    val torRouteVisible =
        state.connection.profileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID ||
            state.settings.privacyRoute.enabled
    return torRouteVisible &&
        (!state.torOperation.active || state.torOperation.canAcceptTorIp(info))
}
