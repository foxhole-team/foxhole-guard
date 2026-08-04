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
    // Claim the operation (checked against the source-of-truth mutable, not the derived UI state,
    // which lags a frame) and clear it BEFORE the suspending banner emit: the bridge/timeout tick
    // and the TOR_ROUTE dashboard refresh race to finish the same operation, and the loser must
    // see it already cleared — otherwise the user gets two "Tor connected" banners.
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
    container.settingsRepository.updatePrivacyRouteMode(PrivacyRouteMode.OFF)
    if (snapshot.profileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID) {
        container.connectionController.disconnect(suppressLocalGuard = false)
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
    // The operation may run out of its window with the runtime already carrying a validated Tor
    // route while only the display-side exit probe is slow — that is not a bootstrap failure, and
    // the red "Tor did not connect" banner on a working route confused users. Clear silently; the
    // background exit refresh keeps filling the Tor window in.
    val snapshot = container.connectionController.snapshot.value
    val torRouteCarried =
        snapshot.state == ConnectionState.CONNECTED &&
            (snapshot.profileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID || snapshot.torActive)
    if (!torRouteCarried) {
        emitError(getApplication<Application>().getString(R.string.privacy_route_bootstrap_timeout))
    }
    clearTorOperation()
}

// The Tor exit IP is display data (Tor window, map node, network-card TOR identity), not a
// liveness gate — the runtime validates Tor sessions on Tor's own bootstrap. The post-connect
// TOR_ROUTE refresh is a BOUNDED retry round, and a fresh circuit can start answering the exit
// probe long after that round exhausts; giving up forever left the dashboard stuck without a Tor
// exit for the whole session. This supervisor keeps re-probing quietly (exponential backoff capped
// below) for as long as an engaged CONNECTED Tor route has no accepted exit yet.
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
                // Stay out of a foreground TOR_ROUTE round's way instead of doubling its probes.
                if (ipInfoRefreshJob == null && postConnectTorRouteRefreshJob == null) {
                    val info =
                        runCatching {
                            container.connectionController.refreshTorRouteIpInfo(IpInfoFetchMode.ENTRY_QUICK)
                        }.getOrNull()
                    if (info != null && publishTorRouteExit(info)) {
                        break
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

internal suspend fun HomeViewModel.emitTorConnectedBanner(ipInfo: IpInfo) {
    // Tor is country-only: exit geo comes from the offline geoip database, no city lookup.
    val country =
        ipInfo.countryName
            ?: ipInfo.countryCode
            ?: getApplication<Application>().getString(R.string.unknown_country)
    emitSuccess(getApplication<Application>().getString(R.string.privacy_route_connected_banner, country))
}
