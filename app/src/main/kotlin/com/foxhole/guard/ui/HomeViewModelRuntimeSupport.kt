package com.foxhole.guard.ui
import android.app.Application
import androidx.lifecycle.viewModelScope
import com.foxhole.core.model.ACTIVE_CONNECTION_STATES
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.PrivacyRouteMode
import com.foxhole.core.model.Profile
import com.foxhole.core.model.isUdpTransport
import com.foxhole.guard.R
import com.foxhole.guard.runtime.FoxholeVpnService
import com.foxhole.guard.userFacingErrorMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// Runtime connect/reconnect/reload control: connect entry points, runtime-setting updates that
// reload or reconnect the live session, and the reload/reconnect pending markers.

internal suspend fun HomeViewModel.reconnectProfileIfRequested(
    profileId: Long,
    reconnectNow: Boolean,
): Boolean {
    val shouldReconnect =
        reconnectNow &&
            controlUiState.value.activeProfile?.id == profileId &&
            controlUiState.value.connection.state in ACTIVE_CONNECTION_STATES
    if (!shouldReconnect) {
        return false
    }

    return runCatching {
        setDashboardConnectionMetricsLoading(true)
        container.connectionController.disconnect(suppressLocalGuard = true, userInitiated = false)
        waitForRuntimeDisconnect()
        connectNow(profileId)
        true
    }.onFailure {
        setDashboardConnectionMetricsLoading(false)
        if (it is CancellationException) {
            container.diagnosticsLogger.record("connection", "profile reconnect cancelled")
        } else {
            emitError(runtimeConnectionFailureMessage(it))
        }
    }.getOrDefault(false)
}

internal fun HomeViewModel.updateRuntimeSettingAndMaybeReconnect(
    updateAction: suspend () -> Unit,
) {
    viewModelScope.launch {
        val reconnectProfileId =
            activeRuntimeProfileIdForReload()
                ?.takeIf {
                    container.connectionController.snapshot.value.state in ACTIVE_CONNECTION_STATES
                }
        if (reconnectProfileId != null) {
            markRuntimeReloadPending()
        }
        runCatching {
            updateAction()
            if (reconnectProfileId != null) {
                setDashboardConnectionMetricsLoading(true)
                container.connectionController.disconnect(suppressLocalGuard = true, userInitiated = false)
                waitForRuntimeDisconnect()
                connectNow(reconnectProfileId)
            }
        }.onFailure {
            setDashboardConnectionMetricsLoading(false)
            clearRuntimeReloadPending()
            if (it is CancellationException) {
                container.diagnosticsLogger.record("connection", "runtime setting reconnect cancelled")
            } else {
                emitError(runtimeConnectionFailureMessage(it))
            }
        }
    }
}

internal fun HomeViewModel.updateRuntimeSettingAndMaybeReload(
    updateAction: suspend () -> Unit,
) {
    viewModelScope.launch {
        val shouldSuppressReconnectWarning =
            activeRuntimeProfileIdForReload() != null &&
                container.connectionController.snapshot.value.state in ACTIVE_CONNECTION_STATES
        if (shouldSuppressReconnectWarning) {
            markRuntimeReloadPending()
        }
        updateAction()
        val reloadRequested = maybeReloadActiveRuntime()
        if (shouldSuppressReconnectWarning && !reloadRequested) {
            clearRuntimeReloadPending()
        }
    }
}

internal suspend fun HomeViewModel.maybeReloadActiveRuntime(): Boolean {
    // Every declined branch is logged: a setting that should reconfigure a live runtime but
    // silently doesn't (e.g. Stop TOR leaving the tor process up) is otherwise undiagnosable
    // from device logs.
    val targetProfileId = activeRuntimeProfileIdForReload()
    val declineReason = runtimeReloadDeclineReason(targetProfileId)
    if (declineReason != null) {
        container.diagnosticsLogger.record("runtime", "reload skipped: $declineReason")
        return false
    }
    val reloaded = container.connectionController.reload(checkNotNull(targetProfileId))
    if (reloaded) {
        scheduleDashboardRefreshAfterRuntimeReload()
    } else {
        container.diagnosticsLogger.record("runtime", "reload declined by controller: profile=$targetProfileId")
    }
    return reloaded
}

private suspend fun HomeViewModel.runtimeReloadDeclineReason(targetProfileId: Long?): String? {
    if (runtimeReconnectRequiredMutable.value) {
        return "reconnect already required"
    }
    if (targetProfileId == null) {
        return "no active runtime profile"
    }
    val snapshot = container.connectionController.snapshot.value
    if (snapshot.state !in ACTIVE_CONNECTION_STATES) {
        return "state=${snapshot.state}"
    }
    val liveTorOnly = targetProfileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID
    val routingChange =
        resolveRoutingChangeAction(
            old = RoutingChangeState(snapshot.trafficMode, torOnlyRuntime = liveTorOnly),
            new = RoutingChangeState(
                container.settingsRepository.current().traffic.mode,
                torOnlyRuntime = liveTorOnly,
            ),
        )
    if (routingChange != RoutingChangeAction.HOT_RELOAD) {
        return "traffic mode switch requires a full transition"
    }
    val currentFingerprint = container.connectionController.currentRuntimeFingerprint()
    if (container.connectionController.appliedRuntimeSignature.value == currentFingerprint) {
        return "fingerprint unchanged ($currentFingerprint)"
    }
    return null
}

internal fun HomeViewModel.activeRuntimeProfileIdForReload(): Long? {
    val snapshot = container.connectionController.snapshot.value
    return resolveActiveRuntimeProfileIdForReload(
        snapshot = snapshot,
        activeProfileId = controlUiState.value.activeProfile?.id,
    )
}

internal fun resolveActiveRuntimeProfileIdForReload(
    snapshot: com.foxhole.core.model.ConnectionSnapshot,
    activeProfileId: Long?,
): Long? =
    when {
        snapshot.state !in ACTIVE_CONNECTION_STATES -> null
        snapshot.profileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID -> FoxholeVpnService.TOR_ONLY_PROFILE_ID
        snapshot.profileId == FoxholeVpnService.LOCAL_GUARD_PROFILE_ID -> null
        snapshot.profileId == activeProfileId -> activeProfileId
        else -> null
    }

internal fun HomeViewModel.connect(
    profileId: Long,
    protocolOptionId: String? = null,
) {
    setDashboardConnectionMetricsLoading(true)
    viewModelScope.launch {
        runCatching {
            // Only a real profile (id > 0) becomes the active profile; the negative runtime sentinels
            // (LOCAL_GUARD, TOR_ONLY) are not profiles, and setting one active corrupts the
            // active-profile state and spins syncLocalGuard.
            if (
                profileId > 0L &&
                controlUiState.value.activeProfile?.id != profileId
            ) {
                container.connectionController.setActiveProfile(profileId)
                val updated = container.profileRepository.getProfile(profileId)?.copy(isActive = true)
                startupActiveProfileMutable.value = updated
            }
            connectNow(profileId, protocolOptionId = protocolOptionId)
        }
            .onFailure {
                setDashboardConnectionMetricsLoading(false)
                if (it is CancellationException) {
                    container.diagnosticsLogger.record("connection", "connect cancelled")
                } else {
                    emitError(runtimeConnectionFailureMessage(it))
                }
            }
    }
}

private suspend fun HomeViewModel.waitForRuntimeDisconnect() {
    val stopped =
        withTimeoutOrNull(RUNTIME_RECONNECT_DISCONNECT_TIMEOUT_MS) {
            container.connectionController.snapshot.first { snapshot ->
                snapshot.state == ConnectionState.IDLE || snapshot.state == ConnectionState.ERROR
            }
            true
        } == true
    if (!stopped) {
        error(getApplication<Application>().getString(R.string.error_runtime_stopped))
    }
}

internal fun HomeViewModel.runtimeConnectionFailureMessage(error: Throwable): String {
    val app = getApplication<Application>()
    return app.userFacingErrorMessage(error, R.string.error_runtime_missing)
}

internal fun HomeViewModel.infoBanner(stringRes: Int): FoxholeBannerEvent =
    FoxholeBannerEvent(
        message = getApplication<Application>().getString(stringRes),
        tone = FoxholeBannerTone.INFO,
    )

internal fun HomeViewModel.warningBanner(stringRes: Int): FoxholeBannerEvent =
    FoxholeBannerEvent(
        message = getApplication<Application>().getString(stringRes),
        tone = FoxholeBannerTone.WARNING,
    )

internal fun HomeViewModel.successBanner(stringRes: Int): FoxholeBannerEvent =
    FoxholeBannerEvent(
        message = getApplication<Application>().getString(stringRes),
        tone = FoxholeBannerTone.SUCCESS,
    )

internal fun HomeViewModel.errorBanner(stringRes: Int): FoxholeBannerEvent =
    FoxholeBannerEvent(
        message = getApplication<Application>().getString(stringRes),
        tone = FoxholeBannerTone.ERROR,
    )

internal suspend fun HomeViewModel.emitInfo(message: String) {
    snackbars.emit(
        FoxholeBannerEvent(
            message = message,
            tone = FoxholeBannerTone.INFO,
        ),
    )
}

internal suspend fun HomeViewModel.emitSuccess(message: String) {
    snackbars.emit(
        FoxholeBannerEvent(
            message = message,
            tone = FoxholeBannerTone.SUCCESS,
        ),
    )
}

private const val RUNTIME_RECONNECT_DISCONNECT_TIMEOUT_MS = 12_000L

internal suspend fun HomeViewModel.emitError(message: String) {
    snackbars.emit(
        FoxholeBannerEvent(
            message = message,
            tone = FoxholeBannerTone.ERROR,
        ),
    )
}

internal suspend fun HomeViewModel.connectNow(
    profileId: Long,
    protocolOptionId: String? = null,
    statusMessage: String? = null,
    isSmartStartConnection: Boolean = false,
    previousVpnNetworkHandle: Long? = null,
) {
    supersedeIpInfoRefreshesForConnect()
    requestNotificationPermission.tryEmit(Unit)
    if (profileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID) {
        container.connectionController.connectTorOnly(statusMessage = statusMessage)
        return
    }
    warnIfTorRouteCannotRunForProfile(profileId, protocolOptionId)
    container.connectionController.connect(
        profileId = profileId,
        protocolOptionId = protocolOptionId,
        statusMessage = statusMessage,
        isSmartStartConnection = isSmartStartConnection,
        previousVpnNetworkHandle = previousVpnNetworkHandle,
    )
}

private fun HomeViewModel.supersedeIpInfoRefreshesForConnect() {
    if (shouldSupersedeIpRefreshForConnect(activeIpInfoRefreshReason)) {
        pendingPostConnectIpRefresh = true
        container.diagnosticsLogger.record(
            "ip",
            "dashboard refresh superseded by connect reason=${activeIpInfoRefreshReason?.name?.lowercase().orEmpty()}",
        )
    }
    invalidateIpInfoRefreshes()
}

private suspend fun HomeViewModel.warnIfTorRouteCannotRunForProfile(
    profileId: Long,
    protocolOptionId: String?,
) {
    val settings = container.settingsRepository.current()
    if (settings.privacyRoute.mode != PrivacyRouteMode.TOR_OVER_VPN || settings.privacyRoute.bypassVpnTunnel) {
        return
    }
    val profile = container.profileRepository.getProfile(profileId) ?: return
    val protocolHint = profile.runtimeProtocolHint(protocolOptionId)
    if (protocolHint.isUdpTransport()) {
        snackbars.emit(errorBanner(R.string.privacy_route_udp_warning))
    }
}

private fun Profile.runtimeProtocolHint(protocolOptionId: String?) =
    (
        protocolOptionId
            ?.takeIf(String::isNotBlank)
            ?.let { requestedId -> protocolOptions.firstOrNull { option -> option.id == requestedId } }
            ?: selectedProtocolOptionId
                ?.takeIf(String::isNotBlank)
                ?.let { selectedId -> protocolOptions.firstOrNull { option -> option.id == selectedId } }
            ?: protocolOptions.firstOrNull()
        )?.protocolHint ?: protocolHint

internal fun HomeViewModel.markRuntimeReloadPending() {
    runtimeReloadPendingJob?.cancel()
    runtimeReloadPendingMutable.value = true
    runtimeReloadPendingJob =
        viewModelScope.launch {
            delay(HomeViewModel.RUNTIME_RELOAD_PENDING_TIMEOUT_MS)
            runtimeReloadPendingMutable.value = false
            runtimeReloadPendingJob = null
        }
}

internal fun HomeViewModel.clearRuntimeReloadPending() {
    runtimeReloadPendingJob?.cancel()
    runtimeReloadPendingJob = null
    runtimeReloadPendingMutable.value = false
}

internal fun HomeViewModel.markRuntimeReconnectRequired() {
    runtimeReconnectRequiredMutable.value = true
}

internal fun HomeViewModel.clearRuntimeReconnectRequired() {
    runtimeReconnectRequiredMutable.value = false
}
