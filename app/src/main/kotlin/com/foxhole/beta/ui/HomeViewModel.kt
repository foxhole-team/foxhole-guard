package com.foxhole.beta.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.foxhole.beta.BuildConfig
import com.foxhole.beta.FoxholeApplication
import com.foxhole.beta.FoxholeHomeDependencies
import com.foxhole.beta.R
import com.foxhole.beta.applyAppLocale
import com.foxhole.beta.core.data.RoutingRepository
import com.foxhole.beta.core.diagnostics.DiagnosticEntry
import com.foxhole.beta.core.model.AppLocale
import com.foxhole.beta.core.model.AutoConnectReasonCode
import com.foxhole.beta.core.model.ClashApiSettings
import com.foxhole.beta.core.model.CachedActiveProfile
import com.foxhole.beta.core.model.ConnectionSnapshot
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.DiagnosticsRetention
import com.foxhole.beta.core.model.DomainStrategy
import com.foxhole.beta.core.model.ExpertSettings
import com.foxhole.beta.core.model.InstalledAppOption
import com.foxhole.beta.core.model.IpInfo
import com.foxhole.beta.core.model.LocalAuthSettings
import com.foxhole.beta.core.model.PerAppRoutingMode
import com.foxhole.beta.core.model.Profile
import com.foxhole.beta.core.model.ProfileSourceType
import com.foxhole.beta.core.model.ProxyInboundSettings
import com.foxhole.beta.core.model.RoutingCatalog
import com.foxhole.beta.core.model.RoutingPreset
import com.foxhole.beta.core.model.RoutingPresetOverrideMode
import com.foxhole.beta.core.model.RoutingPresetSource
import com.foxhole.beta.core.model.RoutingRule
import com.foxhole.beta.core.model.RoutingRuleAction
import com.foxhole.beta.core.model.Settings as FoxholeSettings
import com.foxhole.beta.core.model.ThemeMode
import com.foxhole.beta.core.model.TrafficMode
import com.foxhole.beta.core.model.TrafficSnapshot
import com.foxhole.beta.core.model.TunStack
import com.foxhole.beta.core.model.V2RayApiSettings
import com.foxhole.beta.core.network.NetworkFingerprint
import com.foxhole.beta.core.network.IpInfoFetchMode
import com.foxhole.beta.core.profile.AutoConnectProbeCandidate
import com.foxhole.beta.core.profile.AutoConnectProbeResult
import com.foxhole.beta.core.profile.MultiProtocolProfileSupport
import com.foxhole.beta.core.profile.PreparedProfileExport
import com.foxhole.beta.core.profile.ProfileExportRequest
import com.foxhole.beta.core.profile.classifyAutoConnectProbeFailure
import com.foxhole.beta.core.smart.SmartStartController
import com.foxhole.beta.core.settings.rememberedSmartStartLatencyByOptionId
import com.foxhole.beta.core.settings.rememberedSmartStartLatencyByProfileId
import com.foxhole.beta.core.settings.rememberedSmartProfileServerPingByOptionId
import com.foxhole.beta.core.settings.rememberedSmartProfileServerPingByProfileId
import com.foxhole.beta.core.settings.rememberedSmartProfileMetricsUpdatedAtByOptionId
import com.foxhole.beta.core.settings.rememberedSmartProfileMetricsUpdatedAtByProfileId
import com.foxhole.beta.core.settings.smartProfilePreference
import com.foxhole.beta.vpn.FoxholeVpnService
import com.foxhole.beta.vpn.FoxholeVpnRuntimeBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

class HomeViewModel(
    application: Application,
) : AndroidViewModel(application) {
    internal val container: FoxholeHomeDependencies = (application as FoxholeApplication).appGraph
    internal val initialSettings = container.settingsRepository.settings.value
    internal val clipboard = application.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    internal val installedAppsMutable = MutableStateFlow<List<InstalledAppOption>>(emptyList())
    internal val profilesLoadedMutable = MutableStateFlow(false)
    internal val installedAppsLoadingMutable = MutableStateFlow(false)
    internal val installedAppsLoadedMutable = MutableStateFlow(false)
    internal val ipInfoLoadingMutable = MutableStateFlow(false)
    internal val profileOptionLatenciesMutable = MutableStateFlow<Map<ProfileOptionLatencyKey, Long>>(emptyMap())
    internal val profileOptionLatencyUnavailableMutable = MutableStateFlow<Set<ProfileOptionLatencyKey>>(emptySet())
    internal val profileOptionServerPingsMutable = MutableStateFlow<Map<ProfileOptionLatencyKey, ProfileOptionServerPingState>>(emptyMap())
    internal val profileOptionMetricsUpdatedAtMutable = MutableStateFlow<Map<ProfileOptionLatencyKey, Long>>(emptyMap())
    internal val protocolMetricsRefreshingProfileIdsMutable = MutableStateFlow<Set<Long>>(emptySet())
    internal val recommendedProtocolMutable = MutableStateFlow<ProtocolRecommendationState?>(null)
    internal val runtimeReloadPendingMutable = MutableStateFlow(false)
    internal val profileReconnectPromptUntilMutable = MutableStateFlow(0L)
    internal val insecureTlsImportWarningMutable = MutableStateFlow<InsecureTlsImportWarningState?>(null)
    internal val catalogPresetPreviewsMutable = MutableStateFlow<Map<Long, List<RoutingRepository.RoutingCatalogPresetPreview>>>(emptyMap())
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
                runtimeReloadPendingMutable,
                catalogPresetPreviewsMutable,
                startupActiveProfileMutable,
                container.connectionController.appliedRuntimeSignature,
            ) { runtimeReloadPending, catalogPresetPreviews, startupActiveProfile, appliedRuntimeSignature ->
                HomeTrailingLocalState(
                    runtimeReloadPending = runtimeReloadPending,
                    catalogPresetPreviews = catalogPresetPreviews,
                    startupActiveProfile = startupActiveProfile,
                    appliedRuntimeSignature = appliedRuntimeSignature,
                )
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
                        runtimeReloadPending = trailingState.runtimeReloadPending,
                        catalogPresetPreviews = trailingState.catalogPresetPreviews,
                        appliedRuntimeSignature = trailingState.appliedRuntimeSignature,
                    ),
                startupActiveProfile = trailingState.startupActiveProfile,
            )
        }

    val uiState: StateFlow<HomeUiState> =
        combine(
            connectionStreams,
            routingStreams,
            localState,
            container.diagnosticsLogger.entries,
            profileReconnectPromptUntilMutable,
        ) { connectionStreams, routingStreams, localState, diagnosticEntries, profileReconnectPromptUntil ->
            val localStreams = localState.streams
            val currentFingerprint =
                container.runtimeConfigAssembler.runtimeFingerprint(connectionStreams.settings, routingStreams.activePreset)
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
                    profileReconnectPromptUntil > 0L &&
                    SystemClock.elapsedRealtime() <= profileReconnectPromptUntil
            val runtimeReconnectRequired =
                localStreams.appliedRuntimeSignature != null &&
                    connectionStreams.connection.state in ACTIVE_CONNECTION_STATES &&
                    localStreams.appliedRuntimeSignature != currentFingerprint &&
                    !localStreams.runtimeReloadPending &&
                    !profileReconnectRequiredRaw
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
                traffic = connectionStreams.traffic,
                presets = routingStreams.presets,
                activePreset = routingStreams.activePreset,
                catalogs = routingStreams.catalogs,
                installedApps = localStreams.installedApps,
                installedAppsLoading = localStreams.installedAppsLoading,
                installedAppsLoaded = localStreams.installedAppsLoaded,
                reconnectRequired = runtimeReconnectRequired || profileReconnectRequired,
                diagnosticEntries = diagnosticEntries,
                catalogPresetPreviews = localStreams.catalogPresetPreviews,
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            HomeUiState(),
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

    private val protocolMetricsState =
        combine(
            profileOptionServerPingsMutable,
            profileOptionMetricsUpdatedAtMutable,
            protocolMetricsRefreshingProfileIdsMutable,
            recommendedProtocolMutable,
        ) { serverPings, updatedAt, refreshingProfileIds, recommendation ->
            ProtocolMetricsUiState(
                serverPings = serverPings,
                updatedAt = updatedAt,
                refreshingProfileIds = refreshingProfileIds,
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
            val currentNetworkFingerprintKey = container.networkFingerprintProvider.currentFingerprint()?.key
            val activeProfileLatencies =
                state.activeProfile
                    ?.let { activeProfile ->
                        profileOptionLatencies
                            .filterKeys { key -> key.profileId == activeProfile.id }
                            .mapKeys { (key, _) -> key.optionId }
                    }.orEmpty()
            val activeProfileLatencyUnavailable =
                state.activeProfile
                    ?.let { activeProfile ->
                        profileOptionLatencyUnavailable
                            .filter { key -> key.profileId == activeProfile.id }
                            .map(ProfileOptionLatencyKey::optionId)
                            .toSet()
                    }.orEmpty()
            val activeProfileServerPings =
                state.activeProfile
                    ?.let { activeProfile ->
                        val rememberedServerPings =
                            state.settings
                                .smartProfilePreference(activeProfile.id)
                                ?.rememberedSmartProfileServerPingByOptionId(currentNetworkFingerprintKey)
                                .orEmpty()
                        val liveServerPings =
                            protocolMetrics.serverPings
                            .filterKeys { key -> key.profileId == activeProfile.id }
                            .mapNotNull { (key, value) -> value.pingMs?.let { key.optionId to it } }
                            .toMap()
                        rememberedServerPings + liveServerPings
                    }.orEmpty()
            val activeProfileServerPingUnavailable =
                state.activeProfile
                    ?.let { activeProfile ->
                        protocolMetrics.serverPings
                            .filter { (key, value) -> key.profileId == activeProfile.id && value.unavailable }
                            .map { (key, _) -> key.optionId }
                            .filterNot(activeProfileServerPings::containsKey)
                            .toSet()
                    }.orEmpty()
            val activeProfileMetricsUpdatedAt =
                state.activeProfile
                    ?.let { activeProfile ->
                        val rememberedUpdatedAt =
                            state.settings
                                .smartProfilePreference(activeProfile.id)
                                ?.rememberedSmartProfileMetricsUpdatedAtByOptionId(currentNetworkFingerprintKey)
                                .orEmpty()
                        val liveUpdatedAt =
                            protocolMetrics.updatedAt
                            .filterKeys { key -> key.profileId == activeProfile.id }
                            .mapKeys { (key, _) -> key.optionId }
                        (rememberedUpdatedAt.keys + liveUpdatedAt.keys)
                            .associateWith { optionId ->
                                listOfNotNull(rememberedUpdatedAt[optionId], liveUpdatedAt[optionId]).maxOrNull() ?: 0L
                            }.filterValues { updatedAt -> updatedAt > 0L }
                    }.orEmpty()
            val selectedLatencyOptionId = resolveDashboardLatencyOptionId(state.activeProfile)
            val selectedProtocolLatencyMs =
                selectedLatencyOptionId
                    ?.let(activeProfileLatencies::get)
            val selectedProtocolLatencyUnavailable =
                selectedLatencyOptionId != null &&
                    selectedProtocolLatencyMs == null &&
                    selectedLatencyOptionId in activeProfileLatencyUnavailable
            val smartStartRememberedLatenciesByOptionId =
                state.activeProfile
                    ?.let { activeProfile ->
                        state.settings
                            .smartProfilePreference(activeProfile.id)
                            ?.rememberedSmartStartLatencyByOptionId(currentNetworkFingerprintKey)
                    }.orEmpty()
            val activeRecommendedProtocolOptionIds =
                state.activeProfile
                    ?.let { activeProfile ->
                        val baseline =
                            state.settings
                                .smartProfilePreference(activeProfile.id)
                                ?.recommendedProtocolIds
                                .orEmpty()
                        val transient =
                            protocolMetrics.recommendation
                                ?.takeIf { recommendation -> recommendation.profileId == activeProfile.id }
                                ?.optionId
                        (baseline + listOfNotNull(transient)).toSet()
                    }.orEmpty()
            state.toHomeRouteUiState(
                autoConnect = autoConnect,
                selectedProtocolLatencyMs = selectedProtocolLatencyMs,
                protocolLatenciesByOptionId = activeProfileLatencies,
                selectedProtocolLatencyUnavailable = selectedProtocolLatencyUnavailable,
                protocolLatencyUnavailableOptionIds = activeProfileLatencyUnavailable,
                protocolServerPingsByOptionId = activeProfileServerPings,
                protocolServerPingUnavailableOptionIds = activeProfileServerPingUnavailable,
                protocolMetricsUpdatedAtByOptionId = activeProfileMetricsUpdatedAt,
                protocolMetricsRefreshing = state.activeProfile?.id in protocolMetrics.refreshingProfileIds,
                recommendedProtocolOptionId =
                    protocolMetrics.recommendation
                        ?.takeIf { recommendation -> recommendation.profileId == state.activeProfile?.id }
                        ?.optionId
                        ?: activeRecommendedProtocolOptionIds.firstOrNull(),
                recommendedProtocolOptionIds = activeRecommendedProtocolOptionIds,
                smartStartRememberedLatenciesByOptionId = smartStartRememberedLatenciesByOptionId,
            )
        }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                HomeRouteUiState(),
            )

    val profilesRouteState: StateFlow<ProfilesRouteUiState> =
        combine(
            uiState,
            protocolMetricsState,
        ) { state, protocolMetrics ->
                val networkFingerprint = container.networkFingerprintProvider.currentFingerprint()?.key
                val rememberedServerPingsByProfileId =
                    state.settings.rememberedSmartProfileServerPingByProfileId(
                        networkFingerprint = networkFingerprint,
                    )
                val rememberedMetricsUpdatedAtByProfileId =
                    state.settings.rememberedSmartProfileMetricsUpdatedAtByProfileId(
                        networkFingerprint = networkFingerprint,
                    )
                val liveServerPingsByProfileId =
                    protocolMetrics.serverPings
                        .mapNotNull { (key, value) -> value.pingMs?.let { key.profileId to (key.optionId to it) } }
                        .groupBy({ it.first }, { it.second })
                        .mapValues { (_, values) -> values.toMap() }
                val mergedServerPingsByProfileId =
                    (rememberedServerPingsByProfileId.keys + liveServerPingsByProfileId.keys)
                        .associateWith { profileId ->
                            rememberedServerPingsByProfileId[profileId].orEmpty() +
                                liveServerPingsByProfileId[profileId].orEmpty()
                        }
                val liveMetricsUpdatedAtByProfileId =
                    protocolMetrics.updatedAt
                        .map { (key, value) -> key.profileId to (key.optionId to value) }
                        .groupBy({ it.first }, { it.second })
                        .mapValues { (_, values) -> values.toMap() }
                val mergedMetricsUpdatedAtByProfileId =
                    (rememberedMetricsUpdatedAtByProfileId.keys + liveMetricsUpdatedAtByProfileId.keys)
                        .associateWith { profileId ->
                            val rememberedUpdatedAt = rememberedMetricsUpdatedAtByProfileId[profileId].orEmpty()
                            val liveUpdatedAt = liveMetricsUpdatedAtByProfileId[profileId].orEmpty()
                            (rememberedUpdatedAt.keys + liveUpdatedAt.keys)
                                .associateWith { optionId ->
                                    listOfNotNull(rememberedUpdatedAt[optionId], liveUpdatedAt[optionId]).maxOrNull() ?: 0L
                                }.filterValues { updatedAt -> updatedAt > 0L }
                        }
                state.toProfilesRouteUiState(
                    smartStartRememberedLatenciesByProfileId =
                        state.settings.rememberedSmartStartLatencyByProfileId(
                            networkFingerprint = networkFingerprint,
                        ),
                    smartProfileServerPingsByProfileId = mergedServerPingsByProfileId,
                    smartProfileServerPingUnavailableByProfileId =
                        protocolMetrics.serverPings
                            .filter { (_, value) -> value.unavailable }
                            .keys
                            .groupBy(ProfileOptionLatencyKey::profileId, ProfileOptionLatencyKey::optionId)
                            .mapValues { (profileId, values) ->
                                values.filterNot(mergedServerPingsByProfileId[profileId].orEmpty()::containsKey).toSet()
                            },
                    smartProfileMetricsUpdatedAtByProfileId = mergedMetricsUpdatedAtByProfileId,
                    smartProfileMetricsRefreshingProfileIds = protocolMetrics.refreshingProfileIds,
                    recommendedProtocolOptionByProfileId =
                        state.settings.smartProfilePreferences
                            .mapNotNull { preference ->
                                preference.recommendedProtocolIds.firstOrNull()?.let { optionId ->
                                    preference.profileId to optionId
                                }
                            }.toMap() +
                            protocolMetrics.recommendation
                                ?.let { recommendation -> mapOf(recommendation.profileId to recommendation.optionId) }
                                .orEmpty(),
                    recommendedProtocolOptionsByProfileId =
                        state.settings.smartProfilePreferences
                            .associate { preference ->
                                preference.profileId to preference.recommendedProtocolIds.toSet()
                            } +
                            protocolMetrics.recommendation
                                ?.let { recommendation -> mapOf(recommendation.profileId to setOf(recommendation.optionId)) }
                                .orEmpty(),
                )
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                ProfilesRouteUiState(),
            )

    val settingsRouteState: StateFlow<SettingsRouteUiState> =
        uiState
            .map(HomeUiState::toSettingsRouteUiState)
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

    val aboutRouteState: StateFlow<AboutRouteUiState> =
        uiState
            .map(HomeUiState::toAboutRouteUiState)
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                AboutRouteUiState(),
            )

    internal val snackbars = MutableSharedFlow<FoxholeBannerEvent>(extraBufferCapacity = 16)
    val requestVpnPermission = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    internal var pendingConnectRequest: PendingConnectRequest? = null
    internal var ipInfoRefreshJob: Job? = null
    internal var ipInfoRefreshToken: Long = 0L
    internal var connectedIpRefreshJob: Job? = null
    internal var profileLatencyRefreshJob: Job? = null
    internal var runtimeReloadPendingJob: Job? = null
    internal var profileReconnectPromptJob: Job? = null
    internal var autoConnectJob: Job? = null
    internal var protocolMetricsRefreshJob: Job? = null

    init {
        viewModelScope.launch {
            runCatching { container.settingsRepository.warmUp() }
                .onFailure {
                    snackbars.emit(errorBanner(R.string.settings_secure_storage_failed))
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
                val shouldRefreshDeviceIp =
                    previousState in ACTIVE_CONNECTION_STATES &&
                        currentState in setOf(ConnectionState.IDLE, ConnectionState.ERROR)
                previousState = currentState
                if (currentState !in ACTIVE_CONNECTION_STATES) {
                    clearRuntimeReloadPending()
                    clearProfileLatencyRefresh()
                    clearProtocolLatencyState()
                }
                if (shouldRefreshConnectedIp) {
                    scheduleConnectedIpRefresh()
                    if (!autoConnectUiStateMutable.value.running) {
                        scheduleActiveProfileLatencyRefresh()
                    }
                }
                if (shouldRefreshDeviceIp) {
                    // After disconnect, keep the last IP visible until the quick local-network refresh completes.
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
    }

    fun onAppForegrounded() {
        val runtimeState = container.connectionController.snapshot.value.state
        if (!shouldAutoRefreshIpOnForeground(runtimeState) || ipInfoLoadingMutable.value) {
            return
        }
        startIpInfoRefresh(
            reportFailures = false,
            showLoading = true,
            clearExistingIp = true,
            fetchMode = IpInfoFetchMode.ENTRY_QUICK,
            minimumLoadingDurationMs = 0L,
        )
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
        importRaw(value)
    }

    fun onToggleConnection() {
        cancelAutoConnect(clearUiOnly = true)
        val state = uiState.value
        val activeProfile = state.activeProfile
        if (activeProfile == null) {
            snackbars.tryEmit(errorBanner(R.string.error_profile_missing))
            return
        }
        if (state.connection.state in ACTIVE_CONNECTION_STATES) {
            if (state.reconnectRequired) {
                requestReconnect(activeProfile.id)
            } else {
                container.connectionController.disconnect()
            }
            return
        }
        if (state.settings.traffic.mode == TrafficMode.PROXY) {
            connect(activeProfile.id)
        } else {
            val prepareIntent = android.net.VpnService.prepare(getApplication())
            if (prepareIntent != null) {
                pendingConnectRequest =
                    PendingConnectRequest(
                        profileId = activeProfile.id,
                        action = PendingConnectAction.MANUAL,
                    )
                requestVpnPermission.tryEmit(Unit)
            } else {
                connect(activeProfile.id)
            }
        }
    }

    fun onVpnPermissionResult(granted: Boolean) {
        val request = pendingConnectRequest
        pendingConnectRequest = null
        if (!granted || request == null) {
            snackbars.tryEmit(errorBanner(R.string.vpn_permission_denied))
            return
        }
        when (request.action) {
            PendingConnectAction.MANUAL -> connect(request.profileId)
            PendingConnectAction.AUTO_CONNECT -> startAutoConnect(request.profileId)
            PendingConnectAction.RECONNECT -> reconnect(request.profileId)
        }
    }

    fun onRefreshProfile() {
        val activeProfile = uiState.value.activeProfile ?: return
        if (activeProfile.sourceType != ProfileSourceType.SUBSCRIPTION_URL) {
            snackbars.tryEmit(infoBanner(R.string.profile_not_refreshable))
            return
        }
        viewModelScope.launch {
            runCatching { refreshProfileAndMaybeReconnect(activeProfile.id) }
                .onFailure { handleProfileRefreshFailure(activeProfile.id, it) }
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
                    scheduleActiveProfileLatencyRefresh()
                }
            }.onFailure {
                emitError(it.message ?: getApplication<Application>().getString(R.string.profile_update_failed))
            }
        }
    }

    private fun markProfileReconnectPromptWindow() {
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
    ) = recordAutoConnectCandidateOutcomeInternal(
        profileId = profileId,
        result = result,
        networkFingerprint = networkFingerprint,
        headline = headline,
        markAsLastKnownGood = markAsLastKnownGood,
        countTowardOutcomeHistory = countTowardOutcomeHistory,
    )

    internal suspend fun awaitAutoConnectConnectionOutcome(): ConnectionSnapshot? =
        awaitAutoConnectConnectionOutcomeInternal()

    internal suspend fun awaitAutoConnectValidationGraceOutcome(): ConnectionSnapshot? =
        awaitAutoConnectValidationGraceOutcomeInternal()

    internal suspend fun awaitDisconnectedForAutoConnect(previousVpnNetworkHandle: Long? = null): Long? =
        awaitDisconnectedForAutoConnectInternal(previousVpnNetworkHandle)

    internal fun initializeAutoConnectUi(candidates: List<AutoConnectProbeCandidate>) =
        initializeAutoConnectUiInternal(candidates)

    internal fun markAutoConnectCandidateTesting(candidate: AutoConnectProbeCandidate) =
        markAutoConnectCandidateTestingInternal(candidate)

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

    internal fun clearProtocolLatencyState(
        profileId: Long? = null,
        optionId: String? = null,
    ) = clearProtocolLatencyStateInternal(profileId, optionId)

    fun refreshSmartProfileMetrics(profileId: Long) = refreshSmartProfileMetricsInternal(profileId)

    fun cancelSmartProfileMetricsRefresh() = cancelSmartProfileMetricsRefreshInternal()

    fun onProtocolRecommendationAccepted() = onProtocolRecommendationAcceptedInternal()

    internal fun scheduleActiveProfileLatencyRefresh() = scheduleActiveProfileLatencyRefreshInternal()

    internal fun clearProfileLatencyRefresh() = clearProfileLatencyRefreshInternal()

    fun profile(profileId: Long): Profile? = profileInternal(profileId)

    fun refreshProfile(profileId: Long) = refreshProfileInternal(profileId)

    fun deleteProfile(profileId: Long) = deleteProfileInternal(profileId)

    fun onThemeSelected(value: ThemeMode) = onThemeSelectedInternal(value)

    fun onLocaleSelected(value: AppLocale) = onLocaleSelectedInternal(value)

    fun onSupportBotHandleChanged(value: String?) = onSupportBotHandleChangedInternal(value)

    fun onAutoReconnectChanged(value: Boolean) = onAutoReconnectChangedInternal(value)

    fun onAutoStartChanged(value: Boolean) = onAutoStartChangedInternal(value)

    fun onIpInfoEndpointChanged(value: String) = onIpInfoEndpointChangedInternal(value)

    fun onTunStackSelected(value: TunStack) = onTunStackSelectedInternal(value)

    fun onTrafficModeSelected(value: TrafficMode) = onTrafficModeSelectedInternal(value)

    fun onMtuChanged(value: Int) = onMtuChangedInternal(value)

    fun onPreferIpv6Changed(value: Boolean) = onPreferIpv6ChangedInternal(value)

    fun onDomainStrategySelected(value: DomainStrategy) = onDomainStrategySelectedInternal(value)

    fun acknowledgeUnsafeWarning() = acknowledgeUnsafeWarningInternal()

    fun unlockExpertSettings() = unlockExpertSettingsInternal()

    fun onShowExpertSettingsChanged(value: Boolean) = onShowExpertSettingsChangedInternal(value)

    fun onBlockScreenshotsChanged(value: Boolean) = onBlockScreenshotsChangedInternal(value)

    fun onNetworkActivityLoggingChanged(value: Boolean) = onNetworkActivityLoggingChangedInternal(value)

    fun onSmartStartReplayLoggingChanged(value: Boolean) = onSmartStartReplayLoggingChangedInternal(value)

    fun onDiagnosticsRetentionSelected(value: DiagnosticsRetention) = onDiagnosticsRetentionSelectedInternal(value)

    fun onAllowHttpConfigImportsChanged(value: Boolean) = onAllowHttpConfigImportsChangedInternal(value)

    fun onAllowInsecureTlsChanged(value: Boolean) = onAllowInsecureTlsChangedInternal(value)

    fun onSniffChanged(value: Boolean) = onSniffChangedInternal(value)

    fun onRouteOnlyChanged(value: Boolean) = onRouteOnlyChangedInternal(value)

    fun onStrictRouteChanged(value: Boolean) = onStrictRouteChangedInternal(value)

    fun onBypassLanChanged(value: Boolean) = onBypassLanChangedInternal(value)

    fun onAllowPrivateOutboundHostsChanged(value: Boolean) = onAllowPrivateOutboundHostsChangedInternal(value)

    fun onPerAppRoutingModeSelected(value: PerAppRoutingMode) = onPerAppRoutingModeSelectedInternal(value)

    fun onSelectedPackagesChanged(value: List<String>) = onSelectedPackagesChangedInternal(value)

    fun onSocksSurfaceChanged(value: ProxyInboundSettings) = onSocksSurfaceChangedInternal(value)

    fun onHttpSurfaceChanged(value: ProxyInboundSettings) = onHttpSurfaceChangedInternal(value)

    fun onMixedSurfaceChanged(value: ProxyInboundSettings) = onMixedSurfaceChangedInternal(value)

    fun onLocalProxyAuthEnabledChanged(value: Boolean) = onLocalProxyAuthEnabledChangedInternal(value)

    fun onLocalProxyAuthChanged(value: LocalAuthSettings) = onLocalProxyAuthChangedInternal(value)

    fun onLocalProxyLanAccessChanged(value: Boolean) = onLocalProxyLanAccessChangedInternal(value)

    fun onClashApiChanged(value: ClashApiSettings) = onClashApiChangedInternal(value)

    fun onV2RayApiChanged(value: V2RayApiSettings) = onV2RayApiChangedInternal(value)

    fun resetExpertToSafeDefaults() = resetExpertToSafeDefaultsInternal()

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
    ) = refreshIpInfoInternalInternal(
        reportFailures = reportFailures,
        showLoading = showLoading,
        clearExistingIp = clearExistingIp,
        fetchMode = fetchMode,
        minimumLoadingDurationMs = minimumLoadingDurationMs,
    )

    suspend fun getResolvedConfig(profileId: Long): String = getResolvedConfigInternal(profileId)

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
    ): Boolean = updateResolvedConfigInternal(profileId, editedJson, reconnectAfterSave)

    fun saveSiteRule(
        ruleId: Long?,
        domains: List<String>,
        action: RoutingRuleAction,
    ) = saveSiteRuleInternal(ruleId, domains, action)

    fun createDiagnosticsArchive(): File = createDiagnosticsArchiveInternal()

    fun exportDiagnostics(file: File = createDiagnosticsArchive()): Intent = exportDiagnosticsInternal(file)

    internal fun importRaw(value: String) = importRawInternal(value)

    fun confirmInsecureTlsImport() = confirmInsecureTlsImportInternal()

    fun dismissInsecureTlsImportWarning() = dismissInsecureTlsImportWarningInternal()

    internal fun profileImportFailureMessage(
        rawInput: String,
        throwable: Throwable,
    ): String = profileImportFailureMessageInternal(rawInput, throwable)

    internal suspend fun refreshProfileAndMaybeReconnect(profileId: Long) =
        refreshProfileAndMaybeReconnectInternal(profileId)

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
        previousVpnNetworkHandle: Long? = null,
    ) = connectNowInternal(profileId, protocolOptionId, statusMessage, previousVpnNetworkHandle)

    internal fun invalidateIpInfoRefreshes(): Long = invalidateIpInfoRefreshesInternal()

    internal fun scheduleConnectedIpRefresh() = scheduleConnectedIpRefreshInternal()

    internal fun markRuntimeReloadPending() = markRuntimeReloadPendingInternal()

    internal fun clearRuntimeReloadPending() = clearRuntimeReloadPendingInternal()

    internal fun loadInstalledApps() = loadInstalledAppsInternal()

    fun ensureInstalledAppsLoaded() = ensureInstalledAppsLoadedInternal()

    fun onTrafficUiVisibilityChanged(visible: Boolean) = onTrafficUiVisibilityChangedInternal(visible)

    internal fun ClipData.firstTextItem(): String? =
        if (itemCount > 0) {
            getItemAt(0).coerceToText(getApplication<Application>()).toString()
        } else {
            null
        }

    companion object {
        internal const val CONNECTED_IP_REFRESH_DELAY_MS = 1_250L
        internal const val MANUAL_IP_REFRESH_MIN_LOADING_MS = 666L
        internal const val CONNECTED_PROTOCOL_LATENCY_REFRESH_DELAY_MS = 900L
        internal const val CONNECTED_PROTOCOL_LATENCY_REFRESH_INTERVAL_MS = 5L * 60L * 1000L
        internal const val PROFILE_RECONNECT_PROMPT_WINDOW_MS = 10_000L
        internal const val RUNTIME_RELOAD_PENDING_TIMEOUT_MS = 1_500L
        internal const val AUTO_CONNECT_CONNECTION_TIMEOUT_MS =
            FoxholeVpnService.CONNECTIVITY_PROBE_TOTAL_TIMEOUT_MS +
                FoxholeVpnService.CONNECTIVITY_PROBE_NETWORK_WAIT_TIMEOUT_MS +
                FoxholeVpnService.CONNECTIVITY_PROBE_GRACE_MAX_TIMEOUT_MS
        internal const val AUTO_CONNECT_VALIDATION_GRACE_TIMEOUT_MS = FoxholeVpnService.VPN_NETWORK_WAIT_TIMEOUT_MS
        internal const val AUTO_CONNECT_DISCONNECT_TIMEOUT_MS =
            FoxholeVpnService.VPN_NETWORK_WAIT_TIMEOUT_MS +
                FoxholeVpnService.CONNECTIVITY_PROBE_NETWORK_WAIT_TIMEOUT_MS
        internal const val AUTO_CONNECT_DISCONNECT_POLL_DELAY_MS = FoxholeVpnService.VPN_NETWORK_WAIT_POLL_DELAY_MS
        internal const val AUTO_CONNECT_LATENCY_MEASUREMENT_SETTLE_MS = CONNECTED_PROTOCOL_LATENCY_REFRESH_DELAY_MS
        internal const val AUTO_CONNECT_LATENCY_MEASUREMENT_RETRY_THRESHOLD_MS = 1_000L
        internal const val AUTO_CONNECT_LATENCY_MEASUREMENT_RETRY_DELAY_MS = 300L
        internal const val AUTO_CONNECT_PROTOCOL_TRANSITION_SETTLE_MS = 220L
        internal const val AUTO_CONNECT_RESULT_SETTLE_MS = 850L
        internal const val AUTO_CONNECT_TOTAL_TIMEOUT_MS = 60_000L
        internal const val AUTO_CONNECT_MAX_ATTEMPTS = SmartStartController.AUTO_CONNECT_MAX_ATTEMPTS
        internal const val PROTOCOL_METRICS_PROBE_TIMEOUT_MS = 12_000L
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
