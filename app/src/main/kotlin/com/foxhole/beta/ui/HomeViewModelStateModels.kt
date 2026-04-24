package com.foxhole.beta.ui

import com.foxhole.beta.BuildConfig
import com.foxhole.beta.core.data.RoutingRepository
import com.foxhole.beta.core.diagnostics.DiagnosticEntry
import com.foxhole.beta.core.model.AppLocale
import com.foxhole.beta.core.model.CachedActiveProfile
import com.foxhole.beta.core.model.ConnectionSnapshot
import com.foxhole.beta.core.model.DiagnosticsRetention
import com.foxhole.beta.core.model.ExpertSettings
import com.foxhole.beta.core.model.InstalledAppOption
import com.foxhole.beta.core.model.IpInfo
import com.foxhole.beta.core.model.Profile
import com.foxhole.beta.core.model.ProfileSourceType
import com.foxhole.beta.core.model.ProtocolHint
import com.foxhole.beta.core.model.RoutingCatalog
import com.foxhole.beta.core.model.RoutingPreset
import com.foxhole.beta.core.model.TrafficSnapshot
import com.foxhole.beta.core.model.Settings as FoxholeSettings

data class HomeUiState(
    val profiles: List<Profile> = emptyList(),
    val profilesLoaded: Boolean = false,
    val activeProfile: Profile? = null,
    val settings: FoxholeSettings = FoxholeSettings(),
    val connection: ConnectionSnapshot = ConnectionSnapshot(),
    val ipInfo: IpInfo? = null,
    val ipInfoLoading: Boolean = false,
    val traffic: TrafficSnapshot = TrafficSnapshot(),
    val presets: List<RoutingPreset> = emptyList(),
    val activePreset: RoutingPreset? = null,
    val catalogs: List<RoutingCatalog> = emptyList(),
    val installedApps: List<InstalledAppOption> = emptyList(),
    val installedAppsLoading: Boolean = false,
    val installedAppsLoaded: Boolean = false,
    val reconnectRequired: Boolean = false,
    val diagnosticEntries: List<DiagnosticEntry> = emptyList(),
    val catalogPresetPreviews: Map<Long, List<RoutingRepository.RoutingCatalogPresetPreview>> = emptyMap(),
    val appVersion: String = BuildConfig.VERSION_NAME,
    val coreVersion: String = BuildConfig.LIBBOX_SOURCE_VERSION,
)

internal data class HomeConnectionStreams(
    val profiles: List<Profile>,
    val activeProfile: Profile?,
    val settings: FoxholeSettings,
    val connection: ConnectionSnapshot,
    val ipInfo: IpInfo?,
    val traffic: TrafficSnapshot,
)

internal data class HomeProfileStreams(
    val profiles: List<Profile>,
    val activeProfile: Profile?,
    val settings: FoxholeSettings,
)

internal data class HomeRealtimeStreams(
    val connection: ConnectionSnapshot,
    val ipInfo: IpInfo?,
    val traffic: TrafficSnapshot,
)

internal data class HomeRoutingStreams(
    val presets: List<RoutingPreset>,
    val activePreset: RoutingPreset?,
    val catalogs: List<RoutingCatalog>,
)

internal data class HomeLocalStreams(
    val profilesLoaded: Boolean,
    val installedApps: List<InstalledAppOption>,
    val installedAppsLoading: Boolean,
    val installedAppsLoaded: Boolean,
    val ipInfoLoading: Boolean,
    val runtimeReloadPending: Boolean,
    val catalogPresetPreviews: Map<Long, List<RoutingRepository.RoutingCatalogPresetPreview>>,
    val appliedRuntimeSignature: Int?,
)

internal data class HomeInstalledAppsStreams(
    val profilesLoaded: Boolean,
    val installedApps: List<InstalledAppOption>,
    val installedAppsLoading: Boolean,
    val installedAppsLoaded: Boolean,
    val ipInfoLoading: Boolean,
)

internal data class HomeLocalState(
    val streams: HomeLocalStreams,
    val startupActiveProfile: Profile?,
)

internal data class HomeTrailingLocalState(
    val runtimeReloadPending: Boolean,
    val catalogPresetPreviews: Map<Long, List<RoutingRepository.RoutingCatalogPresetPreview>>,
    val startupActiveProfile: Profile?,
    val appliedRuntimeSignature: Int?,
)

internal enum class PendingConnectAction {
    MANUAL,
    AUTO_CONNECT,
    RECONNECT,
}

internal data class PendingConnectRequest(
    val profileId: Long,
    val action: PendingConnectAction,
)

internal data class ProfileOptionLatencyKey(
    val profileId: Long,
    val optionId: String,
)
