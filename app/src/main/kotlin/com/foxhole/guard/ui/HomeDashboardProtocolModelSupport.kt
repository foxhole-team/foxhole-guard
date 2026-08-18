package com.foxhole.guard.ui
import com.foxhole.core.model.ConnectionState
import com.foxhole.guard.runtime.FoxholeVpnService

private data class HomeDashboardProfileLatencyState(
    val presentation: HomeDashboardLatencyPresentation,
    val latenciesByOptionId: Map<String, Long>,
    val downOptionIds: Set<String>,
    val latencyUnavailableOptionIds: Set<String>,
    val showSmartStartLatency: Boolean,
    val connectionMetricsLoading: Boolean,
)

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
