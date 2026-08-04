package com.foxhole.guard.ui
import com.foxhole.core.model.ConnectionState
import com.foxhole.guard.runtime.FoxholeVpnService

// Dashboard protocol row model: merges probe results, remembered smart-start latencies and the
// live tunnel latency into the per-option presentation. Extracted from HomeUiSupport (file split
// by domain).

private data class HomeDashboardProfileLatencyState(
    val presentation: HomeDashboardLatencyPresentation,
    val latenciesByOptionId: Map<String, Long>,
    val downOptionIds: Set<String>,
    val latencyUnavailableOptionIds: Set<String>,
    val showSmartStartLatency: Boolean,
    val connectionMetricsLoading: Boolean,
)

// The server ping only backs the connection-details section for a real VPN profile route (or an
// active auto-connect scan). A local-guard/firewall or Tor-only runtime has no VPN profile ping to
// show, matching resolveDashboardLatencyPresentation which blanks the profile latency there.
private fun serverPingRelevantForDashboardDetails(state: HomeRouteUiState): Boolean =
    state.autoConnect.running ||
        (
            state.connection.profileId != FoxholeVpnService.LOCAL_GUARD_PROFILE_ID &&
                state.connection.profileId != FoxholeVpnService.TOR_ONLY_PROFILE_ID
            )

internal fun resolveHomeDashboardProtocolModel(state: HomeRouteUiState): HomeDashboardProtocolModel {
    val optionLatencyInputs = state.dashboardOptionLatencyInputs()
    val protocolPresentation =
        resolveHomeDashboardProtocolPresentation(
            activeProfile = state.activeProfile,
            connection = state.connection,
            autoConnect = state.autoConnect,
            pinSelectionToProfile = state.protocolMetricsRefreshing || state.reconnectRequired,
            excludedOptionIds = emptySet(),
        )
    val selectedServerPingOptionId = resolveDashboardLatencyOptionId(state.activeProfile, state.connection)
    val selectedServerPingMs = selectedServerPingOptionId?.let(state.protocolServerPingsByOptionId::get)
    val selectedServerPingUnavailable =
        selectedServerPingOptionId != null &&
            selectedServerPingMs == null &&
            selectedServerPingOptionId in state.protocolServerPingUnavailableOptionIds
    val latencyState =
        state.resolveDashboardProfileLatencyState(
            latenciesByOptionId = optionLatencyInputs.latenciesByOptionId,
            downOptionIds = optionLatencyInputs.downOptionIds,
            latencyUnavailableOptionIds = optionLatencyInputs.latencyUnavailableOptionIds,
        )
    val iconOverlay = state.dashboardIconLatencyOverlay(latencyState)
    return HomeDashboardProtocolModel(
        presentation = protocolPresentation,
        latencyPresentation = latencyState.presentation,
        latenciesByOptionId = iconOverlay.latenciesByOptionId,
        downOptionIds = iconOverlay.downOptionIds,
        latencyUnavailableOptionIds = iconOverlay.latencyUnavailableOptionIds,
        showSmartStartLatency = latencyState.showSmartStartLatency,
        selectedServerPingMs = selectedServerPingMs,
        selectedServerPingUnavailable = selectedServerPingUnavailable,
        connectionDetailsReady =
        shouldRenderDashboardConnectionDetails(
            connectionState = state.connection.state,
            activeProfile = state.activeProfile,
            selectedLatencyMs = latencyState.presentation.latencyMs,
            selectedLatencyDown = latencyState.presentation.isDown,
            selectedLatencyUnavailable = latencyState.presentation.isUnavailable,
            selectedServerPingAvailable =
            serverPingRelevantForDashboardDetails(state) &&
                (selectedServerPingMs != null || selectedServerPingUnavailable),
        ),
        connectionMetricsLoading = latencyState.connectionMetricsLoading,
    )
}

private data class DashboardOptionLatencyInputs(
    val latenciesByOptionId: Map<String, Long>,
    val downOptionIds: Set<String>,
    val latencyUnavailableOptionIds: Set<String>,
)

// Merges the remembered smart-start latencies, live protocol metrics and the auto-connect scan
// results into the per-option latency/down/unavailable inputs of the dashboard protocol row.
private fun HomeRouteUiState.dashboardOptionLatencyInputs(): DashboardOptionLatencyInputs {
    val mergedLatenciesByOptionId =
        smartStartRememberedLatenciesByOptionId +
            protocolLatenciesByOptionId +
            autoConnect.options
                .mapNotNull { option ->
                    option.latencyMs
                        ?.takeIf {
                            option.status == AutoConnectProbeStatus.SUCCESS ||
                                option.status == AutoConnectProbeStatus.WINNER
                        }?.let { latencyMs -> option.optionId to latencyMs }
                }.toMap()
    val activeConnectedOptionId = activeConnectedAutoConnectOptionId()
    val refreshingOptionId =
        protocolMetricsRefreshingOptionId.takeIf {
            protocolMetricsRefreshing
        }
    val refreshingProbeOptionIds =
        if (protocolMetricsRefreshing) {
            autoConnect.options.map(AutoConnectProbeOptionUiState::optionId).toSet()
        } else {
            emptySet()
        }
    val autoConnectFailedOptionIds =
        if (protocolMetricsRefreshing) {
            emptySet()
        } else {
            autoConnect.options
                .filter { option -> option.status == AutoConnectProbeStatus.FAILED }
                .map(AutoConnectProbeOptionUiState::optionId)
                .toSet()
        }
    val mergedDownOptionIds =
        (
            (protocolDownOptionIds - refreshingProbeOptionIds) +
                autoConnectFailedOptionIds
            ).withoutOption(activeConnectedOptionId)
            .withoutOption(refreshingOptionId)
    val autoConnectUnavailableOptionIds =
        if (protocolMetricsRefreshing) {
            emptySet()
        } else {
            autoConnect.options
                .filter { option ->
                    option.status in setOf(AutoConnectProbeStatus.SUCCESS, AutoConnectProbeStatus.WINNER) &&
                        option.latencyUnavailable
                }.map(AutoConnectProbeOptionUiState::optionId)
                .toSet()
        }
    val mergedLatencyUnavailableOptionIds =
        (
            (protocolLatencyUnavailableOptionIds - refreshingProbeOptionIds) +
                autoConnectUnavailableOptionIds
            ).withoutOption(activeConnectedOptionId)
            .withoutOption(refreshingOptionId)
    return DashboardOptionLatencyInputs(
        latenciesByOptionId = mergedLatenciesByOptionId,
        downOptionIds = mergedDownOptionIds,
        latencyUnavailableOptionIds = mergedLatencyUnavailableOptionIds,
    )
}

private data class DashboardIconLatencyOverlay(
    val latenciesByOptionId: Map<String, Long>,
    val downOptionIds: Set<String>,
    val latencyUnavailableOptionIds: Set<String>,
)

// The live tunnel latency (the pill next to the profile name) must colour the protocol icon of
// the connected option immediately, not wait for the next smart-start probe sweep — otherwise
// the pill goes yellow/red while the icon keeps a stale green. During an auto-connect scan the
// presentation belongs to the option being probed, not the resolved one, and the per-option
// scan results are merged already — so the overlay applies to settled connections only.
private fun HomeRouteUiState.dashboardIconLatencyOverlay(
    latencyState: HomeDashboardProfileLatencyState,
): DashboardIconLatencyOverlay {
    val liveLatencyOptionId =
        resolveDashboardLatencyOptionId(activeProfile, connection)
            .takeUnless { autoConnect.running }
    val liveLatencyMs = latencyState.presentation.latencyMs
    val iconLatenciesByOptionId =
        if (liveLatencyOptionId != null && liveLatencyMs != null) {
            latencyState.latenciesByOptionId + (liveLatencyOptionId to liveLatencyMs)
        } else {
            latencyState.latenciesByOptionId
        }
    val iconDownOptionIds =
        if (liveLatencyOptionId != null && latencyState.presentation.isDown) {
            latencyState.downOptionIds + liveLatencyOptionId
        } else {
            latencyState.downOptionIds
        }
    val iconLatencyUnavailableOptionIds =
        if (liveLatencyOptionId != null && latencyState.presentation.isUnavailable) {
            latencyState.latencyUnavailableOptionIds + liveLatencyOptionId
        } else {
            latencyState.latencyUnavailableOptionIds
        }
    return DashboardIconLatencyOverlay(
        latenciesByOptionId = iconLatenciesByOptionId,
        downOptionIds = iconDownOptionIds,
        latencyUnavailableOptionIds = iconLatencyUnavailableOptionIds,
    )
}

private fun HomeRouteUiState.resolveDashboardProfileLatencyState(
    latenciesByOptionId: Map<String, Long>,
    downOptionIds: Set<String>,
    latencyUnavailableOptionIds: Set<String>,
): HomeDashboardProfileLatencyState {
    if (!shouldShowDashboardProfileLatency()) {
        return HomeDashboardProfileLatencyState(
            presentation = HomeDashboardLatencyPresentation(),
            latenciesByOptionId = emptyMap(),
            downOptionIds = emptySet(),
            latencyUnavailableOptionIds = emptySet(),
            showSmartStartLatency = false,
            connectionMetricsLoading = false,
        )
    }
    return HomeDashboardProfileLatencyState(
        presentation = resolveDashboardLatencyPresentation(this),
        latenciesByOptionId = latenciesByOptionId,
        downOptionIds = downOptionIds,
        latencyUnavailableOptionIds = latencyUnavailableOptionIds,
        showSmartStartLatency =
        latenciesByOptionId.isNotEmpty() ||
            downOptionIds.isNotEmpty() ||
            latencyUnavailableOptionIds.isNotEmpty(),
        connectionMetricsLoading = dashboardConnectionMetricsLoading || reconnectInProgress || protocolMetricsRefreshing,
    )
}

private fun HomeRouteUiState.shouldShowDashboardProfileLatency(): Boolean =
    if (
        (
            connection.profileId == FoxholeVpnService.LOCAL_GUARD_PROFILE_ID ||
                // A Tor-only runtime has no VPN profile ping: while Tor is connecting/connected the
                // profile card must not animate a latency skeleton for the idle VPN profile.
                connection.profileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID
            ) &&
        !autoConnect.running
    ) {
        false
    } else {
        reconnectInProgress ||
            connection.state in DASHBOARD_LATENCY_ACTIVE_STATES ||
            autoConnect.running
    }

private fun HomeRouteUiState.activeConnectedAutoConnectOptionId(): String? =
    autoConnect.currentOptionId?.takeIf { optionId ->
        autoConnect.running &&
            connection.state in setOf(ConnectionState.CONNECTING, ConnectionState.CONNECTED, ConnectionState.RECONNECTING) &&
            (
                connection.state != ConnectionState.CONNECTED ||
                    connection.protocolOptionId == optionId ||
                    (
                        connection.protocolOptionId == null &&
                            autoConnect.options.firstOrNull { option -> option.optionId == optionId }?.protocolHint == connection.protocolHint
                        )
                )
    }

private fun Set<String>.withoutOption(optionId: String?): Set<String> =
    optionId?.let { this - it } ?: this
