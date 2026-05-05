package com.foxhole.beta.ui

import com.foxhole.beta.core.data.RoutingRepository
import com.foxhole.beta.core.diagnostics.DiagnosticEntry
import com.foxhole.beta.core.model.ConnectionSnapshot
import com.foxhole.beta.core.model.IpInfo
import com.foxhole.beta.core.model.InstalledAppOption
import com.foxhole.beta.core.model.Profile
import com.foxhole.beta.core.model.ProfileSourceType
import com.foxhole.beta.core.model.ProtocolHint
import com.foxhole.beta.core.model.RoutingCatalog
import com.foxhole.beta.core.model.RoutingPreset
import com.foxhole.beta.core.model.TrafficSnapshot
import com.foxhole.beta.core.profile.MultiProtocolProfileSupport
import com.foxhole.beta.core.model.Settings as FoxholeSettings

enum class AutoConnectProbeStatus {
    PENDING,
    TESTING,
    SUCCESS,
    FAILED,
    WINNER,
}

data class AutoConnectProbeOptionUiState(
    val optionId: String,
    val displayName: String,
    val protocolHint: ProtocolHint,
    val status: AutoConnectProbeStatus = AutoConnectProbeStatus.PENDING,
    val latencyMs: Long? = null,
    val latencyUnavailable: Boolean = false,
)

data class AutoConnectUiState(
    val running: Boolean = false,
    val currentOptionId: String? = null,
    val currentProtocolHint: ProtocolHint? = null,
    val currentDisplayName: String? = null,
    val options: List<AutoConnectProbeOptionUiState> = emptyList(),
)

data class HomeRouteUiState(
    val profilesLoaded: Boolean = false,
    val activeProfile: Profile? = null,
    val activeProfileExcludedOptionIds: Set<String> = emptySet(),
    val settings: FoxholeSettings = FoxholeSettings(),
    val activePreset: RoutingPreset? = null,
    val connection: ConnectionSnapshot = ConnectionSnapshot(),
    val ipInfo: IpInfo? = null,
    val ipInfoLoading: Boolean = false,
    val dashboardConnectionMetricsLoading: Boolean = false,
    val traffic: TrafficSnapshot = TrafficSnapshot(),
    val reconnectRequired: Boolean = false,
    val reconnectInProgress: Boolean = false,
    val profileReconnectPromptUntilElapsedMs: Long = 0L,
    val selectedProtocolLatencyMs: Long? = null,
    val selectedProtocolLatencyUnavailable: Boolean = false,
    val protocolLatenciesByOptionId: Map<String, Long> = emptyMap(),
    val protocolDownOptionIds: Set<String> = emptySet(),
    val protocolLatencyUnavailableOptionIds: Set<String> = emptySet(),
    val protocolServerPingsByOptionId: Map<String, Long> = emptyMap(),
    val protocolServerPingUnavailableOptionIds: Set<String> = emptySet(),
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

data class ProfilesRouteUiState(
    val profiles: List<Profile> = emptyList(),
    val profilesLoaded: Boolean = false,
    val activeProfileId: Long? = null,
    val settings: FoxholeSettings = FoxholeSettings(),
    val smartProfileExcludedOptionIdsByProfileId: Map<Long, Set<String>> = emptyMap(),
    val smartStartRememberedLatenciesByProfileId: Map<Long, Map<String, Long>> = emptyMap(),
    val smartProfileDownOptionIdsByProfileId: Map<Long, Set<String>> = emptyMap(),
    val smartProfileServerPingsByProfileId: Map<Long, Map<String, Long>> = emptyMap(),
    val smartProfileServerPingUnavailableByProfileId: Map<Long, Set<String>> = emptyMap(),
    val smartProfileMetricsUpdatedAtByProfileId: Map<Long, Map<String, Long>> = emptyMap(),
    val smartProfileMetricsRefreshingProfileIds: Set<Long> = emptySet(),
    val smartProfileMetricsRefreshingOptionIdByProfileId: Map<Long, String> = emptyMap(),
    val recommendedProtocolOptionByProfileId: Map<Long, String> = emptyMap(),
    val recommendedProtocolOptionsByProfileId: Map<Long, Set<String>> = emptyMap(),
    val favoriteProtocolOptionByProfileId: Map<Long, String> = emptyMap(),
)

data class SettingsRouteUiState(
    val settings: FoxholeSettings = FoxholeSettings(),
    val appVersion: String = "",
    val reconnectRequired: Boolean = false,
    val hasSmartProfile: Boolean = false,
    val hasSubscriptionProfile: Boolean = false,
    val installedApps: List<InstalledAppOption> = emptyList(),
)

data class RoutingRouteUiState(
    val settings: FoxholeSettings = FoxholeSettings(),
    val presets: List<RoutingPreset> = emptyList(),
    val activePreset: RoutingPreset? = null,
    val catalogs: List<RoutingCatalog> = emptyList(),
    val installedApps: List<InstalledAppOption> = emptyList(),
    val installedAppsLoading: Boolean = false,
    val installedAppsLoaded: Boolean = false,
    val catalogPresetPreviews: Map<Long, List<RoutingRepository.RoutingCatalogPresetPreview>> = emptyMap(),
)

data class DiagnosticsRouteUiState(
    val settings: FoxholeSettings = FoxholeSettings(),
    val activeProfile: Profile? = null,
    val traffic: TrafficSnapshot = TrafficSnapshot(),
    val diagnosticEntries: List<DiagnosticEntry> = emptyList(),
)

internal fun HomeUiState.toHomeRouteUiState(
    autoConnect: AutoConnectUiState = AutoConnectUiState(),
    selectedProtocolLatencyMs: Long? = null,
    protocolLatenciesByOptionId: Map<String, Long> = emptyMap(),
    protocolDownOptionIds: Set<String> = emptySet(),
    selectedProtocolLatencyUnavailable: Boolean = false,
    protocolLatencyUnavailableOptionIds: Set<String> = emptySet(),
    protocolServerPingsByOptionId: Map<String, Long> = emptyMap(),
    protocolServerPingUnavailableOptionIds: Set<String> = emptySet(),
    protocolMetricsUpdatedAtByOptionId: Map<String, Long> = emptyMap(),
    protocolMetricsRefreshing: Boolean = false,
    protocolMetricsRefreshingOptionId: String? = null,
    recommendedProtocolOptionId: String? = null,
    recommendedProtocolOptionIds: Set<String> = emptySet(),
    favoriteProtocolOptionId: String? = null,
    smartStartRememberedLatenciesByOptionId: Map<String, Long> = emptyMap(),
): HomeRouteUiState =
    HomeRouteUiState(
        profilesLoaded = profilesLoaded,
        activeProfile = activeProfile,
        activeProfileExcludedOptionIds =
            activeProfile
                ?.let { profile ->
                    settings.smartProfilePreferences
                        .firstOrNull { preference -> preference.profileId == profile.id }
                        ?.excludedProtocolOptionIds
                        ?.toSet()
                }.orEmpty(),
        settings = settings,
        activePreset = activePreset,
        connection = connection,
        ipInfo = ipInfo,
        ipInfoLoading = ipInfoLoading,
        dashboardConnectionMetricsLoading = dashboardConnectionMetricsLoading,
        traffic = traffic,
        reconnectRequired = reconnectRequired,
        reconnectInProgress = reconnectInProgress,
        profileReconnectPromptUntilElapsedMs = profileReconnectPromptUntilElapsedMs,
        selectedProtocolLatencyMs = selectedProtocolLatencyMs,
        selectedProtocolLatencyUnavailable = selectedProtocolLatencyUnavailable,
        protocolLatenciesByOptionId = protocolLatenciesByOptionId,
        protocolDownOptionIds = protocolDownOptionIds,
        protocolLatencyUnavailableOptionIds = protocolLatencyUnavailableOptionIds,
        protocolServerPingsByOptionId = protocolServerPingsByOptionId,
        protocolServerPingUnavailableOptionIds = protocolServerPingUnavailableOptionIds,
        protocolMetricsUpdatedAtByOptionId = protocolMetricsUpdatedAtByOptionId,
        protocolMetricsRefreshing = protocolMetricsRefreshing,
        protocolMetricsRefreshingOptionId = protocolMetricsRefreshingOptionId,
        recommendedProtocolOptionId = recommendedProtocolOptionId,
        recommendedProtocolOptionIds = recommendedProtocolOptionIds,
        favoriteProtocolOptionId = favoriteProtocolOptionId,
        smartStartRememberedLatenciesByOptionId = smartStartRememberedLatenciesByOptionId,
        autoConnect = autoConnect,
        installedApps = installedApps,
    )

internal fun HomeUiState.toProfilesRouteUiState(
    smartStartRememberedLatenciesByProfileId: Map<Long, Map<String, Long>> = emptyMap(),
    smartProfileDownOptionIdsByProfileId: Map<Long, Set<String>> = emptyMap(),
    smartProfileServerPingsByProfileId: Map<Long, Map<String, Long>> = emptyMap(),
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
        smartProfileExcludedOptionIdsByProfileId =
            settings.smartProfilePreferences.associate { preference ->
                preference.profileId to preference.excludedProtocolOptionIds.toSet()
            },
        smartStartRememberedLatenciesByProfileId = smartStartRememberedLatenciesByProfileId,
        smartProfileDownOptionIdsByProfileId = smartProfileDownOptionIdsByProfileId,
        smartProfileServerPingsByProfileId = smartProfileServerPingsByProfileId,
        smartProfileServerPingUnavailableByProfileId = smartProfileServerPingUnavailableByProfileId,
        smartProfileMetricsUpdatedAtByProfileId = smartProfileMetricsUpdatedAtByProfileId,
        smartProfileMetricsRefreshingProfileIds = smartProfileMetricsRefreshingProfileIds,
        smartProfileMetricsRefreshingOptionIdByProfileId = smartProfileMetricsRefreshingOptionIdByProfileId,
        recommendedProtocolOptionByProfileId = recommendedProtocolOptionByProfileId,
        recommendedProtocolOptionsByProfileId = recommendedProtocolOptionsByProfileId,
        favoriteProtocolOptionByProfileId = favoriteProtocolOptionByProfileId,
    )

internal fun HomeUiState.toSettingsRouteUiState(): SettingsRouteUiState =
    SettingsRouteUiState(
        settings = settings,
        appVersion = appVersion,
        reconnectRequired = reconnectRequired,
        hasSmartProfile = profiles.any(MultiProtocolProfileSupport::hasMultipleSupportedOptions),
        hasSubscriptionProfile = profiles.any { profile -> profile.sourceType == ProfileSourceType.SUBSCRIPTION_URL },
        installedApps = installedApps,
    )

internal fun HomeUiState.toRoutingRouteUiState(): RoutingRouteUiState =
    RoutingRouteUiState(
        settings = settings,
        presets = presets,
        activePreset = activePreset,
        catalogs = catalogs,
        installedApps = installedApps,
        installedAppsLoading = installedAppsLoading,
        installedAppsLoaded = installedAppsLoaded,
        catalogPresetPreviews = catalogPresetPreviews,
    )

internal fun HomeUiState.toDiagnosticsRouteUiState(): DiagnosticsRouteUiState =
    DiagnosticsRouteUiState(
        settings = settings,
        activeProfile = activeProfile,
        traffic = traffic,
        diagnosticEntries = diagnosticEntries,
    )

internal fun ProfilesRouteUiState.profile(profileId: Long): Profile? = profiles.firstOrNull { it.id == profileId }

internal fun ProfilesRouteUiState.smartStartRememberedLatency(profileId: Long): Map<String, Long> =
    smartStartRememberedLatenciesByProfileId[profileId].orEmpty()

internal fun ProfilesRouteUiState.smartProfileDownOptionIds(profileId: Long): Set<String> =
    smartProfileDownOptionIdsByProfileId[profileId].orEmpty()

internal fun ProfilesRouteUiState.smartProfileServerPings(profileId: Long): Map<String, Long> =
    smartProfileServerPingsByProfileId[profileId].orEmpty()

internal fun ProfilesRouteUiState.smartProfileServerPingUnavailable(profileId: Long): Set<String> =
    smartProfileServerPingUnavailableByProfileId[profileId].orEmpty()

internal fun ProfilesRouteUiState.smartProfileMetricsUpdatedAt(profileId: Long): Map<String, Long> =
    smartProfileMetricsUpdatedAtByProfileId[profileId].orEmpty()
