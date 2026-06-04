package com.foxhole.beta.ui

import com.foxhole.beta.BuildConfig
import com.foxhole.beta.core.data.RoutingRepository
import com.foxhole.beta.core.diagnostics.DiagnosticEntry
import com.foxhole.beta.core.model.AnomalyEvent
import com.foxhole.beta.core.model.AppLocale
import com.foxhole.beta.core.model.AppTrafficWindow
import com.foxhole.beta.core.model.CachedActiveProfile
import com.foxhole.beta.core.model.ConnectionSnapshot
import com.foxhole.beta.core.model.DiagnosticsRetention
import com.foxhole.beta.core.model.ExpertSettings
import com.foxhole.beta.core.model.InstalledAppOption
import com.foxhole.beta.core.model.IpInfo
import com.foxhole.beta.core.model.NetworkActivityEvent
import com.foxhole.beta.core.model.Profile
import com.foxhole.beta.core.model.ProfileSourceType
import com.foxhole.beta.core.model.ProtocolHint
import com.foxhole.beta.core.model.RoutingCatalog
import com.foxhole.beta.core.model.RoutingPreset
import com.foxhole.beta.core.model.TrafficSnapshot
import com.foxhole.beta.core.model.TrafficWindow
import com.foxhole.beta.core.model.Settings as FoxholeSettings

data class HomeUiState(
    val profiles: List<Profile> = emptyList(),
    val profilesLoaded: Boolean = false,
    val activeProfile: Profile? = null,
    val settings: FoxholeSettings = FoxholeSettings(),
    val connection: ConnectionSnapshot = ConnectionSnapshot(),
    val ipInfo: IpInfo? = null,
    val deviceIpInfo: IpInfo? = null,
    val torIpInfo: IpInfo? = null,
    val ipInfoLoading: Boolean = false,
    val ipInfoRefreshReason: IpInfoRefreshReason? = null,
    val dashboardConnectionMetricsLoading: Boolean = false,
    val traffic: TrafficSnapshot = TrafficSnapshot(),
    val presets: List<RoutingPreset> = emptyList(),
    val activePreset: RoutingPreset? = null,
    val catalogs: List<RoutingCatalog> = emptyList(),
    val installedApps: List<InstalledAppOption> = emptyList(),
    val installedAppsLoading: Boolean = false,
    val installedAppsLoaded: Boolean = false,
    val reconnectRequired: Boolean = false,
    val reconnectInProgress: Boolean = false,
    val torOperation: HomeTorOperationUiState = HomeTorOperationUiState(),
    val torTransitionPrompt: TorTransitionPrompt? = null,
    val profileReconnectPromptUntilElapsedMs: Long = 0L,
    val diagnosticEntries: List<DiagnosticEntry> = emptyList(),
    val anomalyEvents: List<AnomalyEvent> = emptyList(),
    val appTrafficWindows: List<AppTrafficWindow> = emptyList(),
    val networkActivityEvents: List<NetworkActivityEvent> = emptyList(),
    val trafficWindows: List<TrafficWindow> = emptyList(),
    val catalogPresetPreviews: Map<Long, List<RoutingRepository.RoutingCatalogPresetPreview>> = emptyMap(),
    val appVersion: String = BuildConfig.VERSION_NAME,
    val coreVersion: String = BuildConfig.LIBBOX_SOURCE_VERSION,
)

sealed class TorTransitionPrompt {
    data class DisableTorForUdpProtocol(
        val profileId: Long,
        val protocolOptionId: String,
        val protocolName: String,
    ) : TorTransitionPrompt()

    data class StartTcpVpnWhileTorOnlyActive(
        val profileId: Long,
        val protocolOptionId: String?,
        val profileName: String,
    ) : TorTransitionPrompt()

    data class UdpVpnProtocolNotSupported(
        val protocolName: String?,
    ) : TorTransitionPrompt()
}

internal data class HomeConnectionStreams(
    val profiles: List<Profile>,
    val activeProfile: Profile?,
    val settings: FoxholeSettings,
    val connection: ConnectionSnapshot,
    val ipInfo: IpInfo?,
    val deviceIpInfo: IpInfo?,
    val torIpInfo: IpInfo?,
)

internal data class HomeProfileStreams(
    val profiles: List<Profile>,
    val activeProfile: Profile?,
    val settings: FoxholeSettings,
)

internal data class HomeRealtimeStreams(
    val connection: ConnectionSnapshot,
    val ipInfo: IpInfo?,
    val deviceIpInfo: IpInfo?,
    val torIpInfo: IpInfo?,
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
    val ipInfoRefreshReason: IpInfoRefreshReason?,
    val torIpInfo: IpInfo?,
    val dashboardConnectionMetricsLoading: Boolean,
    val runtimeReloadPending: Boolean,
    val torOperation: HomeTorOperationUiState,
    val torTransitionPrompt: TorTransitionPrompt?,
    val catalogPresetPreviews: Map<Long, List<RoutingRepository.RoutingCatalogPresetPreview>>,
    val appliedRuntimeSignature: Int?,
)

internal data class HomeInstalledAppsStreams(
    val profilesLoaded: Boolean,
    val installedApps: List<InstalledAppOption>,
    val installedAppsLoading: Boolean,
    val installedAppsLoaded: Boolean,
    val ipInfoLoading: Boolean,
    val ipInfoRefreshReason: IpInfoRefreshReason?,
)

internal data class HomeLocalState(
    val streams: HomeLocalStreams,
    val startupActiveProfile: Profile?,
)

internal data class HomeTrailingLocalState(
    val runtimeReloadPending: Boolean,
    val torOperation: HomeTorOperationUiState = HomeTorOperationUiState(),
    val torTransitionPrompt: TorTransitionPrompt? = null,
    val catalogPresetPreviews: Map<Long, List<RoutingRepository.RoutingCatalogPresetPreview>>,
    val startupActiveProfile: Profile?,
    val appliedRuntimeSignature: Int?,
    val dashboardConnectionMetricsLoading: Boolean,
    val torIpInfo: IpInfo?,
)

internal data class HomeTorLocalState(
    val operation: HomeTorOperationUiState,
    val ipInfo: IpInfo?,
    val transitionPrompt: TorTransitionPrompt?,
)

internal data class HomeReconnectStreams(
    val inProgress: Boolean,
    val promptUntilElapsedMs: Long,
    val runtimeReconnectRequired: Boolean,
)

internal data class HomeAppActivityStreams(
    val appTrafficWindows: List<AppTrafficWindow>,
    val networkActivityEvents: List<NetworkActivityEvent>,
)

internal data class HomeActivityStreams(
    val diagnosticEntries: List<DiagnosticEntry>,
    val anomalyEvents: List<AnomalyEvent>,
    val appTrafficWindows: List<AppTrafficWindow>,
    val networkActivityEvents: List<NetworkActivityEvent>,
    val trafficWindows: List<TrafficWindow>,
    val reconnectState: HomeReconnectStreams,
)

internal const val DIAGNOSTICS_PREVIEW_LIMIT = 500
internal const val ANOMALY_EVENTS_UI_LIMIT = 200
internal const val APP_TRAFFIC_WINDOWS_UI_LIMIT = 120
internal const val NETWORK_ACTIVITY_EVENTS_UI_LIMIT = 200
internal const val TRAFFIC_WINDOWS_UI_LIMIT = 120

internal fun homeActivityStreamsPreview(
    diagnosticEntries: List<DiagnosticEntry>,
    anomalyEvents: List<AnomalyEvent>,
    appTrafficWindows: List<AppTrafficWindow>,
    networkActivityEvents: List<NetworkActivityEvent>,
    trafficWindows: List<TrafficWindow>,
    reconnectState: HomeReconnectStreams,
): HomeActivityStreams {
    val previewNetworkActivityEvents =
        networkActivityEvents.takeLatest(
            NETWORK_ACTIVITY_EVENTS_UI_LIMIT,
            NetworkActivityEvent::timestampMs,
        )
    return HomeActivityStreams(
        diagnosticEntries = diagnosticEntries.takeLatest(DIAGNOSTICS_PREVIEW_LIMIT, DiagnosticEntry::timestamp),
        anomalyEvents = anomalyEvents.takeLatest(ANOMALY_EVENTS_UI_LIMIT, AnomalyEvent::createdAtMs),
        appTrafficWindows = appTrafficWindows.takeLatest(APP_TRAFFIC_WINDOWS_UI_LIMIT, AppTrafficWindow::startedAtMs),
        networkActivityEvents = previewNetworkActivityEvents,
        trafficWindows = trafficWindows.takeLatest(TRAFFIC_WINDOWS_UI_LIMIT, TrafficWindow::startedAtMs),
        reconnectState = reconnectState,
    )
}

internal fun TrafficSnapshot.hasSameDashboardTrafficContentAs(other: TrafficSnapshot): Boolean =
    available == other.available &&
        rxBytesPerSec == other.rxBytesPerSec &&
        txBytesPerSec == other.txBytesPerSec &&
        rxTotalBytes == other.rxTotalBytes &&
        txTotalBytes == other.txTotalBytes

private fun <T> List<T>.takeLatest(
    limit: Int,
    timestamp: (T) -> Long,
): List<T> {
    if (size <= limit) {
        return this
    }
    val descending = size < 2 || timestamp(this[0]) >= timestamp(this[size - 1])
    return if (descending) {
        take(limit)
    } else {
        takeLast(limit)
    }
}

internal enum class PendingConnectAction {
    MANUAL,
    AUTO_CONNECT,
    RECONNECT,
    LOCAL_GUARD,
}

internal data class PendingConnectRequest(
    val profileId: Long = 0L,
    val protocolOptionId: String? = null,
    val action: PendingConnectAction,
)

data class InsecureTlsImportWarningState(
    val rawInput: String,
    val profileId: Long? = null,
    val protocolLabels: List<String> = emptyList(),
    val canExcludeAndApply: Boolean = false,
)

internal data class ProfileOptionLatencyKey(
    val profileId: Long,
    val optionId: String,
)

internal data class ProfileOptionServerPingState(
    val pingMs: Long? = null,
    val unavailable: Boolean = false,
)

internal data class ProfileOptionTunnelPingState(
    val pingMs: Long? = null,
    val unavailable: Boolean = false,
)

internal data class ProtocolRecommendationState(
    val profileId: Long,
    val optionId: String,
    val displayName: String,
)

internal data class ProtocolMetricsPingUiState(
    val serverPings: Map<ProfileOptionLatencyKey, ProfileOptionServerPingState> = emptyMap(),
    val tunnelPings: Map<ProfileOptionLatencyKey, ProfileOptionTunnelPingState> = emptyMap(),
)

internal data class ProtocolMetricsUiState(
    val serverPings: Map<ProfileOptionLatencyKey, ProfileOptionServerPingState> = emptyMap(),
    val tunnelPings: Map<ProfileOptionLatencyKey, ProfileOptionTunnelPingState> = emptyMap(),
    val updatedAt: Map<ProfileOptionLatencyKey, Long> = emptyMap(),
    val downOptionIds: Set<ProfileOptionLatencyKey> = emptySet(),
    val refreshingProfileIds: Set<Long> = emptySet(),
    val refreshingOptionIdByProfileId: Map<Long, String> = emptyMap(),
    val recommendation: ProtocolRecommendationState? = null,
)

internal data class ProtocolMetricsRefreshingUiState(
    val profileIds: Set<Long> = emptySet(),
    val optionIdByProfileId: Map<Long, String> = emptyMap(),
)
