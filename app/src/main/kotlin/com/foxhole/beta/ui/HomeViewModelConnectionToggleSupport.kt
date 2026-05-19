package com.foxhole.beta.ui

import com.foxhole.beta.R
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.PrivacyRouteScope
import com.foxhole.beta.core.model.Profile
import com.foxhole.beta.core.model.TrafficMode
import com.foxhole.beta.vpn.FoxholeVpnService

@Suppress("ReturnCount")
internal fun HomeViewModel.toggleConnectionInternal() {
    if (cancelProtocolSearchConnection()) {
        return
    }
    cancelAutoConnect(clearUiOnly = true)
    val state = uiState.value
    if (cancelReconnectConnection(state)) {
        return
    }
    if (toggleStandaloneRuntimeConnection(state)) {
        return
    }
    val activeProfile = state.activeProfile
    if (activeProfile == null) {
        handleToggleWithoutActiveProfile(state)
        return
    }
    val networkOverride = currentNetworkProfileOverride(state)
    val connectProfile = networkOverride?.profile ?: activeProfile
    if (togglePrimaryRuntimeConnection(state, activeProfile)) {
        return
    }
    connectSelectedProfile(state, connectProfile, networkOverride?.protocolOptionId)
}

private fun HomeViewModel.cancelProtocolSearchConnection(): Boolean {
    val autoConnectRunning = autoConnectUiStateMutable.value.running
    val metricsRefreshRunning = protocolMetricsRefreshJob != null
    val protocolSearchRunning = autoConnectRunning || metricsRefreshRunning
    if (!protocolSearchRunning) {
        return false
    }
    cancelAutoConnect(clearUiOnly = true)
    cancelSmartProfileMetricsRefreshInternal(restoreConnection = false)
    if (autoConnectRunning) {
        container.connectionController.disconnect(suppressLocalGuard = false)
    }
    return true
}

private fun HomeViewModel.cancelReconnectConnection(state: HomeUiState): Boolean {
    if (!state.reconnectInProgress) {
        return false
    }
    reconnectJob?.cancel()
    reconnectJob = null
    reconnectInProgressMutable.value = false
    container.connectionController.disconnect(suppressLocalGuard = false)
    return true
}

private fun HomeViewModel.toggleStandaloneRuntimeConnection(state: HomeUiState): Boolean {
    if (state.activeProfile != null || !state.connection.isPrimaryConnectionRuntime()) {
        return false
    }
    container.connectionController.disconnect(suppressLocalGuard = false)
    return true
}

private fun HomeViewModel.handleToggleWithoutActiveProfile(state: HomeUiState) {
    if (!state.torOnlyRouteReady()) {
        snackbars.tryEmit(errorBanner(R.string.error_profile_missing))
        return
    }
    if (state.settings.privacyRoute.scope == PrivacyRouteScope.ALL_APPS) {
        snackbars.tryEmit(infoBanner(R.string.privacy_route_all_apps_start_warning))
    }
    markTorOperation(HomeTorOperationKind.CONNECTING)
    requestManualConnectPermissionOrConnect(FoxholeVpnService.TOR_ONLY_PROFILE_ID)
}

private fun HomeUiState.torOnlyRouteReady(): Boolean =
    settings.privacyRoute.enabled &&
        (
            settings.privacyRoute.scope == PrivacyRouteScope.ALL_APPS ||
                settings.privacyRoute.selectedPackages.any(String::isNotBlank)
            )

private fun HomeViewModel.togglePrimaryRuntimeConnection(
    state: HomeUiState,
    activeProfile: Profile,
): Boolean {
    if (!state.connection.isPrimaryConnectionRuntime()) {
        return false
    }
    if (state.reconnectRequired && state.connection.state == ConnectionState.CONNECTED) {
        requestReconnect(activeProfile.id)
    } else {
        container.connectionController.disconnect(suppressLocalGuard = false)
    }
    return true
}

private fun HomeViewModel.connectSelectedProfile(
    state: HomeUiState,
    connectProfile: Profile,
    protocolOptionId: String? = null,
) {
    if (maybePromptStartTcpVpnWhileTorOnlyActive(connectProfile, protocolOptionId)) {
        return
    }
    if (state.settings.traffic.mode == TrafficMode.PROXY) {
        connect(connectProfile.id, protocolOptionId = protocolOptionId)
    } else {
        requestManualConnectPermissionOrConnect(connectProfile.id, protocolOptionId)
    }
}

internal fun HomeViewModel.requestManualConnectPermissionOrConnect(
    profileId: Long,
    protocolOptionId: String? = null,
) {
    val prepareIntent = android.net.VpnService.prepare(getApplication())
    if (prepareIntent != null) {
        pendingConnectRequest =
            PendingConnectRequest(
                profileId = profileId,
                protocolOptionId = protocolOptionId,
                action = PendingConnectAction.MANUAL,
            )
        requestVpnPermission.tryEmit(Unit)
    } else {
        connect(profileId, protocolOptionId = protocolOptionId)
    }
}
