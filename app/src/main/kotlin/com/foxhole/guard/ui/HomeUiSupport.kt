package com.foxhole.guard.ui
import com.foxhole.core.model.ACTIVE_CONNECTION_STATES
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.IpInfo
import com.foxhole.core.model.Profile
import com.foxhole.core.model.ProfileProtocolOption
import com.foxhole.core.model.ProfileSourceType
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.ProxyInboundSettings
import com.foxhole.core.model.Settings
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.profile.MultiProtocolProfileSupport
import com.foxhole.core.runtime.localGuardModeOrNull
import com.foxhole.core.runtime.network.IpInfoFetchMode
import com.foxhole.guard.R
import com.foxhole.guard.runtime.FoxholeVpnService

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

internal fun isTrafficMapRuntimeAvailable(
    connection: ConnectionSnapshot,
    settings: Settings,
    activeVpnNetworkAvailable: Boolean,
): Boolean {
    val connected = connection.state == ConnectionState.CONNECTED
    val localGuardConnected = connected && connection.profileId == FoxholeVpnService.LOCAL_GUARD_PROFILE_ID
    val tunnelConnected = connected && connection.profileId != FoxholeVpnService.LOCAL_GUARD_PROFILE_ID
    val localGuardAvailable =
        settings.localGuardModeOrNull() != null &&
            (activeVpnNetworkAvailable || localGuardConnected)
    val privacyRouteAvailable =
        settings.privacyRoute.enabled &&
            (
                activeVpnNetworkAvailable ||
                    connected ||
                    settings.privacyRoute.directTorEnabled
                )
    return settings.ui.trafficMapEnabled && (tunnelConnected || localGuardAvailable || privacyRouteAvailable)
}

internal data class HomeDashboardNetworkModel(
    val isTorNetwork: Boolean = false,
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

internal enum class HomeDashboardProfileActionKind {
    REFRESH_SUBSCRIPTION,
    REFRESH_AND_RESTART_SUBSCRIPTION,
    RESTART,
}

internal data class HomeDashboardProfileActionPresentation(
    val labelRes: Int,
    val enabled: Boolean,
    val kind: HomeDashboardProfileActionKind,
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
}

internal fun shouldAutoRefreshIpOnForeground(connectionState: ConnectionState): Boolean =
    connectionState in setOf(
        ConnectionState.IDLE,
        ConnectionState.CONNECTED,
        ConnectionState.ERROR,
    )

internal fun shouldShowIpInfoLoading(
    explicitLoading: Boolean,
): Boolean = explicitLoading

internal fun shouldShowPendingNetworkLoading(
    visibleIpInfo: IpInfo?,
    explicitLoading: Boolean,
    autoConnectRunning: Boolean,
    deviceInternetAvailable: Boolean?,
): Boolean =
    when {
        visibleIpInfo != null -> false
        explicitLoading -> true
        deviceInternetAvailable == false -> false
        autoConnectRunning -> true
        else -> false
    }

internal fun shouldShowDashboardNetworkLoading(
    visibleIpInfo: IpInfo?,
    explicitLoading: Boolean,
    autoConnectRunning: Boolean,
    deviceInternetAvailable: Boolean?,
): Boolean =
    shouldShowIpInfoLoading(
        explicitLoading = explicitLoading,
    ) ||
        shouldShowPendingNetworkLoading(
            visibleIpInfo = visibleIpInfo,
            explicitLoading = explicitLoading,
            autoConnectRunning = autoConnectRunning,
            deviceInternetAvailable = deviceInternetAvailable,
        )

internal fun shouldShowTrafficMapLegendLoading(
    connectionState: ConnectionState,
    explicitLoading: Boolean = false,
): Boolean =
    explicitLoading ||
        connectionState in setOf(ConnectionState.CONNECTING, ConnectionState.RECONNECTING)

internal fun runningOptionIdFromProtocolHint(
    activeProfile: Profile?,
    connection: ConnectionSnapshot,
): String? {
    val hint = connection.protocolHint ?: return null
    val matches = activeProfile?.protocolOptions?.filter { option -> option.protocolHint == hint }.orEmpty()
    return matches.singleOrNull()?.id
}

internal fun isProfileReconnectRequired(
    activeProfile: Profile?,
    connection: ConnectionSnapshot,
): Boolean {
    val profileId = activeProfile?.id
    val connectedProfileId = connection.profileId
    val selectedOptionId = activeProfile?.selectedProtocolOptionId?.takeIf(String::isNotBlank)
    val connectedOptionId =
        connection.protocolOptionId?.takeIf(String::isNotBlank)
            ?: runningOptionIdFromProtocolHint(activeProfile, connection)
    val optionMismatch =
        (selectedOptionId != null || connectedOptionId != null) &&
            selectedOptionId != connectedOptionId
    val protocolMismatch =
        connection.protocolHint?.let { connectedProtocol -> activeProfile?.protocolHint != connectedProtocol } == true
    return when {
        activeProfile == null -> false
        profileId == null -> false
        connectedProfileId == null -> false
        connection.state !in setOf(ConnectionState.CONNECTING, ConnectionState.CONNECTED, ConnectionState.RECONNECTING) -> false
        connectedProfileId == FoxholeVpnService.LOCAL_GUARD_PROFILE_ID -> false
        profileId != connectedProfileId -> true
        optionMismatch -> true
        else -> protocolMismatch
    }
}

internal fun shouldAutoRefreshIpAfterConnect(
    previousState: ConnectionState?,
    currentState: ConnectionState,
): Boolean = previousState != ConnectionState.CONNECTED && currentState == ConnectionState.CONNECTED

internal fun shouldAutoRefreshIpAfterDisconnect(
    previousState: ConnectionState?,
    currentState: ConnectionState,
): Boolean =
    (previousState in ACTIVE_CONNECTION_STATES || previousState == ConnectionState.DISCONNECTING) &&
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
        else -> settings.privacyRoute.enabled
    }

internal fun shouldRefreshTorExitAfterConnect(
    reason: IpInfoRefreshReason,
    snapshot: ConnectionSnapshot,
    settings: Settings,
): Boolean =
    (
        reason == IpInfoRefreshReason.POST_CONNECT ||
            reason == IpInfoRefreshReason.RESTORED_VPN ||
            reason == IpInfoRefreshReason.MANUAL
        ) &&
        snapshot.state == ConnectionState.CONNECTED &&
        snapshot.profileId != FoxholeVpnService.TOR_ONLY_PROFILE_ID &&
        snapshot.profileId != FoxholeVpnService.LOCAL_GUARD_PROFILE_ID &&
        settings.privacyRoute.enabled

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
        snapshot.state in ACTIVE_CONNECTION_STATES &&
            snapshot.trafficMode == TrafficMode.TUNNEL &&
            snapshot.profileId != null &&
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
            IpInfoRefreshReason.TOR_ROUTE,
            IpInfoRefreshReason.NETWORK_CHANGE,
        )

internal fun shouldShowDashboardIpRefreshLoading(
    reason: IpInfoRefreshReason,
    snapshot: ConnectionSnapshot,
    currentIpInfo: IpInfo?,
): Boolean =
    when (reason) {
        IpInfoRefreshReason.MANUAL -> true
        IpInfoRefreshReason.NETWORK_CHANGE -> false
        IpInfoRefreshReason.POST_CONNECT ->
            shouldShowStaleConnectedRouteIpLoading(snapshot, currentIpInfo)
        IpInfoRefreshReason.RESTORED_VPN,
        IpInfoRefreshReason.FOREGROUND,
        IpInfoRefreshReason.POST_UPDATE,
        -> false
        IpInfoRefreshReason.TOR_ROUTE -> currentIpInfo == null || !currentIpInfo.hasTorRouteLocationDetails()
    }

private fun shouldShowStaleConnectedRouteIpLoading(
    snapshot: ConnectionSnapshot,
    currentIpInfo: IpInfo?,
): Boolean =
    snapshot.state == ConnectionState.CONNECTED &&
        snapshot.trafficMode == TrafficMode.TUNNEL &&
        snapshot.profileId != null &&
        snapshot.profileId != FoxholeVpnService.LOCAL_GUARD_PROFILE_ID &&
        snapshot.profileId != FoxholeVpnService.TOR_ONLY_PROFILE_ID &&
        (currentIpInfo == null || currentIpInfo.fetchedAt < snapshot.lastChangeAt)

internal fun IpInfo.hasDashboardLocationDetails(): Boolean =
    countryName?.isNotBlank() == true &&
        city?.isNotBlank() == true

internal fun IpInfo.hasTorRouteLocationDetails(): Boolean = countryName?.isNotBlank() == true

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

internal fun shouldWaitForProfileImportBeforeMissingProfileError(
    activeProfile: Profile?,
    profileImportInProgress: Boolean,
): Boolean = activeProfile == null && profileImportInProgress

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

internal fun resolveHomeDashboardProfileActionPresentation(
    activeProfile: Profile?,
    connection: ConnectionSnapshot,
    torRouteActive: Boolean = false,
): HomeDashboardProfileActionPresentation {
    val connectedToActiveProfile =
        activeProfile != null &&
            connection.state == ConnectionState.CONNECTED &&
            connection.profileId == activeProfile.id
    val subscriptionProfile = activeProfile?.sourceType == ProfileSourceType.SUBSCRIPTION_URL
    return when {
        connectedToActiveProfile && torRouteActive ->
            HomeDashboardProfileActionPresentation(
                labelRes = R.string.reconnect,
                enabled = true,
                kind = HomeDashboardProfileActionKind.RESTART,
            )
        connectedToActiveProfile && subscriptionProfile ->
            HomeDashboardProfileActionPresentation(
                labelRes = R.string.reconnect,
                enabled = true,
                kind = HomeDashboardProfileActionKind.REFRESH_AND_RESTART_SUBSCRIPTION,
            )
        connectedToActiveProfile ->
            HomeDashboardProfileActionPresentation(
                labelRes = R.string.reconnect,
                enabled = true,
                kind = HomeDashboardProfileActionKind.RESTART,
            )
        subscriptionProfile ->
            HomeDashboardProfileActionPresentation(
                labelRes = R.string.refresh,
                enabled = true,
                kind = HomeDashboardProfileActionKind.REFRESH_SUBSCRIPTION,
            )
        else ->
            HomeDashboardProfileActionPresentation(
                labelRes = R.string.reconnect,
                enabled = false,
                kind = HomeDashboardProfileActionKind.RESTART,
            )
    }
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
        isTorNetwork = state.homeNetworkTitleRes(showConnectionStatus) == R.string.home_network_tor_title,
    )
}
