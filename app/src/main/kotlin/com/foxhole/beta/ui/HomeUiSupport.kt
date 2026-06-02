package com.foxhole.beta.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
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
import java.net.Inet6Address
import java.net.InetAddress

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
    val selectedProfileId: Long?,
    val runtimeProfileId: Long?,
    val runtimeMode: HomeDashboardRuntimeMode,
    val selectedProfileConnected: Boolean,
    val localGuardActive: Boolean,
    val isSmartDashboardProfile: Boolean,
)

internal enum class HomeDashboardRuntimeMode {
    NONE,
    SELECTED_PROFILE,
    LOCAL_GUARD,
    TOR_ONLY,
    OTHER_PROFILE,
}

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
    val showGeoRowsLoading: Boolean,
    val showConnectionDetailsLoading: Boolean,
    val showRefreshProgress: Boolean,
    val showConnectionStatus: Boolean,
    val titleRes: Int,
)

internal data class HomeNetworkDetailValue(
    val text: String,
    val loading: Boolean,
)

internal data class HomeNetworkDetailLoadingPolicy(
    val country: Boolean,
    val city: Boolean,
    val ip: Boolean,
    val provider: Boolean,
)

internal const val HOME_NETWORK_GEO_ROW_PENDING_LOADING_MS = 5_000L

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
    connectionState !in setOf(
        ConnectionState.CONNECTING,
        ConnectionState.RECONNECTING,
        ConnectionState.ERROR,
    )

internal fun shouldShowForegroundIpRefreshLoading(
    connectionState: ConnectionState,
    currentIpInfo: IpInfo?,
): Boolean =
    connectionState != ConnectionState.CONNECTED &&
        currentIpInfo == null

internal fun foregroundIpRefreshStartDelayMs(
    firstForeground: Boolean,
    connectionState: ConnectionState,
    currentIpInfo: IpInfo?,
): Long =
    if (
        firstForeground &&
        connectionState == ConnectionState.IDLE &&
        currentIpInfo == null
    ) {
        HomeViewModel.FIRST_FOREGROUND_IP_REFRESH_DELAY_MS
    } else {
        0L
    }

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
        connectionState in setOf(ConnectionState.CONNECTING, ConnectionState.RECONNECTING) -> true
        autoConnectRunning -> true
        connectionState == ConnectionState.IDLE -> true
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
    if (connectedProfileId == FoxholeVpnService.LOCAL_GUARD_PROFILE_ID) {
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
        currentState in setOf(ConnectionState.IDLE, ConnectionState.ERROR)

enum class IpInfoRefreshReason {
    MANUAL,
    FOREGROUND,
    POST_CONNECT,
    POST_UPDATE,
    RESTORED_VPN,
    NETWORK_CHANGE,
    TOR_ROUTE,
}

internal fun runtimeReloadIpRefreshReason(
    snapshot: ConnectionSnapshot,
    settings: Settings,
): IpInfoRefreshReason =
    if (shouldUseTorRouteIpRefreshAfterRuntimeReload(snapshot = snapshot, settings = settings)) {
        IpInfoRefreshReason.TOR_ROUTE
    } else {
        IpInfoRefreshReason.POST_UPDATE
    }

internal fun shouldUseTorRouteIpRefreshAfterRuntimeReload(
    snapshot: ConnectionSnapshot,
    settings: Settings,
): Boolean =
    when {
        snapshot.state != ConnectionState.CONNECTED -> false
        snapshot.profileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID -> true
        else -> settings.privacyRoute.enabled && !settings.privacyRoute.bypassVpnTunnel
    }

internal enum class IpInfoRefreshTarget {
    TOR,
    VPN_BOUND,
    UPSTREAM,
    PROXY,
    LOCAL_GUARD,
}

internal fun ipInfoRefreshTargetForSnapshot(snapshot: ConnectionSnapshot): IpInfoRefreshTarget =
    when {
        snapshot.profileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID -> IpInfoRefreshTarget.TOR
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
        IpInfoRefreshReason.MANUAL,
        IpInfoRefreshReason.FOREGROUND,
        IpInfoRefreshReason.POST_CONNECT,
        IpInfoRefreshReason.POST_UPDATE,
        IpInfoRefreshReason.RESTORED_VPN,
        IpInfoRefreshReason.NETWORK_CHANGE,
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
            IpInfoRefreshReason.NETWORK_CHANGE,
            IpInfoRefreshReason.TOR_ROUTE,
        )

@Suppress("FunctionOnlyReturningConstant", "UNUSED_PARAMETER")
internal fun shouldUseFullDashboardIpRefresh(
    reason: IpInfoRefreshReason,
    snapshot: ConnectionSnapshot,
    currentIpInfo: IpInfo?,
): Boolean = false

internal fun shouldShowDashboardIpRefreshLoading(
    reason: IpInfoRefreshReason,
    snapshot: ConnectionSnapshot,
    currentIpInfo: IpInfo?,
): Boolean =
    when (reason) {
        IpInfoRefreshReason.MANUAL -> true
        IpInfoRefreshReason.NETWORK_CHANGE -> false
        IpInfoRefreshReason.POST_CONNECT ->
            shouldShowMissingConnectedRouteIpLoading(snapshot, currentIpInfo) ||
                shouldUseFullDashboardIpRefresh(reason, snapshot, currentIpInfo)
        IpInfoRefreshReason.RESTORED_VPN -> shouldUseFullDashboardIpRefresh(reason, snapshot, currentIpInfo)
        IpInfoRefreshReason.FOREGROUND,
        IpInfoRefreshReason.POST_UPDATE,
        -> false
        IpInfoRefreshReason.TOR_ROUTE -> currentIpInfo == null || !currentIpInfo.hasDashboardLocationDetails()
    }

private fun shouldShowMissingConnectedRouteIpLoading(
    snapshot: ConnectionSnapshot,
    currentIpInfo: IpInfo?,
): Boolean =
    currentIpInfo == null &&
        snapshot.state == ConnectionState.CONNECTED &&
        snapshot.trafficMode == TrafficMode.TUNNEL &&
        snapshot.profileId != null &&
        snapshot.profileId != FoxholeVpnService.LOCAL_GUARD_PROFILE_ID &&
        snapshot.profileId != FoxholeVpnService.TOR_ONLY_PROFILE_ID

internal fun IpInfo.hasDashboardLocationDetails(): Boolean =
    countryName?.isNotBlank() == true &&
        city?.isNotBlank() == true

internal fun IpInfo.hasDashboardProviderDetails(): Boolean =
    isp?.isNotBlank() == true

internal fun shouldShowIpInfoGeoEnrichmentLoading(info: IpInfo): Boolean =
    !info.hasDashboardLocationDetails() ||
        !info.hasDashboardProviderDetails()

internal fun shouldShowIpInfoGeoEnrichmentRefreshLoading(info: IpInfo): Boolean =
    shouldShowIpInfoGeoEnrichmentLoading(info) &&
        primaryVisibleIpOrNull(info) == null

internal fun shouldAutoRefreshIpAfterUpstreamNetworkChange(
    connectionState: ConnectionState,
    previousRevision: Long?,
    currentRevision: Long,
): Boolean =
    connectionState == ConnectionState.CONNECTED &&
        previousRevision != null &&
        currentRevision > previousRevision

internal fun shouldAutoRefreshIpAfterPendingUpstreamNetworkChange(
    connectionState: ConnectionState,
    pendingRevision: Long?,
    currentRevision: Long,
): Boolean =
    connectionState == ConnectionState.CONNECTED &&
        pendingRevision != null &&
        currentRevision >= pendingRevision

internal fun shouldShowAutoConnectAction(activeProfile: Profile?): Boolean =
    activeProfile?.let { profile ->
        MultiProtocolProfileSupport.hasMultipleSupportedOptions(profile) ||
            (
                profile.sourceType == ProfileSourceType.SUBSCRIPTION_URL &&
                    MultiProtocolProfileSupport.smartStartFullScanCandidates(profile).isNotEmpty()
                )
    } == true

internal fun shouldWaitForProfileImportBeforeMissingProfileError(
    activeProfile: Profile?,
    profileImportInProgress: Boolean,
): Boolean = activeProfile == null && profileImportInProgress

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
    val selectedProfileId = state.activeProfile?.id
    val runtimeActive = state.connection.state in ACTIVE_CONNECTION_STATES
    val runtimeProfileId = state.connection.profileId?.takeIf { runtimeActive }
    val runtimeMode =
        when {
            !runtimeActive || runtimeProfileId == null -> HomeDashboardRuntimeMode.NONE
            runtimeProfileId == FoxholeVpnService.LOCAL_GUARD_PROFILE_ID -> HomeDashboardRuntimeMode.LOCAL_GUARD
            runtimeProfileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID -> HomeDashboardRuntimeMode.TOR_ONLY
            runtimeProfileId == selectedProfileId -> HomeDashboardRuntimeMode.SELECTED_PROFILE
            else -> HomeDashboardRuntimeMode.OTHER_PROFILE
        }
    return HomeDashboardProfileModel(
        selectedProfileId = selectedProfileId,
        runtimeProfileId = runtimeProfileId,
        runtimeMode = runtimeMode,
        selectedProfileConnected = runtimeMode == HomeDashboardRuntimeMode.SELECTED_PROFILE,
        localGuardActive = runtimeMode == HomeDashboardRuntimeMode.LOCAL_GUARD,
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
    val explicitIpInfoSkeletonLoading = state.shouldShowExplicitIpInfoSkeletonLoading(dashboardIpInfo)
    val ipInfoLoading =
        state.shouldShowHomeNetworkIpInfoLoading(
            dashboardIpInfo = dashboardIpInfo,
            routeTransitionRunning = routeTransitionRunning,
            deviceInternetAvailable = deviceInternetAvailable,
            explicitIpInfoSkeletonLoading = explicitIpInfoSkeletonLoading,
        )
    val connectionDetailsLoading =
        state.shouldShowHomeNetworkConnectionDetailsLoading(
            showConnectionStatus = showConnectionStatus,
            routeTransitionRunning = routeTransitionRunning,
            explicitIpInfoLoading = explicitIpInfoSkeletonLoading,
            connectionMetricsLoading = state.dashboardConnectionMetricsLoading,
        )
    val geoRowsLoading =
        state.shouldShowStartupHomeNetworkGeoRowsLoading(
            dashboardIpInfo = dashboardIpInfo,
        )
    return HomeDashboardNetworkModel(
        visibleIpInfo = dashboardIpInfo,
        showLoading = ipInfoLoading || connectionDetailsLoading,
        showIpInfoLoading = ipInfoLoading,
        showGeoRowsLoading = geoRowsLoading,
        showConnectionDetailsLoading = connectionDetailsLoading,
        showRefreshProgress = routeTransitionRunning || analysisOnlyRunning || (explicitIpInfoSkeletonLoading && dashboardIpInfo != null),
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
    explicitIpInfoSkeletonLoading: Boolean,
): Boolean {
    val activeRouteNeedsIp =
        dashboardIpInfo == null &&
            routeTransitionRunning
    val manualRefreshNeedsSkeleton =
        explicitIpInfoSkeletonLoading &&
            (deviceInternetAvailable != false || hasDashboardRouteProfile())
    val missingIpCanShowSkeleton =
        activeRouteNeedsIp ||
            shouldShowDashboardNetworkLoading(
                visibleIpInfo = dashboardIpInfo,
                explicitLoading = explicitIpInfoSkeletonLoading,
                connectionState = connection.state,
                autoConnectRunning = routeTransitionRunning,
                deviceInternetAvailable = deviceInternetAvailable,
                appLoaded = profilesLoaded,
            )
    val missingIpNeedsSkeleton = dashboardIpInfo == null && missingIpCanShowSkeleton
    return manualRefreshNeedsSkeleton || missingIpNeedsSkeleton
}

private fun HomeRouteUiState.shouldShowExplicitIpInfoSkeletonLoading(dashboardIpInfo: IpInfo?): Boolean =
    ipInfoLoading &&
        (
            dashboardIpInfo == null ||
                ipInfoRefreshReason == null ||
                ipInfoRefreshReason == IpInfoRefreshReason.MANUAL
            )

private fun HomeRouteUiState.shouldShowHomeNetworkConnectionDetailsLoading(
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
                connectionMetricsLoading ||
                explicitIpInfoLoading
            )

private fun HomeRouteUiState.shouldShowStartupHomeNetworkGeoRowsLoading(dashboardIpInfo: IpInfo?): Boolean =
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

private fun HomeRouteUiState.homeNetworkTitleRes(
    showConnectionStatus: Boolean,
): Int =
    when {
        connection.profileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID -> R.string.home_network_tor_title
        showConnectionStatus -> R.string.home_network_connection_info_title
        else -> R.string.home_network_current_ip_title
    }

private fun HomeRouteUiState.hasRealTunnelConnectionStatus(): Boolean {
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

private fun HomeRouteUiState.dashboardVisibleIpInfo(visibleIpInfo: IpInfo?): IpInfo? {
    val candidate =
        when {
            connection.profileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID -> torIpInfo
            shouldPreferTorRouteIpInfoOnDashboard() -> torIpInfo
            else -> visibleIpInfo
        }
    return candidate?.takeIf { shouldKeepDashboardIpInfo(it) }
}

private fun HomeRouteUiState.shouldPreferTorRouteIpInfoOnDashboard(): Boolean =
    settings.privacyRoute.enabled &&
        torIpInfo?.hasVisiblePublicAddress() == true &&
        (
            torOperation.active ||
                connection.state in ACTIVE_CONNECTION_STATES
            )

private fun HomeRouteUiState.shouldPinVpnIpDuringTorOperation(): Boolean =
    torOperation.active &&
        settings.privacyRoute.enabled &&
        connection.state == ConnectionState.CONNECTED &&
        hasDashboardRouteProfile()

private fun HomeRouteUiState.shouldKeepDashboardIpInfo(info: IpInfo): Boolean =
    when {
        !info.hasVisiblePublicAddress() -> false
        homeAnalysisOnlyRunning() -> !hasDashboardRouteProfile() || shouldKeepActiveDashboardRouteIpInfo(info)
        shouldPinVpnIpDuringTorOperation() -> true
        hasFailedDashboardRoute() -> info.isPublicFreshForRouteTransition(connection.lastChangeAt)
        hasStoppedDashboardRouteRuntime() -> info.isPublicFreshForRouteTransition(connection.lastChangeAt)
        hasActiveDashboardRouteTransition() -> info.isFreshForRouteTransition(connection.lastChangeAt)
        hasActiveDashboardRouteRuntime() -> shouldKeepActiveDashboardRouteIpInfo(info)
        else -> true
    }

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

private fun IpInfo.hasVisiblePublicAddress(): Boolean =
    visibleIpCandidates().any { candidate -> candidate.isPublicInternetAddress() }

private fun IpInfo.visibleIpCandidates(): List<String> =
    listOfNotNull(ipv4, ip, ipv6)
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()

private fun String.isPublicInternetAddress(): Boolean {
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

@Suppress("UNUSED_PARAMETER")
internal fun trafficMapOriginIpInfoCandidate(
    connection: ConnectionSnapshot,
    deviceIpInfo: IpInfo?,
    ipInfo: IpInfo?,
    protocolSearchRunning: Boolean,
): IpInfo? {
    if (deviceIpInfo == null) {
        return null
    }
    if (connection.shouldRejectTrafficMapOriginAsRouteIp(deviceIpInfo = deviceIpInfo, routeIpInfo = ipInfo)) {
        return null
    }
    return deviceIpInfo
}

private fun ConnectionSnapshot.shouldRejectTrafficMapOriginAsRouteIp(
    deviceIpInfo: IpInfo,
    routeIpInfo: IpInfo?,
): Boolean {
    if (
        state == ConnectionState.CONNECTED &&
        trafficMode == TrafficMode.TUNNEL &&
        profileId != FoxholeVpnService.LOCAL_GUARD_PROFILE_ID &&
        deviceIpInfo.fetchedAt >= lastChangeAt &&
        routeIpInfo == null
    ) {
        return true
    }
    val routeIp = routeIpInfo?.let(::primaryVisibleIpOrNull) ?: return false
    val deviceIp = primaryVisibleIpOrNull(deviceIpInfo) ?: return false
    return state == ConnectionState.CONNECTED &&
        trafficMode == TrafficMode.TUNNEL &&
        profileId != FoxholeVpnService.LOCAL_GUARD_PROFILE_ID &&
        deviceIp == routeIp &&
        deviceIpInfo.fetchedAt >= lastChangeAt
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
internal fun rememberDefaultInternetAvailability(enabled: Boolean = true): State<Boolean?> {
    val appContext = LocalContext.current.applicationContext
    val connectivityManager = remember(appContext) { appContext.getSystemService<ConnectivityManager>() }
    val defaultInternetAvailable =
        remember(connectivityManager) {
            mutableStateOf<Boolean?>(null)
        }
    if (!enabled) {
        DisposableEffect(Unit) {
            defaultInternetAvailable.value = null
            onDispose {}
        }
        return defaultInternetAvailable
    }
    DisposableEffect(connectivityManager) {
        val manager =
            connectivityManager ?: return@DisposableEffect onDispose {
            }
        val handler = Handler(Looper.getMainLooper())
        var registered = false
        fun updateAvailability() {
            val resolved = resolveDefaultInternetAvailability(manager)
            if (defaultInternetAvailable.value != resolved) {
                defaultInternetAvailable.value = resolved
            }
        }
        val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    updateAvailability()
                }

                override fun onLost(network: Network) {
                    updateAvailability()
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities,
                ) {
                    updateAvailability()
                }

                override fun onLinkPropertiesChanged(
                    network: Network,
                    linkProperties: LinkProperties,
                ) {
                    updateAvailability()
                }
            }
        val registerCallback =
            Runnable {
                updateAvailability()
                runCatching {
                    manager.registerDefaultNetworkCallback(callback)
                    registered = true
                }
            }
        handler.postDelayed(registerCallback, DASHBOARD_NETWORK_OBSERVER_START_DELAY_MS)
        onDispose {
            handler.removeCallbacks(registerCallback)
            if (registered) {
                runCatching { manager.unregisterNetworkCallback(callback) }
            }
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

private const val DASHBOARD_NETWORK_OBSERVER_START_DELAY_MS = 350L

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
): Boolean {
    if (connectionState != ConnectionState.CONNECTED || activeProfile == null) {
        return false
    }
    return selectedLatencyMs != null || selectedLatencyDown || selectedLatencyUnavailable
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
): String = stringResource(homeConnectionLabelRes(state, reconnectRequired))

internal fun homeConnectionLabelRes(
    state: ConnectionState,
    reconnectRequired: Boolean,
): Int =
    when (homePrimaryAction(state, reconnectRequired)) {
        HomePrimaryAction.START -> R.string.connect
        HomePrimaryAction.STOP -> R.string.disconnect
        HomePrimaryAction.RECONNECT -> R.string.reconnect
    }

@Composable
internal fun homeConnectionLabel(state: HomeRouteUiState): String =
    stringResource(homeConnectionLabelRes(state))

internal fun homeConnectionLabelRes(state: HomeRouteUiState): Int =
    when (homePrimaryAction(state)) {
        HomePrimaryAction.START ->
            if (
                state.activeProfile != null &&
                state.connection.state in ACTIVE_CONNECTION_STATES &&
                state.connection.profileId == FoxholeVpnService.LOCAL_GUARD_PROFILE_ID
            ) {
                R.string.connect_selected_profile
            } else {
                R.string.connect
            }
        HomePrimaryAction.STOP -> R.string.disconnect
        HomePrimaryAction.RECONNECT -> R.string.reconnect
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

internal fun buildCountryLineOrNull(ipInfo: IpInfo): String? =
    formatDashboardCountryLineOrNull(ipInfo)

internal fun dashboardCountryLineForGeoState(
    ipInfo: IpInfo,
    geoRowsLoading: Boolean,
): String? =
    if (geoRowsLoading) {
        buildCountryLineOrNull(ipInfo)
    } else {
        formatDashboardCountryLineOrNull(ipInfo)
    }

internal fun formatCountryLine(
    ipInfo: IpInfo,
    unknownCountry: String,
): String {
    val country =
        ipInfo.countryName?.takeIf(String::isNotBlank)
            ?: ipInfo.countryCode?.takeIf(String::isNotBlank)
            ?: unknownCountry
    return "${countryEmoji(ipInfo.countryCode)} $country"
}

internal fun formatCountryLineOrNull(ipInfo: IpInfo): String? {
    val country =
        ipInfo.countryName?.takeIf(String::isNotBlank)
            ?: ipInfo.countryCode?.takeIf(String::isNotBlank)
            ?: return null
    return "${countryEmoji(ipInfo.countryCode)} $country".trim()
}

internal fun formatDashboardCountryLineOrNull(ipInfo: IpInfo): String? {
    val country = ipInfo.countryName?.takeIf(String::isNotBlank) ?: return null
    return "${countryEmoji(ipInfo.countryCode)} $country".trim()
}

internal fun buildCityLine(ipInfo: IpInfo): String = ipInfo.city?.takeIf { it.isNotBlank() } ?: "-"

internal fun buildCityLineOrNull(ipInfo: IpInfo): String? = ipInfo.city?.takeIf { it.isNotBlank() }

internal fun primaryVisibleIp(ipInfo: IpInfo): String =
    ipInfo.visibleIpCandidates().firstOrNull { candidate -> candidate.isPublicInternetAddress() } ?: "-"

internal fun primaryVisibleIpOrNull(ipInfo: IpInfo): String? =
    ipInfo.visibleIpCandidates().firstOrNull { candidate -> candidate.isPublicInternetAddress() }

internal fun providerLineOrNull(ipInfo: IpInfo): String? = ipInfo.isp?.takeIf { it.isNotBlank() }

internal fun homeNetworkDetailValue(
    value: String?,
    loading: Boolean,
): HomeNetworkDetailValue =
    HomeNetworkDetailValue(
        text = value ?: if (loading) "" else "-",
        loading = loading && value == null,
    )

internal fun homeNetworkGeoRowDetailValue(
    value: String?,
    refreshLoading: Boolean,
    geoRowsLoading: Boolean,
): HomeNetworkDetailValue =
    homeNetworkDetailValue(
        value = value,
        loading = refreshLoading || (geoRowsLoading && value == null),
    )

internal fun homeNetworkDetailLoadingPolicy(
    refreshLoading: Boolean,
    geoRowsLoading: Boolean,
): HomeNetworkDetailLoadingPolicy =
    HomeNetworkDetailLoadingPolicy(
        country = refreshLoading || geoRowsLoading,
        city = refreshLoading || geoRowsLoading,
        ip = refreshLoading,
        provider = refreshLoading || geoRowsLoading,
    )

internal fun shouldShowHomeNetworkFullLoading(
    visibleIpInfo: IpInfo?,
    showIpInfoLoading: Boolean,
): Boolean = showIpInfoLoading && visibleIpInfo == null

internal fun shouldShowHomeNetworkGeoRowsLoading(
    ipInfo: IpInfo?,
    nowMs: Long,
    refreshLoading: Boolean = false,
): Boolean {
    if (ipInfo == null || !shouldShowIpInfoGeoEnrichmentLoading(ipInfo)) {
        return false
    }
    if (refreshLoading) {
        return true
    }
    val ageMs = nowMs - ipInfo.fetchedAt
    return ageMs in 0..HOME_NETWORK_GEO_ROW_PENDING_LOADING_MS
}

internal fun homeNetworkGeoRowsLoadingRemainingMs(
    ipInfo: IpInfo,
    nowMs: Long,
): Long = (ipInfo.fetchedAt + HOME_NETWORK_GEO_ROW_PENDING_LOADING_MS - nowMs).coerceAtLeast(0L)

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
    return ipInfo.visibleIpCandidates()
        .firstOrNull { candidate -> candidate != primary && candidate.isPublicInternetAddress() }
}

internal fun remoteVisibleDnsServers(ipInfo: IpInfo?): List<String> = visibleDnsServers(
    ipInfo?.remoteDnsServers.orEmpty()
)

private fun visibleDnsServers(addresses: List<String>): List<String> =
    addresses
        .filterNot { it.contains(':') }
        .take(2)
