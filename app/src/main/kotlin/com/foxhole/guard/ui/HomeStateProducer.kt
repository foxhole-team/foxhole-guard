package com.foxhole.guard.ui
import android.os.SystemClock
import com.foxhole.core.model.ACTIVE_CONNECTION_STATES
import com.foxhole.core.model.I2pPhaseSnapshot
import com.foxhole.core.model.InstalledAppOption
import com.foxhole.core.model.IpInfo
import com.foxhole.core.model.Profile
import com.foxhole.core.model.Settings
import com.foxhole.core.model.TrafficSnapshot
import com.foxhole.core.runtime.RuntimeIpPanelState
import com.foxhole.core.runtime.RuntimeIpRefreshReason
import com.foxhole.core.runtime.RuntimePhase
import com.foxhole.core.runtime.RuntimeUiState
import com.foxhole.core.runtime.i2pRuntimeActive
import com.foxhole.guard.FoxholeHomeDependencies
import com.foxhole.guard.core.data.RoutingRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transform

internal data class HomeStateProducerInputs(
    val installedApps: StateFlow<List<InstalledAppOption>>,
    val profilesLoaded: StateFlow<Boolean>,
    val installedAppsLoading: StateFlow<Boolean>,
    val installedAppsLoaded: StateFlow<Boolean>,
    val ipInfoLoading: StateFlow<Boolean>,
    val ipInfoRefreshReason: StateFlow<IpInfoRefreshReason?>,
    val torIpInfo: StateFlow<IpInfo?>,
    val dashboardConnectionMetricsLoading: StateFlow<Boolean>,
    val runtimeReloadPending: StateFlow<Boolean>,
    val runtimeReconnectRequired: StateFlow<Boolean>,
    val catalogPresetPreviews: StateFlow<Map<Long, List<RoutingRepository.RoutingCatalogPresetPreview>>>,
    val startupActiveProfile: StateFlow<Profile?>,
    val torOperation: StateFlow<HomeTorOperationUiState>,
    val torTransitionPrompt: StateFlow<TorTransitionPrompt?>,
    val reconnectInProgress: StateFlow<Boolean>,
    val profileReconnectPromptUntil: StateFlow<Long>,
)

internal class HomeStateProducer(
    private val scope: CoroutineScope,
    private val container: FoxholeHomeDependencies,
    private val initialSettings: Settings,
    private val inputs: HomeStateProducerInputs,
) {
    val profileStreams =
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

    private val networkPhaseStreams =
        combine(
            container.connectionController.torPhase,
            container.connectionController.i2pPhase,
        ) { torPhase, i2pPhase -> torPhase to i2pPhase }

    val realtimeStreams =
        combine(
            container.connectionController.snapshot,
            container.connectionController.ipInfo,
            container.connectionController.deviceIpInfo,
            inputs.torIpInfo,
            networkPhaseStreams,
        ) { connection, ipInfo, deviceIpInfo, torIpInfo, phases ->
            HomeRealtimeStreams(
                connection = connection,
                ipInfo = ipInfo,
                deviceIpInfo = deviceIpInfo,
                torIpInfo = torIpInfo,
                torPhase = phases.first,
                i2pPhase = phases.second,
            )
        }

    val connectionStreams =
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
                deviceIpInfo = realtimeStreams.deviceIpInfo,
                torIpInfo = realtimeStreams.torIpInfo,
                torPhase = realtimeStreams.torPhase,
                i2pPhase = realtimeStreams.i2pPhase,
            )
        }

    private val dashboardIpInfoLoading =
        combine(
            inputs.ipInfoLoading,
            container.connectionController.runtimeUiState,
        ) { explicitLoading, runtimeUiState ->
            explicitLoading || runtimeUiState.hasConnectedPendingDashboardIpRefresh()
        }.distinctUntilChanged()

    private val dashboardIpRefreshState =
        combine(
            dashboardIpInfoLoading,
            inputs.ipInfoRefreshReason,
        ) { loading, reason ->
            loading to reason
        }.distinctUntilChanged()

    val routingStreams =
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

    val localState =
        combine(
            combine(
                inputs.installedApps,
                inputs.profilesLoaded,
                inputs.installedAppsLoading,
                inputs.installedAppsLoaded,
                dashboardIpRefreshState,
            ) { installedApps, profilesLoaded, installedAppsLoading, installedAppsLoaded, ipRefreshState ->
                HomeInstalledAppsStreams(
                    profilesLoaded = profilesLoaded,
                    installedApps = installedApps,
                    installedAppsLoading = installedAppsLoading,
                    installedAppsLoaded = installedAppsLoaded,
                    ipInfoLoading = ipRefreshState.first,
                    ipInfoRefreshReason = ipRefreshState.second,
                )
            },
            combine(
                combine(
                    inputs.runtimeReloadPending,
                    inputs.catalogPresetPreviews,
                    inputs.startupActiveProfile,
                    container.connectionController.appliedRuntimeSignature,
                    inputs.dashboardConnectionMetricsLoading,
                ) { runtimeReloadPending, catalogPresetPreviews, startupActiveProfile, appliedRuntimeSignature, dashboardConnectionMetricsLoading ->
                    HomeTrailingLocalState(
                        runtimeReloadPending = runtimeReloadPending,
                        catalogPresetPreviews = catalogPresetPreviews,
                        startupActiveProfile = startupActiveProfile,
                        appliedRuntimeSignature = appliedRuntimeSignature,
                        dashboardConnectionMetricsLoading = dashboardConnectionMetricsLoading,
                        torIpInfo = inputs.torIpInfo.value,
                    )
                },
                combine(
                    inputs.torOperation,
                    inputs.torIpInfo,
                    inputs.torTransitionPrompt,
                ) { torOperation, torIpInfo, torTransitionPrompt ->
                    HomeTorLocalState(
                        operation = torOperation,
                        ipInfo = torIpInfo,
                        transitionPrompt = torTransitionPrompt,
                    )
                },
            ) { trailingState, torState ->
                trailingState.copy(
                    torOperation = torState.operation,
                    torIpInfo = torState.ipInfo,
                    torTransitionPrompt = torState.transitionPrompt,
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
                    ipInfoRefreshReason = installedAppsStreams.ipInfoRefreshReason,
                    torIpInfo = trailingState.torIpInfo,
                    dashboardConnectionMetricsLoading = trailingState.dashboardConnectionMetricsLoading,
                    runtimeReloadPending = trailingState.runtimeReloadPending,
                    torOperation = trailingState.torOperation,
                    torTransitionPrompt = trailingState.torTransitionPrompt,
                    catalogPresetPreviews = trailingState.catalogPresetPreviews,
                    appliedRuntimeSignature = trailingState.appliedRuntimeSignature,
                ),
                startupActiveProfile = trailingState.startupActiveProfile,
            )
        }

    private val reconnectState =
        combine(
            inputs.reconnectInProgress,
            inputs.profileReconnectPromptUntil,
            inputs.runtimeReconnectRequired,
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
            container.diagnosticsLogger.entries.throttleLatest(LIVE_DIAGNOSTICS_THROTTLE_MS),
            container.anomalyRepository.recentEvents,
            appActivityStreams,
            container.anomalyRepository.recentTrafficWindows,
            reconnectState,
        ) { diagnosticEntries, anomalyEvents, appActivityStreams, trafficWindows, reconnectState ->
            homeActivityStreamsPreview(
                diagnosticEntries = diagnosticEntries,
                anomalyEvents = anomalyEvents,
                appTrafficWindows = appActivityStreams.appTrafficWindows,
                networkActivityEvents = appActivityStreams.networkActivityEvents,
                trafficWindows = trafficWindows,
                reconnectState = reconnectState,
            )
        }

    private val statisticsActivityStreams =
        combine(
            container.anomalyRepository.recentEvents,
            appActivityStreams,
            container.anomalyRepository.recentTrafficWindows,
            reconnectState,
        ) { anomalyEvents, appActivityStreams, trafficWindows, reconnectState ->
            homeActivityStreamsPreview(
                diagnosticEntries = emptyList(),
                anomalyEvents = anomalyEvents,
                appTrafficWindows = appActivityStreams.appTrafficWindows,
                networkActivityEvents = appActivityStreams.networkActivityEvents,
                trafficWindows = trafficWindows,
                reconnectState = reconnectState,
            )
        }

    val coreUiState: StateFlow<HomeUiState> =
        combine(
            connectionStreams,
            routingStreams,
            localState,
            reconnectState,
        ) { connectionStreams, routingStreams, localState, reconnectState ->
            val localStreams = localState.streams
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
            val reconnectPromptActive =
                reconnectState.promptUntilElapsedMs > 0L &&
                    SystemClock.elapsedRealtime() <= reconnectState.promptUntilElapsedMs
            val profileReconnectRequired =
                profileReconnectRequiredRaw &&
                    reconnectPromptActive
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
                deviceIpInfo = connectionStreams.deviceIpInfo,
                torIpInfo = localStreams.torIpInfo,
                ipInfoLoading =
                shouldShowIpInfoLoading(
                    explicitLoading = localStreams.ipInfoLoading,
                ),
                ipInfoRefreshReason = localStreams.ipInfoRefreshReason,
                dashboardConnectionMetricsLoading = localStreams.dashboardConnectionMetricsLoading,
                presets = routingStreams.presets,
                activePreset = routingStreams.activePreset,
                catalogs = routingStreams.catalogs,
                installedApps = localStreams.installedApps,
                installedAppsLoading = localStreams.installedAppsLoading,
                installedAppsLoaded = localStreams.installedAppsLoaded,
                reconnectRequired = profileReconnectRequired || runtimeReconnectRequired,
                reconnectInProgress = reconnectState.inProgress,
                torOperation = localStreams.torOperation,
                torPhase = connectionStreams.torPhase,
                i2pPhase = if (connectionStreams.settings.i2pRuntimeActive()) {
                    connectionStreams.i2pPhase
                } else {
                    I2pPhaseSnapshot()
                },
                torTransitionPrompt = localStreams.torTransitionPrompt,
                profileReconnectPromptUntilElapsedMs =
                if (profileReconnectRequired || (runtimeReconnectRequired && reconnectPromptActive)) {
                    reconnectState.promptUntilElapsedMs
                } else {
                    0L
                },
                catalogPresetPreviews = localStreams.catalogPresetPreviews,
            )
        }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(5_000),
                HomeUiState(settings = initialSettings),
            )

    val controlUiState: StateFlow<HomeUiState> = coreUiState

    val dashboardTraffic: StateFlow<TrafficSnapshot> =
        container.connectionController.traffic
            .distinctUntilChanged { previous, next ->
                previous.hasSameDashboardTrafficContentAs(next)
            }
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(5_000),
                container.connectionController.traffic.value,
            )

    val uiState: StateFlow<HomeUiState> =
        combine(
            coreUiState,
            activityStreams,
        ) { core, activityStreams ->
            core.copy(
                diagnosticEntries = activityStreams.diagnosticEntries,
                anomalyEvents = activityStreams.anomalyEvents,
                appTrafficWindows = activityStreams.appTrafficWindows,
                networkActivityEvents = activityStreams.networkActivityEvents,
                trafficWindows = activityStreams.trafficWindows,
            )
        }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(5_000),
                HomeUiState(settings = initialSettings),
            )

    val statisticsUiState: StateFlow<HomeUiState> =
        combine(
            coreUiState,
            statisticsActivityStreams,
            dashboardTraffic
                .map { traffic -> traffic.toStatisticsTrafficTick() }
                .distinctUntilChanged { previous, next ->
                    previous.sampledAt == next.sampledAt && previous.available == next.available
                },
        ) { core, activityStreams, traffic ->
            core.copy(
                traffic = traffic,
                anomalyEvents = activityStreams.anomalyEvents,
                appTrafficWindows = activityStreams.appTrafficWindows,
                networkActivityEvents = activityStreams.networkActivityEvents,
                trafficWindows = activityStreams.trafficWindows,
            )
        }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(5_000),
                HomeUiState(
                    settings = initialSettings,
                    traffic = dashboardTraffic.value.toStatisticsTrafficTick(),
                ),
            )

    private fun TrafficSnapshot.toStatisticsTrafficTick(): TrafficSnapshot =
        copy(
            rxBytesPerSec = 0,
            txBytesPerSec = 0,
            sampledAt = sampledAt.toStatisticsUiNowBucket(),
        )

    private fun RuntimeUiState.hasConnectedPendingDashboardIpRefresh(): Boolean {
        if (phase != RuntimePhase.Connected) {
            return false
        }
        return sequenceOf(ip.device, ip.tunnel, ip.tor).any { panel ->
            panel is RuntimeIpPanelState.Loading &&
                panel.reason != RuntimeIpRefreshReason.LEGACY_BRIDGE
        }
    }
}

private fun <T> Flow<T>.throttleLatest(windowMs: Long): Flow<T> =
    conflate().transform { value ->
        emit(value)
        delay(windowMs)
    }

private const val LIVE_DIAGNOSTICS_THROTTLE_MS = 400L
