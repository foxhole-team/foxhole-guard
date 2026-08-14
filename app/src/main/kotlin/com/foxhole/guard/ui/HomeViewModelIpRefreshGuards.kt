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

/**
 * With Tor riding inside the tunnel, every tunnel-bound probe egresses through Tor, so a VPN_BOUND
 * result is the *Tor exit*, not the VPN identity. The dashboard network card keeps VPN display
 * priority: such results go to the dedicated Tor channel and must never overwrite the VPN IP.
 * Mirrors the runtime-side hold (shouldPublishRuntimeProxyIpInfoToDashboard) for viewmodel-driven
 * refreshes (foreground/manual/geo-enrichment), which previously leaked the Tor exit into the card.
 */
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

/**
 * Single owner for the Tor route exit shown on the dashboard and map. Every writer (the runtime
 * bridge channel, the TOR_ROUTE refresh, the Tor-operation completion) goes through here so the same
 * guards apply everywhere: Tor must be visible, the exit must differ from the current VPN exit (so a
 * VPN-bound probe is never published as the Tor exit), and an active Tor operation must accept it.
 * The resolved country/city is retained across same-IP updates so a quick country-less probe cannot
 * wipe the geo the map needs to plot the Tor node.
 */
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
