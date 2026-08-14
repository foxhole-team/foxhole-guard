package com.foxhole.guard.ui

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.foxhole.core.model.ACTIVE_CONNECTION_STATES
import com.foxhole.core.model.CachedActiveProfile
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.InstalledAppOption
import com.foxhole.core.model.LanProxyStatusSnapshot
import com.foxhole.core.model.PanelAppearance
import com.foxhole.core.model.PerAppRoutingMode
import com.foxhole.core.model.Profile
import com.foxhole.core.model.ProfileSourceType
import com.foxhole.core.model.ThemeMode
import com.foxhole.core.model.TrafficMapSectionId
import com.foxhole.core.model.TrafficMapUiState
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.model.TrafficSnapshot
import com.foxhole.core.model.normalizedTrafficMapSectionOrder
import com.foxhole.guard.BuildConfig
import com.foxhole.guard.FoxholeApplication
import com.foxhole.guard.FoxholeHomeDependencies
import com.foxhole.guard.R
import com.foxhole.guard.core.data.WebAppEntity
import com.foxhole.guard.core.settings.AppTrafficStatsRecorder
import com.foxhole.guard.core.settings.acknowledgeBetaNotice
import com.foxhole.guard.core.settings.rotatePrivacyRouteIdentity
import com.foxhole.guard.core.settings.updateAlphaNoticeShownVersionCode
import com.foxhole.guard.core.settings.updatePrivacyRouteAutoRotateExit
import com.foxhole.guard.core.settings.updatePrivacyRouteAutoRotateInterval
import com.foxhole.guard.core.webapps.WebAppProxyCredentials
import com.foxhole.guard.runtime.FoxholeVpnService
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// HomeViewModel is deliberately a facade: its body is one-line delegates into the per-domain
// HomeViewModel*Support extension files, so the screens compose against a single UI contract.
// The member count therefore measures the size of that contract, not tangled logic — splitting
// the facade itself would fragment the contract without removing any complexity.
@Suppress("LargeClass", "TooManyFunctions")
class HomeViewModel(
    application: Application,
) : AndroidViewModel(application) {
    internal val container: FoxholeHomeDependencies = (application as FoxholeApplication).appGraph
    internal val appTrafficStatsRecorder =
        AppTrafficStatsRecorder(
            anomalyRepository = container.anomalyRepository,
            context = application,
            diagnosticsLogger = container.diagnosticsLogger,
        )
    internal val initialSettings = container.settingsRepository.settings.value
    internal val clipboard = application.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    // The traffic widget's live-chart ring (per-second lane rates). VM-owned so the history
    // survives section switches and the text/chart flips; range changes resize it in place.
    internal val trafficChartRecorder =
        TrafficChartRecorder(
            scope = viewModelScope,
            traffic = container.connectionController.traffic,
            connection = container.connectionController.snapshot,
            initialCapacityMinutes = initialSettings.ui.trafficChartRangeMinutes,
        ).also { recorder ->
            recorder.start()
            viewModelScope.launch {
                container.settingsRepository.settings
                    .map { it.ui.trafficChartRangeMinutes }
                    .distinctUntilChanged()
                    .collect(recorder::setCapacityMinutes)
            }
        }
    internal val trafficChartFrame: StateFlow<TrafficChartFrame> get() = trafficChartRecorder.frame
    internal val installedAppsMutable = MutableStateFlow<List<InstalledAppOption>>(emptyList())
    internal val profilesLoadedMutable = MutableStateFlow(false)
    internal val installedAppsLoadingMutable = MutableStateFlow(false)
    internal val installedAppsLoadedMutable = MutableStateFlow(false)
    internal val appTrafficUsageAccessGrantedMutable = MutableStateFlow(false)
    internal val onboardingProgressMutable = MutableStateFlow(OnboardingProgress())
    internal val componentUpdates = HomeComponentUpdatesState()
    internal val importFlow = HomeImportFlowState()
    internal val torOperation = HomeTorOperationState()
    internal val protocolMetrics = HomeProtocolMetricsState()
    internal val connectionFlow =
        HomeConnectionFlowState(
            initialStartupProfile = container.settingsRepository.settings.value.lastActiveProfile?.toStartupProfile(),
        )
    internal val ipRefresh = HomeIpRefreshState()
    internal val ipInfoLoadingMutable = ipRefresh.ipInfoLoadingMutable
    internal val ipInfoRefreshReasonMutable = ipRefresh.ipInfoRefreshReasonMutable
    internal val torIpInfoMutable = ipRefresh.torIpInfoMutable
    internal val dashboardConnectionMetricsLoadingMutable = protocolMetrics.dashboardConnectionMetricsLoadingMutable
    internal val statisticsVisibleMutable = MutableStateFlow(false)
    internal val manualSubscriptionRefreshInProgressMutable = MutableStateFlow(false)
    val manualSubscriptionRefreshInProgress: StateFlow<Boolean> =
        manualSubscriptionRefreshInProgressMutable.asStateFlow()
    internal var dashboardConnectionMetricsLoadingStartedAtMs by protocolMetrics::dashboardConnectionMetricsLoadingStartedAtMs

    internal fun beginDashboardLatencyRefresh(): Long = protocolMetrics.beginDashboardLatencyRefresh()

    internal fun isCurrentDashboardLatencyRefresh(generation: Long): Boolean =
        protocolMetrics.isCurrentDashboardLatencyRefresh(generation)

    internal fun acquireDashboardConnectionMetricsLoading(generation: Long): Boolean =
        protocolMetrics.acquireDashboardLoading(
            generation = generation,
            startedAtMs = SystemClock.elapsedRealtime(),
        )

    internal fun releaseDashboardConnectionMetricsLoading(generation: Long): Boolean =
        protocolMetrics.releaseDashboardLoading(generation)

    internal fun invalidateDashboardLatencyRefresh() {
        protocolMetrics.invalidateDashboardLatencyRefresh()
    }

    internal val profileOptionLatenciesMutable = protocolMetrics.profileOptionLatenciesMutable
    internal val profileOptionDownMutable = protocolMetrics.profileOptionDownMutable
    internal val profileOptionLatencyUnavailableMutable = protocolMetrics.profileOptionLatencyUnavailableMutable
    internal val profileOptionServerPingsMutable = protocolMetrics.profileOptionServerPingsMutable
    internal val profileOptionTunnelPingsMutable = protocolMetrics.profileOptionTunnelPingsMutable
    internal val profileOptionMetricsUpdatedAtMutable = protocolMetrics.profileOptionMetricsUpdatedAtMutable
    internal val protocolMetricsRefreshingProfileIdsMutable = protocolMetrics.protocolMetricsRefreshingProfileIdsMutable
    internal val protocolMetricsRefreshingOptionIdByProfileIdMutable = protocolMetrics.protocolMetricsRefreshingOptionIdByProfileIdMutable
    internal val recommendedProtocolMutable = protocolMetrics.recommendedProtocolMutable
    internal val runtimeReloadPendingMutable = connectionFlow.runtimeReloadPendingMutable
    internal val runtimeReconnectRequiredMutable = connectionFlow.runtimeReconnectRequiredMutable
    internal val appPickerQueryFlowMutable = MutableStateFlow("")
    internal val reconnectInProgressMutable = connectionFlow.reconnectInProgressMutable
    internal val torOperationMutable = torOperation.torOperationMutable
    internal val torTransitionPromptMutable = torOperation.torTransitionPromptMutable
    internal val torIdentityProbeMutable = torOperation.torIdentityProbe
    internal val torIdentityProbe = torIdentityProbeMutable.state
    internal val dnsFilterRefreshInProgressMutable = componentUpdates.dnsFilterRefreshInProgressMutable
    internal val dnsFilterUpdateAvailableMutable = componentUpdates.dnsFilterUpdateAvailableMutable
    val dnsFilterUpdateAvailable: kotlinx.coroutines.flow.StateFlow<Boolean> = dnsFilterUpdateAvailableMutable
    internal val dnsFilterUpdatePhaseMutable = componentUpdates.dnsFilterUpdatePhaseMutable
    val dnsFilterUpdatePhase: kotlinx.coroutines.flow.StateFlow<FoxholeUpdatePhase> = dnsFilterUpdatePhaseMutable
    internal val torBridgeRefreshInProgressMutable = componentUpdates.torBridgeRefreshInProgressMutable
    internal val torBridgeUpdatePhaseMutable = componentUpdates.torBridgeUpdatePhaseMutable
    val torBridgeUpdatePhase: kotlinx.coroutines.flow.StateFlow<FoxholeUpdatePhase> = torBridgeUpdatePhaseMutable
    internal val geoIpDatabaseUiStateMutable = componentUpdates.geoIpDatabaseUiStateMutable
    val geoIpDatabaseUiState: kotlinx.coroutines.flow.StateFlow<GeoIpDatabaseUiState> = geoIpDatabaseUiStateMutable

    // Fed by the once-per-start availability probe (Component updates screen). The settings-home
    // blue dot lights only for the MANUAL story: checking on, auto-update off, updates waiting.
    internal val componentGeoIpUpdateAvailableMutable = componentUpdates.componentGeoIpUpdateAvailableMutable
    val componentUpdatesIndicatorVisible: kotlinx.coroutines.flow.StateFlow<Boolean> =
        combine(
            container.settingsRepository.settings,
            componentGeoIpUpdateAvailableMutable,
            dnsFilterUpdateAvailableMutable,
        ) { settings, geoAvailable, dnsAvailable ->
            settings.connection.componentUpdateCheckEnabled &&
                !settings.connection.componentAutoUpdateEnabled &&
                (geoAvailable || dnsAvailable)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    internal val profileImportInProgressMutable = importFlow.profileImportInProgressMutable
    internal val profileReconnectPromptUntilMutable = connectionFlow.profileReconnectPromptUntilMutable
    internal val insecureTlsImportWarningMutable = importFlow.insecureTlsImportWarningMutable

    // Add-profile confirmation: EVERY import (clipboard/file/QR/URL) parks here first with the
    // parsed protocol + domain preview; yes proceeds into the ordinary import path, no drops it.
    internal val profileImportConfirmationMutable = importFlow.profileImportConfirmationMutable
    internal val catalogPresetPreviewsMutable = componentUpdates.catalogPresetPreviewsMutable
    internal val startupActiveProfileMutable = connectionFlow.startupActiveProfileMutable
    internal val benchmarkTrafficMapUiStateMutable = MutableStateFlow<TrafficMapUiState?>(null)

    private val stateProducer =
        HomeStateProducer(
            scope = viewModelScope,
            container = container,
            initialSettings = initialSettings,
            inputs =
            HomeStateProducerInputs(
                installedApps = installedAppsMutable,
                profilesLoaded = profilesLoadedMutable,
                installedAppsLoading = installedAppsLoadingMutable,
                installedAppsLoaded = installedAppsLoadedMutable,
                ipInfoLoading = ipInfoLoadingMutable,
                ipInfoRefreshReason = ipInfoRefreshReasonMutable,
                torIpInfo = torIpInfoMutable,
                dashboardConnectionMetricsLoading = dashboardConnectionMetricsLoadingMutable,
                runtimeReloadPending = runtimeReloadPendingMutable,
                runtimeReconnectRequired = runtimeReconnectRequiredMutable,
                catalogPresetPreviews = catalogPresetPreviewsMutable,
                startupActiveProfile = startupActiveProfileMutable,
                torOperation = torOperationMutable,
                torTransitionPrompt = torTransitionPromptMutable,
                reconnectInProgress = reconnectInProgressMutable,
                profileReconnectPromptUntil = profileReconnectPromptUntilMutable,
            ),
        )

    internal val profileStreams = stateProducer.profileStreams
    internal val realtimeStreams = stateProducer.realtimeStreams
    internal val connectionStreams = stateProducer.connectionStreams
    internal val routingStreams = stateProducer.routingStreams
    internal val localState = stateProducer.localState
    private val coreUiState: StateFlow<HomeUiState> = stateProducer.coreUiState
    internal val controlUiState: StateFlow<HomeUiState> = stateProducer.controlUiState
    val dashboardTraffic: StateFlow<TrafficSnapshot> = stateProducer.dashboardTraffic
    val uiState: StateFlow<HomeUiState> = stateProducer.uiState
    private val statisticsUiState: StateFlow<HomeUiState> = stateProducer.statisticsUiState

    val themeMode: StateFlow<ThemeMode> = container.settingsRepository.themeMode

    val panelAppearance: StateFlow<PanelAppearance> =
        container.settingsRepository.settings
            .map { settings -> settings.ui.panelAppearance }
            .distinctUntilChanged()
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                initialSettings.ui.panelAppearance,
            )

    // Feeds FoxholeTheme directly (like themeMode): the monochrome-TOR palette swap has to reach
    // the CompositionLocal at the theme root, above every screen.
    val monochromeTorTheme: StateFlow<Boolean> =
        container.settingsRepository.settings
            .map { it.ui.monochromeTorTheme }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** A hot projection of one [coreUiState] field for chrome-level observers. */
    private fun <T> uiProjection(
        initial: T,
        selector: (HomeUiState) -> T,
    ): StateFlow<T> =
        coreUiState
            .map(selector)
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    internal val appLockManager = (application as FoxholeApplication).appGraph.appLockManager
    internal val securityComponents = (application as FoxholeApplication).appGraph.securityComponents

    val lockState: StateFlow<com.foxhole.guard.core.security.LockState> = appLockManager.lockState

    // FLAG_SECURE tracks the screenshot toggle OR any locked state: the unlock screen is
    // always protected from screenshots/recents regardless of the user setting.
    val secureScreenEnabled: StateFlow<Boolean> =
        combine(
            uiProjection(initialSettings.expert.blockScreenshots) { it.settings.expert.blockScreenshots },
            lockState,
        ) { blockScreenshots, lock ->
            blockScreenshots || lock != com.foxhole.guard.core.security.LockState.UNLOCKED
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            initialSettings.expert.blockScreenshots || appLockManager.currentLockState() != com.foxhole.guard.core.security.LockState.UNLOCKED,
        )

    val blurEffectsEnabled: StateFlow<Boolean> =
        uiProjection(initialSettings.ui.blurEffectsEnabled) { it.settings.ui.blurEffectsEnabled }

    val mapWidgetMapOnRight: StateFlow<Boolean> =
        uiProjection(initialSettings.ui.mapWidgetMapOnRight) { it.settings.ui.mapWidgetMapOnRight }

    // The "layout editing" switch gating every long-press reorder surface (dashboard cards,
    // statistics widgets, connection-map sections).
    val layoutEditingEnabled: StateFlow<Boolean> =
        uiProjection(initialSettings.ui.layoutEditingEnabled) { it.settings.ui.layoutEditingEnabled }

    val insecureTlsImportWarning: StateFlow<InsecureTlsImportWarningState?> = insecureTlsImportWarningMutable

    val profileImportConfirmation: StateFlow<ProfileImportConfirmationState?> = profileImportConfirmationMutable

    internal val autoConnectUiStateMutable = connectionFlow.autoConnectUiStateMutable

    internal val pendingRoutingScenarioConfirmationMutable =
        connectionFlow.pendingRoutingScenarioConfirmationMutable

    /**
     * The VPN/Tor scenario change waiting for the user's yes, raised only while atomic scenario
     * application is off. The routing screen renders the ordinary confirm sheet; Home operating
     * modes never feed it and retain their own live-switch behavior.
     */
    internal val pendingRoutingScenarioConfirmation: StateFlow<PendingRoutingScenarioChange?> =
        pendingRoutingScenarioConfirmationMutable.asStateFlow()

    private val routeStateProducer =
        HomeRouteStateProducer(
            scope = viewModelScope,
            container = container,
            initialSettings = initialSettings,
            routeSources =
            HomeRouteStateSources(
                coreUiState = coreUiState,
                uiState = uiState,
                profileStreams = profileStreams,
                statisticsUiState = statisticsUiState,
                dashboardTraffic = dashboardTraffic,
                autoConnect = autoConnectUiStateMutable,
                profileOptionLatencies = profileOptionLatenciesMutable,
                profileOptionLatencyUnavailable = profileOptionLatencyUnavailableMutable,
                dnsFilterRefreshInProgress = dnsFilterRefreshInProgressMutable,
                appTrafficUsageAccessGranted = appTrafficUsageAccessGrantedMutable,
                statisticsVisible = statisticsVisibleMutable,
                appPickerQuery = appPickerQueryFlowMutable,
            ),
            protocolMetricsSources =
            HomeProtocolMetricsStateSources(
                serverPings = profileOptionServerPingsMutable,
                tunnelPings = profileOptionTunnelPingsMutable,
                updatedAt = profileOptionMetricsUpdatedAtMutable,
                downOptionIds = profileOptionDownMutable,
                refreshingProfileIds = protocolMetricsRefreshingProfileIdsMutable,
                refreshingOptionIdByProfileId = protocolMetricsRefreshingOptionIdByProfileIdMutable,
                recommendation = recommendedProtocolMutable,
            ),
            trafficMapSources =
            HomeTrafficMapStateSources(
                torIpInfo = torIpInfoMutable,
                benchmarkTrafficMapUiState = benchmarkTrafficMapUiStateMutable,
            ),
            benchmarkTrafficMapEnabled = BuildConfig.DEBUG,
            currentNetworkFingerprintKey = { currentNetworkFingerprintForSmartRules()?.key },
        )

    val homeRouteState: StateFlow<HomeRouteUiState> = routeStateProducer.homeRouteState

    /**
     * The LAN proxy as the core reports it — read through the connection controller like every other
     * runtime stream, not folded into the settings projection: it is a runtime fact, and a screen
     * that renders the saved switch instead is exactly the bug this replaced.
     */
    val lanProxyStatus: StateFlow<LanProxyStatusSnapshot> = container.connectionController.lanProxyStatus
    internal val dashboardLayoutState: StateFlow<DashboardLayoutUiState> = routeStateProducer.dashboardLayoutState
    internal val dashboardHeaderState: StateFlow<DashboardHeaderUiState> = routeStateProducer.dashboardHeaderState
    internal val dashboardProfileCardState: StateFlow<DashboardProfileCardUiState> =
        routeStateProducer.dashboardProfileCardState
    internal val dashboardActionsCardState: StateFlow<DashboardActionsCardUiState> =
        routeStateProducer.dashboardActionsCardState
    internal val dashboardNetworkCardState: StateFlow<DashboardNetworkCardUiState> =
        routeStateProducer.dashboardNetworkCardState
    internal val dashboardTrafficCardState: StateFlow<DashboardTrafficCardUiState> =
        routeStateProducer.dashboardTrafficCardState
    internal val dashboardMapCardState: StateFlow<DashboardMapCardUiState> = routeStateProducer.dashboardMapCardState
    internal val dashboardDialogState: StateFlow<DashboardDialogUiState> = routeStateProducer.dashboardDialogState
    internal val dashboardFeatureDialogState: StateFlow<DashboardFeatureDialogUiState> =
        routeStateProducer.dashboardFeatureDialogState
    val trafficMapUiState: StateFlow<TrafficMapUiState> = routeStateProducer.trafficMapUiState

    // Persisted display order of the connection-map screen sections (map / route / table).
    val trafficMapSectionOrder: StateFlow<List<TrafficMapSectionId>> =
        container.settingsRepository.settings
            .map { settings -> normalizedTrafficMapSectionOrder(settings.ui.trafficMapSectionOrder) }
            .distinctUntilChanged()
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                normalizedTrafficMapSectionOrder(initialSettings.ui.trafficMapSectionOrder),
            )

    val profilesRouteState: StateFlow<ProfilesRouteUiState> = routeStateProducer.profilesRouteState
    val settingsHomeNavState: StateFlow<SettingsHomeNavUiState> = routeStateProducer.settingsHomeNavState
    val settingsRouteState: StateFlow<SettingsRouteUiState> = routeStateProducer.settingsRouteState
    val settingsProfileRouteState: StateFlow<SettingsRouteUiState> = routeStateProducer.settingsProfileRouteState
    val trafficSettingsRouteState: StateFlow<TrafficSettingsRouteUiState> = routeStateProducer.trafficSettingsRouteState
    val statisticsRouteState: StateFlow<StatisticsRouteUiState> = routeStateProducer.statisticsRouteState
    val routingRouteState: StateFlow<RoutingRouteUiState> = routeStateProducer.routingRouteState
    val appPickerRouteState: StateFlow<RoutingRouteUiState> = routeStateProducer.appPickerRouteState
    val filteredPickerAppsFlow: StateFlow<List<InstalledAppOption>> = routeStateProducer.filteredPickerAppsFlow

    val diagnosticsRouteState: StateFlow<DiagnosticsRouteUiState> = routeStateProducer.diagnosticsRouteState

    /**
     * Whether the first-run wizard still has to run.
     *
     * Gated on [SettingsRepository.hydrated] and seeded false on purpose: the bootstrap settings read
     * before hydration carry the *default* onboarding flag, not the stored one, so an install that
     * finished the wizard long ago would flash it again on every cold start.
     */
    val onboardingRequired: StateFlow<Boolean> =
        combine(
            container.settingsRepository.hydrated,
            container.settingsRepository.settings,
        ) { hydrated, settings -> hydrated && !settings.ui.onboardingCompleted }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Follows quick start once: shown only after that sheet is done and before acknowledgement. */
    val betaNoticeRequired: StateFlow<Boolean> =
        combine(
            container.settingsRepository.hydrated,
            container.settingsRepository.settings,
        ) { hydrated, settings ->
            hydrated &&
                settings.ui.onboardingCompleted &&
                settings.ui.quickStartShown &&
                !settings.ui.betaNoticeAcknowledged
        }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun onBetaNoticeAcknowledged() {
        viewModelScope.launch { container.settingsRepository.acknowledgeBetaNotice() }
    }

    /**
     * User-facing banners, which the CLI front end prints into the terminal journal. Buffered
     * across activity lifetimes on purpose — see [FoxholeBannerEvents]; a `MutableSharedFlow` here
     * silently discarded everything emitted while the UI was destroyed.
     */
    internal val snackbars = FoxholeBannerEvents()

    // Web apps: the screen list, the add-form state and the open full-screen frame. The frame
    // state lives in the view model rather than rememberSaveable, so it survives rotation and can
    // open from a notification or widget before the webapps screen composes.
    val webAppsState: StateFlow<List<WebAppEntity>> =
        container.webAppsRepository.observeWebApps()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    internal val webAppAddStateMutable = MutableStateFlow<WebAppAddUiState>(WebAppAddUiState.Idle)
    internal val webAppAddState: StateFlow<WebAppAddUiState> = webAppAddStateMutable.asStateFlow()
    internal val openWebAppMutable = MutableStateFlow<WebAppEntity?>(null)
    val openWebAppState: StateFlow<WebAppEntity?> = openWebAppMutable.asStateFlow()

    /**
     * Credentials for the proxy the open frame was routed through, so its WebView can answer the
     * proxy's auth challenge. Set only while a route is actually installed.
     */
    internal val webAppProxyCredentialsMutable = MutableStateFlow<WebAppProxyCredentials?>(null)
    internal val webAppProxyCredentials: StateFlow<WebAppProxyCredentials?> =
        webAppProxyCredentialsMutable.asStateFlow()

    // The action-confirmation gate: the pending action outlives the prompt's composition.
    internal var pendingSensitiveAction: (() -> Unit)? = null
    internal val actionAuthVisibleMutable = MutableStateFlow(false)
    val actionAuthVisible: StateFlow<Boolean> = actionAuthVisibleMutable.asStateFlow()
    private val requestVpnPermissionChannel = Channel<Unit>(Channel.CONFLATED)
    val requestVpnPermission = requestVpnPermissionChannel.receiveAsFlow()
    val requestNotificationPermission = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    internal var pendingConnectRequest: PendingConnectRequest? = null
    internal var pendingLocalGuardPermissionSync: Boolean = false
    internal val runtimeSettingUpdates = RuntimeSettingUpdateBarrier()
    internal val ipRefreshCoordinator = ipRefresh.coordinator
    internal var ipInfoRefreshJob: Job? by ipRefresh::ipInfoRefreshJob
    internal var ipInfoRefreshToken: Long by ipRefresh::ipInfoRefreshToken
    internal var activeIpInfoRefreshReason: IpInfoRefreshReason? by ipRefresh::activeIpInfoRefreshReason
    internal var pendingPostConnectIpRefresh: Boolean by ipRefresh::pendingPostConnectIpRefresh
    internal var lastForegroundDashboardRefreshElapsedMs: Long by ipRefresh::lastForegroundDashboardRefreshElapsedMs
    internal var lastAppForegroundRefreshElapsedMs: Long by ipRefresh::lastAppForegroundRefreshElapsedMs
    internal var firstAppForegroundHandled: Boolean by ipRefresh::firstAppForegroundHandled
    internal var foregroundRefreshJob: Job? by ipRefresh::foregroundRefreshJob
    internal var pendingNetworkChangeRefreshJob: Job? by ipRefresh::pendingNetworkChangeRefreshJob
    internal var connectedIpRefreshJob: Job? by ipRefresh::connectedIpRefreshJob
    internal var postConnectLatencyRefreshJob: Job? by protocolMetrics::postConnectLatencyRefreshJob
    internal var postConnectLatencyRefreshGeneration: Long? by protocolMetrics::postConnectLatencyRefreshGeneration
    internal var postConnectTorRouteRefreshJob: Job? by ipRefresh::postConnectTorRouteRefreshJob
    internal var torExitBackgroundRefreshJob: Job? by torOperation::torExitBackgroundRefreshJob
    internal var protocolSwitchRevertJob: Job? by torOperation::protocolSwitchRevertJob
    internal var liveModeSwitchCountdownJob: Job? by torOperation::liveModeSwitchCountdownJob
    internal var profileLatencyRefreshJob: Job? by protocolMetrics::profileLatencyRefreshJob
    internal var profileLatencyRefreshGeneration: Long? by protocolMetrics::profileLatencyRefreshGeneration
    internal var dnsFilterEnablePreflightJob: Job? by componentUpdates::dnsFilterEnablePreflightJob
    internal var dnsFilterManualRefreshJob: Job? by componentUpdates::dnsFilterManualRefreshJob
    internal var geoIpDatabaseUpdateJob: Job? by componentUpdates::geoIpDatabaseUpdateJob
    internal var torBridgeManualRefreshJob: Job? by componentUpdates::torBridgeManualRefreshJob
    internal var runtimeReloadPendingJob: Job? by connectionFlow::runtimeReloadPendingJob
    internal var routeModeRestartPromptJob: Job? by connectionFlow::routeModeRestartPromptJob
    internal var routeModeRestartBaseline: Pair<TrafficMode, PerAppRoutingMode>? by connectionFlow::routeModeRestartBaseline
    internal var torOperationTimeoutJob: Job? by torOperation::torOperationTimeoutJob
    internal var torIdentityProbeTimeoutJob: Job? by torOperation::torIdentityProbeTimeoutJob
    internal var profileReconnectPromptJob: Job? = null
    internal var autoConnectJob: Job? by connectionFlow::autoConnectJob
    internal var reconnectJob: Job? by connectionFlow::reconnectJob
    internal var protocolMetricsRefreshJob: Job? by protocolMetrics::protocolMetricsRefreshJob
    internal var appTrafficStatsJob: Job? = null
    internal var installedAppsLoadJob: Job? = null
    internal var manualSubscriptionRefreshJob: Job? = null
    internal var appTrafficStatsIntervalMs: Long = APP_TRAFFIC_BACKGROUND_SAMPLE_INTERVAL_MS
    internal var protocolMetricsRestoreOnCancel: Boolean by protocolMetrics::protocolMetricsRestoreOnCancel
    internal var dashboardVisible: Boolean = false
    internal var profilesUiVisible: Boolean = false
    internal var trafficUiVisible: Boolean = false
    internal var statisticsVisible: Boolean = false
    internal var reconnectPromptPendingUntilDashboard: Boolean = false

    // Network-rules reactions: the last handled network+profile pair (so one network change is
    // acted on once) and the recommendation awaiting the user's confirmation via banner action.
    internal var lastNetworkRuleHandledKey: String? by connectionFlow::lastNetworkRuleHandledKey
    internal var pendingNetworkRuleOverride: NetworkProfileOverride? by connectionFlow::pendingNetworkRuleOverride

    // Provider-DNS fallback notice dedupe: one banner per profile+option+server combination.
    internal var lastProviderDnsFallbackNoticeKey: String? = null

    init {
        startNetworkRulesWatchInternal()
        observeDefaultNetworkChangesInternal()
        observeDnsNoticesInternal()
        startSessionOnlyStatisticsWipe()
        superviseComponentUpdateAvailabilityInternal()
        startSettingsWarmupSupervision()
        startAppTrafficSamplerSettingsSync()
        startStartupProfilePreload()
        startActiveProfileStartupSync()
        startConnectionSnapshotSupervision()
        startTorOperationSupervision()
        startRuntimeTorExitSupervision()
        startAuthAttemptNoticeSupervision()
        startGuardJournalCheckpointSupervision()
        startWebAppRouteSupervision()
    }

    fun onAppForegrounded() {
        appLockManager.onAppForegrounded()
        onAppForegroundedInternal()
    }

    fun onAppBackgrounded() = appLockManager.onAppBackgrounded()

    fun unlockWithSystemAuth() = appLockManager.unlockWithSystemAuth()

    fun currentFailedAttempts(): Int = appLockManager.failedAttempts()

    fun onToggleConnection() {
        if (isConnectionControlThrottled() || manualSubscriptionRefreshInProgressMutable.value) return
        toggleConnectionInternal()
    }

    fun onVpnPermissionResult(granted: Boolean) {
        val request = pendingConnectRequest
        pendingConnectRequest = null
        if (request == null) {
            container.diagnosticsLogger.record(
                "permissions",
                "ignored vpn permission result without active request granted=$granted",
            )
            drainPendingLocalGuardPermissionSync()
            return
        }
        if (!granted) {
            if (request.profileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID) {
                clearTorOperation()
            }
            snackbars.tryEmit(errorBanner(R.string.vpn_permission_denied))
            drainPendingLocalGuardPermissionSync()
            return
        }
        when (request.action) {
            PendingConnectAction.MANUAL -> connect(request.profileId, protocolOptionId = request.protocolOptionId)
            PendingConnectAction.RECONNECT -> reconnect(request.profileId)
            PendingConnectAction.LOCAL_GUARD ->
                viewModelScope.launch {
                    container.connectionController.syncLocalGuard()
                }
        }
        drainPendingLocalGuardPermissionSync()
    }

    fun onRefreshProfile() {
        val activeProfile = controlUiState.value.activeProfile ?: return
        if (activeProfile.sourceType != ProfileSourceType.SUBSCRIPTION_URL) {
            snackbars.tryEmit(infoBanner(R.string.profile_not_refreshable))
            return
        }
        if (!manualSubscriptionRefreshInProgressMutable.compareAndSet(expect = false, update = true)) return
        manualSubscriptionRefreshJob =
            viewModelScope.launch {
                try {
                    refreshProfileWithInsecureTlsDecision(
                        profileId = activeProfile.id,
                        allowInsecureTlsForProfile = false,
                        excludeInsecureTlsOptions = false,
                    )
                } finally {
                    manualSubscriptionRefreshInProgressMutable.value = false
                    manualSubscriptionRefreshJob = null
                }
            }
    }

    // The old dashboard Restart button (and its "restart VPN or TOR?" chooser modal) is gone; a
    // plain restart reconnects the VPN profile - Tor rides along per its own placement rules.
    fun onRestartActiveProfile() {
        if (isConnectionControlThrottled()) return
        val activeProfile = controlUiState.value.activeProfile ?: return
        val snapshot = controlUiState.value.connection
        if (snapshot.state != ConnectionState.CONNECTED || snapshot.profileId != activeProfile.id) {
            return
        }
        requestReconnect(activeProfile.id)
    }

    fun onRefreshAndRestartActiveProfile() {
        val activeProfile = controlUiState.value.activeProfile ?: return
        if (activeProfile.sourceType != ProfileSourceType.SUBSCRIPTION_URL) {
            onRestartActiveProfile()
            return
        }
        if (!manualSubscriptionRefreshInProgressMutable.compareAndSet(expect = false, update = true)) return
        manualSubscriptionRefreshJob =
            viewModelScope.launch {
                try {
                    refreshProfileWithInsecureTlsDecision(
                        profileId = activeProfile.id,
                        allowInsecureTlsForProfile = false,
                        excludeInsecureTlsOptions = false,
                        restartActiveRuntime = true,
                    )
                } finally {
                    manualSubscriptionRefreshInProgressMutable.value = false
                    manualSubscriptionRefreshJob = null
                }
            }
    }

    // The alpha notice: shown on a fresh install and once after every upgrade (the acknowledged
    // versionCode stops matching the build's). Acknowledging it stores THIS build's code.
    val alphaNoticeVisible: StateFlow<Boolean> =
        container.settingsRepository.settings
            .map { it.ui.alphaNoticeShownVersionCode != BuildConfig.VERSION_CODE }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun dismissAlphaNotice() {
        viewModelScope.launch {
            container.settingsRepository.updateAlphaNoticeShownVersionCode(BuildConfig.VERSION_CODE)
        }
    }

    fun onSelectProfile(profileId: Long) {
        cancelAutoConnect(clearUiOnly = true)
        viewModelScope.launch {
            container.connectionController.setActiveProfile(profileId)
            val updated =
                container.profileRepository.getProfile(profileId)?.copy(isActive = true)
                    ?: controlUiState.value.profiles.firstOrNull { it.id == profileId }?.copy(isActive = true)
            startupActiveProfileMutable.value = updated
            markProfileReconnectPromptWindow()
        }
    }

    fun onSelectProfileProtocolOption(
        profileId: Long,
        optionId: String,
    ) = onSelectProfileProtocolOptionRequested(profileId, optionId)

    internal fun markProfileReconnectPromptWindow() {
        if (!dashboardVisible) {
            reconnectPromptPendingUntilDashboard = true
            profileReconnectPromptJob?.cancel()
            profileReconnectPromptJob = null
            profileReconnectPromptUntilMutable.value = 0L
            return
        }
        profileReconnectPromptJob?.cancel()
        profileReconnectPromptUntilMutable.value =
            SystemClock.elapsedRealtime() + PROFILE_RECONNECT_PROMPT_WINDOW_MS
        profileReconnectPromptJob =
            viewModelScope.launch {
                delay(PROFILE_RECONNECT_PROMPT_WINDOW_MS)
                profileReconnectPromptUntilMutable.value = 0L
                profileReconnectPromptJob = null
                // The reconnect offer expired unused: the primary button returns to a plain Stop
                // (same contract as the route-mode restart prompt, which reverts on expiry).
                if (!reconnectInProgressMutable.value) {
                    clearRuntimeReconnectRequired()
                }
            }
    }

    internal fun startPendingProfileReconnectPromptIfNeeded() {
        if (!reconnectPromptPendingUntilDashboard) {
            return
        }
        reconnectPromptPendingUntilDashboard = false
        markProfileReconnectPromptWindow()
    }

    fun onInstalledAppMonitoringChanged(value: Boolean) {
        if (value) {
            onInstalledAppMonitoringChangedInternal(true)
        } else {
            runConfirmedAction { onInstalledAppMonitoringChangedInternal(false) }
        }
    }

    fun openSystemVpnSettings() {
        viewModelScope.launch {
            openSystemVpnSettingsInternal()
        }
    }

    fun onFirewallEnabledChanged(value: Boolean) {
        if (value) {
            onFirewallEnabledChangedInternal(true)
        } else {
            runConfirmedAction { onFirewallEnabledChangedInternal(false) }
        }
    }

    fun onTorRuntimeToggled(enabled: Boolean) {
        if (isConnectionControlThrottled()) return
        onTorRuntimeToggledInternal(enabled)
    }

    fun onPrivacyRouteAutoRotateExitChanged(value: Boolean) {
        viewModelScope.launch {
            container.settingsRepository.updatePrivacyRouteAutoRotateExit(value)
        }
    }

    fun onPrivacyRouteAutoRotateIntervalSelected(minutes: Int) {
        viewModelScope.launch {
            container.settingsRepository.updatePrivacyRouteAutoRotateInterval(minutes)
        }
    }

    fun onRenewTorIp() {
        val state = controlUiState.value
        val profileId =
            state.connection.profileId?.takeIf { it == FoxholeVpnService.TOR_ONLY_PROFILE_ID }
                ?: state.activeProfile?.id
                ?: return
        if (state.connection.state in ACTIVE_CONNECTION_STATES && !state.reconnectInProgress) {
            viewModelScope.launch {
                clearRuntimeReconnectRequired()
                markTorOperation(HomeTorOperationKind.CHANGING_LOCATION)
                container.settingsRepository.rotatePrivacyRouteIdentity()
                markRuntimeReloadPending()
                if (container.connectionController.reload(profileId)) {
                    scheduleDashboardRefreshAfterRuntimeReload()
                } else {
                    clearTorOperation()
                    clearRuntimeReloadPending()
                }
            }
        }
    }

    internal fun scheduleDashboardRefreshAfterRuntimeReload() {
        val snapshot = container.connectionController.snapshot.value
        if (snapshot.state != ConnectionState.CONNECTED) {
            return
        }
        val refreshReason =
            runtimeReloadIpRefreshReason(
                snapshot = snapshot,
                settings = controlUiState.value.settings,
            )
        scheduleConnectedIpRefresh(reason = refreshReason, clearExistingIp = false)
        if (dashboardVisible && !autoConnectUiStateMutable.value.running) {
            scheduleActiveProfileLatencyRefresh(showLoading = true, refreshImmediately = true)
        }
    }

    internal fun enqueueVpnPermissionRequest(request: PendingConnectRequest): Boolean {
        val active = pendingConnectRequest
        if (active != null) {
            container.diagnosticsLogger.record(
                "permissions",
                "ignored vpn permission request while active request is pending " +
                    "active=${active.action.name.lowercase()} requested=${request.action.name.lowercase()}",
            )
            return false
        }
        pendingConnectRequest = request
        requestVpnPermissionChannel.trySend(Unit)
        return true
    }

    private fun drainPendingLocalGuardPermissionSync() {
        if (!pendingLocalGuardPermissionSync) {
            return
        }
        pendingLocalGuardPermissionSync = false
        container.diagnosticsLogger.record(
            "connection",
            "local guard permission sync retrying after vpn permission result",
        )
        viewModelScope.launch {
            syncLocalGuardWithPermissionRequest()
        }
    }

    companion object {
        internal const val CONNECTED_IP_REFRESH_DELAY_MS = 250L
        internal const val CONNECTED_IP_REFRESH_ATTEMPTS = 4
        internal const val CONNECTED_IP_REFRESH_RETRY_DELAY_MS = 1_500L
        internal const val POST_CONNECT_LATENCY_AFTER_IP_DELAY_MS = 5_000L
        internal const val POST_CONNECT_TOR_ROUTE_DELAY_MS = 1_200L
        internal const val PROFILE_PRELOAD_TIMEOUT_MS = 2_500L
        internal const val PROFILE_IMPORT_CONNECT_WAIT_TIMEOUT_MS = 8_000L
        internal const val PROFILE_IMPORT_CONNECT_POLL_MS = 100L
        internal const val MANUAL_IP_REFRESH_MIN_LOADING_MS = 666L
        internal const val CONNECTION_CONTROL_DEBOUNCE_MS = 700L
        internal const val AUTO_IP_REFRESH_MIN_LOADING_MS = 450L
        internal const val DASHBOARD_CONNECTION_METRICS_MIN_LOADING_MS = 450L
        internal const val FIRST_FOREGROUND_REFRESH_STARTUP_DELAY_MS = 2_500L
        internal const val APP_FOREGROUND_REFRESH_MIN_INTERVAL_MS = 15_000L
        internal const val FOREGROUND_DASHBOARD_REFRESH_MIN_INTERVAL_MS = 20_000L
        internal const val CONNECTED_LATENCY_FIRST_DELAY_MS = 350L
        internal const val CONNECTED_LATENCY_REFRESH_INTERVAL_MS = 15_000L
        internal const val CONNECTED_METRICS_MEMORY_PERSIST_INTERVAL_MS = 20_000L
        internal const val CONNECTED_LATENCY_TIMEOUT_MS = 2_500L
        internal const val CONNECTED_LATENCY_TOTAL_TIMEOUT_MS = 8_000L
        internal const val CONNECTED_DASHBOARD_PING_TIMEOUT_MS = CONNECTED_LATENCY_TIMEOUT_MS
        internal const val CONNECTED_SERVER_PING_TIMEOUT_MS = 2_500L
        internal const val CONNECTED_SERVER_PING_TOTAL_TIMEOUT_MS = CONNECTED_SERVER_PING_TIMEOUT_MS + 500L
        internal const val PROFILE_RECONNECT_PROMPT_WINDOW_MS = 13_000L
        internal const val RUNTIME_RELOAD_PENDING_TIMEOUT_MS = 1_500L
        internal const val TOR_OPERATION_MIN_VISIBLE_MS = 3_500L
        internal const val STOP_VPN_KEEP_TOR_SETTLE_TIMEOUT_MS = 6_000L
        internal const val TOR_OPERATION_BOOTSTRAP_NOTICE_MS = 20_000L
        internal const val TOR_OPERATION_TIMEOUT_MS = 240_000L
        internal const val TOR_IP_REFRESH_ATTEMPTS = 6
        internal const val TOR_IP_REFRESH_RETRY_DELAY_MS = 1_000L
        internal const val TOR_IDENTITY_PROBE_TIMEOUT_MS = 20_000L
        internal const val TOR_EXIT_BACKGROUND_REFRESH_INITIAL_DELAY_MS = 5_000L
        internal const val TOR_EXIT_BACKGROUND_REFRESH_MAX_DELAY_MS = 30_000L
        internal const val AUTO_CONNECT_CONNECTION_TIMEOUT_MS =
            FoxholeVpnService.CONNECTIVITY_PROBE_TOTAL_TIMEOUT_MS +
                FoxholeVpnService.CONNECTIVITY_PROBE_NETWORK_WAIT_TIMEOUT_MS +
                FoxholeVpnService.CONNECTIVITY_PROBE_GRACE_MAX_TIMEOUT_MS
        internal const val AUTO_CONNECT_VALIDATION_GRACE_TIMEOUT_MS = FoxholeVpnService.VPN_NETWORK_WAIT_TIMEOUT_MS
        internal const val AUTO_CONNECT_DISCONNECT_TIMEOUT_MS =
            FoxholeVpnService.VPN_NETWORK_WAIT_TIMEOUT_MS +
                FoxholeVpnService.CONNECTIVITY_PROBE_NETWORK_WAIT_TIMEOUT_MS
        internal const val AUTO_CONNECT_DISCONNECT_FORCE_STABILIZE_TIMEOUT_MS = 3_000L
        internal const val AUTO_CONNECT_DISCONNECT_POLL_DELAY_MS = FoxholeVpnService.VPN_NETWORK_WAIT_POLL_DELAY_MS
        internal const val AUTO_CONNECT_LATENCY_MEASUREMENT_SETTLE_MS = CONNECTED_LATENCY_FIRST_DELAY_MS
        internal const val AUTO_CONNECT_LATENCY_MEASUREMENT_RETRY_THRESHOLD_MS = 350L
        internal const val AUTO_CONNECT_LATENCY_MEASUREMENT_RETRY_DELAY_MS = 160L
        internal const val AUTO_CONNECT_PROTOCOL_TRANSITION_SETTLE_MS = 90L
        internal const val AUTO_CONNECT_RESULT_SETTLE_MS = 500L
        internal const val AUTO_CONNECT_TOTAL_TIMEOUT_MS = 145_000L
        internal const val AUTO_CONNECT_MAX_ATTEMPTS = 3
        internal const val PROTOCOL_METRICS_PROBE_TIMEOUT_MS = 12_000L
        internal const val APP_TRAFFIC_BACKGROUND_SAMPLE_INTERVAL_MS = 60_000L
        internal const val AUTO_CONNECT_LATENCY_FALLBACK_PENALTY_MS = 750L
        internal val TERMINAL_CONNECTION_STATES =
            setOf(
                ConnectionState.CONNECTED,
                ConnectionState.ERROR,
                ConnectionState.IDLE,
            )

        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                    return HomeViewModel(application) as T
                }
            }
    }
}

internal fun CachedActiveProfile.toStartupProfile(): Profile =
    Profile(
        id = id,
        name = name,
        sourceType = sourceType,
        secretRef = "",
        protocolHint = protocolHint,
        lastUpdatedAt = null,
        lastEtag = null,
        isActive = true,
    )
