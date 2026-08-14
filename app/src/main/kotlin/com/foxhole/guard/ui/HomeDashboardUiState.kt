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
    // Long-press drag reorder for the dashboard cards; off locks the current arrangement.
    val layoutEditingEnabled: Boolean = false,
) {
    fun visibleCardOrder(order: List<DashboardCard> = cardOrder): List<DashboardCard> =
        order.filter { card ->
            when (card) {
                DashboardCard.STATUS -> true
                DashboardCard.TRAFFIC_MAP -> trafficMapEnabled
                // The profile card is always on duty — its hide switch was retired (dev.38).
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
    // An in-flight Tor handshake: the Start/Stop TOR button counts an engagement from the moment
    // it is REQUESTED, not from the moment the circuit completes.
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

    // Tor riding a real VPN runtime is one FoxCore session, not two --
    // but turning Tor off here always uses the settings hot-reload path (see
    // onPrivacyRouteModeSelected/resolveRoutingChangeAction), which
    // reconfigures the live session without disconnecting it. So "Stop Tor" and "Stop VPN" are both
    // safe, independent actions in this state, regardless of whether Tor is tunnelled or beside.
    // A Tor-only runtime is explicitly excluded: it has no VPN to stop separately, and its profile id
    // is a non-LOCAL_GUARD sentinel so hasPrimaryConnectionRuntime() alone would misclassify it as a
    // VPN. The predicate stays correct on its own here rather than relying on the call site's
    // separate torOnlyEngaged guard.
    fun hasVpnAndTorBothActive(): Boolean =
        hasPrimaryConnectionRuntime() &&
            !hasTorOnlyRuntime() &&
            connection.torActive

    /**
     * A Tor-only session is engaged whenever it is anything other than fully idle — connecting,
     * bootstrapping, up, reconnecting, or stalled in [ConnectionState.ERROR]. There is no VPN tunnel
     * in this mode (the profile id is the Tor-only sentinel), so the Stop-Tor action must stay
     * available to cancel a pending/errored Tor that [ACTIVE_CONNECTION_STATES] alone would miss.
     */
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
    // A live i2pd router adds the third identity page (and the traffic widget's I2P page).
    val i2pPhase: I2pPhaseSnapshot = I2pPhaseSnapshot(),
)

/**
 * The identity pager the network and traffic widgets share: one page per live network. VPN is
 * always present; TOR joins while both connections stand confirmed; I2P joins while the i2pd
 * router runs. A left swipe cycles through whatever pages currently exist.
 */
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
    // TOR-lane byte accounting for the widget's TOR page (VPN + Tor running side by side).
    val torTraffic: TorTrafficSnapshot = TorTrafficSnapshot(),
    // I2P-lane byte accounting for the widget's I2P page (a live i2pd router).
    val i2pTraffic: I2pTrafficSnapshot = I2pTrafficSnapshot(),
    // The i2pd router runs: the I2P page exists and the caption counts the network as active.
    val i2pActive: Boolean = false,
    // Both connections confirmed (VPN session CONNECTED and the Tor route engaged in it):
    // the only state where the network card's identity pager — and this card's TOR page — apply.
    val vpnAndTorConfirmed: Boolean = false,
    // A Tor-only runtime: the whole tunnel IS the Tor lane, the plain counters already show it.
    val torOnlyActive: Boolean = false,
    // Connect/reconnect in flight: the caption reads "waiting for connection".
    val transitionRunning: Boolean = false,
    // "Don't show again" opt-out of the swipe-clear confirmation dialog.
    val suppressClearConfirm: Boolean = false,
    // The widget body (text stat blocks vs the live chart) and the chart's paint settings.
    // Chart monochrome follows the single app-wide monochrome toggle (ui.monochromeTorTheme).
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
    // What the OWN runtime actually carries — the fox terminal announces the connected lane
    // (VPN connected / TOR / I2P / firewall) from these, never from passive detection.
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

/**
 * Both connections stand confirmed: a real VPN session is CONNECTED and the engaged Tor route
 * rides it. Local-guard and Tor-only runtimes are excluded — those are single-connection states.
 */
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
        // Same predicate as the status rows: i2pd carries traffic from BUILDING_TUNNELS on, and
        // CONNECTED only ever appears once awaitReady() has run. Comparing to CONNECTED alone made
        // this line unreachable, so the map fox always claimed VPN while I2P was the live lane.
        i2pConnected = i2pPhase.phase.networkUp,
    )

/**
 * The fox terminal's view of the OWN runtime: sleeping until the user starts a connection,
 * the connecting announcement through CONNECTING, and the announce-then-dissolve script on CONNECTED. Passive
 * VPN detection never moves the fox anymore.
 */
internal fun DashboardMapCardUiState.toTrafficMapFoxPhase(): TrafficMapFoxPhase =
    when (connectionState) {
        ConnectionState.CONNECTED -> TrafficMapFoxPhase.CONNECTED
        ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> TrafficMapFoxPhase.CONNECTING
        else -> TrafficMapFoxPhase.DISCONNECTED
    }

/**
 * What the fox types once connected: one line per lane. A Tor-only runtime is TOR; a connected
 * I2P router announces itself (it is the lane the user is waiting on when the map stays empty);
 * the local-guard runtime is the firewall; everything else that carries traffic reads as VPN.
 */
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
        // A merely PERMITTED core (switch on, route not armed yet) already offers Connect TOR:
        // pressing it arms the route exactly like the Tor window's start button does.
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
