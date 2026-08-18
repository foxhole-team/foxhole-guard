package com.foxhole.guard.ui
import com.foxhole.core.model.ACTIVE_CONNECTION_STATES
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.IpInfo
import com.foxhole.core.model.retainKnownDetailsFrom
import com.foxhole.core.runtime.shouldPublishRuntimeProxyIpInfoToDashboard
import com.foxhole.guard.runtime.FoxholeVpnService

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
    state in ACTIVE_CONNECTION_STATES &&
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
        startedTarget == currentTarget ||
        (
            reason == IpInfoRefreshReason.FOREGROUND &&
                startedTarget.isPhysicalDeviceIdentityTarget() &&
                currentTarget.isPhysicalDeviceIdentityTarget()
            )

private fun IpInfoRefreshTarget.isPhysicalDeviceIdentityTarget(): Boolean =
    this == IpInfoRefreshTarget.UPSTREAM || this == IpInfoRefreshTarget.LOCAL_GUARD

internal fun shouldPublishTorIpInfoForDashboardRefresh(
    target: IpInfoRefreshTarget,
    reason: IpInfoRefreshReason,
): Boolean =
    target == IpInfoRefreshTarget.TOR ||
        reason == IpInfoRefreshReason.TOR_ROUTE

internal fun HomeViewModel.torRouteOwnsTunnelEgress(target: IpInfoRefreshTarget): Boolean {
    if (target != IpInfoRefreshTarget.VPN_BOUND) {
        return false
    }
    val snapshot = container.connectionController.snapshot.value
    return snapshot.torActive &&
        !container.settingsRepository.settings.value.shouldPublishRuntimeProxyIpInfoToDashboard(snapshot)
}

internal fun HomeViewModel.canAcceptTorRouteIpRefresh(info: IpInfo): Boolean {
    val state = controlUiState.value
    val torRouteVisible =
        state.connection.profileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID ||
            state.settings.privacyRoute.enabled
    return shouldAcceptTorRouteIpRefresh(
        info = info,
        currentNonTorIpInfo = container.connectionController.ipInfo.value,
        torRouteVisible = torRouteVisible,
        torOperation = state.torOperation,
    )
}

internal fun shouldAcceptTorRouteIpRefresh(
    info: IpInfo,
    currentNonTorIpInfo: IpInfo?,
    torRouteVisible: Boolean,
    torOperation: HomeTorOperationUiState,
): Boolean =
    torRouteVisible &&
        !info.matchesVisibleIpAddress(currentNonTorIpInfo) &&
        (!torOperation.active || torOperation.canAcceptTorIp(info))

internal fun HomeViewModel.publishTorRouteExit(candidate: IpInfo): Boolean {
    val probePhase = torIdentityProbeMutable.state.value.phase
    if (probePhase == TorIdentityProbePhase.FAILED || probePhase == TorIdentityProbePhase.CANCELLED) {
        return false
    }
    if (!canAcceptTorRouteIpRefresh(candidate)) {
        return false
    }
    torIpInfoMutable.value = candidate.retainKnownDetailsFrom(torIpInfoMutable.value)
    candidate.confirmedTorIdentityOrNull()?.let { confirmTorIdentityProbe() }
    return true
}

private fun IpInfo.matchesVisibleIpAddress(other: IpInfo?): Boolean {
    val currentIp = primaryVisibleIpOrNull(this)
    val otherIp = other?.let(::primaryVisibleIpOrNull)
    return currentIp != null && otherIp != null && currentIp == otherIp
}
