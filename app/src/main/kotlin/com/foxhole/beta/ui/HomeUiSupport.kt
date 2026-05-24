package com.foxhole.beta.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.text.format.Formatter
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.getSystemService
import com.foxhole.beta.R
import com.foxhole.beta.core.model.ConnectionSnapshot
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.IpInfo
import com.foxhole.beta.core.model.LocalSurfaceSettings
import com.foxhole.beta.core.model.PerAppRoutingMode
import com.foxhole.beta.core.model.Profile
import com.foxhole.beta.core.model.ProfileProtocolOption
import com.foxhole.beta.core.model.ProfileSourceType
import com.foxhole.beta.core.model.ProfileTrafficTotal
import com.foxhole.beta.core.model.ProtocolHint
import com.foxhole.beta.core.model.ProxyInboundSettings
import com.foxhole.beta.core.model.SecureDnsMode
import com.foxhole.beta.core.model.Settings
import com.foxhole.beta.core.model.TrafficMode
import com.foxhole.beta.core.model.isUdpTransport
import com.foxhole.beta.core.network.IpInfoFetchMode
import com.foxhole.beta.core.profile.MultiProtocolProfileSupport
import com.foxhole.beta.core.settings.needsSmartStartColdScan
import com.foxhole.beta.core.settings.smartProfilePreference
import com.foxhole.beta.core.settings.smartStartEnabledProtocolSetHash
import com.foxhole.beta.vpn.ACTIVE_CONNECTION_STATES
import com.foxhole.beta.vpn.FoxholeVpnService
import com.foxhole.beta.vpn.LocalGuardMode
import com.foxhole.beta.vpn.localGuardModeOrNull

internal data class HomeProxySurface(
    val label: String,
    val settings: ProxyInboundSettings,
    val lanOnly: Boolean = false,
)

internal data class HomeDashboardProtocolPresentation(
    val protocolHint: ProtocolHint,
    val protocolOptions: List<ProfileProtocolOption>,
    val selectedProtocolOptionId: String?,
)

internal data class HomeDashboardLatencyPresentation(
    val latencyMs: Long? = null,
    val isDown: Boolean = false,
    val isUnavailable: Boolean = false,
)

internal data class HomeDashboardProtocolModel(
    val presentation: HomeDashboardProtocolPresentation,
    val latencyPresentation: HomeDashboardLatencyPresentation,
    val latenciesByOptionId: Map<String, Long>,
    val downOptionIds: Set<String>,
    val latencyUnavailableOptionIds: Set<String>,
    val showSmartStartLatency: Boolean,
    val selectedServerPingMs: Long?,
    val selectedServerPingUnavailable: Boolean,
    val connectionDetailsReady: Boolean,
    val connectionMetricsLoading: Boolean,
)

internal data class HomeDashboardProfileModel(
    val activeProfileId: Long?,
    val isSmartDashboardProfile: Boolean,
)

private data class HomeDashboardProfileLatencyState(
    val presentation: HomeDashboardLatencyPresentation,
    val latenciesByOptionId: Map<String, Long>,
    val downOptionIds: Set<String>,
    val latencyUnavailableOptionIds: Set<String>,
    val showSmartStartLatency: Boolean,
    val connectionMetricsLoading: Boolean,
)

internal fun isTrafficMapRuntimeAvailable(
    connection: ConnectionSnapshot,
    settings: Settings,
    activeVpnNetworkAvailable: Boolean,
): Boolean {
    val connected = connection.state == ConnectionState.CONNECTED
    val localGuardConnected = connected && connection.profileId == FoxholeVpnService.LOCAL_GUARD_PROFILE_ID
    val tunnelConnected = connected && connection.profileId != FoxholeVpnService.LOCAL_GUARD_PROFILE_ID
    val countryTrafficLocalGuardEnabled =
        settings.expert.firewallEnabled &&
            settings.statistics.enabled &&
            settings.statistics.countryTrafficEnabled
    val localGuardAvailable =
        countryTrafficLocalGuardEnabled &&
            settings.localGuardModeOrNull() != null &&
            (activeVpnNetworkAvailable || localGuardConnected)
    return settings.ui.trafficMapEnabled && (tunnelConnected || localGuardAvailable)
}

internal data class HomeDashboardNetworkModel(
    val visibleIpInfo: IpInfo?,
    val showLoading: Boolean,
    val showIpInfoLoading: Boolean,
    val showConnectionDetailsLoading: Boolean,
    val showRefreshProgress: Boolean,
    val showConnectionStatus: Boolean,
    val titleRes: Int,
)

internal data class HomeDashboardProxyModel(
    val modeOption: HomeModeOption,
    val proxySurface: HomeProxySurface?,
    val lanProxySurface: HomeProxySurface?,
    val dashboardProxySurface: HomeProxySurface?,
    val lanProxyActive: Boolean,
)

internal data class HomeDashboardTrafficModel(
    val totalBytes: Long,
    val totalDays: Long,
    val hasIncomingTraffic: Boolean,
    val hasOutgoingTraffic: Boolean,
    val selectedProtocolTotalBytes: Long? = null,
    val selectedProtocolHint: ProtocolHint? = null,
)

internal enum class HomePrimaryAction {
    START,
    STOP,
    RECONNECT,
}

internal enum class HomeModeOption {
    TUNNEL,
    SPLIT,
    PROXY,
}

internal fun shouldAutoRefreshIpOnForeground(connectionState: ConnectionState): Boolean =
    connectionState != ConnectionState.CONNECTING && connectionState != ConnectionState.RECONNECTING

internal fun shouldShowIpInfoLoading(
    currentIpInfo: IpInfo?,
    explicitLoading: Boolean,
    connectionState: ConnectionState,
): Boolean = explicitLoading

@Suppress("UNUSED_PARAMETER")
internal fun shouldShowPendingNetworkLoading(
    visibleIpInfo: IpInfo?,
    explicitLoading: Boolean,
    connectionState: ConnectionState,
    autoConnectRunning: Boolean,
    deviceInternetAvailable: Boolean?,
    appLoaded: Boolean,
): Boolean =
    when {
        visibleIpInfo != null -> false
        deviceInternetAvailable == false -> false
        explicitLoading -> true
        !appLoaded && shouldAutoRefreshIpOnForeground(connectionState) -> true
        autoConnectRunning || connectionState in setOf(ConnectionState.CONNECTING, ConnectionState.RECONNECTING) -> true
        else -> false
    }

internal fun shouldShowDashboardNetworkLoading(
    visibleIpInfo: IpInfo?,
    explicitLoading: Boolean,
    connectionState: ConnectionState,
    autoConnectRunning: Boolean,
    deviceInternetAvailable: Boolean?,
    appLoaded: Boolean,
): Boolean =
    shouldShowIpInfoLoading(
        currentIpInfo = visibleIpInfo,
        explicitLoading = explicitLoading,
        connectionState = connectionState,
    ) ||
        shouldShowPendingNetworkLoading(
            visibleIpInfo = visibleIpInfo,
            explicitLoading = explicitLoading,
            connectionState = connectionState,
            autoConnectRunning = autoConnectRunning,
            deviceInternetAvailable = deviceInternetAvailable,
            appLoaded = appLoaded,
        )

internal fun shouldShowTrafficMapLegendLoading(
    connectionState: ConnectionState,
    appLoaded: Boolean,
    explicitLoading: Boolean = false,
): Boolean =
    explicitLoading ||
        !appLoaded ||
        connectionState in setOf(ConnectionState.CONNECTING, ConnectionState.RECONNECTING)

internal fun isProfileReconnectRequired(
    activeProfile: Profile?,
    connection: ConnectionSnapshot,
): Boolean {
    val profileId = activeProfile?.id ?: return false
    val connectedProfileId = connection.profileId ?: return false
    if (connection.state !in setOf(ConnectionState.CONNECTING, ConnectionState.CONNECTED, ConnectionState.RECONNECTING)) {
        return false
    }
    if (profileId != connectedProfileId) {
        return true
    }
    val selectedOptionId = activeProfile.selectedProtocolOptionId?.takeIf(String::isNotBlank)
    val connectedOptionId = connection.protocolOptionId?.takeIf(String::isNotBlank)
    if ((selectedOptionId != null || connectedOptionId != null) && selectedOptionId != connectedOptionId) {
        return true
    }
    val connectedProtocol = connection.protocolHint ?: return false
    return activeProfile.protocolHint != connectedProtocol
}

internal fun shouldAutoRefreshIpAfterConnect(
    previousState: ConnectionState?,
    currentState: ConnectionState,
): Boolean = previousState != ConnectionState.CONNECTED && currentState == ConnectionState.CONNECTED

internal fun shouldAutoRefreshIpAfterDisconnect(
    previousState: ConnectionState?,
    currentState: ConnectionState,
): Boolean =
    previousState in ACTIVE_CONNECTION_STATES &&
        currentState !in ACTIVE_CONNECTION_STATES

internal enum class IpInfoRefreshReason {
    MANUAL,
    FOREGROUND,
    POST_CONNECT,
    POST_UPDATE,
    RESTORED_VPN,
    TOR_ROUTE,
}

internal enum class IpInfoRefreshTarget {
    VPN_BOUND,
    UPSTREAM,
    PROXY,
    LOCAL_GUARD,
}

internal fun ipInfoRefreshTargetForSnapshot(snapshot: ConnectionSnapshot): IpInfoRefreshTarget =
    when {
        snapshot.state == ConnectionState.CONNECTED &&
            snapshot.trafficMode == TrafficMode.TUNNEL &&
            snapshot.profileId != FoxholeVpnService.LOCAL_GUARD_PROFILE_ID -> IpInfoRefreshTarget.VPN_BOUND
        snapshot.state == ConnectionState.CONNECTED && snapshot.trafficMode == TrafficMode.PROXY -> IpInfoRefreshTarget.PROXY
        snapshot.profileId == FoxholeVpnService.LOCAL_GUARD_PROFILE_ID -> IpInfoRefreshTarget.LOCAL_GUARD
        else -> IpInfoRefreshTarget.UPSTREAM
    }

internal fun shouldSupersedeIpRefreshForConnect(activeReason: IpInfoRefreshReason?): Boolean =
    activeReason == IpInfoRefreshReason.MANUAL ||
        activeReason == IpInfoRefreshReason.FOREGROUND

internal fun ipInfoFetchModeForRefreshReason(reason: IpInfoRefreshReason): IpInfoFetchMode =
    when (reason) {
        IpInfoRefreshReason.MANUAL -> IpInfoFetchMode.FULL
        IpInfoRefreshReason.FOREGROUND,
        IpInfoRefreshReason.POST_CONNECT,
        IpInfoRefreshReason.POST_UPDATE,
        IpInfoRefreshReason.RESTORED_VPN,
        IpInfoRefreshReason.TOR_ROUTE,
        -> IpInfoFetchMode.ENTRY_QUICK
    }

internal fun shouldClearExistingIpForRefresh(
    reason: IpInfoRefreshReason,
    clearExistingIp: Boolean,
): Boolean =
    clearExistingIp &&
        reason !in setOf(
            IpInfoRefreshReason.POST_CONNECT,
            IpInfoRefreshReason.RESTORED_VPN,
            IpInfoRefreshReason.TOR_ROUTE,
        )

internal fun shouldShowAutoConnectAction(activeProfile: Profile?): Boolean =
    activeProfile?.let { profile ->
        MultiProtocolProfileSupport.hasMultipleSupportedOptions(profile) ||
            (
                profile.sourceType == ProfileSourceType.SUBSCRIPTION_URL &&
                    MultiProtocolProfileSupport.smartStartFullScanCandidates(profile).isNotEmpty()
                )
    } == true

internal fun isDashboardSmartStartControlsEnabled(settings: Settings): Boolean =
    settings.connection.smartStartEnabled && settings.ui.smartStartDashboardControlsEnabled

internal fun shouldShowSmartStartFirstAnalysisInfo(state: HomeRouteUiState): Boolean {
    if (!state.settings.connection.smartStartEnabled) {
        return false
    }
    val profile = state.activeProfile?.takeIf(MultiProtocolProfileSupport::hasMultipleSupportedOptions)
    return profile?.let { smartProfile ->
        val candidates =
            MultiProtocolProfileSupport.smartStartFullScanCandidates(
                profile = smartProfile,
                allowInsecureTlsGlobally = state.settings.expert.allowInsecureTls,
                excludedOptionIds = state.activeProfileExcludedOptionIds,
                transportPriority = state.settings.connection.smartStartTransportPriority,
            )
        val enabledProtocolSetHash =
            smartStartEnabledProtocolSetHash(candidates.map { candidate -> candidate.optionId })
        candidates.isNotEmpty() &&
            state.settings.smartProfilePreference(smartProfile.id)?.needsSmartStartColdScan(enabledProtocolSetHash) != false
    } == true
}

internal fun shouldAwaitAutoConnectValidationGrace(
    connectionState: ConnectionState,
    vpnNetworkAvailable: Boolean,
): Boolean = connectionState == ConnectionState.CONNECTING && vpnNetworkAvailable

internal fun resolveHomeDashboardProxyModel(
    state: HomeRouteUiState,
    wifiLanAddress: String?,
): HomeDashboardProxyModel {
    val proxySurface = activeProxySurface(state)
    val lanProxySurface = activeLanProxySurface(state)
    return HomeDashboardProxyModel(
        modeOption = currentHomeModeOption(state),
        proxySurface = proxySurface,
        lanProxySurface = lanProxySurface,
        dashboardProxySurface = null,
        lanProxyActive = wifiLanAddress != null && lanProxySurface != null,
    )
}

internal fun resolveHomeDashboardProtocolModel(state: HomeRouteUiState): HomeDashboardProtocolModel {
    val latenciesByOptionId =
        state.smartStartRememberedLatenciesByOptionId +
            state.protocolLatenciesByOptionId +
            state.autoConnect.options
                .mapNotNull { option ->
                    option.latencyMs
                        ?.takeIf {
                            option.status == AutoConnectProbeStatus.SUCCESS ||
                                option.status == AutoConnectProbeStatus.WINNER
                        }?.let { latencyMs -> option.optionId to latencyMs }
                }.toMap()
    val activeConnectedAutoConnectOptionId = state.activeConnectedAutoConnectOptionId()
    val refreshingOptionId =
        state.protocolMetricsRefreshingOptionId.takeIf {
            state.protocolMetricsRefreshing
        }
    val refreshingProbeOptionIds =
        if (state.protocolMetricsRefreshing) {
            state.autoConnect.options.map(AutoConnectProbeOptionUiState::optionId).toSet()
        } else {
            emptySet()
        }
    val autoConnectFailedOptionIds =
        if (state.protocolMetricsRefreshing) {
            emptySet()
        } else {
            state.autoConnect.options
                .filter { option -> option.status == AutoConnectProbeStatus.FAILED }
                .map(AutoConnectProbeOptionUiState::optionId)
                .toSet()
        }
    val downOptionIds =
        (
            (state.protocolDownOptionIds - refreshingProbeOptionIds) +
                autoConnectFailedOptionIds
            ).withoutOption(activeConnectedAutoConnectOptionId)
            .withoutOption(refreshingOptionId)
    val autoConnectUnavailableOptionIds =
        if (state.protocolMetricsRefreshing) {
            emptySet()
        } else {
            state.autoConnect.options
                .filter { option ->
                    option.status in setOf(AutoConnectProbeStatus.SUCCESS, AutoConnectProbeStatus.WINNER) &&
                        option.latencyUnavailable
                }.map(AutoConnectProbeOptionUiState::optionId)
                .toSet()
        }
    val latencyUnavailableOptionIds =
        (
            (state.protocolLatencyUnavailableOptionIds - refreshingProbeOptionIds) +
                autoConnectUnavailableOptionIds
            ).withoutOption(activeConnectedAutoConnectOptionId)
            .withoutOption(refreshingOptionId)
    val protocolPresentation =
        resolveHomeDashboardProtocolPresentation(
            activeProfile = state.activeProfile,
            connection = state.connection,
            autoConnect = state.autoConnect,
            pinSelectionToProfile = state.protocolMetricsRefreshing || state.reconnectRequired,
        )
    val selectedServerPingOptionId = resolveDashboardLatencyOptionId(state.activeProfile, state.connection)
    val selectedServerPingMs = selectedServerPingOptionId?.let(state.protocolServerPingsByOptionId::get)
    val selectedServerPingUnavailable =
        selectedServerPingOptionId != null &&
            selectedServerPingMs == null &&
            selectedServerPingOptionId in state.protocolServerPingUnavailableOptionIds
    val latencyState =
        state.resolveDashboardProfileLatencyState(
            latenciesByOptionId = latenciesByOptionId,
            downOptionIds = downOptionIds,
            latencyUnavailableOptionIds = latencyUnavailableOptionIds,
        )
    return HomeDashboardProtocolModel(
        presentation = protocolPresentation,
        latencyPresentation = latencyState.presentation,
        latenciesByOptionId = latencyState.latenciesByOptionId,
        downOptionIds = latencyState.downOptionIds,
        latencyUnavailableOptionIds = latencyState.latencyUnavailableOptionIds,
        showSmartStartLatency = latencyState.showSmartStartLatency,
        selectedServerPingMs = selectedServerPingMs,
        selectedServerPingUnavailable = selectedServerPingUnavailable,
        connectionDetailsReady =
        shouldRenderDashboardConnectionDetails(
            connectionState = state.connection.state,
            activeProfile = state.activeProfile,
            selectedLatencyMs = latencyState.presentation.latencyMs,
            selectedLatencyDown = latencyState.presentation.isDown,
            selectedLatencyUnavailable = latencyState.presentation.isUnavailable,
            selectedServerPingMs = selectedServerPingMs,
            selectedServerPingUnavailable = selectedServerPingUnavailable,
            selectedServerPingUnsupported = protocolPresentation.protocolHint.isUdpTransport(),
        ),
        connectionMetricsLoading = latencyState.connectionMetricsLoading,
    )
}

private fun HomeRouteUiState.resolveDashboardProfileLatencyState(
    latenciesByOptionId: Map<String, Long>,
    downOptionIds: Set<String>,
    latencyUnavailableOptionIds: Set<String>,
): HomeDashboardProfileLatencyState {
    if (!shouldShowDashboardProfileLatency()) {
        return HomeDashboardProfileLatencyState(
            presentation = HomeDashboardLatencyPresentation(),
            latenciesByOptionId = emptyMap(),
            downOptionIds = emptySet(),
            latencyUnavailableOptionIds = emptySet(),
            showSmartStartLatency = false,
            connectionMetricsLoading = false,
        )
    }
    return HomeDashboardProfileLatencyState(
        presentation = resolveDashboardLatencyPresentation(this),
        latenciesByOptionId = latenciesByOptionId,
        downOptionIds = downOptionIds,
        latencyUnavailableOptionIds = latencyUnavailableOptionIds,
        showSmartStartLatency =
        latenciesByOptionId.isNotEmpty() ||
            downOptionIds.isNotEmpty() ||
            latencyUnavailableOptionIds.isNotEmpty(),
        connectionMetricsLoading = dashboardConnectionMetricsLoading || reconnectInProgress || protocolMetricsRefreshing,
    )
}

private fun HomeRouteUiState.shouldShowDashboardProfileLatency(): Boolean =
    if (connection.profileId == FoxholeVpnService.LOCAL_GUARD_PROFILE_ID && !autoConnect.running) {
        false
    } else {
        reconnectInProgress ||
            connection.state in DASHBOARD_LATENCY_ACTIVE_STATES ||
            autoConnect.running
    }

private fun HomeRouteUiState.activeConnectedAutoConnectOptionId(): String? =
    autoConnect.currentOptionId?.takeIf { optionId ->
        autoConnect.running &&
            connection.state in setOf(ConnectionState.CONNECTING, ConnectionState.CONNECTED, ConnectionState.RECONNECTING) &&
            (
                connection.state != ConnectionState.CONNECTED ||
                    connection.protocolOptionId == optionId ||
                    (
                        connection.protocolOptionId == null &&
                            autoConnect.options.firstOrNull { option -> option.optionId == optionId }?.protocolHint == connection.protocolHint
                        )
                )
    }

private fun Set<String>.withoutOption(optionId: String?): Set<String> =
    optionId?.let { this - it } ?: this

internal fun resolveHomeDashboardProfileModel(
    state: HomeRouteUiState,
): HomeDashboardProfileModel {
    val activeProfileId = state.activeProfile?.id
    return HomeDashboardProfileModel(
        activeProfileId = activeProfileId,
        isSmartDashboardProfile = state.activeProfile?.let(MultiProtocolProfileSupport::hasMultipleSupportedOptions) == true,
    )
}

internal fun resolveHomeDashboardNetworkModel(
    state: HomeRouteUiState,
    visibleIpInfo: IpInfo?,
    deviceInternetAvailable: Boolean?,
): HomeDashboardNetworkModel {
    val showConnectionStatus = state.hasRealTunnelConnectionStatus()
    val dashboardIpInfo = state.dashboardVisibleIpInfo(visibleIpInfo)
    val routeTransitionRunning = state.homeRouteTransitionRunning()
    val analysisOnlyRunning = state.homeAnalysisOnlyRunning()
    val ipInfoLoading =
        state.shouldShowHomeNetworkIpInfoLoading(
            dashboardIpInfo = dashboardIpInfo,
            routeTransitionRunning = routeTransitionRunning,
            deviceInternetAvailable = deviceInternetAvailable,
        )
    val connectionDetailsLoading =
        state.shouldShowHomeNetworkConnectionDetailsLoading(
            showConnectionStatus = showConnectionStatus,
            routeTransitionRunning = routeTransitionRunning,
            explicitIpInfoLoading = state.ipInfoLoading,
        )
    return HomeDashboardNetworkModel(
        visibleIpInfo = dashboardIpInfo,
        showLoading = ipInfoLoading || connectionDetailsLoading,
        showIpInfoLoading = ipInfoLoading,
        showConnectionDetailsLoading = connectionDetailsLoading,
        showRefreshProgress = routeTransitionRunning || analysisOnlyRunning || (state.ipInfoLoading && dashboardIpInfo != null),
        showConnectionStatus = showConnectionStatus,
        titleRes = state.homeNetworkTitleRes(showConnectionStatus),
    )
}

private fun HomeRouteUiState.homeRouteTransitionRunning(): Boolean =
    reconnectInProgress ||
        connection.state in setOf(ConnectionState.CONNECTING, ConnectionState.RECONNECTING) ||
        (autoConnect.running && connection.state !in ACTIVE_CONNECTION_STATES)

private fun HomeRouteUiState.homeAnalysisOnlyRunning(): Boolean =
    protocolMetricsRefreshing &&
        connection.state in ACTIVE_CONNECTION_STATES &&
        !reconnectInProgress &&
        !autoConnect.running

private fun HomeRouteUiState.shouldShowHomeNetworkIpInfoLoading(
    dashboardIpInfo: IpInfo?,
    routeTransitionRunning: Boolean,
    deviceInternetAvailable: Boolean?,
): Boolean {
    val manualRefreshNeedsSkeleton = ipInfoLoading
    val missingIpCanShowSkeleton =
        shouldShowLocalGuardNetworkLoading(
            deviceInternetAvailable = deviceInternetAvailable,
            routeTransitionRunning = routeTransitionRunning,
            explicitIpInfoLoading = ipInfoLoading,
        ) ||
            reconnectInProgress ||
            (routeTransitionRunning && hasDashboardRouteProfile()) ||
            shouldShowVpnTransitionLoading(routeTransitionRunning) ||
            shouldShowDashboardNetworkLoading(
                visibleIpInfo = dashboardIpInfo,
                explicitLoading = ipInfoLoading,
                connectionState = connection.state,
                autoConnectRunning = routeTransitionRunning,
                deviceInternetAvailable = deviceInternetAvailable,
                appLoaded = profilesLoaded,
            )
    val missingIpNeedsSkeleton = dashboardIpInfo == null && missingIpCanShowSkeleton
    return manualRefreshNeedsSkeleton || missingIpNeedsSkeleton
}

private fun HomeRouteUiState.shouldShowLocalGuardNetworkLoading(
    deviceInternetAvailable: Boolean?,
    routeTransitionRunning: Boolean,
    explicitIpInfoLoading: Boolean,
): Boolean =
    connection.profileId == FoxholeVpnService.LOCAL_GUARD_PROFILE_ID &&
        settings.localGuardModeOrNull() != null &&
        deviceInternetAvailable != false &&
        (explicitIpInfoLoading || routeTransitionRunning)

private fun HomeRouteUiState.shouldShowHomeNetworkConnectionDetailsLoading(
    showConnectionStatus: Boolean,
    routeTransitionRunning: Boolean,
    explicitIpInfoLoading: Boolean,
): Boolean =
    showConnectionStatus &&
        (
            reconnectInProgress ||
                shouldShowVpnTransitionLoading(routeTransitionRunning) ||
                routeTransitionRunning ||
                explicitIpInfoLoading
            )

private fun HomeRouteUiState.shouldShowVpnTransitionLoading(routeTransitionRunning: Boolean): Boolean =
    routeTransitionRunning &&
        connection.state in setOf(ConnectionState.CONNECTING, ConnectionState.RECONNECTING) &&
        hasDashboardRouteProfile()

private fun HomeRouteUiState.hasDashboardRouteProfile(): Boolean =
    connection.trafficMode in setOf(TrafficMode.TUNNEL, TrafficMode.PROXY) &&
        connection.profileId != FoxholeVpnService.LOCAL_GUARD_PROFILE_ID &&
        connection.profileId != FoxholeVpnService.TOR_ONLY_PROFILE_ID

private fun HomeRouteUiState.homeNetworkTitleRes(
    showConnectionStatus: Boolean,
): Int =
    when {
        connection.profileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID -> R.string.home_network_tor_title
        showConnectionStatus -> R.string.home_network_connection_info_title
        else -> R.string.home_network_current_ip_title
    }

private fun HomeRouteUiState.hasRealTunnelConnectionStatus(): Boolean =
    reconnectInProgress ||
        (
            connection.state in setOf(ConnectionState.CONNECTING, ConnectionState.CONNECTED, ConnectionState.RECONNECTING) &&
                hasDashboardRouteProfile()
            )

private fun HomeRouteUiState.dashboardVisibleIpInfo(visibleIpInfo: IpInfo?): IpInfo? {
    if (visibleIpInfo == null) {
        return visibleIpInfo
    }
    if (homeAnalysisOnlyRunning()) {
        return visibleIpInfo
    }
    if (shouldPinVpnIpDuringTorOperation()) {
        return visibleIpInfo
    }
    val protocolSearchRunning = autoConnect.running
    val routeTransitionActive =
        reconnectInProgress ||
            (
                connection.state in setOf(ConnectionState.CONNECTING, ConnectionState.RECONNECTING) &&
                    hasDashboardRouteProfile()
                )
    if (routeTransitionActive) {
        return visibleIpInfo.takeIf { info -> info.isFreshForRouteTransition(connection.lastChangeAt) }
    }
    val routeRuntimeActive =
        connection.state in ACTIVE_CONNECTION_STATES &&
            hasDashboardRouteProfile()
    if (routeRuntimeActive) {
        return visibleIpInfo.takeIf { info ->
            val freshForConnectedRoute =
                info.fetchedAt >= connection.lastChangeAt ||
                    (!ipInfoLoading && info.isFreshForConnectedRouteSettle(connection.lastChangeAt))
            (protocolSearchRunning && info.isFreshForRouteTransition(connection.lastChangeAt)) ||
                freshForConnectedRoute
        }
    }
    return visibleIpInfo
}

private fun HomeRouteUiState.shouldPinVpnIpDuringTorOperation(): Boolean =
    torOperation.active &&
        settings.privacyRoute.enabled &&
        connection.state == ConnectionState.CONNECTED &&
        hasDashboardRouteProfile()

private fun IpInfo.isFreshForRouteTransition(lastChangeAt: Long): Boolean =
    lastChangeAt <= 0L || fetchedAt >= lastChangeAt

private fun IpInfo.isFreshForConnectedRouteSettle(lastChangeAt: Long): Boolean =
    lastChangeAt <= 0L || fetchedAt >= lastChangeAt - CONNECTED_ROUTE_IP_INFO_SETTLE_GRACE_MS

private const val CONNECTED_ROUTE_IP_INFO_SETTLE_GRACE_MS = 250L

internal fun resolveHomeDashboardTrafficModel(
    state: HomeRouteUiState,
    now: Long,
): HomeDashboardTrafficModel {
    val totals = visibleProfileTrafficTotals(state)
    val totalBytes = totals.sumOf { total -> total.rxTotalBytes + total.txTotalBytes }
    val totalDays = ((now - state.settings.usageTrackingStartedAt).coerceAtLeast(0L) / 86_400_000L) + 1L
    val selectedProtocolTotal = selectedSmartProtocolTrafficTotal(state.activeProfile, totals)
    return HomeDashboardTrafficModel(
        totalBytes = totalBytes,
        totalDays = totalDays,
        hasIncomingTraffic = state.traffic.rxBytesPerSec > 0L,
        hasOutgoingTraffic = state.traffic.txBytesPerSec > 0L,
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

@Composable
internal fun rememberDefaultInternetAvailability(): State<Boolean?> {
    val appContext = LocalContext.current.applicationContext
    val connectivityManager = remember(appContext) { appContext.getSystemService<ConnectivityManager>() }
    val defaultInternetAvailable =
        remember(connectivityManager) {
            mutableStateOf(resolveDefaultInternetAvailability(connectivityManager))
        }
    DisposableEffect(connectivityManager) {
        val manager =
            connectivityManager ?: return@DisposableEffect onDispose {
            }
        val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    defaultInternetAvailable.value = resolveDefaultInternetAvailability(manager)
                }

                override fun onLost(network: Network) {
                    defaultInternetAvailable.value = resolveDefaultInternetAvailability(manager)
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities,
                ) {
                    defaultInternetAvailable.value = resolveDefaultInternetAvailability(manager)
                }

                override fun onLinkPropertiesChanged(
                    network: Network,
                    linkProperties: LinkProperties,
                ) {
                    defaultInternetAvailable.value = resolveDefaultInternetAvailability(manager)
                }
            }
        defaultInternetAvailable.value = resolveDefaultInternetAvailability(manager)
        manager.registerDefaultNetworkCallback(callback)
        onDispose {
            runCatching { manager.unregisterNetworkCallback(callback) }
        }
    }
    return defaultInternetAvailable
}

private fun resolveDefaultInternetAvailability(
    connectivityManager: ConnectivityManager?,
): Boolean? {
    val manager = connectivityManager ?: return null
    val activeNetwork = manager.activeNetwork ?: return false
    val capabilities = manager.getNetworkCapabilities(activeNetwork) ?: return null
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
        !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
}

internal fun resolveDashboardSelectedOptionId(activeProfile: Profile?): String? =
    activeProfile
        ?.let(MultiProtocolProfileSupport::selectedOption)
        ?.id

internal fun resolveDashboardSelectedOptionId(
    activeProfile: Profile?,
    connection: ConnectionSnapshot,
): String? {
    val selectedProfileOptionId =
        activeProfile
            ?.selectedProtocolOptionId
            ?.takeIf { optionId -> activeProfile.protocolOptions.any { option -> option.id == optionId } }
    val connectedOptionId =
        connection.protocolOptionId
            ?.takeIf { connection.profileId == activeProfile?.id }
            ?.takeIf {
                connection.state in setOf(
                    ConnectionState.CONNECTED,
                    ConnectionState.CONNECTING,
                    ConnectionState.RECONNECTING,
                )
            }?.takeIf { optionId ->
                activeProfile?.protocolOptions?.any { option -> option.id == optionId } == true
            }
    val connectedProtocol =
        connection.protocolHint
            ?.takeIf { connection.profileId == activeProfile?.id }
            ?.takeIf {
                connection.state in setOf(
                    ConnectionState.CONNECTED,
                    ConnectionState.CONNECTING,
                    ConnectionState.RECONNECTING,
                )
            }
    val connectedProtocolOptionId =
        connectedProtocol
            ?.let { protocol ->
                activeProfile
                    ?.protocolOptions
                    ?.firstOrNull { option -> option.protocolHint == protocol }
                    ?.id
            }
    return connectedOptionId
        ?: connectedProtocolOptionId
        ?: selectedProfileOptionId
        ?: resolveDashboardSelectedOptionId(activeProfile)
}

internal fun homeTopStatusState(state: HomeRouteUiState): ConnectionState =
    when {
        state.torOperation.active -> ConnectionState.RECONNECTING
        state.reconnectInProgress -> ConnectionState.RECONNECTING
        state.autoConnect.running -> ConnectionState.CONNECTING
        else -> state.connection.state
    }

internal fun shouldShowHomeTopStatusLoading(state: HomeRouteUiState): Boolean =
    !state.profilesLoaded ||
        (
            state.settings.localGuardModeOrNull() != null &&
                state.connection.state == ConnectionState.IDLE &&
                state.connection.profileId != FoxholeVpnService.LOCAL_GUARD_PROFILE_ID
            )

internal fun resolveDashboardLatencyOptionId(
    activeProfile: Profile?,
    connection: ConnectionSnapshot? = null,
): String? =
    connection?.let { resolveDashboardSelectedOptionId(activeProfile, it) }
        ?: resolveDashboardSelectedOptionId(activeProfile)
        ?: activeProfile
            ?.protocolHint
            ?.takeIf { hint -> hint !in setOf(ProtocolHint.UNKNOWN, ProtocolHint.SING_BOX) }
            ?.name
            ?.lowercase()

internal fun protocolHintChipLabel(protocol: ProtocolHint): String =
    when (protocol) {
        ProtocolHint.VLESS -> "VLESS"
        ProtocolHint.TROJAN -> "TROJAN"
        ProtocolHint.SHADOWSOCKS -> "SHADOWSOCKS"
        ProtocolHint.WIREGUARD -> "WIREGUARD"
        ProtocolHint.HYSTERIA2 -> "HYSTERIA2"
        ProtocolHint.VMESS -> "VMESS"
        ProtocolHint.OUTLINE -> "OUTLINE"
        ProtocolHint.SING_BOX -> "SING-BOX"
        ProtocolHint.UNKNOWN -> "UNKNOWN"
    }

internal fun dashboardTransportTypeLabel(protocol: ProtocolHint): String =
    when {
        protocol in setOf(ProtocolHint.UNKNOWN, ProtocolHint.SING_BOX) -> "-"
        protocol.isUdpTransport() -> "UDP"
        else -> "TCP"
    }

@Composable
internal fun homeStatusLabel(state: ConnectionState): String =
    when (state) {
        ConnectionState.CONNECTED -> stringResource(R.string.home_status_connected)
        ConnectionState.CONNECTING -> stringResource(R.string.status_connecting)
        ConnectionState.RECONNECTING -> stringResource(R.string.status_reconnecting)
        ConnectionState.ERROR -> stringResource(R.string.status_error)
        ConnectionState.IDLE -> stringResource(R.string.home_status_not_connected)
    }

@Composable
internal fun homeStatusLabel(
    routeState: HomeRouteUiState,
    state: ConnectionState,
): String =
    when {
        routeState.torOperation.kind == HomeTorOperationKind.CHANGING_LOCATION ->
            stringResource(R.string.home_status_tor_changing_location)
        routeState.torOperation.kind == HomeTorOperationKind.BOOTSTRAPPING ->
            stringResource(R.string.home_status_tor_bootstrapping)
        routeState.torOperation.kind == HomeTorOperationKind.CONNECTING ->
            stringResource(R.string.home_status_tor_connecting)
        state == ConnectionState.CONNECTED &&
            routeState.connection.profileId == FoxholeVpnService.LOCAL_GUARD_PROFILE_ID ->
            homeLocalGuardStatusLabel(routeState)
        else -> homeStatusLabel(state)
    }

@Composable
private fun homeLocalGuardStatusLabel(routeState: HomeRouteUiState): String =
    when (routeState.settings.localGuardModeOrNull()) {
        LocalGuardMode.FIREWALL -> stringResource(R.string.notification_status_firewall)
        LocalGuardMode.JOURNAL -> stringResource(R.string.notification_status_journal)
        LocalGuardMode.DNS -> stringResource(R.string.notification_status_dns_guard)
        null -> stringResource(R.string.firewall_modal_mode_local_guard)
    }

@Composable
internal fun homeStatusTone(state: ConnectionState): Color =
    when (state) {
        ConnectionState.CONNECTED -> Color(0xFF7BD69D)
        ConnectionState.ERROR -> Color(0xFFC95353)
        ConnectionState.CONNECTING,
        ConnectionState.RECONNECTING,
        ConnectionState.IDLE,
        -> MaterialTheme.colorScheme.onSurfaceVariant
    }

internal fun activeProxySurface(state: HomeRouteUiState): HomeProxySurface? {
    if (state.settings.traffic.mode != TrafficMode.PROXY) {
        return null
    }
    return state.settings.expert.localSurfaces.proxySurface()
}

internal fun activeLanProxySurface(state: HomeRouteUiState): HomeProxySurface? {
    if (!state.settings.expert.localSurfaces.allowLanAccess) {
        return null
    }
    return state.settings.expert.localSurfaces.lanProxySurface()
}

internal fun resolveHomeDashboardProtocolPresentation(
    activeProfile: Profile?,
    connection: ConnectionSnapshot = ConnectionSnapshot(),
    autoConnect: AutoConnectUiState,
    pinSelectionToProfile: Boolean = false,
): HomeDashboardProtocolPresentation {
    if (activeProfile == null) {
        return HomeDashboardProtocolPresentation(
            protocolHint = ProtocolHint.UNKNOWN,
            protocolOptions = emptyList(),
            selectedProtocolOptionId = null,
        )
    }
    val selectedProtocolOptionId =
        when {
            pinSelectionToProfile -> resolveDashboardSelectedOptionId(activeProfile)
            autoConnect.running && !pinSelectionToProfile ->
                autoConnect.currentOptionId ?: activeProfile.selectedProtocolOptionId
            else -> resolveDashboardSelectedOptionId(activeProfile, connection)
        }
    val protocolOptions =
        if (autoConnect.running && !pinSelectionToProfile && autoConnect.options.isNotEmpty()) {
            autoConnect.options.map { option ->
                ProfileProtocolOption(
                    id = option.optionId,
                    displayName = option.displayName,
                    protocolHint = option.protocolHint,
                    isSelected = option.optionId == selectedProtocolOptionId,
                )
            }
        } else {
            activeProfile.protocolOptions.map { option ->
                option.copy(isSelected = option.id == selectedProtocolOptionId)
            }
        }
    val protocolHint =
        protocolOptions.firstOrNull { option -> option.id == selectedProtocolOptionId }?.protocolHint
            ?: activeProfile.protocolHint
    return HomeDashboardProtocolPresentation(
        protocolHint = protocolHint,
        protocolOptions = protocolOptions,
        selectedProtocolOptionId = selectedProtocolOptionId,
    )
}

internal fun shouldRenderDashboardConnectionDetails(
    connectionState: ConnectionState,
    activeProfile: Profile?,
    selectedLatencyMs: Long?,
    selectedLatencyDown: Boolean,
    selectedLatencyUnavailable: Boolean,
    selectedServerPingMs: Long?,
    selectedServerPingUnavailable: Boolean,
    selectedServerPingUnsupported: Boolean,
): Boolean {
    if (connectionState != ConnectionState.CONNECTED || activeProfile == null) {
        return false
    }
    val latencyReady = selectedLatencyMs != null || selectedLatencyDown || selectedLatencyUnavailable
    val serverPingReady = selectedServerPingMs != null || selectedServerPingUnavailable || selectedServerPingUnsupported
    return latencyReady && serverPingReady
}

internal fun resolveDashboardLatencyPresentation(state: HomeRouteUiState): HomeDashboardLatencyPresentation {
    if (state.connection.profileId == FoxholeVpnService.LOCAL_GUARD_PROFILE_ID && !state.autoConnect.running) {
        return HomeDashboardLatencyPresentation()
    }
    if (state.autoConnect.running) {
        return autoConnectDashboardLatencyPresentation(state.autoConnect)
    }
    if (state.connection.state !in DASHBOARD_LATENCY_ACTIVE_STATES) {
        return HomeDashboardLatencyPresentation()
    }
    return connectedDashboardLatencyPresentation(state)
}

private fun autoConnectDashboardLatencyPresentation(autoConnect: AutoConnectUiState): HomeDashboardLatencyPresentation {
    val currentOption =
        autoConnect.options.firstOrNull { option ->
            option.optionId == autoConnect.currentOptionId
        }
    val latencyMs = currentOption?.latencyMs
    return when {
        currentOption?.status == AutoConnectProbeStatus.FAILED ->
            HomeDashboardLatencyPresentation(isDown = true)
        currentOption?.status in DASHBOARD_LATENCY_SUCCESS_STATES && latencyMs != null ->
            HomeDashboardLatencyPresentation(latencyMs = latencyMs)
        currentOption?.status in DASHBOARD_LATENCY_SUCCESS_STATES && currentOption?.latencyUnavailable == true ->
            HomeDashboardLatencyPresentation(isUnavailable = true)
        else -> HomeDashboardLatencyPresentation()
    }
}

private fun connectedDashboardLatencyPresentation(state: HomeRouteUiState): HomeDashboardLatencyPresentation {
    val selectedOptionId = resolveDashboardLatencyOptionId(state.activeProfile, state.connection)
    val rememberedLatencyMs = selectedOptionId?.let(state.smartStartRememberedLatenciesByOptionId::get)
    return when {
        state.selectedProtocolLatencyMs != null ->
            HomeDashboardLatencyPresentation(latencyMs = state.selectedProtocolLatencyMs)
        state.selectedProtocolRefreshing(selectedOptionId) -> HomeDashboardLatencyPresentation()
        selectedOptionId != null && selectedOptionId in state.protocolDownOptionIds ->
            HomeDashboardLatencyPresentation(isDown = true)
        rememberedLatencyMs != null ->
            HomeDashboardLatencyPresentation(latencyMs = rememberedLatencyMs)
        state.selectedProtocolLatencyUnavailable ->
            HomeDashboardLatencyPresentation(isUnavailable = true)
        else -> HomeDashboardLatencyPresentation()
    }
}

private fun HomeRouteUiState.selectedProtocolRefreshing(selectedOptionId: String?): Boolean =
    protocolMetricsRefreshing &&
        selectedOptionId != null &&
        (
            protocolMetricsRefreshingOptionId == null ||
                protocolMetricsRefreshingOptionId == selectedOptionId
            )

private val DASHBOARD_LATENCY_ACTIVE_STATES =
    setOf(
        ConnectionState.CONNECTED,
        ConnectionState.CONNECTING,
        ConnectionState.RECONNECTING,
    )

private val DASHBOARD_LATENCY_SUCCESS_STATES =
    setOf(
        AutoConnectProbeStatus.SUCCESS,
        AutoConnectProbeStatus.WINNER,
    )

internal fun resolveDashboardSelectedLatencyMs(state: HomeRouteUiState): Long? =
    resolveDashboardLatencyPresentation(state).latencyMs

internal fun resolveDashboardSelectedLatencyDown(state: HomeRouteUiState): Boolean =
    resolveDashboardLatencyPresentation(state).isDown

internal fun resolveDashboardSelectedLatencyUnavailable(state: HomeRouteUiState): Boolean =
    resolveDashboardLatencyPresentation(state).isUnavailable

private fun LocalSurfaceSettings.proxySurface(): HomeProxySurface =
    when (proxyMode) {
        com.foxhole.beta.core.model.ProxySurfaceMode.SOCKS5 -> HomeProxySurface(label = "SOCKS5", settings = socks)
        com.foxhole.beta.core.model.ProxySurfaceMode.HTTP -> HomeProxySurface(label = "HTTP", settings = http)
        com.foxhole.beta.core.model.ProxySurfaceMode.ALL -> HomeProxySurface(label = "ALL", settings = mixed)
    }

private fun LocalSurfaceSettings.lanProxySurface(): HomeProxySurface =
    when (lanProxyMode) {
        com.foxhole.beta.core.model.ProxySurfaceMode.SOCKS5 -> HomeProxySurface(
            label = "SOCKS5",
            settings = socks,
            lanOnly = true
        )
        com.foxhole.beta.core.model.ProxySurfaceMode.HTTP -> HomeProxySurface(
            label = "HTTP",
            settings = http,
            lanOnly = true
        )
        com.foxhole.beta.core.model.ProxySurfaceMode.ALL -> HomeProxySurface(
            label = "ALL",
            settings = mixed,
            lanOnly = true
        )
    }

internal fun ConnectionSnapshot.isPrimaryConnectionRuntime(): Boolean =
    state in ACTIVE_CONNECTION_STATES &&
        profileId != FoxholeVpnService.LOCAL_GUARD_PROFILE_ID

internal fun HomeRouteUiState.hasPrimaryConnectionRuntime(): Boolean = connection.isPrimaryConnectionRuntime()

internal fun HomeRouteUiState.hasTorOnlyRuntime(): Boolean =
    connection.state in ACTIVE_CONNECTION_STATES &&
        connection.profileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID

internal fun homePrimaryAction(state: HomeRouteUiState): HomePrimaryAction =
    if (state.hasPrimaryConnectionRuntime()) {
        homePrimaryAction(state.connection.state, state.reconnectRequired)
    } else {
        HomePrimaryAction.START
    }

internal fun homePrimaryAction(
    state: ConnectionState,
    reconnectRequired: Boolean,
): HomePrimaryAction =
    when {
        state == ConnectionState.CONNECTED && reconnectRequired ->
            HomePrimaryAction.RECONNECT
        state in setOf(ConnectionState.CONNECTED, ConnectionState.CONNECTING, ConnectionState.RECONNECTING) ->
            HomePrimaryAction.STOP
        else -> HomePrimaryAction.START
    }

@Composable
internal fun homeConnectionLabel(
    state: ConnectionState,
    reconnectRequired: Boolean,
): String =
    when (homePrimaryAction(state, reconnectRequired)) {
        HomePrimaryAction.START -> stringResource(R.string.connect)
        HomePrimaryAction.STOP -> stringResource(R.string.disconnect)
        HomePrimaryAction.RECONNECT -> stringResource(R.string.reconnect)
    }

@Composable
internal fun homeConnectionLabel(state: HomeRouteUiState): String =
    when (homePrimaryAction(state)) {
        HomePrimaryAction.START -> stringResource(R.string.connect)
        HomePrimaryAction.STOP -> stringResource(R.string.disconnect)
        HomePrimaryAction.RECONNECT -> stringResource(R.string.reconnect)
    }

internal fun formatBytes(
    context: Context,
    bytes: Long,
): String = Formatter.formatShortFileSize(context, bytes)

internal fun formatRate(
    context: Context,
    bytesPerSecond: Long,
): String = "${formatBytes(context, bytesPerSecond)}/s"

internal fun countryEmoji(countryCode: String?): String {
    val code = countryCode?.uppercase().orEmpty()
    if (code.length != 2) {
        return "\uD83C\uDF10"
    }
    val first = Character.codePointAt(code, 0) - 0x41 + 0x1F1E6
    val second = Character.codePointAt(code, 1) - 0x41 + 0x1F1E6
    return String(Character.toChars(first)) + String(Character.toChars(second))
}

internal fun currentHomeModeOption(state: HomeRouteUiState): HomeModeOption =
    currentHomeModeOption(state.settings)

internal fun currentHomeModeOption(settings: Settings): HomeModeOption =
    when {
        settings.traffic.mode == TrafficMode.PROXY -> HomeModeOption.PROXY
        settings.homeSplitTunnelConfigured() -> HomeModeOption.SPLIT
        else -> HomeModeOption.TUNNEL
    }

internal fun Settings.homeSplitTunnelConfigured(): Boolean =
    expert.perAppRoutingMode != PerAppRoutingMode.FULL_TUNNEL &&
        expert.selectedPackages.any(String::isNotBlank)

internal fun homeModeOptionIcon(option: HomeModeOption): ImageVector =
    when (option) {
        HomeModeOption.TUNNEL -> Icons.Outlined.Shield
        HomeModeOption.SPLIT -> Icons.Outlined.Apps
        HomeModeOption.PROXY -> Icons.Outlined.SwapVert
    }

internal fun homeModeChipLabel(option: HomeModeOption): String =
    when (option) {
        HomeModeOption.TUNNEL -> "TUN"
        HomeModeOption.SPLIT -> "SPLIT"
        HomeModeOption.PROXY -> "PROXY"
    }

@Composable
internal fun homeModeMenuLabel(option: HomeModeOption): String =
    when (option) {
        HomeModeOption.TUNNEL -> stringResource(R.string.per_app_mode_full_tunnel)
        HomeModeOption.SPLIT -> stringResource(R.string.traffic_mode_split)
        HomeModeOption.PROXY -> stringResource(R.string.traffic_mode_proxy)
    }

internal fun applyHomeModeSelection(
    mode: HomeModeOption,
    onTrafficModeSelected: (TrafficMode) -> Unit,
    onPerAppRoutingModeSelected: (PerAppRoutingMode) -> Unit,
    selectedPackages: List<String>,
    currentPerAppRoutingMode: PerAppRoutingMode,
) {
    when (mode) {
        HomeModeOption.TUNNEL -> {
            onPerAppRoutingModeSelected(PerAppRoutingMode.FULL_TUNNEL)
            onTrafficModeSelected(TrafficMode.TUNNEL)
        }
        HomeModeOption.SPLIT -> {
            onTrafficModeSelected(TrafficMode.TUNNEL)
            if (selectedPackages.isNotEmpty()) {
                onPerAppRoutingModeSelected(
                    currentPerAppRoutingMode.takeIf { it != PerAppRoutingMode.FULL_TUNNEL }
                        ?: PerAppRoutingMode.INCLUDE_SELECTED_APPS,
                )
            }
        }
        HomeModeOption.PROXY -> onTrafficModeSelected(TrafficMode.PROXY)
    }
}

@Composable
internal fun buildCountryLine(ipInfo: IpInfo): String {
    return formatCountryLine(
        ipInfo = ipInfo,
        unknownCountry = stringResource(R.string.unknown_country),
    )
}

internal fun formatCountryLine(
    ipInfo: IpInfo,
    unknownCountry: String,
): String {
    val country = ipInfo.countryName ?: ipInfo.countryCode ?: unknownCountry
    return "${countryEmoji(ipInfo.countryCode)} $country"
}

internal fun buildCityLine(ipInfo: IpInfo): String = ipInfo.city?.takeIf { it.isNotBlank() } ?: "-"

internal fun primaryVisibleIp(ipInfo: IpInfo): String = ipInfo.ipv4 ?: ipInfo.ip

@Suppress("UNUSED_PARAMETER")
@Composable
internal fun dashboardDnsModeLine(
    ipInfo: IpInfo?,
    secureMode: SecureDnsMode,
): String = dashboardDnsModeLabel(secureMode)

internal fun dashboardDnsModeLabel(
    secureMode: SecureDnsMode,
): String {
    return when (secureMode) {
        SecureDnsMode.DOH -> "DOH"
        SecureDnsMode.DOT -> "DOT"
        SecureDnsMode.PLAIN -> "Default"
    }
}

internal fun secondaryVisibleIp(ipInfo: IpInfo): String? {
    val primary = primaryVisibleIp(ipInfo)
    return ipInfo.ipv6?.takeIf { it != primary }
}

internal fun remoteVisibleDnsServers(ipInfo: IpInfo?): List<String> = visibleDnsServers(
    ipInfo?.remoteDnsServers.orEmpty()
)

private fun visibleDnsServers(addresses: List<String>): List<String> =
    addresses
        .filterNot { it.contains(':') }
        .take(2)
