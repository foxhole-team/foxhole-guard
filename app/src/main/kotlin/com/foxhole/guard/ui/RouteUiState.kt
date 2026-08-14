package com.foxhole.guard.ui
import androidx.compose.runtime.Immutable
import com.foxhole.core.model.AnomalyEvent
import com.foxhole.core.model.AppTrafficWindow
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.DiagnosticEntry
import com.foxhole.core.model.I2pPhaseSnapshot
import com.foxhole.core.model.I2pTrafficHistory
import com.foxhole.core.model.InstalledAppOption
import com.foxhole.core.model.IpInfo
import com.foxhole.core.model.LanProxyStatusSnapshot
import com.foxhole.core.model.NetworkActivityEvent
import com.foxhole.core.model.OverallStatisticsUiItem
import com.foxhole.core.model.Profile
import com.foxhole.core.model.ProfileSourceType
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.ProtocolMetricEvent
import com.foxhole.core.model.RoutingCatalog
import com.foxhole.core.model.RoutingPreset
import com.foxhole.core.model.Settings
import com.foxhole.core.model.StatisticsRange
import com.foxhole.core.model.StatisticsUiState
import com.foxhole.core.model.TorPhaseSnapshot
import com.foxhole.core.model.TrafficSnapshot
import com.foxhole.core.model.TrafficWindow
import com.foxhole.core.profile.MultiProtocolProfileSupport
import com.foxhole.guard.core.data.RoutingRepository

enum class AutoConnectProbeStatus {
    PENDING,
    TESTING,
    SUCCESS,
    FAILED,
    WINNER,
}

@Immutable
data class AutoConnectProbeOptionUiState(
    val optionId: String,
    val displayName: String,
    val protocolHint: ProtocolHint,
    val status: AutoConnectProbeStatus = AutoConnectProbeStatus.PENDING,
    val latencyMs: Long? = null,
    val latencyUnavailable: Boolean = false,
)

@Immutable
data class AutoConnectUiState(
    val running: Boolean = false,
    val currentOptionId: String? = null,
    val currentProtocolHint: ProtocolHint? = null,
    val currentDisplayName: String? = null,
    val options: List<AutoConnectProbeOptionUiState> = emptyList(),
)

enum class HomeTorOperationKind {
    NONE,
    CONNECTING,
    BOOTSTRAPPING,
    CHANGING_LOCATION,
}

@Immutable
data class HomeTorOperationUiState(
    val kind: HomeTorOperationKind = HomeTorOperationKind.NONE,
    val startedAt: Long = 0L,
    val startedIpAddress: String? = null,
) {
    val active: Boolean
        get() = kind != HomeTorOperationKind.NONE
}

@Immutable
data class HomeRouteUiState(
    val profilesLoaded: Boolean = false,
    val settingsHydrated: Boolean = false,
    val activeProfile: Profile? = null,
    val settings: Settings = Settings(),
    val activePreset: RoutingPreset? = null,
    val connection: ConnectionSnapshot = ConnectionSnapshot(),
    val ipInfo: IpInfo? = null,
    val deviceIpInfo: IpInfo? = null,
    val torIpInfo: IpInfo? = null,
    val ipInfoLoading: Boolean = false,
    val ipInfoRefreshReason: IpInfoRefreshReason? = null,
    val dashboardConnectionMetricsLoading: Boolean = false,
    val traffic: TrafficSnapshot = TrafficSnapshot(),
    val reconnectRequired: Boolean = false,
    val reconnectInProgress: Boolean = false,
    val torOperation: HomeTorOperationUiState = HomeTorOperationUiState(),
    val torPhase: TorPhaseSnapshot = TorPhaseSnapshot(),
    val i2pPhase: I2pPhaseSnapshot = I2pPhaseSnapshot(),
    /** What the core says the LAN proxy is doing — not what the settings switch was set to. */
    val lanProxy: LanProxyStatusSnapshot = LanProxyStatusSnapshot(),
    val torTransitionPrompt: TorTransitionPrompt? = null,
    val profileReconnectPromptUntilElapsedMs: Long = 0L,
    val selectedProtocolLatencyMs: Long? = null,
    val selectedProtocolLatencyUnavailable: Boolean = false,
    val protocolLatenciesByOptionId: Map<String, Long> = emptyMap(),
    val protocolDownOptionIds: Set<String> = emptySet(),
    val protocolLatencyUnavailableOptionIds: Set<String> = emptySet(),
    val protocolServerPingsByOptionId: Map<String, Long> = emptyMap(),
    val protocolServerPingUnavailableOptionIds: Set<String> = emptySet(),
    val protocolTunnelPingsByOptionId: Map<String, Long> = emptyMap(),
    val protocolTunnelPingUnavailableOptionIds: Set<String> = emptySet(),
    val protocolMetricsUpdatedAtByOptionId: Map<String, Long> = emptyMap(),
    val protocolMetricsRefreshing: Boolean = false,
    val protocolMetricsRefreshingOptionId: String? = null,
    val recommendedProtocolOptionId: String? = null,
    val recommendedProtocolOptionIds: Set<String> = emptySet(),
    val favoriteProtocolOptionId: String? = null,
    val smartStartRememberedLatenciesByOptionId: Map<String, Long> = emptyMap(),
    val autoConnect: AutoConnectUiState = AutoConnectUiState(),
    val installedApps: List<InstalledAppOption> = emptyList(),
)

@Immutable
data class ProfilesRouteUiState(
    val profiles: List<Profile> = emptyList(),
    val profilesLoaded: Boolean = false,
    val activeProfileId: Long? = null,
    val settings: Settings = Settings(),
    val smartStartRememberedLatenciesByProfileId: Map<Long, Map<String, Long>> = emptyMap(),
    val smartProfileDownOptionIdsByProfileId: Map<Long, Set<String>> = emptyMap(),
    val smartProfileLatencyUnavailableByProfileId: Map<Long, Set<String>> = emptyMap(),
    val smartProfileServerPingsByProfileId: Map<Long, Map<String, Long>> = emptyMap(),
    val smartProfileConnectDurationsByProfileId: Map<Long, Map<String, Long>> = emptyMap(),
    val smartProfileServerPingUnavailableByProfileId: Map<Long, Set<String>> = emptyMap(),
    val smartProfileMetricsUpdatedAtByProfileId: Map<Long, Map<String, Long>> = emptyMap(),
    val smartProfileMetricsRefreshingProfileIds: Set<Long> = emptySet(),
    val smartProfileMetricsRefreshingOptionIdByProfileId: Map<Long, String> = emptyMap(),
    val recommendedProtocolOptionByProfileId: Map<Long, String> = emptyMap(),
    val recommendedProtocolOptionsByProfileId: Map<Long, Set<String>> = emptyMap(),
    val favoriteProtocolOptionByProfileId: Map<Long, String> = emptyMap(),
)

@Immutable
data class SettingsRouteUiState(
    val settings: Settings = Settings(),
    val appVersion: String = "",
    val reconnectRequired: Boolean = false,
    val hasSmartProfile: Boolean = false,
    val hasSubscriptionProfile: Boolean = false,
    val profiles: List<Profile> = emptyList(),
    val activeProfile: Profile? = null,
    val traffic: TrafficSnapshot = TrafficSnapshot(),
    val ipInfo: IpInfo? = null,
    val installedApps: List<InstalledAppOption> = emptyList(),
    val diagnosticEntries: List<DiagnosticEntry> = emptyList(),
    val anomalyEvents: List<AnomalyEvent> = emptyList(),
    val appTrafficWindows: List<AppTrafficWindow> = emptyList(),
    val networkActivityEvents: List<NetworkActivityEvent> = emptyList(),
    val trafficWindows: List<TrafficWindow> = emptyList(),
    val dnsFilterRefreshInProgress: Boolean = false,
    val statisticsDashboard: StatisticsDashboardUiState = StatisticsDashboardUiState(),
)

@Immutable
data class TrafficSettingsRouteUiState(
    val settings: Settings = Settings(),
    val hasSubscriptionProfile: Boolean = false,
)

@Immutable
data class StatisticsRouteUiState(
    val settings: Settings = Settings(),
    // False while [settings] still carries the pre-hydration bootstrap defaults: the screen must
    // show the preloader instead of concluding "statistics disabled" from a default value.
    val settingsHydrated: Boolean = false,
    val profiles: List<Profile> = emptyList(),
    val activeProfile: Profile? = null,
    val traffic: TrafficSnapshot = TrafficSnapshot(),
    val ipInfo: IpInfo? = null,
    val installedApps: List<InstalledAppOption> = emptyList(),
    val anomalyEvents: List<AnomalyEvent> = emptyList(),
    val appTrafficWindows: List<AppTrafficWindow> = emptyList(),
    val networkActivityEvents: List<NetworkActivityEvent> = emptyList(),
    val trafficWindows: List<TrafficWindow> = emptyList(),
    val protocolMetricEvents: List<ProtocolMetricEvent> = emptyList(),
    // Persisted I2P accounting (hourly buckets + the lifetime aggregate), delivered by a late
    // combine so a recorded sample never re-runs the heavy dashboard build. I2P has no per-app
    // attribution — no VpnMode.I2P, no I2P AppTunnelLane — so its own store is the only thing that
    // can answer "how much I2P traffic in the last week"; TOR needs no such field, its bytes come
    // from the per-app windows the summary already slices by window.
    val i2pTrafficHistory: I2pTrafficHistory = I2pTrafficHistory(),
    val statisticsDashboard: StatisticsDashboardUiState = StatisticsDashboardUiState(),
)

@Immutable
data class StatisticsDashboardUiState(
    // False until the visible-route producer has actually built this snapshot; the screen shows the
    // fade-in preloader instead of empty cards while heavy aggregation runs off the main thread.
    val ready: Boolean = false,
    // 0 for the not-ready placeholder: a live default clock made every default instance unequal,
    // breaking dedupe. Real snapshots carry the builder's bucketed clock.
    val nowMs: Long = 0L,
    val statistics: StatisticsUiState = emptyStatisticsUiState(),
    val appRows: List<AppTrafficRow> = emptyList(),
    val countryRows: List<CountryTrafficUiRow> = emptyList(),
    val dnsSummary: DnsProtectionSummary = DnsProtectionSummary(
        blockedQueries = 0,
        allowedQueries = 0,
        categoryRows = emptyList(),
        appRows = emptyList(),
    ),
    val appChanges: List<com.foxhole.core.model.InstalledAppInventoryChange> = emptyList(),
)

private fun emptyStatisticsUiState(): StatisticsUiState =
    StatisticsUiState(
        range = StatisticsRange.FOREVER,
        extendedMode = false,
        profileTraffic = emptyList(),
        total =
        OverallStatisticsUiItem(
            totalBytes = 0L,
            vpnSessions = 0,
            successCount = 0,
            failureCount = 0,
            avgLatencyMs = null,
            lastActivityAt = null,
        ),
        vpnProtocols = emptyList(),
        profileComparisons = emptyList(),
        transports = emptyList(),
    )

@Immutable
data class RoutingRouteUiState(
    val settings: Settings = Settings(),
    val presets: List<RoutingPreset> = emptyList(),
    val activePreset: RoutingPreset? = null,
    val catalogs: List<RoutingCatalog> = emptyList(),
    val installedApps: List<InstalledAppOption> = emptyList(),
    val installedAppsLoading: Boolean = false,
    val installedAppsLoaded: Boolean = false,
    val catalogPresetPreviews: Map<Long, List<RoutingRepository.RoutingCatalogPresetPreview>> = emptyMap(),
)

@Immutable
data class DiagnosticsRouteUiState(
    val settings: Settings = Settings(),
    // Same contract as the statistics route: default-settings frames must not render as state.
    val settingsHydrated: Boolean = false,
    val activeProfile: Profile? = null,
    val ipInfo: IpInfo? = null,
    val diagnosticEntries: List<DiagnosticEntry> = emptyList(),
    val networkActivityEvents: List<NetworkActivityEvent> = emptyList(),
)

/**
 * Per-option protocol metric inputs of the dashboard route model, bundled so the route-state
 * builder hands them over as one value instead of a wall of parallel parameters.
 */
@Immutable
internal data class HomeRouteProtocolMetricsInputs(
    val selectedProtocolLatencyMs: Long? = null,
    val selectedProtocolLatencyUnavailable: Boolean = false,
    val protocolLatenciesByOptionId: Map<String, Long> = emptyMap(),
    val protocolDownOptionIds: Set<String> = emptySet(),
    val protocolLatencyUnavailableOptionIds: Set<String> = emptySet(),
    val protocolServerPingsByOptionId: Map<String, Long> = emptyMap(),
    val protocolServerPingUnavailableOptionIds: Set<String> = emptySet(),
    val protocolTunnelPingsByOptionId: Map<String, Long> = emptyMap(),
    val protocolTunnelPingUnavailableOptionIds: Set<String> = emptySet(),
    val protocolMetricsUpdatedAtByOptionId: Map<String, Long> = emptyMap(),
    val protocolMetricsRefreshing: Boolean = false,
    val protocolMetricsRefreshingOptionId: String? = null,
    val recommendedProtocolOptionId: String? = null,
    val recommendedProtocolOptionIds: Set<String> = emptySet(),
    val favoriteProtocolOptionId: String? = null,
    val smartStartRememberedLatenciesByOptionId: Map<String, Long> = emptyMap(),
)

internal fun HomeUiState.toHomeRouteUiState(
    autoConnect: AutoConnectUiState = AutoConnectUiState(),
    protocolMetricsInputs: HomeRouteProtocolMetricsInputs = HomeRouteProtocolMetricsInputs(),
    includeLiveTraffic: Boolean = true,
): HomeRouteUiState =
    HomeRouteUiState(
        profilesLoaded = profilesLoaded,
        activeProfile = activeProfile,
        settings = settings,
        activePreset = activePreset,
        connection = connection,
        ipInfo = ipInfo,
        deviceIpInfo = deviceIpInfo,
        torIpInfo = torIpInfo,
        ipInfoLoading = ipInfoLoading,
        ipInfoRefreshReason = ipInfoRefreshReason,
        dashboardConnectionMetricsLoading = dashboardConnectionMetricsLoading,
        traffic = if (includeLiveTraffic) traffic else TrafficSnapshot(),
        reconnectRequired = reconnectRequired,
        reconnectInProgress = reconnectInProgress,
        torOperation = torOperation,
        torPhase = torPhase,
        i2pPhase = i2pPhase,
        torTransitionPrompt = torTransitionPrompt,
        profileReconnectPromptUntilElapsedMs = profileReconnectPromptUntilElapsedMs,
        selectedProtocolLatencyMs = protocolMetricsInputs.selectedProtocolLatencyMs,
        selectedProtocolLatencyUnavailable = protocolMetricsInputs.selectedProtocolLatencyUnavailable,
        protocolLatenciesByOptionId = protocolMetricsInputs.protocolLatenciesByOptionId,
        protocolDownOptionIds = protocolMetricsInputs.protocolDownOptionIds,
        protocolLatencyUnavailableOptionIds = protocolMetricsInputs.protocolLatencyUnavailableOptionIds,
        protocolServerPingsByOptionId = protocolMetricsInputs.protocolServerPingsByOptionId,
        protocolServerPingUnavailableOptionIds = protocolMetricsInputs.protocolServerPingUnavailableOptionIds,
        protocolTunnelPingsByOptionId = protocolMetricsInputs.protocolTunnelPingsByOptionId,
        protocolTunnelPingUnavailableOptionIds = protocolMetricsInputs.protocolTunnelPingUnavailableOptionIds,
        protocolMetricsUpdatedAtByOptionId = protocolMetricsInputs.protocolMetricsUpdatedAtByOptionId,
        protocolMetricsRefreshing = protocolMetricsInputs.protocolMetricsRefreshing,
        protocolMetricsRefreshingOptionId = protocolMetricsInputs.protocolMetricsRefreshingOptionId,
        recommendedProtocolOptionId = protocolMetricsInputs.recommendedProtocolOptionId,
        recommendedProtocolOptionIds = protocolMetricsInputs.recommendedProtocolOptionIds,
        favoriteProtocolOptionId = protocolMetricsInputs.favoriteProtocolOptionId,
        smartStartRememberedLatenciesByOptionId = protocolMetricsInputs.smartStartRememberedLatenciesByOptionId,
        autoConnect = autoConnect,
        installedApps = installedApps,
    )

internal fun HomeUiState.toProfilesRouteUiState(
    smartStartRememberedLatenciesByProfileId: Map<Long, Map<String, Long>> = emptyMap(),
    smartProfileDownOptionIdsByProfileId: Map<Long, Set<String>> = emptyMap(),
    smartProfileLatencyUnavailableByProfileId: Map<Long, Set<String>> = emptyMap(),
    smartProfileServerPingsByProfileId: Map<Long, Map<String, Long>> = emptyMap(),
    smartProfileConnectDurationsByProfileId: Map<Long, Map<String, Long>> = emptyMap(),
    smartProfileServerPingUnavailableByProfileId: Map<Long, Set<String>> = emptyMap(),
    smartProfileMetricsUpdatedAtByProfileId: Map<Long, Map<String, Long>> = emptyMap(),
    smartProfileMetricsRefreshingProfileIds: Set<Long> = emptySet(),
    smartProfileMetricsRefreshingOptionIdByProfileId: Map<Long, String> = emptyMap(),
    recommendedProtocolOptionByProfileId: Map<Long, String> = emptyMap(),
    recommendedProtocolOptionsByProfileId: Map<Long, Set<String>> = emptyMap(),
    favoriteProtocolOptionByProfileId: Map<Long, String> = emptyMap(),
): ProfilesRouteUiState =
    ProfilesRouteUiState(
        profiles = profiles,
        profilesLoaded = profilesLoaded,
        activeProfileId = activeProfile?.id,
        settings = settings,
        smartStartRememberedLatenciesByProfileId = smartStartRememberedLatenciesByProfileId,
        smartProfileDownOptionIdsByProfileId = smartProfileDownOptionIdsByProfileId,
        smartProfileLatencyUnavailableByProfileId = smartProfileLatencyUnavailableByProfileId,
        smartProfileServerPingsByProfileId = smartProfileServerPingsByProfileId,
        smartProfileConnectDurationsByProfileId = smartProfileConnectDurationsByProfileId,
        smartProfileServerPingUnavailableByProfileId = smartProfileServerPingUnavailableByProfileId,
        smartProfileMetricsUpdatedAtByProfileId = smartProfileMetricsUpdatedAtByProfileId,
        smartProfileMetricsRefreshingProfileIds = smartProfileMetricsRefreshingProfileIds,
        smartProfileMetricsRefreshingOptionIdByProfileId = smartProfileMetricsRefreshingOptionIdByProfileId,
        recommendedProtocolOptionByProfileId = recommendedProtocolOptionByProfileId,
        recommendedProtocolOptionsByProfileId = recommendedProtocolOptionsByProfileId,
        favoriteProtocolOptionByProfileId = favoriteProtocolOptionByProfileId,
    )

internal fun HomeUiState.toSettingsRouteUiState(
    dnsFilterRefreshInProgress: Boolean = false,
    statisticsDashboard: StatisticsDashboardUiState = StatisticsDashboardUiState(),
    includeLiveTraffic: Boolean = true,
    includeInstalledApps: Boolean = true,
    includeActivityState: Boolean = true,
): SettingsRouteUiState =
    SettingsRouteUiState(
        settings = settings,
        appVersion = appVersion,
        reconnectRequired = reconnectRequired,
        hasSmartProfile = profiles.any(MultiProtocolProfileSupport::hasMultipleSupportedOptions),
        hasSubscriptionProfile = profiles.any { profile -> profile.sourceType == ProfileSourceType.SUBSCRIPTION_URL },
        profiles = profiles,
        activeProfile = activeProfile,
        traffic = if (includeLiveTraffic) traffic else TrafficSnapshot(),
        // ipInfo rides the live-traffic gate too: none of the settings screens read it, and an
        // ungated copy re-emitted this state (recomposing the whole settings/Tor screen) on
        // every IP refresh while connected.
        ipInfo = if (includeLiveTraffic) ipInfo else null,
        installedApps = if (includeInstalledApps) installedApps else emptyList(),
        diagnosticEntries = if (includeActivityState) diagnosticEntries else emptyList(),
        anomalyEvents = if (includeActivityState) anomalyEvents else emptyList(),
        appTrafficWindows = if (includeActivityState) appTrafficWindows else emptyList(),
        networkActivityEvents = if (includeActivityState) networkActivityEvents else emptyList(),
        trafficWindows = if (includeActivityState) trafficWindows else emptyList(),
        dnsFilterRefreshInProgress = dnsFilterRefreshInProgress,
        statisticsDashboard = statisticsDashboard,
    )

internal fun HomeProfileStreams.toTrafficSettingsRouteUiState(): TrafficSettingsRouteUiState =
    TrafficSettingsRouteUiState(
        settings = settings,
        hasSubscriptionProfile = profiles.any { profile -> profile.sourceType == ProfileSourceType.SUBSCRIPTION_URL },
    )

internal fun HomeUiState.toStatisticsRouteUiState(
    statisticsDashboard: StatisticsDashboardUiState = StatisticsDashboardUiState(),
): StatisticsRouteUiState =
    StatisticsRouteUiState(
        settings = settings,
        profiles = profiles,
        activeProfile = activeProfile,
        traffic = traffic,
        ipInfo = ipInfo,
        installedApps = installedApps,
        anomalyEvents = anomalyEvents,
        appTrafficWindows = appTrafficWindows,
        networkActivityEvents = networkActivityEvents,
        trafficWindows = trafficWindows,
        statisticsDashboard = statisticsDashboard,
    )

internal fun HomeUiState.toRoutingRouteUiState(
    includeInstalledApps: Boolean = true,
): RoutingRouteUiState =
    RoutingRouteUiState(
        settings = settings,
        presets = presets,
        activePreset = activePreset,
        catalogs = catalogs,
        installedApps = if (includeInstalledApps) installedApps else emptyList(),
        installedAppsLoading = includeInstalledApps && installedAppsLoading,
        installedAppsLoaded = includeInstalledApps && installedAppsLoaded,
        catalogPresetPreviews = catalogPresetPreviews,
    )

internal fun HomeUiState.toDiagnosticsRouteUiState(): DiagnosticsRouteUiState =
    DiagnosticsRouteUiState(
        settings = settings,
        activeProfile = activeProfile,
        ipInfo = ipInfo,
        diagnosticEntries = diagnosticEntries,
        networkActivityEvents = networkActivityEvents,
    )

internal fun ProfilesRouteUiState.profile(profileId: Long): Profile? = profiles.firstOrNull { it.id == profileId }

internal fun ProfilesRouteUiState.smartStartRememberedLatency(profileId: Long): Map<String, Long> =
    smartStartRememberedLatenciesByProfileId[profileId].orEmpty()

internal fun ProfilesRouteUiState.smartProfileDownOptionIds(profileId: Long): Set<String> =
    smartProfileDownOptionIdsByProfileId[profileId].orEmpty()

internal fun ProfilesRouteUiState.smartProfileLatencyUnavailable(profileId: Long): Set<String> =
    smartProfileLatencyUnavailableByProfileId[profileId].orEmpty()

internal fun ProfilesRouteUiState.smartProfileServerPings(profileId: Long): Map<String, Long> =
    smartProfileServerPingsByProfileId[profileId].orEmpty()

internal fun ProfilesRouteUiState.smartProfileConnectDurations(profileId: Long): Map<String, Long> =
    smartProfileConnectDurationsByProfileId[profileId].orEmpty()

internal fun ProfilesRouteUiState.smartProfileServerPingUnavailable(profileId: Long): Set<String> =
    smartProfileServerPingUnavailableByProfileId[profileId].orEmpty()

internal fun ProfilesRouteUiState.smartProfileMetricsUpdatedAt(profileId: Long): Map<String, Long> =
    smartProfileMetricsUpdatedAtByProfileId[profileId].orEmpty()
