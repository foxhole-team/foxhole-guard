package com.foxhole.beta.ui

import com.foxhole.beta.core.data.RoutingRepository
import com.foxhole.beta.core.diagnostics.DiagnosticEntry
import com.foxhole.beta.core.model.ConnectionSnapshot
import com.foxhole.beta.core.model.IpInfo
import com.foxhole.beta.core.model.InstalledAppOption
import com.foxhole.beta.core.model.Profile
import com.foxhole.beta.core.model.ProtocolHint
import com.foxhole.beta.core.model.RoutingCatalog
import com.foxhole.beta.core.model.RoutingPreset
import com.foxhole.beta.core.model.TrafficSnapshot
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
    val currentDisplayName: String? = null,
    val options: List<AutoConnectProbeOptionUiState> = emptyList(),
)

data class HomeRouteUiState(
    val profilesLoaded: Boolean = false,
    val activeProfile: Profile? = null,
    val activeProfileExcludedOptionIds: Set<String> = emptySet(),
    val settings: FoxholeSettings = FoxholeSettings(),
    val connection: ConnectionSnapshot = ConnectionSnapshot(),
    val ipInfo: IpInfo? = null,
    val ipInfoLoading: Boolean = false,
    val traffic: TrafficSnapshot = TrafficSnapshot(),
    val reconnectRequired: Boolean = false,
    val selectedProtocolLatencyMs: Long? = null,
    val selectedProtocolLatencyUnavailable: Boolean = false,
    val protocolLatenciesByOptionId: Map<String, Long> = emptyMap(),
    val protocolLatencyUnavailableOptionIds: Set<String> = emptySet(),
    val smartStartRememberedLatenciesByOptionId: Map<String, Long> = emptyMap(),
    val autoConnect: AutoConnectUiState = AutoConnectUiState(),
)

data class ProfilesRouteUiState(
    val profiles: List<Profile> = emptyList(),
    val profilesLoaded: Boolean = false,
    val activeProfileId: Long? = null,
    val smartProfileExcludedOptionIdsByProfileId: Map<Long, Set<String>> = emptyMap(),
    val smartStartRememberedLatenciesByProfileId: Map<Long, Map<String, Long>> = emptyMap(),
)

data class SettingsRouteUiState(
    val settings: FoxholeSettings = FoxholeSettings(),
    val appVersion: String = "",
    val reconnectRequired: Boolean = false,
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

data class AboutRouteUiState(
    val appVersion: String = "",
    val coreVersion: String = "",
)

internal fun HomeUiState.toHomeRouteUiState(
    autoConnect: AutoConnectUiState = AutoConnectUiState(),
    selectedProtocolLatencyMs: Long? = null,
    protocolLatenciesByOptionId: Map<String, Long> = emptyMap(),
    selectedProtocolLatencyUnavailable: Boolean = false,
    protocolLatencyUnavailableOptionIds: Set<String> = emptySet(),
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
        connection = connection,
        ipInfo = ipInfo,
        ipInfoLoading = ipInfoLoading,
        traffic = traffic,
        reconnectRequired = reconnectRequired,
        selectedProtocolLatencyMs = selectedProtocolLatencyMs,
        selectedProtocolLatencyUnavailable = selectedProtocolLatencyUnavailable,
        protocolLatenciesByOptionId = protocolLatenciesByOptionId,
        protocolLatencyUnavailableOptionIds = protocolLatencyUnavailableOptionIds,
        smartStartRememberedLatenciesByOptionId = smartStartRememberedLatenciesByOptionId,
        autoConnect = autoConnect,
    )

internal fun HomeUiState.toProfilesRouteUiState(
    smartStartRememberedLatenciesByProfileId: Map<Long, Map<String, Long>> = emptyMap(),
): ProfilesRouteUiState =
    ProfilesRouteUiState(
        profiles = profiles,
        profilesLoaded = profilesLoaded,
        activeProfileId = activeProfile?.id,
        smartProfileExcludedOptionIdsByProfileId =
            settings.smartProfilePreferences.associate { preference ->
                preference.profileId to preference.excludedProtocolOptionIds.toSet()
            },
        smartStartRememberedLatenciesByProfileId = smartStartRememberedLatenciesByProfileId,
    )

internal fun HomeUiState.toSettingsRouteUiState(): SettingsRouteUiState =
    SettingsRouteUiState(
        settings = settings,
        appVersion = appVersion,
        reconnectRequired = reconnectRequired,
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

internal fun HomeUiState.toAboutRouteUiState(): AboutRouteUiState =
    AboutRouteUiState(
        appVersion = appVersion,
        coreVersion = coreVersion,
    )

internal fun ProfilesRouteUiState.profile(profileId: Long): Profile? = profiles.firstOrNull { it.id == profileId }

internal fun ProfilesRouteUiState.smartStartRememberedLatency(profileId: Long): Map<String, Long> =
    smartStartRememberedLatenciesByProfileId[profileId].orEmpty()
