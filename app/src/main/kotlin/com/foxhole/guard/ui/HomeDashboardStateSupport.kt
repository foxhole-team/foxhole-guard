package com.foxhole.guard.ui

import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.Profile
import com.foxhole.core.model.ProfileProtocolOption
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.profile.MultiProtocolProfileSupport
import com.foxhole.core.runtime.localGuardModeOrNull
import com.foxhole.guard.runtime.FoxholeVpnService

internal fun resolveDashboardSelectedOptionId(activeProfile: Profile?): String? =
    activeProfile?.let(MultiProtocolProfileSupport::selectedOption)?.id

internal fun resolveDashboardSelectedOptionId(
    activeProfile: Profile?,
    connection: ConnectionSnapshot,
): String? {
    val selected = activeProfile?.selectedProtocolOptionId
        ?.takeIf { id -> activeProfile.protocolOptions.any { option -> option.id == id } }
    val connected = connection.protocolOptionId
        ?.takeIf { connection.profileId == activeProfile?.id && connection.state in LATENCY_ACTIVE_STATES }
        ?.takeIf { id -> activeProfile?.protocolOptions?.any { option -> option.id == id } == true }
    val byProtocol = connection.protocolHint
        ?.takeIf { connection.profileId == activeProfile?.id && connection.state in LATENCY_ACTIVE_STATES }
        ?.let { hint -> activeProfile?.protocolOptions?.firstOrNull { it.protocolHint == hint }?.id }
    return connected ?: byProtocol ?: selected ?: resolveDashboardSelectedOptionId(activeProfile)
}

internal fun homeTopStatusState(state: HomeRouteUiState): ConnectionState = when {
    state.torOperation.active || state.reconnectInProgress -> ConnectionState.RECONNECTING
    state.autoConnect.running -> ConnectionState.CONNECTING
    state.localGuardPending() -> ConnectionState.IDLE
    else -> state.connection.state
}

internal fun shouldShowHomeTopStatusLoading(state: HomeRouteUiState): Boolean =
    !state.profilesLoaded || state.localGuardPending()

private fun HomeRouteUiState.localGuardPending(): Boolean =
    settings.localGuardModeOrNull() != null &&
        !torOperation.active &&
        !reconnectInProgress &&
        !autoConnect.running &&
        (
            connection.state == ConnectionState.IDLE ||
                (
                    connection.profileId == FoxholeVpnService.LOCAL_GUARD_PROFILE_ID &&
                        connection.state == ConnectionState.CONNECTING
                    )
            ) &&
        !(
            connection.profileId == FoxholeVpnService.LOCAL_GUARD_PROFILE_ID &&
                connection.state == ConnectionState.CONNECTED
            )

internal fun resolveDashboardLatencyOptionId(
    activeProfile: Profile?,
    connection: ConnectionSnapshot? = null,
): String? = connection?.let { resolveDashboardSelectedOptionId(activeProfile, it) }
    ?: resolveDashboardSelectedOptionId(activeProfile)
    ?: activeProfile?.protocolHint
        ?.takeIf { it != ProtocolHint.UNKNOWN && it != ProtocolHint.CUSTOM_CONFIG }
        ?.name
        ?.lowercase()

internal fun resolveHomeDashboardProtocolPresentation(
    activeProfile: Profile?,
    connection: ConnectionSnapshot,
    autoConnect: AutoConnectUiState,
    pinSelectionToProfile: Boolean,
    excludedOptionIds: Set<String>,
): HomeDashboardProtocolPresentation {
    if (activeProfile == null) return HomeDashboardProtocolPresentation(ProtocolHint.UNKNOWN, emptyList(), null)
    val selectedId = when {
        pinSelectionToProfile -> resolveDashboardSelectedOptionId(activeProfile)
        autoConnect.running -> autoConnect.currentOptionId ?: activeProfile.selectedProtocolOptionId
        else -> resolveDashboardSelectedOptionId(activeProfile, connection)
    }
    val options =
        dashboardProtocolOptions(activeProfile, autoConnect, selectedId, pinSelectionToProfile, excludedOptionIds)
    return HomeDashboardProtocolPresentation(
        protocolHint = options.firstOrNull { it.id == selectedId }?.protocolHint ?: activeProfile.protocolHint,
        protocolOptions = options,
        selectedProtocolOptionId = selectedId,
    )
}

private fun dashboardProtocolOptions(
    profile: Profile,
    autoConnect: AutoConnectUiState,
    selectedId: String?,
    pinned: Boolean,
    excluded: Set<String>,
): List<ProfileProtocolOption> =
    if (autoConnect.running && !pinned && autoConnect.options.isNotEmpty()) {
        autoConnect.options.map { option ->
            ProfileProtocolOption(
                id = option.optionId,
                displayName = option.displayName,
                protocolHint = option.protocolHint,
                isSelected = option.optionId == selectedId,
            )
        }
    } else {
        profile.protocolOptions
            .filter { option -> option.id == selectedId || option.id !in excluded }
            .map { option -> option.copy(isSelected = option.id == selectedId) }
    }

internal fun shouldRenderDashboardConnectionDetails(
    connectionState: ConnectionState,
    activeProfile: Profile?,
    selectedLatencyMs: Long?,
    selectedLatencyDown: Boolean,
    selectedLatencyUnavailable: Boolean,
    selectedServerPingAvailable: Boolean,
): Boolean = connectionState == ConnectionState.CONNECTED && activeProfile != null &&
    (selectedLatencyMs != null || selectedLatencyDown || selectedLatencyUnavailable || selectedServerPingAvailable)

internal fun resolveDashboardLatencyPresentation(state: HomeRouteUiState): HomeDashboardLatencyPresentation = when {
    state.connection.profileId in setOf(
        FoxholeVpnService.LOCAL_GUARD_PROFILE_ID,
        FoxholeVpnService.TOR_ONLY_PROFILE_ID,
    ) && !state.autoConnect.running -> HomeDashboardLatencyPresentation()
    state.autoConnect.running -> autoConnectLatency(state.autoConnect)
    state.connection.state !in LATENCY_ACTIVE_STATES -> HomeDashboardLatencyPresentation()
    else -> connectedLatency(state)
}

private fun autoConnectLatency(state: AutoConnectUiState): HomeDashboardLatencyPresentation {
    val option = state.options.firstOrNull { it.optionId == state.currentOptionId }
    return when {
        option?.status == AutoConnectProbeStatus.FAILED -> HomeDashboardLatencyPresentation(isDown = true)
        option?.status in LATENCY_SUCCESS_STATES && option?.latencyMs != null ->
            HomeDashboardLatencyPresentation(latencyMs = option.latencyMs)
        option?.status in LATENCY_SUCCESS_STATES && option?.latencyUnavailable == true ->
            HomeDashboardLatencyPresentation(isUnavailable = true)
        else -> HomeDashboardLatencyPresentation()
    }
}

private fun connectedLatency(state: HomeRouteUiState): HomeDashboardLatencyPresentation {
    val id = resolveDashboardLatencyOptionId(state.activeProfile, state.connection)
    val latency = id?.let(state.protocolTunnelPingsByOptionId::get)
    if (latency != null) return HomeDashboardLatencyPresentation(latencyMs = latency)
    if (state.isSelectedProtocolRefreshing(id)) {
        return HomeDashboardLatencyPresentation()
    }
    val down = id in state.protocolDownOptionIds
    val unavailable = id in state.protocolTunnelPingUnavailableOptionIds
    if ((!down && !unavailable) || System.currentTimeMillis() - state.connection.lastChangeAt < LATENCY_HOLD_MS) {
        return HomeDashboardLatencyPresentation()
    }
    return HomeDashboardLatencyPresentation(isDown = down, isUnavailable = unavailable && !down)
}

private fun HomeRouteUiState.isSelectedProtocolRefreshing(optionId: String?): Boolean {
    if (!protocolMetricsRefreshing || optionId == null) return false
    val refreshingOptionId = protocolMetricsRefreshingOptionId
    return refreshingOptionId == null || refreshingOptionId == optionId
}

internal fun homeProtocolMetricsAnalysisState(
    state: HomeRouteUiState,
    presentation: HomeDashboardProtocolPresentation,
): AutoConnectUiState {
    val option = presentation.protocolOptions.firstOrNull { it.id == state.protocolMetricsRefreshingOptionId }
        ?: presentation.protocolOptions.firstOrNull { it.id == presentation.selectedProtocolOptionId }
        ?: presentation.protocolOptions.firstOrNull()
    return AutoConnectUiState(
        running = true,
        currentOptionId = option?.id,
        currentProtocolHint = option?.protocolHint ?: presentation.protocolHint,
        currentDisplayName = option?.displayName,
        options = option?.let {
            listOf(
                AutoConnectProbeOptionUiState(it.id, it.displayName, it.protocolHint, AutoConnectProbeStatus.TESTING)
            )
        }.orEmpty(),
    )
}

private const val LATENCY_HOLD_MS = 64_000L
private val LATENCY_ACTIVE_STATES = setOf(
    ConnectionState.CONNECTED,
    ConnectionState.CONNECTING,
    ConnectionState.RECONNECTING,
)
internal val DASHBOARD_LATENCY_ACTIVE_STATES = LATENCY_ACTIVE_STATES
private val LATENCY_SUCCESS_STATES = setOf(AutoConnectProbeStatus.SUCCESS, AutoConnectProbeStatus.WINNER)
