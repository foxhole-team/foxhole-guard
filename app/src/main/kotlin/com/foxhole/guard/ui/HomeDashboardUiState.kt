package com.foxhole.guard.ui
import androidx.compose.runtime.Immutable
import com.foxhole.core.model.ACTIVE_CONNECTION_STATES
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.DashboardCard
import com.foxhole.core.model.I2pNetworkPhase
import com.foxhole.core.model.I2pPhaseSnapshot
import com.foxhole.core.model.I2pTrafficSnapshot
import com.foxhole.core.model.IpInfo
import com.foxhole.core.model.Profile
import com.foxhole.core.model.Settings
import com.foxhole.core.model.TorTrafficSnapshot
import com.foxhole.core.model.TrafficCardView
import com.foxhole.core.model.TrafficChartPage
import com.foxhole.core.model.TrafficSnapshot
import com.foxhole.core.model.networkUp
import com.foxhole.core.model.torScopeRunnable
import com.foxhole.core.runtime.localGuardModeOrNull
import com.foxhole.guard.R
import com.foxhole.guard.runtime.FoxholeVpnService

@Immutable
internal data class DashboardLayoutUiState(
    val cardOrder: List<DashboardCard> = DashboardCard.entries,
    val trafficMapEnabled: Boolean = true,
    val networkCardEnabled: Boolean = true,
    val trafficCardEnabled: Boolean = true,
    val trafficChartPage: TrafficChartPage = TrafficChartPage.VPN,
    val layoutEditingEnabled: Boolean = false,
) {
    fun visibleCardOrder(order: List<DashboardCard> = cardOrder): List<DashboardCard> =
        order.filter { card ->
            when (card) {
                DashboardCard.STATUS -> true
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
    val profilesLoaded: Boolean = false,
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
    val latencyProbeMethod: com.foxhole.core.model.LatencyProbeMethod =
        Settings().connection.latencyProbeMethod,
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
    val profilesLoaded: Boolean = false,
    val activeProfile: Profile? = null,
    val connection: ConnectionSnapshot = ConnectionSnapshot(),
    val autoConnect: AutoConnectUiState = AutoConnectUiState(),
    val protocolMetricsRefreshing: Boolean = false,
    val reconnectInProgress: Boolean = false,
    val reconnectRequired: Boolean = false,
    val profileReconnectPromptUntilElapsedMs: Long = 0L,
    val torOperation: HomeTorOperationUiState = HomeTorOperationUiState(),
    val profileActionPresentation: HomeDashboardProfileActionPresentation =
        HomeDashboardProfileActionPresentation(
            labelRes = com.foxhole.guard.R.string.refresh,
            enabled = false,
            kind = HomeDashboardProfileActionKind.RESTART,
        ),
) {
    val activeProfileId: Long?
        get() = activeProfile?.id

    fun hasPrimaryConnectionRuntime(): Boolean =
        connection.state in ACTIVE_CONNECTION_STATES &&
            connection.profileId != FoxholeVpnService.LOCAL_GUARD_PROFILE_ID

    fun hasTorOnlyRuntime(): Boolean =
        connection.state in ACTIVE_CONNECTION_STATES &&
            connection.profileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID

    fun hasVpnAndTorBothActive(): Boolean =
        hasPrimaryConnectionRuntime() &&
            !hasTorOnlyRuntime() &&
            connection.torActive

    fun hasTorOnlyEngagement(): Boolean =
        connection.profileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID &&
            connection.state != ConnectionState.IDLE
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
    val torOperation: HomeTorOperationUiState = HomeTorOperationUiState(),
    val autoConnectRunning: Boolean = false,
    val protocolMetricsRefreshing: Boolean = false,
    val protocolModel: HomeDashboardProtocolModel = emptyDashboardProtocolModel(),
    val i2pPhase: I2pPhaseSnapshot = I2pPhaseSnapshot(),
)

internal enum class HomeNetworkIdentityPage {
    VPN,
    TOR,
    I2P,
}

internal fun DashboardNetworkCardUiState.availableIdentityPages(): List<HomeNetworkIdentityPage> =
    buildList {
        add(HomeNetworkIdentityPage.VPN)
        if (connection.hasConfirmedVpnAndTor()) add(HomeNetworkIdentityPage.TOR)
        if (i2pPhase.phase != I2pNetworkPhase.OFFLINE) add(HomeNetworkIdentityPage.I2P)
    }

@Immutable
internal data class DashboardTrafficCardUiState(
    val traffic: TrafficSnapshot = TrafficSnapshot(),
    val trafficModel: HomeDashboardTrafficModel = HomeDashboardTrafficModel(
        totalBytes = 0L,
        totalDays = 0L,
        hasIncomingTraffic = false,
        hasOutgoingTraffic = false,
    ),
    val profilesLoaded: Boolean = false,
    val connectionState: ConnectionState = ConnectionState.IDLE,
    val torTraffic: TorTrafficSnapshot = TorTrafficSnapshot(),
    val i2pTraffic: I2pTrafficSnapshot = I2pTrafficSnapshot(),
    val i2pActive: Boolean = false,
    val vpnAndTorConfirmed: Boolean = false,
    val torOnlyActive: Boolean = false,
    val transitionRunning: Boolean = false,
    val suppressClearConfirm: Boolean = false,
    val trafficCardView: TrafficCardView = TrafficCardView.TEXT,
    val trafficChartMonochrome: Boolean = false,
    val trafficChartCombined: Boolean = true,
)

@Immutable
internal data class DashboardMapCardUiState(
    val enabled: Boolean = true,
    val connectionState: ConnectionState = ConnectionState.IDLE,
    val profilesLoaded: Boolean = false,
    val statusRefreshing: Boolean = false,
    val geoOfflineMode: Boolean = false,
    val torActive: Boolean = false,
    val torOnlyRuntime: Boolean = false,
    val localGuardRuntime: Boolean = false,
    val i2pConnected: Boolean = false,
)

@Immutable
internal data class DashboardDialogUiState(
    val torTransitionPrompt: TorTransitionPrompt? = null,
)

@Immutable
internal data class DashboardFeatureDialogUiState(
    val routeState: HomeRouteUiState = HomeRouteUiState(),
)

internal fun HomeRouteUiState.toDashboardLayoutUiState(): DashboardLayoutUiState =
    DashboardLayoutUiState(
        cardOrder = normalizedDashboardCardOrder(settings.ui.dashboardCardOrder),
        trafficMapEnabled = settings.ui.trafficMapEnabled,
        networkCardEnabled = settings.ui.networkCardEnabled,
        trafficCardEnabled = settings.ui.trafficCardEnabled,
        trafficChartPage = settings.ui.trafficChartPage,
        layoutEditingEnabled = settings.ui.layoutEditingEnabled,
    )

internal fun HomeRouteUiState.toDashboardHeaderUiState(): DashboardHeaderUiState {
    val protocolModel = resolveHomeDashboardProtocolModel(this)
    return DashboardHeaderUiState(
        settings = settings,
        profilesLoaded = profilesLoaded,
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
        profileModel = resolveHomeDashboardProfileModel(this),
        protocolModel = resolveHomeDashboardProtocolModel(this),
    )

internal fun HomeRouteUiState.toDashboardActionsCardUiState(): DashboardActionsCardUiState =
    DashboardActionsCardUiState(
        settings = settings,
        profilesLoaded = profilesLoaded,
        activeProfile = activeProfile,
        connection = connection,
        autoConnect = autoConnect,
        protocolMetricsRefreshing = protocolMetricsRefreshing,
        reconnectInProgress = reconnectInProgress,
        reconnectRequired = reconnectRequired,
        profileReconnectPromptUntilElapsedMs = profileReconnectPromptUntilElapsedMs,
        torOperation = torOperation,
        profileActionPresentation = resolveHomeDashboardProfileActionPresentation(
            activeProfile = activeProfile,
            connection = connection,
            torRouteActive = connection.torActive,
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
        torOperation = torOperation,
        autoConnectRunning = autoConnect.running,
        protocolMetricsRefreshing = protocolMetricsRefreshing,
        protocolModel = resolveHomeDashboardProtocolModel(this),
        i2pPhase = i2pPhase,
    )

internal fun HomeRouteUiState.toDashboardTrafficCardUiState(
    traffic: TrafficSnapshot,
    now: Long,
    torTraffic: TorTrafficSnapshot = TorTrafficSnapshot(),
    i2pTraffic: I2pTrafficSnapshot = I2pTrafficSnapshot(),
): DashboardTrafficCardUiState =
    DashboardTrafficCardUiState(
        traffic = traffic,
        trafficModel = resolveHomeDashboardTrafficModel(
            state = this,
            traffic = traffic,
            now = now,
        ),
        profilesLoaded = profilesLoaded,
        connectionState = connection.state,
        torTraffic = torTraffic,
        i2pTraffic = i2pTraffic,
        i2pActive = i2pPhase.phase != I2pNetworkPhase.OFFLINE,
        vpnAndTorConfirmed = connection.hasConfirmedVpnAndTor(),
        torOnlyActive =
        connection.profileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID &&
            connection.state in ACTIVE_CONNECTION_STATES,
        transitionRunning = homeRouteTransitionRunning() || torOperation.active,
        suppressClearConfirm = settings.ui.suppressTrafficClearConfirm,
        trafficCardView = settings.ui.trafficCardView,
        trafficChartMonochrome = settings.ui.monochromeTorTheme,
        trafficChartCombined = settings.ui.trafficChartCombined,
    )

internal fun ConnectionSnapshot.hasConfirmedVpnAndTor(): Boolean =
    state == ConnectionState.CONNECTED &&
        torActive &&
        profileId != null &&
        profileId != FoxholeVpnService.LOCAL_GUARD_PROFILE_ID &&
        profileId != FoxholeVpnService.TOR_ONLY_PROFILE_ID

internal fun HomeRouteUiState.toDashboardMapCardUiState(): DashboardMapCardUiState =
    DashboardMapCardUiState(
        enabled = settings.ui.trafficMapEnabled,
        connectionState = connection.state,
        profilesLoaded = profilesLoaded,
        statusRefreshing =
        !profilesLoaded ||
            connection.state in ACTIVE_CONNECTION_STATES ||
            settings.localGuardModeOrNull() != null,
        geoOfflineMode = settings.connection.geoOfflineMode,
        torActive = connection.torActive,
        torOnlyRuntime = connection.profileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID,
        localGuardRuntime = connection.profileId == FoxholeVpnService.LOCAL_GUARD_PROFILE_ID,
        i2pConnected = i2pPhase.phase.networkUp,
    )

internal fun DashboardMapCardUiState.toTrafficMapFoxPhase(): TrafficMapFoxPhase =
    when (connectionState) {
        ConnectionState.CONNECTED -> TrafficMapFoxPhase.CONNECTED
        ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> TrafficMapFoxPhase.CONNECTING
        else -> TrafficMapFoxPhase.DISCONNECTED
    }

internal fun DashboardMapCardUiState.trafficMapFoxConnectedLabelRes(): Int =
    when {
        torOnlyRuntime -> R.string.traffic_map_fox_connected_tor
        i2pConnected -> R.string.traffic_map_fox_connected_i2p
        localGuardRuntime -> R.string.traffic_map_fox_connected_firewall
        else -> R.string.traffic_map_fox_connected_vpn
    }

internal fun HomeRouteUiState.toDashboardDialogUiState(): DashboardDialogUiState =
    DashboardDialogUiState(
        torTransitionPrompt = torTransitionPrompt,
    )

internal fun HomeRouteUiState.toDashboardFeatureDialogUiState(): DashboardFeatureDialogUiState =
    DashboardFeatureDialogUiState(
        routeState =
        HomeRouteUiState(
            activeProfile = activeProfile,
            settings = settings,
            connection = connection,
            torIpInfo = torIpInfo,
            reconnectRequired = reconnectRequired,
            reconnectInProgress = reconnectInProgress,
            torOperation = torOperation,
            installedApps = installedApps,
        ),
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
        torOperation = torOperation,
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
        (settings.privacyRoute.enabled || settings.privacyRoute.permitted) &&
        settings.torScopeRunnable()

private fun emptyDashboardProtocolModel(): HomeDashboardProtocolModel =
    HomeDashboardProtocolModel(
        presentation = HomeDashboardProtocolPresentation(
            protocolHint = com.foxhole.core.model.ProtocolHint.UNKNOWN,
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
