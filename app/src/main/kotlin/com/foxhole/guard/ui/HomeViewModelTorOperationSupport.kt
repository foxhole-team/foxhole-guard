package com.foxhole.guard.ui

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.IpInfo
import com.foxhole.core.model.PrivacyRouteMode
import com.foxhole.core.runtime.network.IpInfoFetchMode
import com.foxhole.guard.R
import com.foxhole.guard.core.settings.updatePrivacyRouteMode
import com.foxhole.guard.runtime.FoxholeVpnService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal fun HomeViewModel.markTorOperation(kind: HomeTorOperationKind) {
    torOperationTimeoutJob?.cancel()
    torIdentityProbeTimeoutJob?.cancel()
    torIdentityProbeMutable.restart()
    val startedAt = System.currentTimeMillis()
    val startedIpAddress = torOperationStartedIpAddress(torIpInfoMutable.value)
    torOperationMutable.value =
        HomeTorOperationUiState(
            kind = kind,
            startedAt = startedAt,
            startedIpAddress = startedIpAddress,
        )
    torOperationTimeoutJob =
        viewModelScope.launch {
            delay(HomeViewModel.TOR_OPERATION_MIN_VISIBLE_MS)
            maybeFinishTorOperation(startedAt)
            delay(
                (HomeViewModel.TOR_OPERATION_BOOTSTRAP_NOTICE_MS - HomeViewModel.TOR_OPERATION_MIN_VISIBLE_MS)
                    .coerceAtLeast(0L),
            )
            markTorBootstrappingIfStillConnecting(kind, startedAt)
            maybeFinishTorOperation(startedAt)
            delay(
                (HomeViewModel.TOR_OPERATION_TIMEOUT_MS - HomeViewModel.TOR_OPERATION_BOOTSTRAP_NOTICE_MS)
                    .coerceAtLeast(0L),
            )
            failTorOperationIfStillActive(startedAt)
        }
}

internal fun torOperationStartedIpAddress(previousTorIpInfo: IpInfo?): String? =
    previousTorIpInfo?.let(::primaryVisibleIp)

internal fun torOperationCompletionIpInfo(currentTorIpInfo: IpInfo?): IpInfo? = currentTorIpInfo

internal fun torOperationCompletionIpInfo(
    snapshot: ConnectionSnapshot,
    torOperation: HomeTorOperationUiState,
    currentTorIpInfo: IpInfo?,
): IpInfo? =
    currentTorIpInfo?.takeIf {
        torOperation.active && snapshot.state == ConnectionState.CONNECTED
    }

internal fun shouldClearTorOperationAfterValidatedConnect(
    snapshot: ConnectionSnapshot,
    torOperation: HomeTorOperationUiState,
    nowMs: Long = System.currentTimeMillis(),
): Boolean =
    torOperation.kind in setOf(HomeTorOperationKind.CONNECTING, HomeTorOperationKind.BOOTSTRAPPING) &&
        snapshot.state == ConnectionState.CONNECTED &&
        nowMs - torOperation.startedAt >= HomeViewModel.TOR_OPERATION_MIN_VISIBLE_MS

internal suspend fun HomeViewModel.maybeFinishTorOperation(
    torOperation: HomeTorOperationUiState,
    ipInfo: IpInfo?,
) {
    val publishableIpInfo = ipInfo?.takeIf(torOperation::canPublishTorIp) ?: return
    if (!torOperationMutable.value.active) {
        return
    }
    clearTorOperation()
    publishTorRouteExit(publishableIpInfo)
    emitTorConnectedBanner(publishableIpInfo)
}

internal suspend fun HomeViewModel.disableTorOperationAfterRuntimeError(
    snapshot: ConnectionSnapshot,
    torOperation: HomeTorOperationUiState,
) {
    if (!torOperation.active || snapshot.state != ConnectionState.ERROR) {
        return
    }
    clearTorOperation()
    torIpInfoMutable.value = null
    val ticket = runtimeSettingUpdates.reserve()
    try {
        ticket.awaitTurn()
        runAuthoritativeRuntimeSettingUpdate(
            updateAction = {
                container.settingsRepository.updatePrivacyRouteMode(PrivacyRouteMode.OFF)
            },
            applyAction = {
                clearRuntimeReconnectRequired()
                when {
                    snapshot.profileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID ->
                        container.connectionController.disconnectTorOnly(userInitiated = false)
                    snapshot.torActive ->
                        container.connectionController.disconnect(suppressLocalGuard = false, userInitiated = false)
                }
                true
            },
        )
    } finally {
        ticket.complete()
    }
}

private suspend fun HomeViewModel.maybeFinishTorOperation(startedAt: Long) {
    val torOperation = torOperationMutable.value
    if (torOperation.startedAt != startedAt) {
        return
    }
    maybeFinishTorOperation(
        torOperation,
        torOperationCompletionIpInfo(torIpInfoMutable.value),
    )
}

private fun HomeViewModel.markTorBootstrappingIfStillConnecting(
    requestedKind: HomeTorOperationKind,
    startedAt: Long,
) {
    if (requestedKind != HomeTorOperationKind.CONNECTING) {
        return
    }
    val current = torOperationMutable.value
    if (current.startedAt == startedAt && current.kind == HomeTorOperationKind.CONNECTING) {
        torOperationMutable.value = current.copy(kind = HomeTorOperationKind.BOOTSTRAPPING)
    }
}

private suspend fun HomeViewModel.failTorOperationIfStillActive(startedAt: Long) {
    val current = torOperationMutable.value
    if (current.startedAt != startedAt || !current.active) {
        return
    }
    val snapshot = container.connectionController.snapshot.value
    val torRouteCarried =
        snapshot.state == ConnectionState.CONNECTED &&
            (snapshot.profileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID || snapshot.torActive)
    if (!torRouteCarried) {
        failTorIdentityProbe(beginTorIdentityProbe())
        emitError(getApplication<Application>().getString(R.string.privacy_route_bootstrap_timeout))
    }
    clearTorOperation()
}

internal fun HomeViewModel.superviseTorExitBackgroundRefresh(
    snapshot: ConnectionSnapshot,
    torIpInfo: IpInfo?,
    torOperation: HomeTorOperationUiState,
) {
    val needsExit =
        torIpInfo == null &&
            !torOperation.active &&
            snapshot.state == ConnectionState.CONNECTED &&
            (snapshot.profileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID || snapshot.torActive)
    if (!needsExit) {
        torExitBackgroundRefreshJob?.cancel()
        torExitBackgroundRefreshJob = null
        return
    }
    if (torExitBackgroundRefreshJob?.isActive == true) {
        return
    }
    torExitBackgroundRefreshJob =
        viewModelScope.launch {
            var delayMs = HomeViewModel.TOR_EXIT_BACKGROUND_REFRESH_INITIAL_DELAY_MS
            while (true) {
                delay(delayMs)
                if (ipInfoRefreshJob == null && postConnectTorRouteRefreshJob == null) {
                    val info = refreshTorExitInBackground()
                    if (info != null) {
                        val generation = beginTorIdentityProbe(retryAfterFailure = true)
                        if (publishTorRouteExit(info)) {
                            break
                        }
                        failTorIdentityProbe(generation)
                    }
                }
                delayMs = (delayMs * 2).coerceAtMost(HomeViewModel.TOR_EXIT_BACKGROUND_REFRESH_MAX_DELAY_MS)
            }
            torExitBackgroundRefreshJob = null
        }
}

private fun HomeTorOperationUiState.canPublishTorIp(ipInfo: IpInfo): Boolean {
    val readyToPublish =
        active &&
            ipInfo.fetchedAt >= startedAt &&
            System.currentTimeMillis() - startedAt >= HomeViewModel.TOR_OPERATION_MIN_VISIBLE_MS
    return readyToPublish && canAcceptTorIp(ipInfo)
}

internal fun HomeViewModel.clearTorOperation() {
    torOperationTimeoutJob?.cancel()
    torOperationTimeoutJob = null
    torOperationMutable.value = HomeTorOperationUiState()
}

internal fun HomeViewModel.beginTorIdentityProbe(retryAfterFailure: Boolean = false): Long {
    val generation = torIdentityProbeMutable.begin(retryAfterFailure)
    if (
        torIdentityProbeMutable.state.value.phase == TorIdentityProbePhase.LOOKING_UP &&
        torIdentityProbeTimeoutJob?.isActive != true
    ) {
        torIdentityProbeTimeoutJob =
            viewModelScope.launch {
                delay(HomeViewModel.TOR_IDENTITY_PROBE_TIMEOUT_MS)
                failTorIdentityProbe(generation)
            }
    }
    return generation
}

internal fun HomeViewModel.failTorIdentityProbe(generation: Long) {
    torIdentityProbeMutable.fail(generation)
    if (torIdentityProbeMutable.state.value.phase == TorIdentityProbePhase.FAILED) {
        torIdentityProbeTimeoutJob?.cancel()
        torIdentityProbeTimeoutJob = null
    }
}

private suspend fun HomeViewModel.refreshTorExitInBackground(): IpInfo? {
    return try {
        container.connectionController.refreshTorRouteIpInfo(IpInfoFetchMode.ENTRY_QUICK)
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        container.diagnosticsLogger.recordFailure(
            "ip",
            "background Tor exit refresh failed: ${error.javaClass.simpleName}",
        )
        null
    }
}

internal fun HomeViewModel.confirmTorIdentityProbe() {
    torIdentityProbeMutable.confirm()
    if (torIdentityProbeMutable.state.value.phase == TorIdentityProbePhase.CONFIRMED) {
        torIdentityProbeTimeoutJob?.cancel()
        torIdentityProbeTimeoutJob = null
    }
}

internal fun HomeViewModel.cancelTorIdentityProbe() {
    torIdentityProbeTimeoutJob?.cancel()
    torIdentityProbeTimeoutJob = null
    torIdentityProbeMutable.cancel()
}

internal suspend fun HomeViewModel.emitTorConnectedBanner(ipInfo: IpInfo) {
    val country =
        ipInfo.countryName
            ?: ipInfo.countryCode
            ?: getApplication<Application>().getString(R.string.unknown_country)
    emitSuccess(getApplication<Application>().getString(R.string.privacy_route_connected_banner, country))
}
