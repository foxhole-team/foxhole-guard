package com.foxhole.guard.ui

import android.os.Trace
import com.foxhole.core.model.I2pTrafficStats
import com.foxhole.core.model.InstalledAppOption
import com.foxhole.core.model.IpInfo
import com.foxhole.core.model.Settings
import com.foxhole.core.model.TorTrafficStats
import com.foxhole.core.model.TrafficMapUiState
import com.foxhole.core.model.TrafficSnapshot
import com.foxhole.guard.FoxholeHomeDependencies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext

private val settingsRouteStateSharing = SharingStarted.Lazily
private val settingsProfileRouteStateSharing = SharingStarted.Eagerly

// The cheap dashboard projections share EAGERLY. While another section is shown the dashboard
// pane's lifecycle is capped at CREATED, so WhileSubscribed flows stopped, cached the pre-change
// value and replayed one stale frame on return — a settings toggle visibly re-applied AFTER
// navigating back instead of being in place. These recompute only on settings/connection events;
// the 1 Hz traffic state and the map state keep the lazy policy (their upstreams tick constantly)
// and are refreshed by an immediate sample on wake instead.
private val dashboardProjectionSharing = SharingStarted.Eagerly

internal data class HomeProtocolMetricsStateSources(
    val serverPings: StateFlow<Map<ProfileOptionLatencyKey, ProfileOptionServerPingState>>,
    val tunnelPings: StateFlow<Map<ProfileOptionLatencyKey, ProfileOptionTunnelPingState>>,
    val updatedAt: StateFlow<Map<ProfileOptionLatencyKey, Long>>,
    val downOptionIds: StateFlow<Set<ProfileOptionLatencyKey>>,
    val refreshingProfileIds: StateFlow<Set<Long>>,
    val refreshingOptionIdByProfileId: StateFlow<Map<Long, String>>,
    val recommendation: StateFlow<ProtocolRecommendationState?>,
)

internal data class HomeRouteStateSources(
    val coreUiState: StateFlow<HomeUiState>,
    val uiState: StateFlow<HomeUiState>,
    val profileStreams: Flow<HomeProfileStreams>,
    val statisticsUiState: StateFlow<HomeUiState>,
    val dashboardTraffic: StateFlow<TrafficSnapshot>,
    val autoConnect: StateFlow<AutoConnectUiState>,
    val profileOptionLatencies: StateFlow<Map<ProfileOptionLatencyKey, Long>>,
    val profileOptionLatencyUnavailable: StateFlow<Set<ProfileOptionLatencyKey>>,
    val dnsFilterRefreshInProgress: StateFlow<Boolean>,
    val appTrafficUsageAccessGranted: StateFlow<Boolean>,
    val statisticsVisible: StateFlow<Boolean>,
    val appPickerQuery: StateFlow<String>,
)

internal data class HomeTrafficMapStateSources(
    val torIpInfo: StateFlow<IpInfo?>,
    val benchmarkTrafficMapUiState: StateFlow<TrafficMapUiState?>,
)

internal class HomeRouteStateProducer(
    private val scope: CoroutineScope,
    private val container: FoxholeHomeDependencies,
    private val initialSettings: Settings,
    private val routeSources: HomeRouteStateSources,
    private val protocolMetricsSources: HomeProtocolMetricsStateSources,
    private val trafficMapSources: HomeTrafficMapStateSources,
    private val benchmarkTrafficMapEnabled: Boolean,
    private val currentNetworkFingerprintKey: () -> String?,
) {
    // The map's own sharing scope. `scope` is the ViewModel's (Dispatchers.Main.immediate), and a
    // stateIn STARTED there publishes every value on the main thread even when the computation
    // rides flowOn(Default). While the dashboard is live, Compose's AndroidUiDispatcher trampolines
    // its snapshot-apply work in a single looper message and can hold the thread for seconds — the
    // map's publications then simply never got a turn, so a connected tunnel kept showing standby
    // (caught on device: main RUNNABLE inside advanceGlobalSnapshot while the map flow had already
    // computed isAvailable=true). Sharing the map on Default keeps the value moving; Compose reads
    // it through collectAsStateWithLifecycle on main as before.
    private val mapSharingScope = scope + Dispatchers.Default

    private val protocolMetricsPingState =
        combine(
            protocolMetricsSources.serverPings,
            protocolMetricsSources.tunnelPings,
        ) { serverPings, tunnelPings ->
            ProtocolMetricsPingUiState(
                serverPings = serverPings,
                tunnelPings = tunnelPings,
            )
        }

    private val protocolMetricsRefreshingState =
        combine(
            protocolMetricsSources.refreshingProfileIds,
            protocolMetricsSources.refreshingOptionIdByProfileId,
        ) { profileIds, optionIds ->
            ProtocolMetricsRefreshingUiState(
                profileIds = profileIds,
                optionIdByProfileId = optionIds,
            )
        }

    private val protocolMetricsState =
        combine(
            protocolMetricsPingState,
            protocolMetricsSources.updatedAt,
            protocolMetricsSources.downOptionIds,
            protocolMetricsRefreshingState,
            protocolMetricsSources.recommendation,
        ) { pingState, updatedAt, downOptionIds, refreshingState, recommendation ->
            ProtocolMetricsUiState(
                serverPings = pingState.serverPings,
                tunnelPings = pingState.tunnelPings,
                updatedAt = updatedAt,
                downOptionIds = downOptionIds,
                refreshingProfileIds = refreshingState.profileIds,
                refreshingOptionIdByProfileId = refreshingState.optionIdByProfileId,
                recommendation = recommendation,
            )
        }

    val homeRouteState: StateFlow<HomeRouteUiState> =
        combine(
            routeSources.coreUiState,
            routeSources.autoConnect,
            routeSources.profileOptionLatencies,
            routeSources.profileOptionLatencyUnavailable,
            protocolMetricsState,
        ) { state, autoConnect, profileOptionLatencies, profileOptionLatencyUnavailable, protocolMetrics ->
            buildHomeRouteUiState(
                state = state,
                autoConnect = autoConnect,
                profileOptionLatencies = profileOptionLatencies,
                profileOptionLatencyUnavailable = profileOptionLatencyUnavailable,
                protocolMetrics = protocolMetrics,
                currentNetworkFingerprintKey = currentNetworkFingerprintKey(),
            )
        }
            // Folded in after the five typed sources rather than as a sixth: the LAN proxy state is
            // published by the runtime, not derived from any of them, and the typed combine is full.
            .combine(container.connectionController.lanProxyStatus) { state, lanProxy ->
                state.copy(lanProxy = lanProxy)
            }
            .combine(container.settingsRepository.hydrated) { state, hydrated ->
                state.copy(settingsHydrated = hydrated)
            }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
            .stateIn(
                scope,
                dashboardProjectionSharing,
                HomeRouteUiState(
                    settings = initialSettings,
                    settingsHydrated = container.settingsRepository.hydrated.value,
                ),
            )

    val dashboardLayoutState: StateFlow<DashboardLayoutUiState> =
        homeRouteState
            .map(HomeRouteUiState::toDashboardLayoutUiState)
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
            .stateIn(
                scope,
                dashboardProjectionSharing,
                HomeRouteUiState(settings = initialSettings).toDashboardLayoutUiState(),
            )

    val dashboardHeaderState: StateFlow<DashboardHeaderUiState> =
        homeRouteState
            .map(HomeRouteUiState::toDashboardHeaderUiState)
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
            .stateIn(
                scope,
                dashboardProjectionSharing,
                HomeRouteUiState(settings = initialSettings).toDashboardHeaderUiState(),
            )

    val dashboardProfileCardState: StateFlow<DashboardProfileCardUiState> =
        homeRouteState
            .map(HomeRouteUiState::toDashboardProfileCardUiState)
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
            .stateIn(
                scope,
                dashboardProjectionSharing,
                HomeRouteUiState(settings = initialSettings).toDashboardProfileCardUiState(),
            )

    val dashboardActionsCardState: StateFlow<DashboardActionsCardUiState> =
        homeRouteState
            .map(HomeRouteUiState::toDashboardActionsCardUiState)
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
            .stateIn(
                scope,
                dashboardProjectionSharing,
                HomeRouteUiState(settings = initialSettings).toDashboardActionsCardUiState(),
            )

    val dashboardNetworkCardState: StateFlow<DashboardNetworkCardUiState> =
        homeRouteState
            .map(HomeRouteUiState::toDashboardNetworkCardUiState)
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
            .stateIn(
                scope,
                dashboardProjectionSharing,
                HomeRouteUiState(settings = initialSettings).toDashboardNetworkCardUiState(),
            )

    val dashboardTrafficCardState: StateFlow<DashboardTrafficCardUiState> =
        combine(
            homeRouteState,
            routeSources.dashboardTraffic,
            TorTrafficStats.snapshot,
            I2pTrafficStats.snapshot,
        ) { state, traffic, torTraffic, i2pTraffic ->
            state.toDashboardTrafficCardUiState(
                traffic = traffic,
                now = System.currentTimeMillis(),
                torTraffic = torTraffic,
                i2pTraffic = i2pTraffic,
            )
        }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(5_000),
                HomeRouteUiState(settings = initialSettings).toDashboardTrafficCardUiState(
                    traffic = routeSources.dashboardTraffic.value,
                    now = System.currentTimeMillis(),
                ),
            )

    val dashboardMapCardState: StateFlow<DashboardMapCardUiState> =
        homeRouteState
            .map(HomeRouteUiState::toDashboardMapCardUiState)
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(5_000),
                HomeRouteUiState(settings = initialSettings).toDashboardMapCardUiState(),
            )

    val dashboardDialogState: StateFlow<DashboardDialogUiState> =
        homeRouteState
            .map(HomeRouteUiState::toDashboardDialogUiState)
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
            .stateIn(
                scope,
                dashboardProjectionSharing,
                HomeRouteUiState(settings = initialSettings).toDashboardDialogUiState(),
            )

    val dashboardFeatureDialogState: StateFlow<DashboardFeatureDialogUiState> =
        homeRouteState
            .map(HomeRouteUiState::toDashboardFeatureDialogUiState)
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
            .stateIn(
                scope,
                dashboardProjectionSharing,
                HomeRouteUiState(settings = initialSettings).toDashboardFeatureDialogUiState(),
            )

    // The network revision is in here for its edges, not its value: hasActiveVpnNetwork() is a poll
    // of the connectivity snapshot, so without it the map only re-asked "is our tunnel up?" when
    // the connection snapshot or the settings happened to emit — and the VPN network routinely
    // appears AFTER the CONNECTED snapshot, leaving the card on standby until the next unrelated
    // change woke the flow.
    private val trafficMapRuntimeAvailable =
        combine(
            container.connectionController.snapshot,
            container.settingsRepository.settings,
            container.connectionController.networkRevision,
        ) { connection, settings, _ ->
            isTrafficMapRuntimeAvailable(
                connection = connection,
                settings = settings,
                activeVpnNetworkAvailable = container.connectionController.hasActiveVpnNetwork(),
            )
        }.distinctUntilChanged()

    private val initialTrafficMapOriginIpInfo =
        trafficMapOriginIpInfoCandidate(
            connection = container.connectionController.snapshot.value,
            deviceIpInfo = container.connectionController.deviceIpInfo.value,
            ipInfo = container.connectionController.ipInfo.value,
        )

    private val trafficMapOriginIpInfo: StateFlow<IpInfo?> =
        combine(
            container.connectionController.snapshot,
            container.connectionController.deviceIpInfo,
            container.connectionController.ipInfo,
            routeSources.autoConnect,
            protocolMetricsSources.refreshingProfileIds,
        ) { connection, deviceIpInfo, ipInfo, autoConnect, protocolMetricsRefreshingProfileIds ->
            val protocolSearchRunning = autoConnect.running || protocolMetricsRefreshingProfileIds.isNotEmpty()
            TrafficMapOriginSelection(
                connection = connection,
                routeIpInfo = ipInfo,
                candidate = trafficMapOriginIpInfoCandidate(
                    connection = connection,
                    deviceIpInfo = deviceIpInfo,
                    ipInfo = ipInfo,
                ),
                protocolSearchRunning = protocolSearchRunning,
            )
        }
            .runningFold(initialTrafficMapOriginIpInfo) { previous, next ->
                when {
                    next.protocolSearchRunning -> previous
                    shouldRetainTrafficMapOriginIpInfo(
                        connection = next.connection,
                        previousOriginIpInfo = previous,
                        candidateOriginIpInfo = next.candidate,
                        routeIpInfo = next.routeIpInfo,
                    ) -> previous
                    else -> next.candidate
                }
            }
            .distinctUntilChanged()
            .stateIn(
                scope,
                SharingStarted.Eagerly,
                initialTrafficMapOriginIpInfo,
            )

    private val trafficMapRouteIpInfo =
        combine(
            container.connectionController.snapshot,
            container.connectionController.ipInfo,
        ) { connection, ipInfo ->
            trafficMapRouteIpInfoCandidate(
                connection = connection,
                ipInfo = ipInfo,
            )
        }
            .distinctUntilChanged()

    private val trafficMapTorIpInfo =
        combine(
            container.connectionController.snapshot,
            trafficMapSources.torIpInfo,
        ) { connection, torIpInfo ->
            trafficMapTorIpInfoCandidate(
                connection = connection,
                torIpInfo = torIpInfo,
            )
        }
            .distinctUntilChanged()

    private val liveTrafficMapUiState =
        container.trafficMapRepository.trafficMapState(
            scope = mapSharingScope,
            originIpInfo = trafficMapOriginIpInfo,
            routeIpInfo = trafficMapRouteIpInfo,
            torIpInfo = trafficMapTorIpInfo,
            runtimeAvailable = trafficMapRuntimeAvailable,
            recentTrafficWindows = container.anomalyRepository.recentTrafficWindows,
            recentNetworkActivityEvents = container.anomalyRepository.recentNetworkActivityEvents,
            // Always raw: the data only exists while the user's own journal switch is on, so a
            // second privacy gate on top of it was retired with the sanitize setting.
            showPrivateNetworkDetails = flowOf(true),
            historyCutoffMs =
            container.settingsRepository.settings
                .map { settings -> settings.ui.trafficMapHistoryClearedAtMs }
                .distinctUntilChanged(),
        )

    @OptIn(FlowPreview::class)
    val trafficMapUiState: StateFlow<TrafficMapUiState> =
        combine(
            liveTrafficMapUiState,
            routeSources.coreUiState,
            trafficMapSources.benchmarkTrafficMapUiState,
            protocolMetricsSources.serverPings,
            protocolMetricsSources.tunnelPings,
        ) { liveState, coreState, benchmarkState, serverPings, tunnelPings ->
            val state =
                if (benchmarkTrafficMapEnabled) {
                    benchmarkState ?: liveState
                } else {
                    liveState
                }
            state.withTrafficMapRouteContext(coreState, serverPings, tunnelPings)
        }
            .distinctUntilChanged()
            // Cap the map's update rate: traffic/ping samples tick sub-second, but redrawing the
            // Canvas-backed map that often janks scrolling. ~1 Hz is plenty for a map and keeps the
            // (uniquely heavy) map card from recomposing mid-scroll.
            .sample(TRAFFIC_MAP_REFRESH_INTERVAL_MS)
            // Off the main thread: enabling the firewall over a live VPN flips
            // map+statistics+activity logging on in one burst, and running this route-context
            // merge on Main used to starve input dispatching for >5s.
            .flowOn(Dispatchers.Default)
            .stateIn(
                mapSharingScope,
                // Loading and simplifying the country registry is deliberately demand-driven.
                // Home/settings don't render map data, while the map and statistics routes both
                // subscribe here and retain the last value across their short navigation gaps.
                SharingStarted.WhileSubscribed(5_000),
                TrafficMapUiState(),
            )

    val profilesRouteState: StateFlow<ProfilesRouteUiState> =
        combine(
            routeSources.coreUiState,
            routeSources.autoConnect,
            routeSources.profileOptionLatencies,
            routeSources.profileOptionLatencyUnavailable,
            protocolMetricsState,
        ) { state, autoConnect, profileOptionLatencies, profileOptionLatencyUnavailable, protocolMetrics ->
            buildProfilesRouteUiState(
                state = state,
                autoConnect = autoConnect,
                protocolMetrics = protocolMetrics,
                profileOptionLatencies = profileOptionLatencies,
                profileOptionLatencyUnavailable = profileOptionLatencyUnavailable,
                networkFingerprintKey = currentNetworkFingerprintKey(),
            )
        }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(5_000),
                ProfilesRouteUiState(),
            )

    val settingsHomeNavState: StateFlow<SettingsHomeNavUiState> =
        container.settingsRepository.settings
            .map { settings -> settings.toSettingsHomeNavUiState() }
            .distinctUntilChanged()
            .stateIn(
                scope,
                settingsRouteStateSharing,
                initialSettings.toSettingsHomeNavUiState(),
            )

    val settingsRouteState: StateFlow<SettingsRouteUiState> =
        combine(
            routeSources.coreUiState,
            routeSources.dnsFilterRefreshInProgress,
        ) { state, dnsFilterRefreshInProgress ->
            state.toSettingsRouteUiState(
                dnsFilterRefreshInProgress = dnsFilterRefreshInProgress,
                includeLiveTraffic = false,
                includeInstalledApps = false,
                includeActivityState = false,
            )
        }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
            .stateIn(
                scope,
                settingsRouteStateSharing,
                HomeUiState(settings = initialSettings).toSettingsRouteUiState(
                    includeLiveTraffic = false,
                    includeInstalledApps = false,
                    includeActivityState = false,
                ),
            )

    val settingsProfileRouteState: StateFlow<SettingsRouteUiState> =
        routeSources.profileStreams
            .map { streams ->
                HomeUiState(
                    profiles = streams.profiles,
                    activeProfile = streams.activeProfile,
                    settings = streams.settings,
                ).toSettingsRouteUiState(
                    includeLiveTraffic = false,
                    includeInstalledApps = false,
                    includeActivityState = false,
                )
            }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
            .stateIn(
                scope,
                settingsProfileRouteStateSharing,
                HomeUiState(settings = initialSettings).toSettingsRouteUiState(
                    includeLiveTraffic = false,
                    includeInstalledApps = false,
                    includeActivityState = false,
                ),
            )

    val trafficSettingsRouteState: StateFlow<TrafficSettingsRouteUiState> =
        routeSources.profileStreams
            .map { streams -> streams.toTrafficSettingsRouteUiState() }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
            .stateIn(
                scope,
                settingsProfileRouteStateSharing,
                HomeProfileStreams(
                    profiles = emptyList(),
                    activeProfile = null,
                    settings = initialSettings,
                ).toTrafficSettingsRouteUiState(),
            )

    val statisticsRouteState: StateFlow<StatisticsRouteUiState> =
        run {
            // The dashboard build only reads the live destination points — the rest of the ~1 Hz
            // traffic-map state (routes, animation phases) must not rebuild the whole statistics
            // snapshot on every tick.
            val statisticsMapDestinations =
                trafficMapUiState
                    .map { mapState -> mapState.destinations }
                    .distinctUntilChanged()
            // Last built snapshot survives leaving the route so re-entry paints instantly;
            // retention is bounded (see retainedStatisticsDashboard).
            var lastDashboard: StatisticsDashboardUiState? = null
            combine(
                routeSources.statisticsUiState,
                statisticsMapDestinations,
                routeSources.appTrafficUsageAccessGranted,
                routeSources.statisticsVisible,
                container.anomalyRepository.recentProtocolMetricEvents,
            ) { state, liveDestinations, usageAccessGranted, statisticsVisible, protocolMetricEvents ->
                val routeState =
                    state.toStatisticsRouteUiState()
                        .copy(protocolMetricEvents = protocolMetricEvents)
                if (statisticsVisible) {
                    val dashboard =
                        buildStatisticsDashboardUiState(
                            state = routeState,
                            liveDestinations = liveDestinations,
                            // Bucketed so equal inputs build EQUAL snapshots — the
                            // distinctUntilChanged below dedupes them and the UI stops
                            // re-emitting once a second on the traffic tick.
                            nowMs = System.currentTimeMillis().toStatisticsUiNowBucket(),
                            usageAccessGranted = usageAccessGranted,
                        ).copy(ready = true)
                    lastDashboard = dashboard
                    routeState.copy(statisticsDashboard = dashboard)
                } else {
                    routeState.copy(
                        statisticsDashboard =
                        retainedStatisticsDashboard(
                            last = lastDashboard,
                            nowMs = System.currentTimeMillis(),
                        ),
                    )
                }
            }
                // Hydration rides as a separate late combine so the heavy dashboard build above
                // never re-runs just because the settings store finished loading.
                .combine(container.settingsRepository.hydrated) { routeState, hydrated ->
                    routeState.copy(settingsHydrated = hydrated)
                }
                // Same reason for the persisted I2P counters: the recorder writes on its own
                // cadence and must not restart the dashboard aggregation, so the store rides its
                // own late combine. TOR needs no such field — its bytes come from the persisted
                // per-app windows, which the summary can actually slice by the picked window.
                .combine(container.i2pTrafficRepository.history) { routeState, i2pTrafficHistory ->
                    routeState.copy(i2pTrafficHistory = i2pTrafficHistory)
                }
                .distinctUntilChanged()
                .flowOn(Dispatchers.Default)
                .stateIn(
                    scope,
                    SharingStarted.WhileSubscribed(5_000),
                    // Seed with the LIVE settings snapshot, not defaults: the first combine
                    // emission takes seconds (heavy statistics flows), and a default-settings
                    // seed marked hydrated would resurrect the "flashes disabled" bug.
                    StatisticsRouteUiState(
                        settings = container.settingsRepository.settings.value,
                        settingsHydrated = container.settingsRepository.hydrated.value,
                    ),
                )
        }

    val routingRouteState: StateFlow<RoutingRouteUiState> =
        routeSources.coreUiState
            .map { state -> state.toRoutingRouteUiState(includeInstalledApps = false) }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(5_000),
                RoutingRouteUiState(),
            )

    val appPickerRouteState: StateFlow<RoutingRouteUiState> =
        routeSources.coreUiState
            .map { state -> state.toRoutingRouteUiState(includeInstalledApps = true) }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(5_000),
                RoutingRouteUiState(),
            )

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val filteredPickerAppsFlow: StateFlow<List<InstalledAppOption>> =
        combine(
            appPickerRouteState
                .map { it.installedApps }
                .distinctUntilChanged()
                .mapLatest { apps ->
                    withContext(Dispatchers.Default) {
                        Trace.beginSection("AppPicker/index")
                        try {
                            buildInstalledAppSearchIndex(apps)
                        } finally {
                            Trace.endSection()
                        }
                    }
                },
            routeSources.appPickerQuery.debounce(150L).distinctUntilChanged(),
        ) { indexedApps, query -> indexedApps to query }
            .mapLatest { (indexedApps, query) ->
                withContext(Dispatchers.Default) {
                    Trace.beginSection("AppPicker/filter")
                    try {
                        filterIndexedApps(indexedApps, query)
                    } finally {
                        Trace.endSection()
                    }
                }
            }
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(5_000),
                emptyList(),
            )

    val diagnosticsRouteState: StateFlow<DiagnosticsRouteUiState> =
        routeSources.uiState
            // No dashboardTraffic in the mix: the Logs screen never renders traffic, and combining
            // with the ~1Hz traffic tick recomposed the whole screen every second. Seeding the
            // initial value from the live uiState kills the first-frame flash of default settings
            // (the settings section popped in a beat after entering the screen).
            .map { state -> state.toDiagnosticsRouteUiState() }
            .combine(container.settingsRepository.hydrated) { routeState, hydrated ->
                routeState.copy(settingsHydrated = hydrated)
            }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(5_000),
                routeSources.uiState.value
                    .toDiagnosticsRouteUiState()
                    .copy(settingsHydrated = container.settingsRepository.hydrated.value),
            )
}

private const val TRAFFIC_MAP_REFRESH_INTERVAL_MS = 900L
