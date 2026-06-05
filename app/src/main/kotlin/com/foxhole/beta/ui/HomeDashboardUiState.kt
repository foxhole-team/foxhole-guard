package com.foxhole.beta.ui

import androidx.compose.runtime.Immutable
import com.foxhole.beta.core.model.ConnectionSnapshot
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.DashboardCard
import com.foxhole.beta.core.model.IpInfo
import com.foxhole.beta.core.model.Profile
import com.foxhole.beta.core.model.Settings
import com.foxhole.beta.core.model.TrafficSnapshot
import com.foxhole.beta.vpn.FoxholeVpnService

@Immutable
internal data class DashboardLayoutUiState(
    val cardOrder: List<DashboardCard> = DashboardCard.entries,
    val trafficMapEnabled: Boolean = true,
    val networkCardEnabled: Boolean = true,
    val trafficCardEnabled: Boolean = true,
) {
    fun visibleCardOrder(order: List<DashboardCard> = cardOrder): List<DashboardCard> =
        order.filter { card ->
            when (card) {
                DashboardCard.TRAFFIC_MAP -> trafficMapEnabled
                DashboardCard.PROFILES -> true
                DashboardCard.ACTIONS -> true
                DashboardCard.NETWORK -> networkCardEnabled
                DashboardCard.TRAFFIC -> trafficCardEnabled
            }
        }
}

@Immutable
internal data class DashboardHeaderUiState(
    val settings: Settings = Settings(),
    val activeProfile: Profile? = null,
    val connection: ConnectionSnapshot = ConnectionSnapshot(),
    val autoConnect: AutoConnectUiState = AutoConnectUiState(),
    val protocolMetricsRefreshing: Boolean = false,
    val protocolMetricsRefreshingOptionId: String? = null,
    val reconnectInProgress: Boolean = false,
    val torOperation: HomeTorOperationUiState = HomeTorOperationUiState(),
    val topStatusState: ConnectionState = ConnectionState.IDLE,
    val topStatusLoading: Boolean = false,
    val protocolMetricsAnalysisState: AutoConnectUiState = AutoConnectUiState(),
    val connectionFeatureIndicators: List<HomeConnectionFeatureIndicator> = emptyList(),
)

@Immutable
internal data class DashboardProfileCardUiState(
    val profilesLoaded: Boolean = false,
    val activeProfile: Profile? = null,
    val activeProfileExcludedOptionIds: Set<String> = emptySet(),
    val connection: ConnectionSnapshot = ConnectionSnapshot(),
    val autoConnect: AutoConnectUiState = AutoConnectUiState(),
    val protocolMetricsRefreshing: Boolean = false,
    val protocolMetricsRefreshingOptionId: String? = null,
    val dashboardConnectionMetricsLoading: Boolean = false,
    val reconnectInProgress: Boolean = false,
    val reconnectRequired: Boolean = false,
    val smartStartRememberedLatenciesByOptionId: Map<String, Long> = emptyMap(),
    val protocolLatenciesByOptionId: Map<String, Long> = emptyMap(),
    val protocolDownOptionIds: Set<String> = emptySet(),
    val protocolLatencyUnavailableOptionIds: Set<String> = emptySet(),
    val protocolServerPingsByOptionId: Map<String, Long> = emptyMap(),
    val protocolServerPingUnavailableOptionIds: Set<String> = emptySet(),
    val protocolMetricsUpdatedAtByOptionId: Map<String, Long> = emptyMap(),
    val recommendedProtocolOptionId: String? = null,
    val recommendedProtocolOptionIds: Set<String> = emptySet(),
    val favoriteProtocolOptionId: String? = null,
    val latencyProbeMethod: com.foxhole.beta.core.model.LatencyProbeMethod =
        Settings().connection.latencyProbeMethod,
    val smartStartControlsEnabled: Boolean = true,
    val profileModel: HomeDashboardProfileModel = HomeDashboardProfileModel(
        selectedProfileId = null,
        runtimeProfileId = null,
        runtimeMode = HomeDashboardRuntimeMode.NONE,
        selectedProfileConnected = false,
        localGuardActive = false,
        isSmartDashboardProfile = false,
    ),
    val protocolModel: HomeDashboardProtocolModel = emptyDashboardProtocolModel(),
)

@Immutable
internal data class DashboardActionsCardUiState(
    val settings: Settings = Settings(),
    val activeProfile: Profile? = null,
    val connection: ConnectionSnapshot = ConnectionSnapshot(),
    val autoConnect: AutoConnectUiState = AutoConnectUiState(),
    val protocolMetricsRefreshing: Boolean = false,
    val reconnectInProgress: Boolean = false,
    val reconnectRequired: Boolean = false,
    val profileReconnectPromptUntilElapsedMs: Long = 0L,
    val smartStartControlsEnabled: Boolean = true,
    val showFirstAnalysisInfo: Boolean = false,
    val profileActionPresentation: HomeDashboardProfileActionPresentation =
        HomeDashboardProfileActionPresentation(
            labelRes = com.foxhole.beta.R.string.refresh,
            enabled = false,
            kind = HomeDashboardProfileActionKind.RESTART,
        ),
) {
    val activeProfileId: Long?
        get() = activeProfile?.id

    fun hasPrimaryConnectionRuntime(): Boolean =
        connection.state in com.foxhole.beta.vpn.ACTIVE_CONNECTION_STATES &&
            connection.profileId != FoxholeVpnService.LOCAL_GUARD_PROFILE_ID

    fun hasTorOnlyRuntime(): Boolean =
        connection.state in com.foxhole.beta.vpn.ACTIVE_CONNECTION_STATES &&
            connection.profileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID
}

@Immutable
internal data class DashboardNetworkCardUiState(
    val settings: Settings = Settings(),
    val profilesLoaded: Boolean = false,
    val activeProfile: Profile? = null,
    val connection: ConnectionSnapshot = ConnectionSnapshot(),
    val ipInfo: IpInfo? = null,
    val deviceIpInfo: IpInfo? = null,
    val torIpInfo: IpInfo? = null,
    val ipInfoLoading: Boolean = false,
    val ipInfoRefreshReason: IpInfoRefreshReason? = null,
    val dashboardConnectionMetricsLoading: Boolean = false,
    val reconnectInProgress: Boolean = false,
    val autoConnectRunning: Boolean = false,
    val protocolMetricsRefreshing: Boolean = false,
    val protocolModel: HomeDashboardProtocolModel = emptyDashboardProtocolModel(),
)

@Immutable
internal data class DashboardTrafficCardUiState(
    val traffic: TrafficSnapshot = TrafficSnapshot(),
    val trafficModel: HomeDashboardTrafficModel = HomeDashboardTrafficModel(
        totalBytes = 0L,
        totalDays = 0L,
        hasIncomingTraffic = false,
        hasOutgoingTraffic = false,
    ),
)

@Immutable
internal data class DashboardMapCardUiState(
    val enabled: Boolean = true,
    val connectionState: ConnectionState = ConnectionState.IDLE,
    val profilesLoaded: Boolean = false,
)

@Immutable
internal data class DashboardDialogUiState(
    val routeState: HomeRouteUiState = HomeRouteUiState(),
)

internal fun HomeRouteUiState.toDashboardLayoutUiState(): DashboardLayoutUiState =
    DashboardLayoutUiState(
        cardOrder = normalizedDashboardCardOrder(settings.ui.dashboardCardOrder),
        trafficMapEnabled = settings.ui.trafficMapEnabled,
        networkCardEnabled = settings.ui.networkCardEnabled,
        trafficCardEnabled = settings.ui.trafficCardEnabled,
    )

internal fun HomeRouteUiState.toDashboardHeaderUiState(): DashboardHeaderUiState {
    val protocolModel = resolveHomeDashboardProtocolModel(this)
    return DashboardHeaderUiState(
        settings = settings,
        activeProfile = activeProfile,
        connection = connection,
        autoConnect = autoConnect,
        protocolMetricsRefreshing = protocolMetricsRefreshing,
        protocolMetricsRefreshingOptionId = protocolMetricsRefreshingOptionId,
        reconnectInProgress = reconnectInProgress,
        torOperation = torOperation,
        topStatusState = homeTopStatusState(this),
        topStatusLoading = shouldShowHomeTopStatusLoading(this),
        protocolMetricsAnalysisState = homeProtocolMetricsAnalysisState(this, protocolModel.presentation),
        connectionFeatureIndicators = homeConnectionFeatureIndicators(this),
    )
}

internal fun HomeRouteUiState.toDashboardProfileCardUiState(): DashboardProfileCardUiState =
    DashboardProfileCardUiState(
        profilesLoaded = profilesLoaded,
        activeProfile = activeProfile,
        activeProfileExcludedOptionIds = activeProfileExcludedOptionIds,
        connection = connection,
        autoConnect = autoConnect,
        protocolMetricsRefreshing = protocolMetricsRefreshing,
        protocolMetricsRefreshingOptionId = protocolMetricsRefreshingOptionId,
        dashboardConnectionMetricsLoading = dashboardConnectionMetricsLoading,
        reconnectInProgress = reconnectInProgress,
        reconnectRequired = reconnectRequired,
        smartStartRememberedLatenciesByOptionId = smartStartRememberedLatenciesByOptionId,
        protocolLatenciesByOptionId = protocolLatenciesByOptionId,
        protocolDownOptionIds = protocolDownOptionIds,
        protocolLatencyUnavailableOptionIds = protocolLatencyUnavailableOptionIds,
        protocolServerPingsByOptionId = protocolServerPingsByOptionId,
        protocolServerPingUnavailableOptionIds = protocolServerPingUnavailableOptionIds,
        protocolMetricsUpdatedAtByOptionId = protocolMetricsUpdatedAtByOptionId,
        recommendedProtocolOptionId = recommendedProtocolOptionId,
        recommendedProtocolOptionIds = recommendedProtocolOptionIds,
        favoriteProtocolOptionId = favoriteProtocolOptionId,
        latencyProbeMethod = settings.connection.latencyProbeMethod,
        smartStartControlsEnabled = isDashboardSmartStartControlsEnabled(settings),
        profileModel = resolveHomeDashboardProfileModel(this),
        protocolModel = resolveHomeDashboardProtocolModel(this),
    )

internal fun HomeRouteUiState.toDashboardActionsCardUiState(): DashboardActionsCardUiState =
    DashboardActionsCardUiState(
        settings = settings,
        activeProfile = activeProfile,
        connection = connection,
        autoConnect = autoConnect,
        protocolMetricsRefreshing = protocolMetricsRefreshing,
        reconnectInProgress = reconnectInProgress,
        reconnectRequired = reconnectRequired,
        profileReconnectPromptUntilElapsedMs = profileReconnectPromptUntilElapsedMs,
        smartStartControlsEnabled = isDashboardSmartStartControlsEnabled(settings),
        showFirstAnalysisInfo = shouldShowSmartStartFirstAnalysisInfo(this),
        profileActionPresentation = resolveHomeDashboardProfileActionPresentation(
            activeProfile = activeProfile,
            connection = connection,
        ),
    )

internal fun HomeRouteUiState.toDashboardNetworkCardUiState(): DashboardNetworkCardUiState =
    DashboardNetworkCardUiState(
        settings = settings,
        profilesLoaded = profilesLoaded,
        activeProfile = activeProfile,
        connection = connection,
        ipInfo = ipInfo,
        deviceIpInfo = deviceIpInfo,
        torIpInfo = torIpInfo,
        ipInfoLoading = ipInfoLoading,
        ipInfoRefreshReason = ipInfoRefreshReason,
        dashboardConnectionMetricsLoading = dashboardConnectionMetricsLoading,
        reconnectInProgress = reconnectInProgress,
        autoConnectRunning = autoConnect.running,
        protocolMetricsRefreshing = protocolMetricsRefreshing,
        protocolModel = resolveHomeDashboardProtocolModel(this),
    )

internal fun HomeRouteUiState.toDashboardTrafficCardUiState(
    traffic: TrafficSnapshot,
    now: Long,
): DashboardTrafficCardUiState =
    DashboardTrafficCardUiState(
        traffic = traffic,
        trafficModel = resolveHomeDashboardTrafficModel(
            state = this,
            traffic = traffic,
            now = now,
        ),
    )

internal fun HomeRouteUiState.toDashboardMapCardUiState(): DashboardMapCardUiState =
    DashboardMapCardUiState(
        enabled = settings.ui.trafficMapEnabled,
        connectionState = connection.state,
        profilesLoaded = profilesLoaded,
    )

internal fun DashboardHeaderUiState.toRouteStateForHeader(): HomeRouteUiState =
    HomeRouteUiState(
        activeProfile = activeProfile,
        settings = settings,
        connection = connection,
        reconnectInProgress = reconnectInProgress,
        torOperation = torOperation,
        protocolMetricsRefreshing = protocolMetricsRefreshing,
        protocolMetricsRefreshingOptionId = protocolMetricsRefreshingOptionId,
        autoConnect = autoConnect,
    )

internal fun DashboardNetworkCardUiState.toRouteStateForNetworkCard(): HomeRouteUiState =
    HomeRouteUiState(
        profilesLoaded = profilesLoaded,
        activeProfile = activeProfile,
        settings = settings,
        connection = connection,
        ipInfo = ipInfo,
        deviceIpInfo = deviceIpInfo,
        torIpInfo = torIpInfo,
        ipInfoLoading = ipInfoLoading,
        ipInfoRefreshReason = ipInfoRefreshReason,
        dashboardConnectionMetricsLoading = dashboardConnectionMetricsLoading,
        reconnectInProgress = reconnectInProgress,
        protocolMetricsRefreshing = protocolMetricsRefreshing,
        autoConnect = AutoConnectUiState(running = autoConnectRunning),
    )

internal fun DashboardActionsCardUiState.primaryAction(): HomePrimaryAction =
    if (autoConnect.running || protocolMetricsRefreshing || reconnectInProgress) {
        HomePrimaryAction.STOP
    } else if (hasPrimaryConnectionRuntime()) {
        homePrimaryAction(connection.state, reconnectRequired)
    } else {
        HomePrimaryAction.START
    }

internal fun DashboardActionsCardUiState.torOnlyStartAvailable(): Boolean =
    activeProfile == null &&
        settings.privacyRoute.enabled &&
        when (settings.privacyRoute.scope) {
            com.foxhole.beta.core.model.PrivacyRouteScope.ALL_APPS -> true
            com.foxhole.beta.core.model.PrivacyRouteScope.SELECTED_APPS ->
                settings.privacyRoute.selectedPackages.any(String::isNotBlank)
        }

private fun emptyDashboardProtocolModel(): HomeDashboardProtocolModel =
    HomeDashboardProtocolModel(
        presentation = HomeDashboardProtocolPresentation(
            protocolHint = com.foxhole.beta.core.model.ProtocolHint.UNKNOWN,
            protocolOptions = emptyList(),
            selectedProtocolOptionId = null,
        ),
        latencyPresentation = HomeDashboardLatencyPresentation(),
        latenciesByOptionId = emptyMap(),
        downOptionIds = emptySet(),
        latencyUnavailableOptionIds = emptySet(),
        showSmartStartLatency = false,
        selectedServerPingMs = null,
        selectedServerPingUnavailable = false,
        connectionDetailsReady = false,
        connectionMetricsLoading = false,
    )
