package com.foxhole.guard.ui
import com.foxhole.core.model.ACTIVE_CONNECTION_STATES
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.IpInfo
import com.foxhole.core.model.Profile
import com.foxhole.core.model.ProfileTrafficTotal
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.model.TrafficSnapshot
import com.foxhole.core.profile.MultiProtocolProfileSupport
import com.foxhole.guard.R
import com.foxhole.guard.runtime.FoxholeVpnService
import java.net.Inet6Address
import java.net.InetAddress

internal fun HomeRouteUiState.homeRouteTransitionRunning(): Boolean =
    reconnectInProgress ||
        connection.state in setOf(ConnectionState.CONNECTING, ConnectionState.RECONNECTING) ||
        (autoConnect.running && connection.state !in ACTIVE_CONNECTION_STATES)

internal fun HomeRouteUiState.homeAnalysisOnlyRunning(): Boolean =
    protocolMetricsRefreshing &&
        connection.state in ACTIVE_CONNECTION_STATES &&
        !reconnectInProgress &&
        !autoConnect.running

internal fun HomeRouteUiState.shouldShowHomeNetworkIpInfoLoading(
    dashboardIpInfo: IpInfo?,
    routeTransitionRunning: Boolean,
    deviceInternetAvailable: Boolean?,
    explicitIpInfoSkeletonLoading: Boolean,
): Boolean {
    if (vpnIdentityUnobservableUnderTor(dashboardIpInfo) && ipInfoRefreshReason != IpInfoRefreshReason.MANUAL) {
        return false
    }
    val manualRefreshNeedsSkeleton =
        explicitIpInfoSkeletonLoading && ipInfoRefreshReason == IpInfoRefreshReason.MANUAL
    val missingIpNeedsSkeleton =
        dashboardIpInfo == null &&
            missingIpCanShowSkeleton(
                dashboardIpInfo = dashboardIpInfo,
                routeTransitionRunning = routeTransitionRunning,
                deviceInternetAvailable = deviceInternetAvailable,
                explicitIpInfoSkeletonLoading = explicitIpInfoSkeletonLoading,
            )
    return manualRefreshNeedsSkeleton || missingIpNeedsSkeleton
}

private fun HomeRouteUiState.vpnIdentityUnobservableUnderTor(dashboardIpInfo: IpInfo?): Boolean =
    dashboardIpInfo == null &&
        connection.torActive &&
        connection.state == ConnectionState.CONNECTED &&
        hasDashboardRouteProfile()

private fun HomeRouteUiState.missingIpCanShowSkeleton(
    dashboardIpInfo: IpInfo?,
    routeTransitionRunning: Boolean,
    deviceInternetAvailable: Boolean?,
    explicitIpInfoSkeletonLoading: Boolean,
): Boolean {
    val missingIpRouteTransitionLoading =
        routeTransitionRunning &&
            hasDashboardRouteProfile()
    val missingIpAnalysisLoading =
        routeTransitionRunning &&
            autoConnect.running &&
            connection.state !in ACTIVE_CONNECTION_STATES
    val missingTorRouteTransitionLoading =
        dashboardIpInfo == null &&
            settings.privacyRoute.enabled &&
            !hasDashboardRouteProfile() &&
            (
                torOperation.active ||
                    (routeTransitionRunning && connection.profileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID)
                )
    return missingIpRouteTransitionLoading ||
        missingIpAnalysisLoading ||
        missingTorRouteTransitionLoading ||
        shouldShowDashboardNetworkLoading(
            visibleIpInfo = dashboardIpInfo,
            explicitLoading = explicitIpInfoSkeletonLoading,
            autoConnectRunning = autoConnect.running,
            deviceInternetAvailable = deviceInternetAvailable,
        )
}

internal fun HomeRouteUiState.shouldShowExplicitIpInfoSkeletonLoading(dashboardIpInfo: IpInfo?): Boolean =
    ipInfoLoading &&
        !(hasStoppedDashboardRouteRuntime() && ipInfoRefreshReason != IpInfoRefreshReason.MANUAL) &&
        !(hasStoppedUnknownDashboardRouteRuntime() && ipInfoRefreshReason != IpInfoRefreshReason.MANUAL) &&
        !(hasStoppedTorOnlyRuntime() && ipInfoRefreshReason != IpInfoRefreshReason.MANUAL) &&
        (
            dashboardIpInfo == null ||
                ipInfoRefreshReason == null ||
                ipInfoRefreshReason == IpInfoRefreshReason.MANUAL
            )

internal fun HomeRouteUiState.shouldShowHomeNetworkConnectionDetailsLoading(
    showConnectionStatus: Boolean,
    routeTransitionRunning: Boolean,
    explicitIpInfoLoading: Boolean,
    connectionMetricsLoading: Boolean,
): Boolean =
    showConnectionStatus &&
        (
            reconnectInProgress ||
                shouldShowVpnTransitionLoading(routeTransitionRunning) ||
                routeTransitionRunning ||
                (connectionMetricsLoading && connection.state != ConnectionState.CONNECTED) ||
                explicitIpInfoLoading
            )

internal fun HomeRouteUiState.shouldShowStartupHomeNetworkGeoRowsLoading(dashboardIpInfo: IpInfo?): Boolean =
    (
        ipInfoLoading ||
            !profilesLoaded ||
            ipInfoRefreshReason == IpInfoRefreshReason.POST_UPDATE
        ) &&
        dashboardIpInfo != null &&
        shouldShowIpInfoGeoEnrichmentLoading(dashboardIpInfo)

private fun HomeRouteUiState.shouldShowVpnTransitionLoading(routeTransitionRunning: Boolean): Boolean =
    routeTransitionRunning &&
        connection.state in setOf(ConnectionState.CONNECTING, ConnectionState.RECONNECTING) &&
        hasDashboardRouteProfile()

private fun HomeRouteUiState.hasDashboardRouteProfile(): Boolean =
    connection.profileId != null &&
        connection.trafficMode in setOf(TrafficMode.TUNNEL, TrafficMode.PROXY) &&
        connection.profileId != FoxholeVpnService.LOCAL_GUARD_PROFILE_ID &&
        connection.profileId != FoxholeVpnService.TOR_ONLY_PROFILE_ID

internal fun HomeRouteUiState.homeNetworkTitleRes(
    showConnectionStatus: Boolean,
): Int =
    when {
        connection.profileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID ||
            shouldShowTorRouteNetworkTitle() -> R.string.home_network_tor_title
        showConnectionStatus -> R.string.home_network_connection_info_title
        else -> R.string.home_network_current_ip_title
    }

private fun HomeRouteUiState.shouldShowTorRouteNetworkTitle(): Boolean =
    settings.privacyRoute.enabled &&
        !hasDashboardRouteProfile() &&
        (
            torOperation.active ||
                (connection.torActive && torIpInfo?.hasVisiblePublicAddress() == true)
            )

internal fun HomeRouteUiState.hasRealTunnelConnectionStatus(): Boolean {
    val routeStatusRunning =
        connection.state in setOf(ConnectionState.CONNECTING, ConnectionState.CONNECTED, ConnectionState.RECONNECTING) ||
            (autoConnect.running && connection.state !in ACTIVE_CONNECTION_STATES)
    val routeIdentityAvailable =
        hasDashboardRouteProfile() ||
            (autoConnect.running && connection.state !in ACTIVE_CONNECTION_STATES)
    return reconnectInProgress || (routeStatusRunning && routeIdentityAvailable)
}

private fun HomeRouteUiState.hasFailedDashboardRoute(): Boolean =
    connection.state == ConnectionState.ERROR &&
        connection.isDashboardRouteTrafficMode() &&
        connection.profileId != null &&
        connection.profileId != FoxholeVpnService.LOCAL_GUARD_PROFILE_ID

private fun HomeRouteUiState.hasStoppedDashboardRouteRuntime(): Boolean =
    connection.state == ConnectionState.IDLE &&
        connection.isDashboardRouteTrafficMode() &&
        connection.profileId != null &&
        connection.profileId != FoxholeVpnService.LOCAL_GUARD_PROFILE_ID &&
        connection.profileId != FoxholeVpnService.TOR_ONLY_PROFILE_ID

private fun ConnectionSnapshot.isDashboardRouteTrafficMode(): Boolean =
    trafficMode == TrafficMode.TUNNEL || trafficMode == TrafficMode.PROXY

internal fun HomeRouteUiState.dashboardVisibleIpInfo(visibleIpInfo: IpInfo?): IpInfo? {
    val candidate =
        when {
            hasEngagedTorOnlyRuntime() -> torIpInfo.takeUnless { torOperation.active || reconnectInProgress }
            torOperation.active && settings.privacyRoute.enabled && !hasDashboardRouteProfile() -> null
            shouldPreferTorRouteIpInfoOnDashboard() -> torIpInfo
            shouldUseDeviceIpInfoAfterStoppedRoute() -> deviceIpInfo
            else -> visibleIpInfo
        }
    return candidate?.takeIf { shouldKeepDashboardIpInfo(it) }
}

private fun HomeRouteUiState.hasEngagedTorOnlyRuntime(): Boolean =
    connection.profileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID &&
        connection.state != ConnectionState.IDLE

private fun HomeRouteUiState.hasStoppedTorOnlyRuntime(): Boolean =
    connection.state == ConnectionState.IDLE &&
        connection.profileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID

private fun HomeRouteUiState.shouldPreferTorRouteIpInfoOnDashboard(): Boolean =
    torIpInfo?.hasVisiblePublicAddress() == true &&
        !hasDashboardRouteProfile() &&
        (
            (torOperation.active && settings.privacyRoute.enabled) ||
                connection.torActive ||
                connection.profileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID
            )

private fun HomeRouteUiState.shouldPinVpnIpDuringTorOperation(): Boolean =
    torOperation.active &&
        settings.privacyRoute.enabled &&
        connection.state == ConnectionState.CONNECTED &&
        hasDashboardRouteProfile()

private fun HomeRouteUiState.shouldUseDeviceIpInfoAfterStoppedRoute(): Boolean =
    deviceIpInfo?.hasVisiblePublicAddress() == true &&
        (
            hasStoppedDashboardRouteRuntime() ||
                hasStoppedUnknownDashboardRouteRuntime() ||
                hasStoppedTorOnlyRuntime()
            )

private fun HomeRouteUiState.shouldKeepDashboardIpInfo(info: IpInfo): Boolean =
    when {
        !info.hasVisiblePublicAddress() -> false
        homeAnalysisOnlyRunning() -> !hasDashboardRouteProfile() || shouldKeepActiveDashboardRouteIpInfo(info)
        shouldPinVpnIpDuringTorOperation() -> true
        hasFailedDashboardRoute() -> info.isPublicFreshForRouteTransition(connection.lastChangeAt)
        shouldUseDeviceIpInfoAfterStoppedRoute() && info == deviceIpInfo -> true
        hasStoppedDashboardRouteRuntime() -> info.isPublicFreshForRouteTransition(connection.lastChangeAt)
        hasStoppedUnknownDashboardRouteRuntime() -> info.isPublicFreshForRouteTransition(connection.lastChangeAt)
        hasActiveDashboardRouteTransition() -> false
        hasActiveDashboardRouteRuntime() -> shouldKeepActiveDashboardRouteIpInfo(info)
        else -> true
    }

private fun HomeRouteUiState.hasStoppedUnknownDashboardRouteRuntime(): Boolean =
    connection.state == ConnectionState.IDLE &&
        connection.isDashboardRouteTrafficMode() &&
        connection.profileId == null &&
        activeProfile != null &&
        !settings.expert.firewallEnabled &&
        connection.lastChangeAt > 0L

private fun HomeRouteUiState.hasActiveDashboardRouteTransition(): Boolean =
    reconnectInProgress ||
        (
            connection.state in setOf(ConnectionState.CONNECTING, ConnectionState.RECONNECTING) &&
                hasDashboardRouteProfile()
            )

private fun HomeRouteUiState.hasActiveDashboardRouteRuntime(): Boolean =
    connection.state in ACTIVE_CONNECTION_STATES &&
        hasDashboardRouteProfile()

private fun HomeRouteUiState.shouldKeepActiveDashboardRouteIpInfo(info: IpInfo): Boolean {
    val freshForConnectedRoute = info.fetchedAt >= connection.lastChangeAt
    return (autoConnect.running && info.isFreshForRouteTransition(connection.lastChangeAt)) ||
        freshForConnectedRoute
}

private fun IpInfo.isPublicFreshForRouteTransition(lastChangeAt: Long): Boolean =
    hasVisiblePublicAddress() && isFreshForRouteTransition(lastChangeAt)

private fun IpInfo.isFreshForRouteTransition(lastChangeAt: Long): Boolean =
    lastChangeAt <= 0L || fetchedAt >= lastChangeAt

internal fun IpInfo.hasVisiblePublicAddress(): Boolean =
    visibleIpCandidates().any { candidate -> candidate.isPublicInternetAddress() }

internal fun IpInfo.visibleIpCandidates(): List<String> {
    val all =
        listOfNotNull(ipv4, ip, ipv6)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    val ipv4Candidates = all.filterNot { candidate -> candidate.substringBefore('%').contains(':') }
    return if (ipv4Candidates.any { candidate -> candidate.isPublicInternetAddress() }) {
        ipv4Candidates
    } else {
        all
    }
}

internal fun String.isPublicInternetAddress(): Boolean {
    val address = runCatching { InetAddress.getByName(substringBefore('%')) }.getOrNull() ?: return true
    return !address.isNonPublicLocalAddress() && !address.isUniqueLocalIpv6Address() && !address.isMulticastAddress
}

private fun InetAddress.isNonPublicLocalAddress(): Boolean =
    isAnyLocalAddress ||
        isLoopbackAddress ||
        isLinkLocalAddress ||
        isSiteLocalAddress

private fun InetAddress.isUniqueLocalIpv6Address(): Boolean =
    this is Inet6Address &&
        address.firstOrNull()?.toInt()?.let { firstByte -> (firstByte and 0xfe) == 0xfc } == true

internal fun resolveHomeDashboardTrafficModel(
    state: HomeRouteUiState,
    now: Long,
): HomeDashboardTrafficModel =
    resolveHomeDashboardTrafficModel(
        state = state,
        traffic = state.traffic,
        now = now,
    )

internal fun resolveHomeDashboardTrafficModel(
    state: HomeRouteUiState,
    traffic: TrafficSnapshot,
    now: Long,
): HomeDashboardTrafficModel {
    val totals = visibleProfileTrafficTotals(state = state, traffic = traffic)
    val totalBytes = totals.sumOf { total -> total.rxTotalBytes + total.txTotalBytes }
    val totalDays = ((now - state.settings.usageTrackingStartedAt).coerceAtLeast(0L) / 86_400_000L) + 1L
    val selectedProtocolTotal = selectedSmartProtocolTrafficTotal(state.activeProfile, totals)
    return HomeDashboardTrafficModel(
        totalBytes = totalBytes,
        totalDays = totalDays,
        hasIncomingTraffic = traffic.rxBytesPerSec > 0L,
        hasOutgoingTraffic = traffic.txBytesPerSec > 0L,
        selectedProtocolTotalBytes = selectedProtocolTotal?.let { total -> total.rxTotalBytes + total.txTotalBytes },
        selectedProtocolHint = selectedProtocolTotal?.protocolHint,
    )
}

private fun selectedSmartProtocolTrafficTotal(
    activeProfile: Profile?,
    totals: List<ProfileTrafficTotal>,
): ProfileTrafficTotal? {
    val selectedProfile = activeProfile?.takeIf(MultiProtocolProfileSupport::hasMultipleSupportedOptions)
    val selectedOption = selectedProfile?.let(MultiProtocolProfileSupport::selectedOption)
    return if (selectedProfile == null || selectedOption == null) {
        null
    } else {
        totals
            .firstOrNull { total ->
                total.profileId == selectedProfile.id &&
                    total.protocolOptionId == selectedOption.id
            }
            ?: ProfileTrafficTotal(
                profileId = selectedProfile.id,
                profileName = selectedProfile.name,
                protocolHint = selectedOption.protocolHint,
                protocolOptionId = selectedOption.id,
            )
    }
}
