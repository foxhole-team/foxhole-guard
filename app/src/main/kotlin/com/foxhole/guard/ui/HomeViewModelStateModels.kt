package com.foxhole.guard.ui
import com.foxhole.core.model.AnomalyEvent
import com.foxhole.core.model.AppTrafficWindow
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.DiagnosticEntry
import com.foxhole.core.model.I2pPhaseSnapshot
import com.foxhole.core.model.InstalledAppOption
import com.foxhole.core.model.IpInfo
import com.foxhole.core.model.NetworkActivityEvent
import com.foxhole.core.model.PrivacyRouteScope
import com.foxhole.core.model.Profile
import com.foxhole.core.model.RoutingCatalog
import com.foxhole.core.model.RoutingModePreset
import com.foxhole.core.model.RoutingPreset
import com.foxhole.core.model.TorPhaseSnapshot
import com.foxhole.core.model.TrafficSnapshot
import com.foxhole.core.model.TrafficWindow
import com.foxhole.guard.BuildConfig
import com.foxhole.guard.core.data.RoutingRepository
import com.foxhole.core.model.Settings as FoxholeSettings

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
    val torPhase: TorPhaseSnapshot = TorPhaseSnapshot(),
    val i2pPhase: I2pPhaseSnapshot = I2pPhaseSnapshot(),
    val torTransitionPrompt: TorTransitionPrompt? = null,
    val profileReconnectPromptUntilElapsedMs: Long = 0L,
    val diagnosticEntries: List<DiagnosticEntry> = emptyList(),
    val anomalyEvents: List<AnomalyEvent> = emptyList(),
    val appTrafficWindows: List<AppTrafficWindow> = emptyList(),
    val networkActivityEvents: List<NetworkActivityEvent> = emptyList(),
    val trafficWindows: List<TrafficWindow> = emptyList(),
    val catalogPresetPreviews: Map<Long, List<RoutingRepository.RoutingCatalogPresetPreview>> = emptyMap(),
    val appVersion: String = BuildConfig.VERSION_NAME,
    val coreVersion: String = BuildConfig.FOXCORE_SOURCE_VERSION,
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

    // Both a VPN route and Tor are up: ask which one the Restart action should restart.

    // Tor is placed inside the tunnel (bypass off), a VPN profile is saved, but the VPN is not
    // running: starting Tor now means a direct-from-device Tor connection, so warn and ask.
    object StartTorFromDeviceWithoutVpn : TorTransitionPrompt()

    // A live UDP tunnel cannot carry Tor inside it, but Tor can run beside the VPN on the device
    // route. Continue switches the placement to beside (bypass on) and starts Tor there.
    object StartTorBesideUdpVpn : TorTransitionPrompt()

    // A mode change requested while a VPN tunnel is already live. Shown with a countdown that reverts
    // to the running mode on timeout, so the button never silently reshapes a live connection.
    // `secondsLeft` ticks 15→0; confirm applies `target`/`scope`, cancel/timeout applies nothing.
    data class LiveModeSwitch(
        val target: RoutingModePreset,
        val scope: PrivacyRouteScope,
        val kind: LiveModeSwitchKind,
        val secondsLeft: Int,
    ) : TorTransitionPrompt()

    // The user asked to activate a DIFFERENT profile than the one carrying the live tunnel. Confirm
    // swaps the active profile and reconnects onto it; cancel leaves the running tunnel untouched.
    data class SwitchProfileWhileConnected(
        val profileId: Long,
        val profileName: String,
    ) : TorTransitionPrompt()

    // A smart profile's protocol option was tapped while its tunnel is live and the chosen option is
    // not the running one. Confirm persists the option and reconnects; cancel keeps the running
    // protocol (the selection is never applied, so nothing has to be reverted).
    data class SwitchProtocolWhileConnected(
        val profileId: Long,
        val protocolOptionId: String,
        val protocolName: String,
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
    val torPhase: TorPhaseSnapshot,
    val i2pPhase: I2pPhaseSnapshot,
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
    val torPhase: TorPhaseSnapshot,
    val i2pPhase: I2pPhaseSnapshot,
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

/**
 * What the settings home list needs to lay itself out. The component switches decide both which
 * rows exist (a disabled Statistics core takes its row with it) and how the privacy-route row names
 * itself — TOR routing, I2P, or both.
 */
data class SettingsHomeNavUiState(
    val expertVisible: Boolean = false,
    val torEnabled: Boolean = false,
    val i2pEnabled: Boolean = false,
    // With both cores on, whether TOR was enabled first — drives the combined row title's order
    // («… TOR и I2P» vs «… I2P и TOR») and the section order inside the screen.
    val torFirst: Boolean = true,
    val statisticsEnabled: Boolean = false,
)

internal enum class PendingConnectAction {
    MANUAL,
    RECONNECT,
    LOCAL_GUARD,
}

internal data class PendingConnectRequest(
    val profileId: Long = 0L,
    val protocolOptionId: String? = null,
    val action: PendingConnectAction,
)

data class ProfileImportConfirmationState(
    val rawInput: String,
    val preview: com.foxhole.guard.core.data.ProfileImportPreview,
    // Insecure-TLS consent is folded into the SAME sheet: the warning block and the extra
    // "exclude and apply" button render right in the confirmation instead of a second dialog.
    val insecureTls: Boolean = false,
    val insecureTlsProtocolLabels: List<String> = emptyList(),
    val canExcludeInsecureTls: Boolean = false,
    // The same source is already stored: the sheet flips into its already-added state, offering
    // cancel or refresh instead of silently importing a twin.
    val duplicateProfileId: Long? = null,
    val duplicateProfileName: String? = null,
    val duplicateIsSubscription: Boolean = false,
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
