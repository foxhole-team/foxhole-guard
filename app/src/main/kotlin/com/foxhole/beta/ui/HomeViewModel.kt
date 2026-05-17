package com.foxhole.beta.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.foxhole.beta.FoxholeApplication
import com.foxhole.beta.FoxholeHomeDependencies
import com.foxhole.beta.R
import com.foxhole.beta.core.data.ProfileImportPayloadTooLargeException
import com.foxhole.beta.core.data.RoutingRepository
import com.foxhole.beta.core.data.requireLocalProfileImportWithinLimit
import com.foxhole.beta.core.model.AnomalyHistoryRetention
import com.foxhole.beta.core.model.AnomalySensitivity
import com.foxhole.beta.core.model.AppLocale
import com.foxhole.beta.core.model.AutoConnectReasonCode
import com.foxhole.beta.core.model.CachedActiveProfile
import com.foxhole.beta.core.model.ClashApiSettings
import com.foxhole.beta.core.model.ConnectionSnapshot
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.DiagnosticsRetention
import com.foxhole.beta.core.model.DnsSettings
import com.foxhole.beta.core.model.DomainStrategy
import com.foxhole.beta.core.model.InstalledAppOption
import com.foxhole.beta.core.model.IpInfo
import com.foxhole.beta.core.model.LatencyProbeMethod
import com.foxhole.beta.core.model.LocalAuthSettings
import com.foxhole.beta.core.model.NetworkRulesSettings
import com.foxhole.beta.core.model.PerAppRoutingMode
import com.foxhole.beta.core.model.PrivacyRouteMode
import com.foxhole.beta.core.model.PrivacyRouteScope
import com.foxhole.beta.core.model.Profile
import com.foxhole.beta.core.model.ProfileSourceType
import com.foxhole.beta.core.model.ProxyInboundSettings
import com.foxhole.beta.core.model.ProxySurfaceMode
import com.foxhole.beta.core.model.RoutingPresetOverrideMode
import com.foxhole.beta.core.model.RoutingPresetSource
import com.foxhole.beta.core.model.RoutingRuleAction
import com.foxhole.beta.core.model.Settings
import com.foxhole.beta.core.model.SmartStartTransportPriority
import com.foxhole.beta.core.model.StatisticsMetric
import com.foxhole.beta.core.model.StatisticsRefreshInterval
import com.foxhole.beta.core.model.StatisticsRetention
import com.foxhole.beta.core.model.SubscriptionRefreshInterval
import com.foxhole.beta.core.model.ThemeMode
import com.foxhole.beta.core.model.TrafficMode
import com.foxhole.beta.core.model.TunStack
import com.foxhole.beta.core.model.V2RayApiSettings
import com.foxhole.beta.core.network.IpInfoFetchMode
import com.foxhole.beta.core.network.NetworkFingerprint
import com.foxhole.beta.core.profile.AutoConnectProbeCandidate
import com.foxhole.beta.core.profile.AutoConnectProbeResult
import com.foxhole.beta.core.profile.PreparedProfileExport
import com.foxhole.beta.core.profile.ProfileExportRequest
import com.foxhole.beta.core.settings.AppTrafficStatsRecorder
import com.foxhole.beta.core.smart.SmartStartController
import com.foxhole.beta.vpn.FoxholeVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class HomeViewModel(
    application: Application,
) : AndroidViewModel(application) {
    internal val container: FoxholeHomeDependencies = (application as FoxholeApplication).appGraph
    internal val appTrafficStatsRecorder =
        AppTrafficStatsRecorder(
            anomalyRepository = container.anomalyRepository,
            context = application,
        )
    internal val initialSettings = container.settingsRepository.settings.value
    internal val clipboard = application.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    internal val installedAppsMutable = MutableStateFlow<List<InstalledAppOption>>(emptyList())
    internal val profilesLoadedMutable = MutableStateFlow(false)
    internal val installedAppsLoadingMutable = MutableStateFlow(false)
    internal val installedAppsLoadedMutable = MutableStateFlow(false)
    internal val ipInfoLoadingMutable = MutableStateFlow(false)
    internal val dashboardConnectionMetricsLoadingMutable = MutableStateFlow(false)
    internal val profileOptionLatenciesMutable = MutableStateFlow<Map<ProfileOptionLatencyKey, Long>>(emptyMap())
    internal val profileOptionDownMutable = MutableStateFlow<Set<ProfileOptionLatencyKey>>(emptySet())
    internal val profileOptionLatencyUnavailableMutable = MutableStateFlow<Set<ProfileOptionLatencyKey>>(emptySet())
    internal val profileOptionServerPingsMutable =
        MutableStateFlow<Map<ProfileOptionLatencyKey, ProfileOptionServerPingState>>(
            emptyMap()
        )
    internal val profileOptionMetricsUpdatedAtMutable = MutableStateFlow<Map<ProfileOptionLatencyKey, Long>>(emptyMap())
    internal val protocolMetricsRefreshingProfileIdsMutable = MutableStateFlow<Set<Long>>(emptySet())
    internal val protocolMetricsRefreshingOptionIdByProfileIdMutable = MutableStateFlow<Map<Long, String>>(emptyMap())
    internal val recommendedProtocolMutable = MutableStateFlow<ProtocolRecommendationState?>(null)
    internal val runtimeReloadPendingMutable = MutableStateFlow(false)
    internal val runtimeReconnectRequiredMutable = MutableStateFlow(false)
    internal val reconnectInProgressMutable = MutableStateFlow(false)
    internal val torOperationMutable = MutableStateFlow(HomeTorOperationUiState())
    internal val dnsFilterRefreshInProgressMutable = MutableStateFlow(false)
    internal val profileReconnectPromptUntilMutable = MutableStateFlow(0L)
    internal val insecureTlsImportWarningMutable = MutableStateFlow<InsecureTlsImportWarningState?>(null)
    internal val catalogPresetPreviewsMutable =
        MutableStateFlow<Map<Long, List<RoutingRepository.RoutingCatalogPresetPreview>>>(
            emptyMap()
        )
    internal val startupActiveProfileMutable =
        MutableStateFlow(container.settingsRepository.settings.value.lastActiveProfile?.toStartupProfile())

    internal val profileStreams =
        combine(
            container.profileRepository.profiles,
            container.profileRepository.activeProfile,
            container.settingsRepository.settings,
        ) { profiles, activeProfile, settings ->
            HomeProfileStreams(
                profiles = profiles,
                activeProfile = activeProfile,
                settings = settings,
            )
        }

    internal val realtimeStreams =
        combine(
            container.connectionController.snapshot,
            container.connectionController.ipInfo,
            container.connectionController.traffic,
        ) { connection, ipInfo, traffic ->
            HomeRealtimeStreams(
                connection = connection,
                ipInfo = ipInfo,
                traffic = traffic,
            )
        }

    internal val connectionStreams =
        combine(
            profileStreams,
            realtimeStreams,
        ) { profileStreams, realtimeStreams ->
            HomeConnectionStreams(
                profiles = profileStreams.profiles,
                activeProfile = profileStreams.activeProfile,
                settings = profileStreams.settings,
                connection = realtimeStreams.connection,
                ipInfo = realtimeStreams.ipInfo,
                traffic = realtimeStreams.traffic,
            )
        }

    internal val routingStreams =
        combine(
            container.routingRepository.presets,
            container.routingRepository.activePreset,
            container.routingRepository.catalogs,
        ) { presets, activePreset, catalogs ->
            HomeRoutingStreams(
                presets = presets,
                activePreset = activePreset,
                catalogs = catalogs,
            )
        }

    internal val localState =
        combine(
            combine(
                installedAppsMutable,
                profilesLoadedMutable,
                installedAppsLoadingMutable,
                installedAppsLoadedMutable,
                ipInfoLoadingMutable,
            ) { installedApps, profilesLoaded, installedAppsLoading, installedAppsLoaded, ipInfoLoading ->
                HomeInstalledAppsStreams(
                    profilesLoaded = profilesLoaded,
                    installedApps = installedApps,
                    installedAppsLoading = installedAppsLoading,
                    installedAppsLoaded = installedAppsLoaded,
                    ipInfoLoading = ipInfoLoading,
                )
            },
            combine(
                combine(
                    runtimeReloadPendingMutable,
                    catalogPresetPreviewsMutable,
                    startupActiveProfileMutable,
                    container.connectionController.appliedRuntimeSignature,
                    dashboardConnectionMetricsLoadingMutable,
                ) { runtimeReloadPending, catalogPresetPreviews, startupActiveProfile, appliedRuntimeSignature, dashboardConnectionMetricsLoading ->
                    HomeTrailingLocalState(
                        runtimeReloadPending = runtimeReloadPending,
                        catalogPresetPreviews = catalogPresetPreviews,
                        startupActiveProfile = startupActiveProfile,
                        appliedRuntimeSignature = appliedRuntimeSignature,
                        dashboardConnectionMetricsLoading = dashboardConnectionMetricsLoading,
                    )
                },
                torOperationMutable,
            ) { trailingState, torOperation ->
                trailingState.copy(torOperation = torOperation)
            },
        ) { installedAppsStreams, trailingState ->
            HomeLocalState(
                streams =
                HomeLocalStreams(
                    profilesLoaded = installedAppsStreams.profilesLoaded,
                    installedApps = installedAppsStreams.installedApps,
                    installedAppsLoading = installedAppsStreams.installedAppsLoading,
                    installedAppsLoaded = installedAppsStreams.installedAppsLoaded,
                    ipInfoLoading = installedAppsStreams.ipInfoLoading,
                    dashboardConnectionMetricsLoading = trailingState.dashboardConnectionMetricsLoading,
                    runtimeReloadPending = trailingState.runtimeReloadPending,
                    torOperation = trailingState.torOperation,
                    catalogPresetPreviews = trailingState.catalogPresetPreviews,
                    appliedRuntimeSignature = trailingState.appliedRuntimeSignature,
                ),
                startupActiveProfile = trailingState.startupActiveProfile,
            )
        }
    private val reconnectState =
        combine(
            reconnectInProgressMutable,
            profileReconnectPromptUntilMutable,
            runtimeReconnectRequiredMutable,
        ) { reconnectInProgress, profileReconnectPromptUntil, runtimeReconnectRequired ->
            HomeReconnectStreams(
                inProgress = reconnectInProgress,
                promptUntilElapsedMs = profileReconnectPromptUntil,
                runtimeReconnectRequired = runtimeReconnectRequired,
            )
        }
    private val appActivityStreams =
        combine(
            container.anomalyRepository.recentAppTrafficWindows,
            container.anomalyRepository.recentNetworkActivityEvents,
        ) { appTrafficWindows, networkActivityEvents ->
            HomeAppActivityStreams(
                appTrafficWindows = appTrafficWindows,
                networkActivityEvents = networkActivityEvents,
            )
        }
    private val activityStreams =
        combine(
            container.diagnosticsLogger.entries,
            container.anomalyRepository.recentEvents,
            appActivityStreams,
            container.anomalyRepository.recentTrafficWindows,
            reconnectState,
        ) { diagnosticEntries, anomalyEvents, appActivityStreams, trafficWindows, reconnectState ->
            HomeActivityStreams(
                diagnosticEntries = diagnosticEntries,
                anomalyEvents = anomalyEvents,
                appTrafficWindows = appActivityStreams.appTrafficWindows,
                networkActivityEvents = appActivityStreams.networkActivityEvents,
                trafficWindows = trafficWindows,
                reconnectState = reconnectState,
            )
        }

    val uiState: StateFlow<HomeUiState> =
        combine(
            connectionStreams,
            routingStreams,
            localState,
            activityStreams,
        ) { connectionStreams, routingStreams, localState, activityStreams ->
            val localStreams = localState.streams
            val reconnectState = activityStreams.reconnectState
            val resolvedActiveProfile =
                HomeActiveProfileResolver.resolve(
                    profiles = connectionStreams.profiles,
                    activeProfile = connectionStreams.activeProfile,
                    startupFallbackProfile = localState.startupActiveProfile,
                )
            val profileReconnectRequiredRaw =
                isProfileReconnectRequired(
                    activeProfile = resolvedActiveProfile,
                    connection = connectionStreams.connection,
                )
            val profileReconnectRequired =
                profileReconnectRequiredRaw &&
                    reconnectState.promptUntilElapsedMs > 0L &&
                    SystemClock.elapsedRealtime() <= reconnectState.promptUntilElapsedMs
            val runtimeReconnectRequired =
                reconnectState.runtimeReconnectRequired &&
                    connectionStreams.connection.state in ACTIVE_CONNECTION_STATES
            HomeUiState(
                profiles = connectionStreams.profiles,
                profilesLoaded = localStreams.profilesLoaded,
                activeProfile = resolvedActiveProfile,
                settings = connectionStreams.settings,
                connection = connectionStreams.connection,
                ipInfo = connectionStreams.ipInfo,
                ipInfoLoading =
                shouldShowIpInfoLoading(
                    currentIpInfo = connectionStreams.ipInfo,
                    explicitLoading = localStreams.ipInfoLoading,
                    connectionState = connectionStreams.connection.state,
                ),
                dashboardConnectionMetricsLoading = localStreams.dashboardConnectionMetricsLoading,
                traffic = connectionStreams.traffic,
                presets = routingStreams.presets,
                activePreset = routingStreams.activePreset,
                catalogs = routingStreams.catalogs,
                installedApps = localStreams.installedApps,
                installedAppsLoading = localStreams.installedAppsLoading,
                installedAppsLoaded = localStreams.installedAppsLoaded,
                reconnectRequired = profileReconnectRequired || runtimeReconnectRequired,
                reconnectInProgress = reconnectState.inProgress,
                torOperation = localStreams.torOperation,
                profileReconnectPromptUntilElapsedMs =
                if (profileReconnectRequired) {
                    reconnectState.promptUntilElapsedMs
                } else {
                    0L
                },
                diagnosticEntries = activityStreams.diagnosticEntries,
                anomalyEvents = activityStreams.anomalyEvents,
                appTrafficWindows = activityStreams.appTrafficWindows,
                networkActivityEvents = activityStreams.networkActivityEvents,
                trafficWindows = activityStreams.trafficWindows,
                catalogPresetPreviews = localStreams.catalogPresetPreviews,
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            HomeUiState(settings = initialSettings),
        )

    val themeMode: StateFlow<ThemeMode> = container.settingsRepository.themeMode

    val secureScreenEnabled: StateFlow<Boolean> =
        uiState
            .map { it.settings.expert.blockScreenshots }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                initialSettings.expert.blockScreenshots,
            )

    val insecureTlsImportWarning: StateFlow<InsecureTlsImportWarningState?> = insecureTlsImportWarningMutable

    internal val autoConnectUiStateMutable = MutableStateFlow(AutoConnectUiState())

    private val protocolMetricsRefreshingState =
        combine(
            protocolMetricsRefreshingProfileIdsMutable,
            protocolMetricsRefreshingOptionIdByProfileIdMutable,
        ) { profileIds, optionIds ->
            ProtocolMetricsRefreshingUiState(
                profileIds = profileIds,
                optionIdByProfileId = optionIds,
            )
        }

    private val protocolMetricsState =
        combine(
            profileOptionServerPingsMutable,
            profileOptionMetricsUpdatedAtMutable,
            profileOptionDownMutable,
            protocolMetricsRefreshingState,
            recommendedProtocolMutable,
        ) { serverPings, updatedAt, downOptionIds, refreshingState, recommendation ->
            ProtocolMetricsUiState(
                serverPings = serverPings,
                updatedAt = updatedAt,
                downOptionIds = downOptionIds,
                refreshingProfileIds = refreshingState.profileIds,
                refreshingOptionIdByProfileId = refreshingState.optionIdByProfileId,
                recommendation = recommendation,
            )
        }

    val homeRouteState: StateFlow<HomeRouteUiState> =
        combine(
            uiState,
            autoConnectUiStateMutable,
            profileOptionLatenciesMutable,
            profileOptionLatencyUnavailableMutable,
            protocolMetricsState,
        ) { state, autoConnect, profileOptionLatencies, profileOptionLatencyUnavailable, protocolMetrics ->
            buildHomeRouteUiState(
                state = state,
                autoConnect = autoConnect,
                profileOptionLatencies = profileOptionLatencies,
                profileOptionLatencyUnavailable = profileOptionLatencyUnavailable,
                protocolMetrics = protocolMetrics,
                currentNetworkFingerprintKey = currentNetworkFingerprintForSmartRules()?.key,
            )
        }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                HomeRouteUiState(settings = initialSettings),
            )

    private val trafficMapRuntimeAvailable =
        combine(
            container.connectionController.snapshot,
            container.settingsRepository.settings,
        ) { connection, settings ->
            isTrafficMapRuntimeAvailable(
                connection = connection,
                settings = settings,
                activeVpnNetworkAvailable = container.connectionController.hasActiveVpnNetwork(),
            )
        }

    private val initialTrafficMapOriginIpInfo =
        trafficMapOriginIpInfoCandidate(
            connection = container.connectionController.snapshot.value,
            deviceIpInfo = container.connectionController.deviceIpInfo.value,
            ipInfo = container.connectionController.ipInfo.value,
            protocolSearchRunning = false,
        )

    private val trafficMapOriginIpInfo: StateFlow<IpInfo?> =
        combine(
            container.connectionController.snapshot,
            container.connectionController.deviceIpInfo,
            container.connectionController.ipInfo,
            autoConnectUiStateMutable,
            protocolMetricsRefreshingProfileIdsMutable,
        ) { connection, deviceIpInfo, ipInfo, autoConnect, protocolMetricsRefreshingProfileIds ->
            val protocolSearchRunning = autoConnect.running || protocolMetricsRefreshingProfileIds.isNotEmpty()
            trafficMapOriginIpInfoCandidate(
                connection = connection,
                deviceIpInfo = deviceIpInfo,
                ipInfo = ipInfo,
                protocolSearchRunning = protocolSearchRunning,
            ) to protocolSearchRunning
        }
            .runningFold(initialTrafficMapOriginIpInfo to false) { previous, next ->
                if (next.second) {
                    previous.first to true
                } else {
                    next
                }
            }
            .map { (ipInfo, _) -> ipInfo }
            .distinctUntilChanged()
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                initialTrafficMapOriginIpInfo,
            )

    val trafficMapUiState =
        container.trafficMapRepository.trafficMapState(
            scope = viewModelScope,
            originIpInfo = trafficMapOriginIpInfo,
            runtimeAvailable = trafficMapRuntimeAvailable,
        )

    private fun trafficMapOriginIpInfoCandidate(
        connection: ConnectionSnapshot,
        deviceIpInfo: IpInfo?,
        ipInfo: IpInfo?,
        protocolSearchRunning: Boolean,
    ): IpInfo? {
        if (deviceIpInfo != null) {
            return deviceIpInfo
        }
        val realTunnelActive =
            connection.state in ACTIVE_CONNECTION_STATES &&
                connection.profileId != FoxholeVpnService.LOCAL_GUARD_PROFILE_ID
        val staleBeforeActiveTunnel =
            ipInfo != null &&
                !protocolSearchRunning &&
                realTunnelActive &&
                ipInfo.fetchedAt < connection.lastChangeAt
        return if (realTunnelActive || staleBeforeActiveTunnel) {
            null
        } else {
            ipInfo
        }
    }

    val profilesRouteState: StateFlow<ProfilesRouteUiState> =
        combine(
            uiState,
            autoConnectUiStateMutable,
            protocolMetricsState,
        ) { state, autoConnect, protocolMetrics ->
            buildProfilesRouteUiState(
                state = state,
                autoConnect = autoConnect,
                protocolMetrics = protocolMetrics,
                networkFingerprintKey = currentNetworkFingerprintForSmartRules()?.key,
            )
        }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                ProfilesRouteUiState(),
            )

    val settingsRouteState: StateFlow<SettingsRouteUiState> =
        combine(
            uiState,
            dnsFilterRefreshInProgressMutable,
            trafficMapUiState,
        ) { state, dnsFilterRefreshInProgress, trafficMapState ->
            val routeState = state.toSettingsRouteUiState(dnsFilterRefreshInProgress = dnsFilterRefreshInProgress)
            routeState.copy(
                statisticsDashboard =
                buildStatisticsDashboardUiState(
                    state = routeState,
                    trafficMapState = trafficMapState,
                ),
            )
        }
            .flowOn(Dispatchers.Default)
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                SettingsRouteUiState(),
            )

    val routingRouteState: StateFlow<RoutingRouteUiState> =
        uiState
            .map(HomeUiState::toRoutingRouteUiState)
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                RoutingRouteUiState(),
            )

    val diagnosticsRouteState: StateFlow<DiagnosticsRouteUiState> =
        uiState
            .map(HomeUiState::toDiagnosticsRouteUiState)
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                DiagnosticsRouteUiState(),
            )

    internal val snackbars = MutableSharedFlow<FoxholeBannerEvent>(extraBufferCapacity = 16)
    val requestVpnPermission = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val requestNotificationPermission = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    internal var pendingConnectRequest: PendingConnectRequest? = null
    internal var ipInfoRefreshJob: Job? = null
    internal var ipInfoRefreshToken: Long = 0L
    internal var activeIpInfoRefreshReason: IpInfoRefreshReason? = null
    internal var pendingPostConnectIpRefresh: Boolean = false
    internal var connectedIpRefreshJob: Job? = null
    internal var profileLatencyRefreshJob: Job? = null
    internal var runtimeReloadPendingJob: Job? = null
    internal var torOperationTimeoutJob: Job? = null
    internal var profileReconnectPromptJob: Job? = null
    internal var autoConnectJob: Job? = null
    internal var reconnectJob: Job? = null
    internal var protocolMetricsRefreshJob: Job? = null
    internal var appTrafficStatsJob: Job? = null
    internal var appTrafficStatsIntervalMs: Long = APP_TRAFFIC_BACKGROUND_SAMPLE_INTERVAL_MS
    internal var protocolMetricsRestoreOnCancel: Boolean = true
    internal var dashboardVisible: Boolean = false
    internal var statisticsVisible: Boolean = false
    internal var reconnectPromptPendingUntilDashboard: Boolean = false

    init {
        viewModelScope.launch {
            runCatching { container.settingsRepository.warmUp() }
                .onFailure {
                    snackbars.emit(errorBanner(R.string.settings_secure_storage_failed))
                }
            val settings = container.settingsRepository.settings.value
            syncAppTrafficStatsSampler(appTrafficStatsRuntimeAllowed(settings))
        }
        viewModelScope.launch {
            container.settingsRepository.settings.collect { settings ->
                val enabled = appTrafficStatsRuntimeAllowed(settings)
                syncAppTrafficStatsSampler(enabled)
            }
        }
        viewModelScope.launch {
            container.profileRepository.ensureActiveProfileInvariant()
            startupActiveProfileMutable.value = container.profileRepository.getActiveProfile()
            container.profileRepository.profiles.first()
            profilesLoadedMutable.value = true
        }
        viewModelScope.launch {
            container.profileRepository.activeProfile.collect { activeProfile ->
                startupActiveProfileMutable.value = activeProfile
            }
        }
        viewModelScope.launch {
            var previousState: ConnectionState? = null
            container.connectionController.snapshot.collect { snapshot ->
                val currentState = snapshot.state
                val shouldRefreshConnectedIp =
                    shouldAutoRefreshIpAfterConnect(
                        previousState = previousState,
                        currentState = currentState,
                    )
                val shouldRefreshIdleIp =
                    shouldAutoRefreshIpAfterDisconnect(
                        previousState = previousState,
                        currentState = currentState,
                    )
                previousState = currentState
                if (currentState !in ACTIVE_CONNECTION_STATES) {
                    invalidateIpInfoRefreshes()
                    clearRuntimeReloadPending()
                    clearRuntimeReconnectRequired()
                    clearTorOperation()
                    clearProfileLatencyRefresh()
                    clearProtocolLatencyState()
                    dashboardConnectionMetricsLoadingMutable.value = false
                }
                if (shouldRefreshConnectedIp) {
                    scheduleConnectedIpRefresh()
                    if (dashboardVisible && !autoConnectUiStateMutable.value.running) {
                        scheduleActiveProfileLatencyRefresh()
                    }
                } else if (shouldRefreshIdleIp && !autoConnectUiStateMutable.value.running) {
                    startIpInfoRefresh(
                        reportFailures = false,
                        showLoading = false,
                        clearExistingIp = false,
                        fetchMode = IpInfoFetchMode.ENTRY_QUICK,
                        minimumLoadingDurationMs = 0L,
                    )
                }
            }
        }
        viewModelScope.launch {
            combine(
                container.connectionController.snapshot,
                container.connectionController.ipInfo,
                torOperationMutable,
            ) { snapshot, ipInfo, torOperation ->
                Triple(snapshot, ipInfo, torOperation)
            }.collect { (snapshot, ipInfo, torOperation) ->
                if (
                    torOperation.active &&
                    snapshot.state == ConnectionState.CONNECTED &&
                    ipInfo != null
                ) {
                    maybeFinishTorOperation(torOperation, ipInfo)
                }
            }
        }
    }

    fun onAppForegrounded() {
        viewModelScope.launch {
            val reconciledActiveVpn = container.connectionController.reconcileActiveVpnNetworkIfNeeded()
            if (reconciledActiveVpn) {
                scheduleConnectedIpRefresh(reason = IpInfoRefreshReason.RESTORED_VPN, clearExistingIp = true)
            } else {
                container.connectionController.syncLocalGuard()
                refreshIpInfoOnForegroundIfNeeded()
            }
        }
    }

    private fun refreshIpInfoOnForegroundIfNeeded() {
        val runtimeState = container.connectionController.snapshot.value.state
        if (!shouldAutoRefreshIpOnForeground(runtimeState) || ipInfoLoadingMutable.value) {
            return
        }
        if (runtimeState == ConnectionState.CONNECTED) {
            scheduleConnectedIpRefresh(reason = IpInfoRefreshReason.FOREGROUND, clearExistingIp = false)
        } else {
            startIpInfoRefresh(
                reportFailures = false,
                showLoading = false,
                clearExistingIp = false,
                fetchMode = IpInfoFetchMode.ENTRY_QUICK,
                minimumLoadingDurationMs = 0L,
            )
        }
    }

    fun onPasteFromClipboard() {
        val text = clipboard.primaryClip?.firstTextItem()
        if (text.isNullOrBlank()) {
            snackbars.tryEmit(infoBanner(R.string.clipboard_empty))
            return
        }
        importProfileRaw(text)
    }

    fun importProfileRaw(value: String) {
        if (value.isBlank()) {
            snackbars.tryEmit(errorBanner(R.string.profile_import_failed))
            return
        }
        val boundedValue =
            try {
                requireLocalProfileImportWithinLimit(value)
            } catch (_: ProfileImportPayloadTooLargeException) {
                snackbars.tryEmit(errorBanner(R.string.profile_import_too_large))
                return
            }
        importRaw(boundedValue)
    }

    fun onToggleConnection() {
        if (cancelProtocolSearchConnection()) {
            return
        }
        cancelAutoConnect(clearUiOnly = true)
        val state = uiState.value
        if (cancelReconnectConnection(state)) {
            return
        }
        val activeProfile = state.activeProfile
        if (activeProfile == null) {
            handleToggleWithoutActiveProfile(state)
            return
        }
        val connectProfile = mobileNetworkProfileOverride(state) ?: activeProfile
        if (togglePrimaryRuntimeConnection(state, activeProfile)) {
            return
        }
        connectSelectedProfile(state, connectProfile)
    }

    private fun cancelProtocolSearchConnection(): Boolean {
        val protocolSearchRunning = autoConnectUiStateMutable.value.running || protocolMetricsRefreshJob != null
        if (!protocolSearchRunning) {
            return false
        }
        cancelAutoConnect(clearUiOnly = true)
        cancelSmartProfileMetricsRefreshInternal(restoreConnection = false)
        container.connectionController.disconnect(suppressLocalGuard = false)
        return true
    }

    private fun cancelReconnectConnection(state: HomeUiState): Boolean {
        if (!state.reconnectInProgress) {
            return false
        }
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectInProgressMutable.value = false
        container.connectionController.disconnect(suppressLocalGuard = false)
        return true
    }

    private fun handleToggleWithoutActiveProfile(state: HomeUiState) {
        if (!state.torOnlyRouteReady()) {
            snackbars.tryEmit(errorBanner(R.string.error_profile_missing))
            return
        }
        if (state.settings.privacyRoute.scope == PrivacyRouteScope.ALL_APPS) {
            snackbars.tryEmit(infoBanner(R.string.privacy_route_all_apps_start_warning))
        }
        markTorOperation(HomeTorOperationKind.CONNECTING)
        requestManualConnectPermissionOrConnect(FoxholeVpnService.TOR_ONLY_PROFILE_ID)
    }

    private fun HomeUiState.torOnlyRouteReady(): Boolean =
        settings.privacyRoute.directTorEnabled &&
            (
                settings.privacyRoute.scope == PrivacyRouteScope.ALL_APPS ||
                    settings.privacyRoute.selectedPackages.any(String::isNotBlank)
                )

    private fun togglePrimaryRuntimeConnection(
        state: HomeUiState,
        activeProfile: Profile,
    ): Boolean {
        if (!state.connection.isPrimaryConnectionRuntime()) {
            return false
        }
        if (state.reconnectRequired) {
            requestReconnect(activeProfile.id)
        } else {
            container.connectionController.disconnect(suppressLocalGuard = false)
        }
        return true
    }

    private fun connectSelectedProfile(
        state: HomeUiState,
        connectProfile: Profile,
    ) {
        if (state.settings.traffic.mode == TrafficMode.PROXY) {
            connect(connectProfile.id)
        } else {
            requestManualConnectPermissionOrConnect(connectProfile.id)
        }
    }

    private fun requestManualConnectPermissionOrConnect(profileId: Long) {
        val prepareIntent = android.net.VpnService.prepare(getApplication())
        if (prepareIntent != null) {
            pendingConnectRequest =
                PendingConnectRequest(
                    profileId = profileId,
                    action = PendingConnectAction.MANUAL,
                )
            requestVpnPermission.tryEmit(Unit)
        } else {
            connect(profileId)
        }
    }

    fun onVpnPermissionResult(granted: Boolean) {
        val request = pendingConnectRequest
        pendingConnectRequest = null
        if (!granted || request == null) {
            if (request?.profileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID) {
                clearTorOperation()
            }
            snackbars.tryEmit(errorBanner(R.string.vpn_permission_denied))
            return
        }
        when (request.action) {
            PendingConnectAction.MANUAL -> connect(request.profileId)
            PendingConnectAction.AUTO_CONNECT -> startAutoConnect(request.profileId)
            PendingConnectAction.RECONNECT -> reconnect(request.profileId)
            PendingConnectAction.LOCAL_GUARD ->
                viewModelScope.launch {
                    container.connectionController.syncLocalGuard()
                }
        }
    }

    fun onRefreshProfile() {
        val activeProfile = uiState.value.activeProfile ?: return
        if (activeProfile.sourceType != ProfileSourceType.SUBSCRIPTION_URL) {
            snackbars.tryEmit(infoBanner(R.string.profile_not_refreshable))
            return
        }
        viewModelScope.launch {
            refreshProfileWithInsecureTlsDecision(
                profileId = activeProfile.id,
                allowInsecureTlsForProfile = false,
                excludeInsecureTlsOptions = false,
            )
        }
    }

    fun onSelectProfile(profileId: Long) {
        cancelAutoConnect(clearUiOnly = true)
        viewModelScope.launch {
            container.connectionController.setActiveProfile(profileId)
            val updated =
                container.profileRepository.getProfile(profileId)?.copy(isActive = true)
                    ?: uiState.value.profiles.firstOrNull { it.id == profileId }?.copy(isActive = true)
            startupActiveProfileMutable.value = updated
            markProfileReconnectPromptWindow()
        }
    }

    fun onSelectProfileProtocolOption(
        profileId: Long,
        optionId: String,
    ) {
        cancelAutoConnect(clearUiOnly = true)
        viewModelScope.launch {
            runCatching {
                val updated = container.profileRepository.selectProfileProtocolOption(profileId, optionId)
                if (updated.isActive) {
                    startupActiveProfileMutable.value = updated
                    markProfileReconnectPromptWindow()
                }
                if (uiState.value.activeProfile?.id == profileId && uiState.value.connection.state in ACTIVE_CONNECTION_STATES) {
                    markRuntimeReloadPending()
                    clearProfileLatencyRefresh()
                }
            }.onFailure {
                emitError(it.message ?: getApplication<Application>().getString(R.string.profile_update_failed))
            }
        }
    }

    private fun markProfileReconnectPromptWindow() {
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
            }
    }

    private fun startPendingProfileReconnectPromptIfNeeded() {
        if (!reconnectPromptPendingUntilDashboard) {
            return
        }
        reconnectPromptPendingUntilDashboard = false
        markProfileReconnectPromptWindow()
    }

    fun onSmartProfileAutoConnectExcludedOptionsChanged(
        profileId: Long,
        excludedOptionIds: Set<String>,
    ) {
        cancelAutoConnect(clearUiOnly = true)
        viewModelScope.launch {
            runCatching {
                container.settingsRepository.updateSmartProfileExcludedProtocolOptionIds(
                    profileId = profileId,
                    excludedProtocolOptionIds = excludedOptionIds,
                )
            }.onFailure {
                emitError(it.message ?: getApplication<Application>().getString(R.string.profile_update_failed))
            }
        }
    }

    fun onAutoConnectActiveProfile() = onAutoConnectActiveProfileInternal()

    internal fun requestReconnect(profileId: Long) = requestReconnectInternal(profileId)

    internal fun reconnect(profileId: Long) = reconnectInternal(profileId)

    internal fun startAutoConnect(profileId: Long) = startAutoConnectInternal(profileId)

    internal suspend fun probeAutoConnectCandidate(
        profileId: Long,
        candidate: AutoConnectProbeCandidate,
        networkFingerprint: String?,
        previousVpnNetworkHandle: Long? = null,
    ): AutoConnectProbeResult =
        probeAutoConnectCandidateInternal(
            profileId = profileId,
            candidate = candidate,
            networkFingerprint = networkFingerprint,
            previousVpnNetworkHandle = previousVpnNetworkHandle,
        )

    internal fun autoConnectFailureMessage(
        reasonCode: AutoConnectReasonCode,
        snapshotMessage: String?,
    ): String = autoConnectFailureMessageInternal(reasonCode, snapshotMessage)

    internal suspend fun recordAutoConnectCandidateOutcome(
        profileId: Long,
        result: AutoConnectProbeResult,
        networkFingerprint: String?,
        headline: String,
        markAsLastKnownGood: Boolean = false,
        countTowardOutcomeHistory: Boolean = true,
        affectsFailureRankingMemory: Boolean = true,
    ) = recordAutoConnectCandidateOutcomeInternal(
        profileId = profileId,
        result = result,
        networkFingerprint = networkFingerprint,
        headline = headline,
        markAsLastKnownGood = markAsLastKnownGood,
        countTowardOutcomeHistory = countTowardOutcomeHistory,
        affectsFailureRankingMemory = affectsFailureRankingMemory,
    )

    internal suspend fun awaitAutoConnectConnectionOutcome(): ConnectionSnapshot? =
        awaitAutoConnectConnectionOutcomeInternal()

    internal suspend fun awaitAutoConnectValidationGraceOutcome(): ConnectionSnapshot? =
        awaitAutoConnectValidationGraceOutcomeInternal()

    internal suspend fun awaitDisconnectedForAutoConnect(previousVpnNetworkHandle: Long? = null): Long? =
        awaitDisconnectedForAutoConnectInternal(previousVpnNetworkHandle)

    internal fun initializeAutoConnectUi(candidates: List<AutoConnectProbeCandidate>) =
        initializeAutoConnectUiInternal(candidates)

    internal fun markAutoConnectCandidateTesting(
        profileId: Long,
        candidate: AutoConnectProbeCandidate,
    ) = markAutoConnectCandidateTestingInternal(profileId, candidate)

    internal fun markAutoConnectCandidateFinished(result: AutoConnectProbeResult) =
        markAutoConnectCandidateFinishedInternal(result)

    internal fun markAutoConnectWinner(result: AutoConnectProbeResult) =
        markAutoConnectWinnerInternal(result)

    internal fun clearAutoConnectUiState() = clearAutoConnectUiStateInternal()

    internal fun cancelAutoConnect(clearUiOnly: Boolean) = cancelAutoConnectInternal(clearUiOnly)

    internal fun availableAutoConnectCandidates(
        profileId: Long,
        profile: Profile,
        networkFingerprint: NetworkFingerprint?,
    ): List<AutoConnectProbeCandidate> =
        availableAutoConnectCandidatesInternal(profileId, profile, networkFingerprint)

    internal fun excludedAutoConnectOptionIds(profileId: Long): Set<String> =
        excludedAutoConnectOptionIdsInternal(profileId)

    internal fun cacheProtocolLatency(
        profileId: Long,
        optionId: String,
        latencyMs: Long,
    ) = cacheProtocolLatencyInternal(profileId, optionId, latencyMs)

    internal fun markProtocolLatencyUnavailable(
        profileId: Long,
        optionId: String,
    ) = markProtocolLatencyUnavailableInternal(profileId, optionId)

    internal fun markProtocolDown(
        profileId: Long,
        optionId: String,
    ) = markProtocolDownInternal(profileId, optionId)

    internal fun clearProtocolLatencyState(
        profileId: Long? = null,
        optionId: String? = null,
    ) = clearProtocolLatencyStateInternal(profileId, optionId)

    fun refreshSmartProfileMetrics(profileId: Long) = refreshSmartProfileMetricsInternal(profileId)

    fun cancelSmartProfileMetricsRefresh() = cancelSmartProfileMetricsRefreshInternal(restoreConnection = true)

    fun onProtocolRecommendationAccepted() = onProtocolRecommendationAcceptedInternal()

    internal fun scheduleActiveProfileLatencyRefresh() = scheduleActiveProfileLatencyRefreshInternal()

    internal fun clearProfileLatencyRefresh() = clearProfileLatencyRefreshInternal()

    fun profile(profileId: Long): Profile? = profileInternal(profileId)

    fun refreshProfile(profileId: Long) = refreshProfileInternal(profileId)

    fun deleteProfile(profileId: Long) = deleteProfileInternal(profileId)

    fun onThemeSelected(value: ThemeMode) = onThemeSelectedInternal(value)

    fun onLocaleSelected(value: AppLocale) = onLocaleSelectedInternal(value)

    fun onAutoReconnectChanged(value: Boolean) = onAutoReconnectChangedInternal(value)

    fun onAutoStartChanged(value: Boolean) = onAutoStartChangedInternal(value)

    fun onAutoRefreshSubscriptionsChanged(value: Boolean) = onAutoRefreshSubscriptionsChangedInternal(value)

    fun onSubscriptionRefreshIntervalSelected(value: SubscriptionRefreshInterval) = onSubscriptionRefreshIntervalSelectedInternal(
        value
    )

    fun onIpInfoEndpointChanged(value: String) = onIpInfoEndpointChangedInternal(value)

    fun onLatencyProbeMethodSelected(value: LatencyProbeMethod) = onLatencyProbeMethodSelectedInternal(value)

    fun onSmartStartProtocolSelectionTimeoutChanged(value: Int) = onSmartStartProtocolSelectionTimeoutChangedInternal(
        value
    )

    fun onSmartStartRefreshSelectionTimeoutChanged(value: Int) = onSmartStartRefreshSelectionTimeoutChangedInternal(
        value
    )

    fun onSmartStartTransportPrioritySelected(value: SmartStartTransportPriority) =
        onSmartStartTransportPrioritySelectedInternal(value)

    fun onSmartStartV2RayTunSubscriptionsEnabledChanged(value: Boolean) =
        onSmartStartV2RayTunSubscriptionsEnabledChangedInternal(value)

    fun onSmartStartFailoverEnabledChanged(value: Boolean) =
        onSmartStartFailoverEnabledChangedInternal(value)

    fun onSmartStartSubscriptionRetryAttemptsChanged(value: Int) =
        onSmartStartSubscriptionRetryAttemptsChangedInternal(value)

    fun onSmartStartSubscriptionRetryDelaySecondsChanged(value: Int) =
        onSmartStartSubscriptionRetryDelaySecondsChangedInternal(value)

    fun clearSmartStartData() = clearSmartStartDataInternal()

    fun onTunStackSelected(value: TunStack) = onTunStackSelectedInternal(value)

    fun onTrafficModeSelected(value: TrafficMode) = onTrafficModeSelectedInternal(value)

    fun onMtuChanged(value: Int) = onMtuChangedInternal(value)

    fun onPreferIpv6Changed(value: Boolean) = onPreferIpv6ChangedInternal(value)

    fun onDomainStrategySelected(value: DomainStrategy) = onDomainStrategySelectedInternal(value)

    fun onDnsSettingsChanged(value: DnsSettings) = onDnsSettingsChangedInternal(value)

    fun onDnsBypassPackagesChanged(value: List<String>) = onDnsBypassPackagesChangedInternal(value)

    fun onDnsDomainBypassRulesChanged(value: List<String>) = onDnsDomainBypassRulesChangedInternal(value)

    fun onDnsFilterManualRefresh() = onDnsFilterManualRefreshInternal()

    fun onNetworkRulesChanged(value: NetworkRulesSettings) = onNetworkRulesChangedInternal(value)

    fun acknowledgeUnsafeWarning() = acknowledgeUnsafeWarningInternal()

    fun unlockExpertSettings() = unlockExpertSettingsInternal()

    fun onShowExpertSettingsChanged(value: Boolean) = onShowExpertSettingsChangedInternal(value)

    fun onBlockScreenshotsChanged(value: Boolean) = onBlockScreenshotsChangedInternal(value)

    fun onNewAppQuarantineChanged(value: Boolean) = onNewAppQuarantineChangedInternal(value)

    fun onTrafficMapEnabledChanged(value: Boolean) = onTrafficMapEnabledChangedInternal(value)

    fun onNetworkCardEnabledChanged(value: Boolean) = onNetworkCardEnabledChangedInternal(value)

    fun onTrafficCardEnabledChanged(value: Boolean) = onTrafficCardEnabledChangedInternal(value)

    fun onShowTorQuickLaunchChanged(value: Boolean) = onShowTorQuickLaunchChangedInternal(value)

    fun onSmartStartDashboardControlsEnabledChanged(value: Boolean) =
        onSmartStartDashboardControlsEnabledChangedInternal(value)

    fun onShowFirewallStatusChanged(value: Boolean) = onShowFirewallStatusChangedInternal(value)

    fun onDashboardCardOrderChanged(value: List<com.foxhole.beta.core.model.DashboardCard>) =
        onDashboardCardOrderChangedInternal(value)

    fun onKillSwitchChanged(value: Boolean) = onKillSwitchChangedInternal(value)

    fun openSystemVpnSettings() {
        viewModelScope.launch {
            openSystemVpnSettingsInternal()
        }
    }

    fun onFirewallEnabledChanged(value: Boolean) = onFirewallEnabledChangedInternal(value)

    fun onSystemDnsProtectionChanged(value: Boolean) = onSystemDnsProtectionChangedInternal(value)

    fun onNetworkActivityLoggingChanged(value: Boolean) = onNetworkActivityLoggingChangedInternal(value)

    fun onNetworkActivityPersistentLoggingChanged(value: Boolean) = onNetworkActivityPersistentLoggingChangedInternal(
        value
    )

    fun onSmartStartReplayLoggingChanged(value: Boolean) = onSmartStartReplayLoggingChangedInternal(value)

    fun onDiagnosticsRetentionSelected(value: DiagnosticsRetention) = onDiagnosticsRetentionSelectedInternal(value)

    fun onNotifyUnusualTrafficChanged(value: Boolean) = onNotifyUnusualTrafficChangedInternal(value)

    fun onAnomalyEnabledChanged(value: Boolean) = onAnomalyEnabledChangedInternal(value)

    fun onAnomalySensitivitySelected(value: AnomalySensitivity) = onAnomalySensitivitySelectedInternal(value)

    fun onAnalyzeBackgroundTrafficChanged(value: Boolean) = onAnalyzeBackgroundTrafficChangedInternal(value)

    fun onAnalyzeDestinationCountriesChanged(value: Boolean) = onAnalyzeDestinationCountriesChangedInternal(value)

    fun onAnomalyHistoryRetentionSelected(value: AnomalyHistoryRetention) = onAnomalyHistoryRetentionSelectedInternal(
        value
    )

    fun onAllowInsecureTlsChanged(value: Boolean) = onAllowInsecureTlsChangedInternal(value)

    fun onSniffChanged(value: Boolean) = onSniffChangedInternal(value)

    fun onRouteOnlyChanged(value: Boolean) = onRouteOnlyChangedInternal(value)

    fun onStrictRouteChanged(value: Boolean) = onStrictRouteChangedInternal(value)

    fun onBypassLanChanged(value: Boolean) = onBypassLanChangedInternal(value)

    fun onAllowPrivateOutboundHostsChanged(value: Boolean) = onAllowPrivateOutboundHostsChangedInternal(value)

    fun onPerAppRoutingModeSelected(value: PerAppRoutingMode) = onPerAppRoutingModeSelectedInternal(value)

    fun onSelectedPackagesChanged(value: List<String>) = onSelectedPackagesChangedInternal(value)

    fun onPrivacyRouteModeSelected(value: PrivacyRouteMode) = onPrivacyRouteModeSelectedInternal(value)

    fun onPrivacyRouteScopeSelected(value: PrivacyRouteScope) = onPrivacyRouteScopeSelectedInternal(value)

    fun onPrivacyRouteBypassVpnTunnelChanged(value: Boolean) = onPrivacyRouteBypassVpnTunnelChangedInternal(value)

    fun onPrivacyRouteSelectedPackagesChanged(value: List<String>) = onPrivacyRouteSelectedPackagesChangedInternal(
        value
    )

    fun onRenewTorIp() {
        val state = uiState.value
        val profileId =
            state.connection.profileId?.takeIf { it == FoxholeVpnService.TOR_ONLY_PROFILE_ID }
                ?: state.activeProfile?.id
                ?: return
        if (state.connection.state in ACTIVE_CONNECTION_STATES && !state.reconnectInProgress) {
            clearRuntimeReconnectRequired()
            markTorOperation(HomeTorOperationKind.CHANGING_LOCATION)
            markRuntimeReloadPending()
            if (!container.connectionController.reload(profileId)) {
                clearTorOperation()
                clearRuntimeReloadPending()
            }
        }
    }

    fun onBlockedPackagesChanged(value: List<String>) = onBlockedPackagesChangedInternal(value)

    fun onBlockedPackagesEnabledChanged(value: Boolean) = onBlockedPackagesEnabledChangedInternal(value)

    fun onBlockAppsAlwaysChanged(value: Boolean) = onBlockAppsAlwaysChangedInternal(value)

    fun onSiteRoutingActionSelected(value: RoutingRuleAction) = onSiteRoutingActionSelectedInternal(value)

    fun onProxySurfaceModeSelected(value: ProxySurfaceMode) = onProxySurfaceModeSelectedInternal(value)

    fun onLanProxySurfaceModeSelected(value: ProxySurfaceMode) = onLanProxySurfaceModeSelectedInternal(value)

    fun onSocksSurfaceChanged(value: ProxyInboundSettings) = onSocksSurfaceChangedInternal(value)

    fun onHttpSurfaceChanged(value: ProxyInboundSettings) = onHttpSurfaceChangedInternal(value)

    fun onMixedSurfaceChanged(value: ProxyInboundSettings) = onMixedSurfaceChangedInternal(value)

    fun onLocalProxyAuthEnabledChanged(value: Boolean) = onLocalProxyAuthEnabledChangedInternal(value)

    fun onLocalProxyAuthChanged(value: LocalAuthSettings) = onLocalProxyAuthChangedInternal(value)

    fun onLanProxyAuthEnabledChanged(value: Boolean) = onLanProxyAuthEnabledChangedInternal(value)

    fun onLanProxyAuthChanged(value: LocalAuthSettings) = onLanProxyAuthChangedInternal(value)

    fun onLocalProxyLanAccessChanged(value: Boolean) = onLocalProxyLanAccessChangedInternal(value)

    fun onClashApiChanged(value: ClashApiSettings) = onClashApiChangedInternal(value)

    fun onV2RayApiChanged(value: V2RayApiSettings) = onV2RayApiChangedInternal(value)

    fun resetExpertToSafeDefaults() = resetExpertToSafeDefaultsInternal()

    fun resetExperimentalSettingsToDefaults() = resetExperimentalSettingsToDefaultsInternal()

    fun resetApplicationSettingsToDefaults() = resetApplicationSettingsToDefaultsInternal()

    fun resetUsageTracking() = resetUsageTrackingInternal()

    fun createPreset(name: String) = createPresetInternal(name)

    fun updatePreset(
        presetId: Long,
        name: String,
        overrideMode: RoutingPresetOverrideMode,
        enabled: Boolean,
    ) = updatePresetInternal(presetId, name, overrideMode, enabled)

    fun setActivePreset(presetId: Long?) = setActivePresetInternal(presetId)

    fun deletePreset(presetId: Long) = deletePresetInternal(presetId)

    fun saveRule(
        presetId: Long,
        ruleId: Long?,
        name: String,
        enabled: Boolean,
        order: Int?,
        action: RoutingRuleAction,
        matchDomains: List<String>,
        matchIpCidrs: List<String>,
        matchPorts: List<String>,
        matchProtocols: List<String>,
        matchNetworks: List<String>,
    ) = saveRuleInternal(
        presetId = presetId,
        ruleId = ruleId,
        name = name,
        enabled = enabled,
        order = order,
        action = action,
        matchDomains = matchDomains,
        matchIpCidrs = matchIpCidrs,
        matchPorts = matchPorts,
        matchProtocols = matchProtocols,
        matchNetworks = matchNetworks,
    )

    fun deleteRule(ruleId: Long) = deleteRuleInternal(ruleId)

    fun addCatalog(
        name: String,
        url: String,
    ) = addCatalogInternal(name, url)

    fun refreshCatalog(catalogId: Long) = refreshCatalogInternal(catalogId)

    fun deleteCatalog(catalogId: Long) = deleteCatalogInternal(catalogId)

    fun importPresetFromCatalog(
        catalogId: Long,
        presetId: String,
    ) = importPresetFromCatalogInternal(catalogId, presetId)

    fun loadCatalogPreview(catalogId: Long) = loadCatalogPreviewInternal(catalogId)

    suspend fun exportPresetDocument(presetId: Long): String = exportPresetDocumentInternal(presetId)

    fun importPresetText(
        raw: String,
        source: RoutingPresetSource,
    ) = importPresetTextInternal(raw, source)

    fun refreshIpInfo() = refreshIpInfoInternal()

    fun refreshIpInfoSilently() = refreshIpInfoSilentlyInternal()

    internal fun startIpInfoRefresh(
        reportFailures: Boolean,
        showLoading: Boolean,
        clearExistingIp: Boolean,
        fetchMode: IpInfoFetchMode,
        minimumLoadingDurationMs: Long,
        reason: IpInfoRefreshReason = IpInfoRefreshReason.FOREGROUND,
    ) = refreshIpInfoInternalInternal(
        reportFailures = reportFailures,
        showLoading = showLoading,
        clearExistingIp = clearExistingIp,
        fetchMode = fetchMode,
        minimumLoadingDurationMs = minimumLoadingDurationMs,
        reason = reason,
    )

    suspend fun getResolvedConfig(
        profileId: Long,
        protocolOptionIdOverride: String? = null,
    ): String = getResolvedConfigInternal(profileId, protocolOptionIdOverride)

    suspend fun createProfileExport(
        profileId: Long,
        selectionKeys: Set<String>,
    ): PreparedProfileExport = createProfileExportInternal(profileId, selectionKeys)

    suspend fun createProfileExport(
        requests: List<ProfileExportRequest>,
    ): PreparedProfileExport = createProfileExportInternal(requests)

    fun exportProfileShareIntent(document: PreparedProfileExport): Intent = exportProfileShareIntentInternal(document)

    suspend fun updateResolvedConfig(
        profileId: Long,
        editedJson: String,
        reconnectAfterSave: Boolean = false,
        protocolOptionIdOverride: String? = null,
    ): Boolean = updateResolvedConfigInternal(profileId, editedJson, reconnectAfterSave, protocolOptionIdOverride)

    fun saveSiteRule(
        ruleId: Long?,
        domains: List<String>,
        action: RoutingRuleAction,
    ) = saveSiteRuleInternal(ruleId, domains, action)

    fun onSiteRuleMoved(
        ruleId: Long,
        action: RoutingRuleAction,
        ruleIdsInOrder: List<Long>,
    ) = onSiteRuleMovedInternal(ruleId, action, ruleIdsInOrder)

    fun createDiagnosticsArchive(sanitize: Boolean = true): File = createDiagnosticsArchiveInternal(sanitize = sanitize)

    fun exportDiagnostics(file: File = createDiagnosticsArchive()): Intent = exportDiagnosticsInternal(file)

    internal fun importRaw(value: String) = importRawInternal(value)

    fun confirmInsecureTlsImport() = confirmInsecureTlsImportInternal(excludeInsecureTlsOptions = false)

    fun excludeInsecureTlsAndImport() = confirmInsecureTlsImportInternal(excludeInsecureTlsOptions = true)

    fun dismissInsecureTlsImportWarning() = dismissInsecureTlsImportWarningInternal()

    internal fun profileImportFailureMessage(
        rawInput: String,
        throwable: Throwable,
    ): String = profileImportFailureMessageInternal(rawInput, throwable)

    internal suspend fun refreshProfileAndMaybeReconnect(profileId: Long) =
        refreshProfileAndMaybeReconnectInternal(profileId)

    internal suspend fun refreshProfileAndMaybeReconnect(
        profileId: Long,
        excludeInsecureTlsOptions: Boolean,
        allowInsecureTlsForProfile: Boolean,
    ) = refreshProfileAndMaybeReconnectInternal(
        profileId = profileId,
        excludeInsecureTlsOptions = excludeInsecureTlsOptions,
        allowInsecureTlsForProfile = allowInsecureTlsForProfile,
    )

    internal suspend fun handleProfileRefreshFailure(
        profileId: Long,
        throwable: Throwable,
    ) = handleProfileRefreshFailureInternal(profileId, throwable)

    internal suspend fun handleProfileImportFailure(
        rawInput: String,
        throwable: Throwable,
    ) = handleProfileImportFailureInternal(rawInput, throwable)

    internal suspend fun reconnectProfileIfRequested(
        profileId: Long,
        reconnectNow: Boolean,
    ): Boolean = reconnectProfileIfRequestedInternal(profileId, reconnectNow)

    internal fun updateRuntimeSettingAndMaybeReload(
        updateAction: suspend () -> Unit,
    ) = updateRuntimeSettingAndMaybeReloadInternal(updateAction)

    internal fun updateRuntimeSettingAndMaybeReconnect(
        updateAction: suspend () -> Unit,
    ) = updateRuntimeSettingAndMaybeReconnectInternal(updateAction)

    internal suspend fun maybeReloadActiveRuntime(): Boolean = maybeReloadActiveRuntimeInternal()

    internal fun connect(profileId: Long) = connectInternal(profileId)

    internal fun infoBanner(stringRes: Int): FoxholeBannerEvent = infoBannerInternal(stringRes)

    internal fun errorBanner(stringRes: Int): FoxholeBannerEvent = errorBannerInternal(stringRes)

    internal suspend fun emitInfo(message: String) = emitInfoInternal(message)

    internal suspend fun emitSuccess(message: String) = emitSuccessInternal(message)

    internal suspend fun emitError(message: String) = emitErrorInternal(message)

    internal suspend fun connectNow(
        profileId: Long,
        protocolOptionId: String? = null,
        statusMessage: String? = null,
        isSmartStartConnection: Boolean = false,
        previousVpnNetworkHandle: Long? = null,
    ) = connectNowInternal(
        profileId = profileId,
        protocolOptionId = protocolOptionId,
        statusMessage = statusMessage,
        isSmartStartConnection = isSmartStartConnection,
        previousVpnNetworkHandle = previousVpnNetworkHandle,
    )

    internal fun invalidateIpInfoRefreshes(): Long = invalidateIpInfoRefreshesInternal()

    internal fun scheduleConnectedIpRefresh(
        reason: IpInfoRefreshReason = IpInfoRefreshReason.POST_CONNECT,
        clearExistingIp: Boolean = true,
    ) = scheduleConnectedIpRefreshInternal(reason = reason, clearExistingIp = clearExistingIp)

    internal fun markRuntimeReloadPending() = markRuntimeReloadPendingInternal()

    internal fun clearRuntimeReloadPending() = clearRuntimeReloadPendingInternal()

    internal fun markRuntimeReconnectRequired() = markRuntimeReconnectRequiredInternal()

    internal fun clearRuntimeReconnectRequired() = clearRuntimeReconnectRequiredInternal()

    internal fun markTorOperation(kind: HomeTorOperationKind) = markTorOperationInternal(kind)

    internal fun clearTorOperation() = clearTorOperationInternal()

    internal suspend fun maybeFinishTorOperation(
        torOperation: HomeTorOperationUiState,
        ipInfo: IpInfo?,
    ) = maybeFinishTorOperationInternal(torOperation, ipInfo)

    internal suspend fun emitTorConnectedBanner(ipInfo: IpInfo) = emitTorConnectedBannerInternal(ipInfo)

    internal fun loadInstalledApps() = loadInstalledAppsInternal()

    fun ensureInstalledAppsLoaded() = ensureInstalledAppsLoadedInternal()

    fun onTrafficUiVisibilityChanged(visible: Boolean) {
        dashboardVisible = visible
        onTrafficUiVisibilityChangedInternal(dashboardVisible || statisticsVisible)
        if (visible) {
            startPendingProfileReconnectPromptIfNeeded()
            if (container.connectionController.snapshot.value.state == ConnectionState.CONNECTED && !autoConnectUiStateMutable.value.running) {
                scheduleConnectedIpRefresh(reason = IpInfoRefreshReason.FOREGROUND, clearExistingIp = false)
                scheduleActiveProfileLatencyRefresh()
            }
        } else {
            clearProfileLatencyRefresh()
        }
    }

    fun onStatisticsUiVisibilityChanged(visible: Boolean) {
        statisticsVisible = visible
        onTrafficUiVisibilityChangedInternal(dashboardVisible || statisticsVisible)
        val runtimeAllowed =
            appTrafficStatsRuntimeAllowed(
                settings = container.settingsRepository.settings.value,
            )
        syncAppTrafficStatsSampler(runtimeAllowed)
        if (visible && runtimeAllowed) {
            viewModelScope.launch {
                loadInstalledApps()
                sampleAppTrafficStats()
            }
        }
        if (visible && container.settingsRepository.settings.value.statistics.appChangesEnabled) {
            viewModelScope.launch {
                recordInstalledAppInventoryFromLoadedApps()
            }
        }
    }

    fun onStatisticsEnabledChanged(value: Boolean) {
        viewModelScope.launch {
            container.settingsRepository.updateStatisticsEnabled(value)
            val runtimeAllowed =
                appTrafficStatsRuntimeAllowed(
                    settings = container.settingsRepository.settings.value,
                )
            syncAppTrafficStatsSampler(runtimeAllowed)
            if (value && runtimeAllowed) {
                loadInstalledApps()
                sampleAppTrafficStats()
            }
            if (value && container.settingsRepository.settings.value.statistics.appChangesEnabled) {
                recordInstalledAppInventoryFromLoadedApps()
            }
            syncLocalGuardWithPermissionRequest()
        }
    }

    fun onStatisticsRetentionSelected(value: StatisticsRetention) {
        viewModelScope.launch {
            container.settingsRepository.updateStatisticsRetention(value)
        }
    }

    fun onStatisticsRefreshIntervalSelected(value: StatisticsRefreshInterval) {
        viewModelScope.launch {
            container.settingsRepository.updateStatisticsRefreshInterval(value)
            syncAppTrafficStatsSampler(
                appTrafficStatsRuntimeAllowed(
                    settings = container.settingsRepository.settings.value,
                ),
            )
        }
    }

    fun onStatisticsMetricEnabledChanged(
        metric: StatisticsMetric,
        value: Boolean,
    ) {
        viewModelScope.launch {
            container.settingsRepository.updateStatisticsMetricEnabled(metric, value)
            val runtimeAllowed =
                appTrafficStatsRuntimeAllowed(
                    settings = container.settingsRepository.settings.value,
                )
            syncAppTrafficStatsSampler(runtimeAllowed)
            if (value && metric == StatisticsMetric.APP_TRAFFIC && runtimeAllowed) {
                loadInstalledApps()
                sampleAppTrafficStats()
            }
            if (value && metric == StatisticsMetric.APP_CHANGES) {
                recordInstalledAppInventoryFromLoadedApps()
            }
            if (metric == StatisticsMetric.COUNTRY_TRAFFIC) {
                syncLocalGuardWithPermissionRequest()
            }
        }
    }

    fun onAppTrafficStatsEnabledChanged(value: Boolean) {
        viewModelScope.launch {
            container.settingsRepository.updateAppTrafficStatsEnabled(value)
            container.connectionController.syncLocalGuard()
            val runtimeAllowed =
                appTrafficStatsRuntimeAllowed(
                    settings = container.settingsRepository.settings.value,
                )
            syncAppTrafficStatsSampler(runtimeAllowed)
            if (value && runtimeAllowed) {
                loadInstalledApps()
                sampleAppTrafficStats()
            }
        }
    }

    internal fun syncAppTrafficStatsSampler(enabled: Boolean) {
        if (!enabled) {
            appTrafficStatsJob?.cancel()
            appTrafficStatsJob = null
            appTrafficStatsIntervalMs = APP_TRAFFIC_BACKGROUND_SAMPLE_INTERVAL_MS
            return
        }
        val targetIntervalMs = appTrafficStatsSampleIntervalMs()
        if (appTrafficStatsJob != null && appTrafficStatsIntervalMs != targetIntervalMs) {
            appTrafficStatsJob?.cancel()
            appTrafficStatsJob = null
        }
        if (appTrafficStatsJob != null) {
            return
        }
        appTrafficStatsIntervalMs = targetIntervalMs
        appTrafficStatsJob =
            viewModelScope.launch {
                while (true) {
                    sampleAppTrafficStats()
                    delay(appTrafficStatsIntervalMs)
                }
            }
    }

    private fun appTrafficStatsSampleIntervalMs(): Long =
        if (statisticsVisible) {
            container.settingsRepository.settings.value.statistics.refreshInterval.seconds * 1_000L
        } else {
            APP_TRAFFIC_BACKGROUND_SAMPLE_INTERVAL_MS
        }

    internal suspend fun sampleAppTrafficStats() {
        appTrafficStatsRecorder.recordSnapshot(minDurationMs = appTrafficStatsIntervalMs)
    }

    private suspend fun recordInstalledAppInventoryFromLoadedApps() {
        val apps = installedAppsMutable.value
        if (apps.isEmpty()) {
            loadInstalledApps()
        } else {
            container.settingsRepository.recordInstalledAppInventory(apps)
        }
    }

    private fun appTrafficStatsRuntimeAllowed(
        settings: Settings,
    ): Boolean =
        settings.statistics.enabled &&
            settings.statistics.appTrafficEnabled &&
            settings.appTrafficStatsEnabled

    internal fun ClipData.firstTextItem(): String? =
        if (itemCount > 0) {
            getItemAt(0).coerceToText(getApplication<Application>()).toString()
        } else {
            null
        }

    companion object {
        internal const val CONNECTED_IP_REFRESH_DELAY_MS = 250L
        internal const val MANUAL_IP_REFRESH_MIN_LOADING_MS = 666L
        internal const val AUTO_IP_REFRESH_MIN_LOADING_MS = 450L
        internal const val CONNECTED_LATENCY_FIRST_DELAY_MS = 350L
        internal const val CONNECTED_LATENCY_REFRESH_INTERVAL_MS = 15_000L
        internal const val CONNECTED_LATENCY_TIMEOUT_MS = 2_500L
        internal const val CONNECTED_SERVER_PING_TIMEOUT_MS = 1_200L
        internal const val PROFILE_RECONNECT_PROMPT_WINDOW_MS = 13_000L
        internal const val RUNTIME_RELOAD_PENDING_TIMEOUT_MS = 1_500L
        internal const val TOR_OPERATION_MIN_VISIBLE_MS = 3_500L
        internal const val TOR_OPERATION_TIMEOUT_MS = 20_000L
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
        internal const val AUTO_CONNECT_LATENCY_MEASUREMENT_RETRY_THRESHOLD_MS = 900L
        internal const val AUTO_CONNECT_LATENCY_MEASUREMENT_RETRY_DELAY_MS = 160L
        internal const val AUTO_CONNECT_PROTOCOL_TRANSITION_SETTLE_MS = 90L
        internal const val AUTO_CONNECT_RESULT_SETTLE_MS = 500L
        internal const val AUTO_CONNECT_TOTAL_TIMEOUT_MS = 110_000L
        internal const val AUTO_CONNECT_MAX_ATTEMPTS = SmartStartController.AUTO_CONNECT_MAX_ATTEMPTS
        internal const val PROTOCOL_METRICS_PROBE_TIMEOUT_MS = 12_000L
        internal const val APP_TRAFFIC_BACKGROUND_SAMPLE_INTERVAL_MS = 60_000L
        internal const val AUTO_CONNECT_LATENCY_FALLBACK_PENALTY_MS = 750L
        internal val ACTIVE_CONNECTION_STATES =
            setOf(
                ConnectionState.CONNECTING,
                ConnectionState.CONNECTED,
                ConnectionState.RECONNECTING,
            )
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

private fun CachedActiveProfile.toStartupProfile(): Profile =
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
