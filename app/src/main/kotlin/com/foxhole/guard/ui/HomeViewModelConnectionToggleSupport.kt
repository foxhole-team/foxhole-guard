package com.foxhole.guard.ui
import androidx.lifecycle.viewModelScope
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.PrivacyRouteScope
import com.foxhole.core.model.Profile
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.model.torScopeRunnable
import com.foxhole.guard.R
import com.foxhole.guard.runtime.FoxholeVpnService
import com.foxhole.guard.runtime.messageResource
import com.foxhole.guard.runtime.vpnTorStartBlockReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.security.GeneralSecurityException

@Suppress("ReturnCount")
internal fun HomeViewModel.toggleConnectionInternal() {
    if (cancelProtocolSearchConnection()) {
        return
    }
    cancelAutoConnect(clearUiOnly = true)
    val state = controlUiState.value
    if (cancelReconnectConnection(state)) {
        return
    }
    if (toggleTorOnlyRuntimeConnection(state)) {
        return
    }
    if (toggleStandaloneRuntimeConnection(state)) {
        return
    }
    val activeProfile = state.activeProfile
    if (activeProfile == null) {
        recoverActiveProfileAndToggleConnection()
        return
    }
    val networkOverride = currentNetworkProfileOverride(state)
    val connectProfile = networkOverride?.profile ?: activeProfile
    if (togglePrimaryRuntimeConnection(state, activeProfile)) {
        return
    }
    connectSelectedProfile(state, connectProfile, networkOverride?.protocolOptionId)
}

/**
 * Split-button "Start VPN": connects the active profile without treating an engaged Tor-only
 * runtime as something to toggle off. With Tor beside the tunnel (bypass) the connect replaces the
 * Tor-only session with one VPN+Tor session, so Tor keeps flowing on the device route.
 */
internal fun HomeViewModel.startVpnConnectionInternal() {
    cancelAutoConnect(clearUiOnly = true)
    val state = controlUiState.value
    val activeProfile =
        state.activeProfile ?: run {
            if (!emitVpnTorStartBlockIfNeeded(state, profile = null, torOnlyConnect = false)) {
                snackbars.tryEmit(errorBanner(R.string.error_profile_missing))
            }
            return
        }
    if (state.connection.isPrimaryConnectionRuntime()) {
        return
    }
    val networkOverride = currentNetworkProfileOverride(state)
    val connectProfile = networkOverride?.profile ?: activeProfile
    connectSelectedProfile(state, connectProfile, networkOverride?.protocolOptionId)
}

private fun HomeViewModel.recoverActiveProfileAndToggleConnection() {
    viewModelScope.launch {
        waitForProfileImportBeforeActiveProfileRecovery()
        val recoveredProfile = recoverActiveProfileForToggle()
        if (recoveredProfile == null) {
            handleToggleWithoutActiveProfile(controlUiState.value)
            return@launch
        }
        val activeProfile = recoveredProfile.copy(isActive = true)
        startupActiveProfileMutable.value = activeProfile
        container.diagnosticsLogger.record("profile", "connect recovered active profile")
        val currentState = controlUiState.value
        val recoveredState =
            currentState.copy(
                activeProfile = activeProfile,
                profiles = currentState.profiles.replaceOrAppendActiveProfile(activeProfile),
            )
        val networkOverride = currentNetworkProfileOverride(recoveredState)
        val connectProfile = networkOverride?.profile ?: activeProfile
        if (togglePrimaryRuntimeConnection(recoveredState, activeProfile)) {
            return@launch
        }
        connectSelectedProfile(recoveredState, connectProfile, networkOverride?.protocolOptionId)
    }
}

private suspend fun HomeViewModel.waitForProfileImportBeforeActiveProfileRecovery() {
    if (
        !shouldWaitForProfileImportBeforeMissingProfileError(
            activeProfile = controlUiState.value.activeProfile,
            profileImportInProgress = profileImportInProgressMutable.value,
        )
    ) {
        return
    }
    container.diagnosticsLogger.record("profile", "connect waiting for active profile import")
    val completed =
        withTimeoutOrNull(HomeViewModel.PROFILE_IMPORT_CONNECT_WAIT_TIMEOUT_MS) {
            while (
                profileImportInProgressMutable.value &&
                controlUiState.value.activeProfile == null
            ) {
                delay(HomeViewModel.PROFILE_IMPORT_CONNECT_POLL_MS)
            }
            true
        } == true
    if (!completed) {
        container.diagnosticsLogger.recordFailure("profile", "connect active profile import wait timed out")
    }
}

private suspend fun HomeViewModel.recoverActiveProfileForToggle(): Profile? =
    try {
        withContext(Dispatchers.IO) {
            container.profileRepository.getActiveProfile()
                ?: run {
                    container.profileRepository.ensureActiveProfileInvariant()
                    container.profileRepository.getActiveProfile()
                }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: RuntimeException) {
        container.diagnosticsLogger.recordFailure(
            "profile",
            "connect active profile recovery failed: ${error::class.simpleName}"
        )
        null
    } catch (error: java.io.IOException) {
        container.diagnosticsLogger.recordFailure(
            "profile",
            "connect active profile recovery failed: ${error::class.simpleName}"
        )
        null
    } catch (error: GeneralSecurityException) {
        container.diagnosticsLogger.recordFailure(
            "profile",
            "connect active profile recovery failed: ${error::class.simpleName}"
        )
        null
    }

private fun List<Profile>.replaceOrAppendActiveProfile(activeProfile: Profile): List<Profile> {
    var replaced = false
    val updated =
        map { profile ->
            if (profile.id == activeProfile.id) {
                replaced = true
                activeProfile
            } else {
                profile.copy(isActive = false)
            }
        }
    return if (replaced) {
        updated
    } else {
        updated + activeProfile
    }
}

private fun HomeViewModel.cancelProtocolSearchConnection(): Boolean {
    val action =
        protocolSearchToggleActionFor(
            autoConnectRunning = autoConnectUiStateMutable.value.running,
            metricsRefreshRunning = protocolMetricsRefreshJob != null,
        )
    if (action == ProtocolSearchToggleAction.NONE) {
        return false
    }
    cancelAutoConnect(clearUiOnly = true)
    cancelSmartProfileMetricsRefresh(restoreConnection = false)
    if (action == ProtocolSearchToggleAction.CANCEL_SEARCH_AND_CONSUME) {
        container.connectionController.disconnect(suppressLocalGuard = false)
        return true
    }
    // CANCEL_REFRESH_AND_CONTINUE: the tap is not consumed and connect proceeds normally.
    return false
}

/**
 * What a START tap does while a protocol run is live, extracted pure for testing. A visible
 * auto-connect run is stopped by the tap — that is its "stop". A background smart-profile
 * measurement is invisible to the user, and consuming START looked like a dead button, so the
 * measurement is cancelled and connect continues.
 */
internal enum class ProtocolSearchToggleAction { NONE, CANCEL_SEARCH_AND_CONSUME, CANCEL_REFRESH_AND_CONTINUE }

internal fun protocolSearchToggleActionFor(
    autoConnectRunning: Boolean,
    metricsRefreshRunning: Boolean,
): ProtocolSearchToggleAction =
    when {
        autoConnectRunning -> ProtocolSearchToggleAction.CANCEL_SEARCH_AND_CONSUME
        metricsRefreshRunning -> ProtocolSearchToggleAction.CANCEL_REFRESH_AND_CONTINUE
        else -> ProtocolSearchToggleAction.NONE
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
    clearTorOperation()
    container.connectionController.disconnect(suppressLocalGuard = false)
    return true
}

private fun HomeViewModel.toggleTorOnlyRuntimeConnection(state: HomeUiState): Boolean {
    // Disconnect any engaged Tor-only session, including the pending/bootstrapping and ERROR states
    // that ACTIVE_CONNECTION_STATES omits, so Stop-Tor always cancels an in-flight Tor (no VPN).
    val torOnlyEngagedInSnapshot =
        state.connection.profileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID &&
            state.connection.state != ConnectionState.IDLE
    // Early-start race: right after Start TOR the service may not have published the Tor-only
    // profile id into the snapshot yet, while the pending operation (yellow pill + IP skeleton) is
    // already showing. Without this branch the toggle fell through to the connect path and
    // RESTARTED Tor instead of stopping it — Stop looked like it did nothing.
    val torStartPendingWithoutPrimaryRuntime =
        state.torOperation.active && !state.connection.isPrimaryConnectionRuntime()
    if (!torOnlyEngagedInSnapshot && !torStartPendingWithoutPrimaryRuntime) {
        return false
    }
    clearTorOperation()
    container.connectionController.disconnectTorOnly()
    return true
}

private fun HomeViewModel.handleToggleWithoutActiveProfile(state: HomeUiState) {
    val privacyRoute = state.settings.privacyRoute
    if (emitVpnTorStartBlockIfNeeded(state, profile = null, torOnlyConnect = true)) {
        return
    }
    val torAvailable = privacyRoute.enabled || privacyRoute.permitted
    val selectedAppsScopeEmpty = !state.settings.torScopeRunnable()
    if (torAvailable && selectedAppsScopeEmpty) {
        snackbars.tryEmit(errorBanner(R.string.privacy_route_select_apps_first))
        return
    }
    if (!privacyRoute.enabled && privacyRoute.permitted) {
        // The core is permitted but the route is not armed yet — the exact state right after
        // flipping the core switch. The dashboard Connect TOR button arms and starts it through
        // the same quick-start path the Tor window uses, instead of erroring out.
        onEnableDirectTorQuickStart()
        return
    }
    if (!state.torOnlyRouteReady()) {
        snackbars.tryEmit(errorBanner(R.string.error_profile_missing))
        return
    }
    if (privacyRoute.scope == PrivacyRouteScope.ALL_APPS) {
        snackbars.tryEmit(warningBanner(R.string.privacy_route_all_apps_start_warning))
    }
    markTorOperation(HomeTorOperationKind.CONNECTING)
    requestManualConnectPermissionOrConnect(FoxholeVpnService.TOR_ONLY_PROFILE_ID)
}

private fun HomeUiState.torOnlyRouteReady(): Boolean =
    settings.privacyRoute.enabled && settings.torScopeRunnable()

private fun HomeViewModel.togglePrimaryRuntimeConnection(
    state: HomeUiState,
    activeProfile: Profile,
): Boolean =
    when (
        primaryRuntimeToggleActionFor(
            isPrimaryConnectionRuntime = state.connection.isPrimaryConnectionRuntime(),
            reconnectRequired = state.reconnectRequired,
            connectionState = state.connection.state,
        )
    ) {
        PrimaryRuntimeToggleAction.NONE -> false
        PrimaryRuntimeToggleAction.RECONNECT -> {
            requestReconnect(activeProfile.id)
            true
        }
        PrimaryRuntimeToggleAction.DISCONNECT -> {
            // Stop tears the whole tunnel down. Any Tor riding beside the VPN dies with it (the
            // teardown stops the Tor process too); an armed-but-permitted route stays armed for the
            // next start. Cancelling the in-flight Tor operation stops the Tor modal pinning forever.
            clearTorOperation()
            container.connectionController.disconnect(suppressLocalGuard = false)
            true
        }
    }

/** The stop/reconnect decision for the primary runtime, extracted pure for testing. */
internal enum class PrimaryRuntimeToggleAction { NONE, RECONNECT, DISCONNECT }

internal fun primaryRuntimeToggleActionFor(
    isPrimaryConnectionRuntime: Boolean,
    reconnectRequired: Boolean,
    connectionState: ConnectionState,
): PrimaryRuntimeToggleAction =
    when {
        !isPrimaryConnectionRuntime -> PrimaryRuntimeToggleAction.NONE
        reconnectRequired && connectionState == ConnectionState.CONNECTED ->
            PrimaryRuntimeToggleAction.RECONNECT
        else -> PrimaryRuntimeToggleAction.DISCONNECT
    }

private fun HomeViewModel.connectSelectedProfile(
    state: HomeUiState,
    connectProfile: Profile,
    protocolOptionId: String? = null,
) {
    if (emitVpnTorStartBlockIfNeeded(state, connectProfile, protocolOptionId, torOnlyConnect = false)) {
        return
    }
    if (maybePromptStartTcpVpnWhileTorOnlyActive(connectProfile, protocolOptionId)) {
        return
    }
    if (state.settings.traffic.mode == TrafficMode.PROXY) {
        connect(connectProfile.id, protocolOptionId = protocolOptionId)
    } else {
        requestManualConnectPermissionOrConnect(connectProfile.id, protocolOptionId)
    }
}

private fun HomeViewModel.emitVpnTorStartBlockIfNeeded(
    state: HomeUiState,
    profile: Profile?,
    protocolOptionId: String? = null,
    torOnlyConnect: Boolean,
): Boolean {
    val reason =
        vpnTorStartBlockReason(
            settings = state.settings,
            profile = profile,
            protocolOptionId = protocolOptionId,
            torOnlyConnect = torOnlyConnect,
        ) ?: return false
    snackbars.tryEmit(errorBanner(reason.messageResource()))
    return true
}

internal fun HomeViewModel.requestManualConnectPermissionOrConnect(
    profileId: Long,
    protocolOptionId: String? = null,
) {
    val prepareIntent = android.net.VpnService.prepare(getApplication())
    if (prepareIntent != null) {
        enqueueVpnPermissionRequest(
            PendingConnectRequest(
                profileId = profileId,
                protocolOptionId = protocolOptionId,
                action = PendingConnectAction.MANUAL,
            ),
        )
    } else {
        connect(profileId, protocolOptionId = protocolOptionId)
    }
}
