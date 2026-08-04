package com.foxhole.guard.ui
import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.viewModelScope
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.IpInfo
import com.foxhole.core.runtime.FoxholeVpnRuntimeBridge
import com.foxhole.core.runtime.network.IpInfoFetchMode
import com.foxhole.guard.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Dashboard IP-info refresh: the manual/silent/reasoned refresh entry points, the geo-enrichment
// pass, refresh invalidation, and the post-connect refresh scheduling chain.

internal fun HomeViewModel.refreshIpInfo() {
    val snapshot = container.connectionController.snapshot.value
    if (snapshot.shouldRefreshDashboardConnectionMetrics()) {
        scheduleActiveProfileLatencyRefresh(
            showLoading = true,
            refreshImmediately = true,
            clearSelectedMetrics = true,
        )
    }
    startIpInfoRefresh(
        reportFailures = snapshot.shouldReportManualDashboardIpRefreshFailures(),
        showLoading = true,
        clearExistingIp = false,
        fetchMode = ipInfoFetchModeForRefreshReason(IpInfoRefreshReason.MANUAL),
        minimumLoadingDurationMs = HomeViewModel.MANUAL_IP_REFRESH_MIN_LOADING_MS,
        reason = IpInfoRefreshReason.MANUAL,
        // One swipe renews BOTH identities: once the VPN lane publishes, chase the Tor exit
        // through the Tor route as well (no-op unless Tor rides over/alongside the VPN).
        onPublished = {
            schedulePostConnectTorRouteRefreshAfterIp(IpInfoRefreshReason.MANUAL)
        },
    )
}

internal fun HomeViewModel.refreshIpInfoSilently() {
    startIpInfoRefresh(
        reportFailures = false,
        showLoading = false,
        clearExistingIp = false,
        fetchMode = ipInfoFetchModeForRefreshReason(IpInfoRefreshReason.FOREGROUND),
        minimumLoadingDurationMs = 0L,
        reason = IpInfoRefreshReason.FOREGROUND,
    )
}

@Suppress("LongMethod", "CyclomaticComplexMethod", "TooGenericExceptionCaught")
internal fun HomeViewModel.startIpInfoRefresh(
    reportFailures: Boolean,
    showLoading: Boolean,
    clearExistingIp: Boolean,
    fetchMode: IpInfoFetchMode,
    minimumLoadingDurationMs: Long = 0L,
    reason: IpInfoRefreshReason = IpInfoRefreshReason.FOREGROUND,
    targetOverride: IpInfoRefreshTarget? = null,
    onPublished: (suspend (IpInfo) -> Unit)? = null,
) {
    // Offline geo mode: never run the FULL pass (which queries the online city/ISP geo providers) —
    // the quick trace probe plus the on-device IP→country database is all the dashboard shows.
    @Suppress("NAME_SHADOWING")
    val fetchMode =
        if (container.settingsRepository.settings.value.connection.geoOfflineMode &&
            fetchMode != IpInfoFetchMode.ENTRY_QUICK
        ) {
            IpInfoFetchMode.ENTRY_QUICK
        } else {
            fetchMode
        }
    val requestSnapshot = container.connectionController.snapshot.value
    val requestTarget = targetOverride ?: ipInfoRefreshTargetForSnapshot(requestSnapshot)
    val requestGeneration = ipInfoRefreshGenerationForSnapshot(requestSnapshot)
    val decision =
        ipRefreshCoordinator.request(
            target = requestTarget,
            reason = reason,
            generation = requestGeneration,
        )
    if (decision is IpRefreshDecision.Coalesced) {
        container.diagnosticsLogger.record(
            "ip",
            "dashboard refresh coalesced active=${decision.activeToken} active_reason=${decision.activeReason.name.lowercase()} reason=${reason.name.lowercase()} target=${decision.target.name.lowercase()} generation=${decision.generation}",
        )
        return
    }
    val startDecision = decision as IpRefreshDecision.Start
    val refreshToken = startDecision.token
    ipInfoRefreshToken = refreshToken
    if (startDecision.supersededActive) {
        ipInfoRefreshJob?.cancel()
        ipInfoRefreshJob = null
        activeIpInfoRefreshReason = null
        ipInfoRefreshReasonMutable.value = null
        ipInfoLoadingMutable.value = false
    }
    if (showLoading) {
        activeIpInfoRefreshReason = reason
        ipInfoRefreshReasonMutable.value = reason
        ipInfoLoadingMutable.value = true
    }
    ipInfoRefreshJob =
        viewModelScope.launch {
            val target = startDecision.target
            activeIpInfoRefreshReason = reason
            ipInfoRefreshReasonMutable.value = reason
            var publishedInfo = false
            container.diagnosticsLogger.record(
                "ip",
                "dashboard refresh started id=$refreshToken reason=${reason.name.lowercase()} mode=${fetchMode.name.lowercase()} target=${target.name.lowercase()} generation=${startDecision.generation} showLoading=$showLoading clearExistingIp=$clearExistingIp",
            )
            if (showLoading) {
                ipInfoLoadingMutable.value = true
            }
            val loadingStartedAtMs = if (showLoading) SystemClock.elapsedRealtime() else 0L
            if (shouldClearExistingIpForRefresh(reason = reason, clearExistingIp = clearExistingIp)) {
                FoxholeVpnRuntimeBridge.updateIpInfo(null)
            }
            try {
                val info = refreshIpInfoForReason(fetchMode = fetchMode, reason = reason)
                if (ipRefreshCoordinator.isCurrent(refreshToken)) {
                    val currentTarget = ipInfoRefreshTargetForSnapshot(container.connectionController.snapshot.value)
                    if (!shouldPublishDashboardIpRefresh(target, currentTarget, reason)) {
                        container.diagnosticsLogger.record(
                            "ip",
                            "dashboard refresh ignored stale target id=$refreshToken reason=${reason.name.lowercase()} started=${target.name.lowercase()} current=${currentTarget.name.lowercase()}",
                        )
                        return@launch
                    }
                    if (!canPublishGeoEnrichmentResult(fetchMode, target, info, refreshToken)) {
                        return@launch
                    }
                    if (
                        shouldPublishTorIpInfoForDashboardRefresh(target = target, reason = reason) ||
                        torRouteOwnsTunnelEgress(target)
                    ) {
                        publishTorIpInfoFromDashboardRefresh(info)
                    } else {
                        if (shouldPublishDeviceIpInfoFromDashboardRefresh(target)) {
                            FoxholeVpnRuntimeBridge.updateDeviceIpInfo(info)
                        }
                        FoxholeVpnRuntimeBridge.updateIpInfo(info)
                    }
                    publishedInfo = true
                    container.diagnosticsLogger.record(
                        "ip",
                        "geo refreshed id=$refreshToken reason=${reason.name.lowercase()} target=${target.name.lowercase()}",
                    )
                    onPublished?.invoke(info)
                    maybeScheduleIpInfoGeoEnrichment(
                        fetchMode = fetchMode,
                        publishedInfo = info,
                        publishedTarget = target,
                    )
                }
            } catch (cancelled: CancellationException) {
                container.diagnosticsLogger.record(
                    "ip",
                    "dashboard refresh cancelled id=$refreshToken reason=${reason.name.lowercase()}",
                )
                throw cancelled
            } catch (error: Throwable) {
                container.diagnosticsLogger.record(
                    "ip",
                    "${ipInfoRefreshFailureDiagnosticLabel(fetchMode, reportFailures)}: ${error.javaClass.simpleName}: ${error.message.orEmpty()}",
                )
                if (reportFailures) {
                    emitError(getApplication<Application>().getString(R.string.ip_info_failed))
                }
            } finally {
                if (showLoading && ipRefreshCoordinator.isCurrent(refreshToken)) {
                    val elapsedLoadingMs = SystemClock.elapsedRealtime() - loadingStartedAtMs
                    val remainingLoadingMs = minimumLoadingDurationMs - elapsedLoadingMs
                    if (remainingLoadingMs > 0L) {
                        runCatching { delay(remainingLoadingMs) }
                    }
                }
                if (showLoading && ipRefreshCoordinator.isCurrent(refreshToken)) {
                    ipInfoLoadingMutable.value = false
                }
                if (
                    reason == IpInfoRefreshReason.POST_CONNECT &&
                    !publishedInfo &&
                    ipRefreshCoordinator.isCurrent(refreshToken)
                ) {
                    container.diagnosticsLogger.record(
                        "latency",
                        "post-connect latency scheduled after ip refresh finished without publication",
                    )
                    schedulePostConnectLatencyRefreshAfterIp(reason)
                }
                container.diagnosticsLogger.record(
                    "ip",
                    "dashboard refresh finished id=$refreshToken reason=${reason.name.lowercase()} loading=${ipInfoLoadingMutable.value}",
                )
                if (ipRefreshCoordinator.isCurrent(refreshToken)) {
                    ipRefreshCoordinator.complete(refreshToken)
                    ipInfoRefreshJob = null
                    activeIpInfoRefreshReason = null
                    ipInfoRefreshReasonMutable.value = null
                }
            }
        }
}

/**
 * An enrichment pass only fills city/ISP for the address ALREADY on the dashboard — it must never
 * swap the shown identity. When its whoami answer names a different address (the network changed
 * mid-flight, or the request rode another egress), the result is discarded and a silent quick pass
 * re-establishes the current identity instead.
 */
private fun HomeViewModel.canPublishGeoEnrichmentResult(
    fetchMode: IpInfoFetchMode,
    target: IpInfoRefreshTarget,
    info: IpInfo,
    refreshToken: Long,
): Boolean {
    if (fetchMode != IpInfoFetchMode.GEO_ENRICHMENT) {
        return true
    }
    val displayedIp = currentDashboardIpInfoForTarget(target)?.let(::primaryVisibleIpOrNull) ?: return true
    val enrichedIp = primaryVisibleIpOrNull(info) ?: return false
    if (enrichedIp == displayedIp) {
        return true
    }
    container.diagnosticsLogger.record(
        "ip",
        "geo enrichment discarded id=$refreshToken: address changed displayed=$displayedIp enriched=$enrichedIp",
    )
    startIpInfoRefresh(
        reportFailures = false,
        showLoading = false,
        clearExistingIp = false,
        fetchMode = IpInfoFetchMode.ENTRY_QUICK,
        reason = IpInfoRefreshReason.POST_UPDATE,
        targetOverride = target,
    )
    return false
}

private fun HomeViewModel.maybeScheduleIpInfoGeoEnrichment(
    fetchMode: IpInfoFetchMode,
    publishedInfo: IpInfo,
    publishedTarget: IpInfoRefreshTarget,
) {
    if (fetchMode != IpInfoFetchMode.ENTRY_QUICK || publishedInfo.hasCompleteDashboardGeoDetails()) {
        return
    }
    // Offline geo mode shows IP + country only; city/ISP enrichment against the online geo
    // providers is never scheduled.
    if (container.settingsRepository.settings.value.connection.geoOfflineMode) {
        return
    }
    // Tor is country-only and the country resolves offline from the local geoip database, so a
    // Tor-bound online enrichment pass (slow, city-oriented) is never scheduled for the TOR target.
    if (publishedTarget == IpInfoRefreshTarget.TOR) {
        return
    }
    val publishedIp = primaryVisibleIp(publishedInfo)
    if (publishedIp == "-") {
        return
    }
    viewModelScope.launch {
        delay(ENTRY_QUICK_GEO_ENRICHMENT_DELAY_MS)
        // One shot is not enough right after connect: the tunnel is still settling and a failed
        // pass would leave the city/provider blank until a manual refresh. Retry with backoff
        // until the details are complete or the route/exit IP changes under us.
        for (pass in 0 until ENTRY_QUICK_GEO_ENRICHMENT_MAX_PASSES) {
            if (pass > 0) {
                delay(ENTRY_QUICK_GEO_ENRICHMENT_RETRY_DELAY_MS * pass)
            }
            if (!awaitIdleIpInfoRefreshForGeoEnrichment()) {
                continue
            }
            if (!runIpInfoGeoEnrichmentPass(publishedTarget, publishedIp, pass)) {
                return@launch
            }
        }
    }
}

private fun IpInfo.hasCompleteDashboardGeoDetails(): Boolean =
    hasDashboardLocationDetails() && hasDashboardProviderDetails()

// One enrichment attempt; false means the route/exit changed (or finished) and retries must stop.
private suspend fun HomeViewModel.runIpInfoGeoEnrichmentPass(
    publishedTarget: IpInfoRefreshTarget,
    publishedIp: String,
    pass: Int,
): Boolean {
    val currentSnapshot = container.connectionController.snapshot.value
    if (ipInfoRefreshTargetForSnapshot(currentSnapshot) != publishedTarget) {
        return false
    }
    val currentInfo = currentDashboardIpInfoForTarget(publishedTarget)
    val enrichmentStillNeeded =
        currentInfo != null &&
            !currentInfo.hasCompleteDashboardGeoDetails() &&
            primaryVisibleIp(currentInfo) == publishedIp
    if (!enrichmentStillNeeded) {
        return false
    }
    val showLoading = shouldShowIpInfoGeoEnrichmentRefreshLoading(currentInfo)
    container.diagnosticsLogger.record(
        "ip",
        "geo enrichment started after entry_quick target=${publishedTarget.name.lowercase()} loading=$showLoading pass=${pass + 1}",
    )
    startIpInfoRefresh(
        reportFailures = false,
        showLoading = showLoading,
        clearExistingIp = false,
        fetchMode = IpInfoFetchMode.GEO_ENRICHMENT,
        minimumLoadingDurationMs = if (showLoading) HomeViewModel.AUTO_IP_REFRESH_MIN_LOADING_MS else 0L,
        reason = IpInfoRefreshReason.POST_UPDATE,
        targetOverride = publishedTarget,
    )
    awaitCompletedIpInfoRefreshForGeoEnrichment()
    return true
}

private fun HomeViewModel.currentDashboardIpInfoForTarget(target: IpInfoRefreshTarget): IpInfo? =
    when (target) {
        IpInfoRefreshTarget.TOR -> torIpInfoMutable.value
        IpInfoRefreshTarget.VPN_BOUND,
        IpInfoRefreshTarget.UPSTREAM,
        IpInfoRefreshTarget.PROXY,
        IpInfoRefreshTarget.LOCAL_GUARD,
        -> container.connectionController.ipInfo.value
    }

private suspend fun HomeViewModel.awaitIdleIpInfoRefreshForGeoEnrichment(): Boolean {
    repeat(ENTRY_QUICK_GEO_ENRICHMENT_WAIT_ATTEMPTS) {
        if (ipInfoRefreshJob == null) {
            return true
        }
        delay(ENTRY_QUICK_GEO_ENRICHMENT_WAIT_INTERVAL_MS)
    }
    return ipInfoRefreshJob == null
}

// Waits out a running enrichment pass (they can take the full geo call timeout) so a retry pass
// evaluates the settled result instead of racing the in-flight refresh.
private suspend fun HomeViewModel.awaitCompletedIpInfoRefreshForGeoEnrichment() {
    repeat(ENTRY_QUICK_GEO_ENRICHMENT_COMPLETION_WAIT_ATTEMPTS) {
        if (ipInfoRefreshJob == null) {
            return
        }
        delay(ENTRY_QUICK_GEO_ENRICHMENT_COMPLETION_WAIT_INTERVAL_MS)
    }
}

private suspend fun HomeViewModel.refreshIpInfoForReason(
    fetchMode: IpInfoFetchMode,
    reason: IpInfoRefreshReason,
): com.foxhole.core.model.IpInfo {
    val attempts = ipInfoRefreshAttemptsForReason(reason)
    val retryDelayMs = ipInfoRefreshRetryDelayMsForReason(reason)
    var lastError: Throwable? = null
    repeat(attempts) { attemptIndex ->
        val infoResult =
            runCatching {
                if (reason == IpInfoRefreshReason.TOR_ROUTE) {
                    container.connectionController.refreshTorRouteIpInfo(fetchMode = fetchMode)
                } else {
                    container.connectionController.refreshIpInfo(fetchMode = fetchMode)
                }
            }
        val info = infoResult.getOrNull()
        infoResult.exceptionOrNull()?.let { error ->
            if (error is CancellationException) {
                throw error
            }
            lastError = error
        }
        if (info != null && (reason != IpInfoRefreshReason.TOR_ROUTE || canAcceptTorRouteIpRefresh(info))) {
            return info
        }
        if (reason == IpInfoRefreshReason.TOR_ROUTE && info != null) {
            lastError = IllegalStateException("tor route ip not ready")
        }
        if (attempts > 1) {
            container.diagnosticsLogger.record(
                "ip",
                "${reason.name.lowercase()} ip refresh attempt ${attemptIndex + 1}/$attempts not ready: ${lastError?.javaClass?.simpleName.orEmpty()}",
            )
        }
        if (attemptIndex < attempts - 1) {
            delay(retryDelayMs)
        }
    }
    lastError?.let { throw it }
    error("ip refresh failed")
}

internal fun ipInfoRefreshAttemptsForReason(reason: IpInfoRefreshReason): Int =
    when (reason) {
        IpInfoRefreshReason.POST_CONNECT,
        IpInfoRefreshReason.RESTORED_VPN,
        IpInfoRefreshReason.NETWORK_CHANGE,
        -> HomeViewModel.CONNECTED_IP_REFRESH_ATTEMPTS
        IpInfoRefreshReason.TOR_ROUTE -> HomeViewModel.TOR_IP_REFRESH_ATTEMPTS
        IpInfoRefreshReason.MANUAL,
        IpInfoRefreshReason.FOREGROUND,
        IpInfoRefreshReason.POST_UPDATE,
        -> 1
    }

internal fun ipInfoRefreshRetryDelayMsForReason(reason: IpInfoRefreshReason): Long =
    when (reason) {
        IpInfoRefreshReason.POST_CONNECT,
        IpInfoRefreshReason.RESTORED_VPN,
        IpInfoRefreshReason.NETWORK_CHANGE,
        -> HomeViewModel.CONNECTED_IP_REFRESH_RETRY_DELAY_MS
        IpInfoRefreshReason.TOR_ROUTE -> HomeViewModel.TOR_IP_REFRESH_RETRY_DELAY_MS
        IpInfoRefreshReason.MANUAL,
        IpInfoRefreshReason.FOREGROUND,
        IpInfoRefreshReason.POST_UPDATE,
        -> 0L
    }

internal fun ipInfoRefreshFailureDiagnosticLabel(
    fetchMode: IpInfoFetchMode,
    reportFailures: Boolean,
): String =
    if (!reportFailures && fetchMode == IpInfoFetchMode.GEO_ENRICHMENT) {
        "geo enrichment unavailable"
    } else {
        "geo refresh failed"
    }

internal fun HomeViewModel.invalidateIpInfoRefreshes(): Long {
    // A pending foreground refresh sits in its start delay before calling startIpInfoRefresh();
    // it must be cancelled here too, otherwise it can fetch and publish an IP after an explicit
    // invalidate and silently overwrite the current network state.
    foregroundRefreshJob?.cancel()
    foregroundRefreshJob = null
    pendingNetworkChangeRefreshJob?.cancel()
    pendingNetworkChangeRefreshJob = null
    connectedIpRefreshJob?.cancel()
    connectedIpRefreshJob = null
    ipInfoRefreshJob?.cancel()
    ipInfoRefreshJob = null
    activeIpInfoRefreshReason = null
    ipInfoRefreshReasonMutable.value = null
    ipInfoLoadingMutable.value = false
    ipInfoRefreshToken = ipRefreshCoordinator.cancelAll()
    return ipInfoRefreshToken
}

internal fun HomeViewModel.scheduleConnectedIpRefresh(
    reason: IpInfoRefreshReason = IpInfoRefreshReason.POST_CONNECT,
    clearExistingIp: Boolean = false,
    showLoading: Boolean = false,
    minimumLoadingDurationMs: Long = 0L,
) {
    connectedIpRefreshJob?.cancel()
    connectedIpRefreshJob =
        viewModelScope.launch {
            connectedIpRefreshStartDelayMs(reason).takeIf { it > 0L }?.let { delay(it) }
            if (container.connectionController.snapshot.value.state != ConnectionState.CONNECTED) {
                return@launch
            }
            if (ipInfoRefreshJob != null) {
                container.diagnosticsLogger.record(
                    "ip",
                    "connected refresh requested while active refresh is running reason=${activeIpInfoRefreshReason?.name?.lowercase().orEmpty()}",
                )
            }
            pendingPostConnectIpRefresh = false
            val snapshot = container.connectionController.snapshot.value
            val currentIpInfo = container.connectionController.ipInfo.value
            val fetchMode = ipInfoFetchModeForRefreshReason(reason)
            val effectiveShowLoading =
                showLoading ||
                    shouldShowDashboardIpRefreshLoading(
                        reason = reason,
                        snapshot = snapshot,
                        currentIpInfo = currentIpInfo,
                    )
            val effectiveMinimumLoadingDurationMs =
                if (effectiveShowLoading && minimumLoadingDurationMs <= 0L) {
                    HomeViewModel.AUTO_IP_REFRESH_MIN_LOADING_MS
                } else {
                    minimumLoadingDurationMs
                }
            startIpInfoRefresh(
                reportFailures = false,
                showLoading = effectiveShowLoading,
                clearExistingIp = clearExistingIp,
                fetchMode = fetchMode,
                minimumLoadingDurationMs = effectiveMinimumLoadingDurationMs,
                reason = reason,
                onPublished = {
                    schedulePostConnectLatencyRefreshAfterIp(reason)
                    schedulePostConnectTorRouteRefreshAfterIp(reason)
                },
            )
        }
}

private fun HomeViewModel.schedulePostConnectTorRouteRefreshAfterIp(reason: IpInfoRefreshReason) {
    if (
        !shouldRefreshTorExitAfterConnect(
            reason = reason,
            snapshot = container.connectionController.snapshot.value,
            settings = controlUiState.value.settings,
        )
    ) {
        return
    }
    postConnectTorRouteRefreshJob?.cancel()
    postConnectTorRouteRefreshJob =
        viewModelScope.launch {
            delay(HomeViewModel.POST_CONNECT_TOR_ROUTE_DELAY_MS)
            postConnectTorRouteRefreshJob = null
            if (
                shouldRefreshTorExitAfterConnect(
                    reason = reason,
                    snapshot = container.connectionController.snapshot.value,
                    settings = controlUiState.value.settings,
                )
            ) {
                scheduleConnectedIpRefresh(
                    reason = IpInfoRefreshReason.TOR_ROUTE,
                    clearExistingIp = false,
                )
            }
        }
}

internal fun connectedIpRefreshStartDelayMs(reason: IpInfoRefreshReason): Long =
    when (reason) {
        IpInfoRefreshReason.NETWORK_CHANGE -> 0L
        else -> HomeViewModel.CONNECTED_IP_REFRESH_DELAY_MS
    }

private fun HomeViewModel.schedulePostConnectLatencyRefreshAfterIp(reason: IpInfoRefreshReason) {
    if (reason != IpInfoRefreshReason.POST_CONNECT) {
        return
    }
    postConnectLatencyRefreshJob?.cancel()
    postConnectLatencyRefreshJob =
        viewModelScope.launch {
            delay(HomeViewModel.POST_CONNECT_LATENCY_AFTER_IP_DELAY_MS)
            postConnectLatencyRefreshJob = null
            if (
                container.connectionController.snapshot.value.state == ConnectionState.CONNECTED &&
                !autoConnectUiStateMutable.value.running &&
                container.connectionController.snapshot.value.shouldRefreshDashboardConnectionMetrics()
            ) {
                scheduleActiveProfileLatencyRefresh(showLoading = false, refreshImmediately = true)
            }
        }
}

private const val ENTRY_QUICK_GEO_ENRICHMENT_DELAY_MS = 120L
private const val ENTRY_QUICK_GEO_ENRICHMENT_WAIT_ATTEMPTS = 8
private const val ENTRY_QUICK_GEO_ENRICHMENT_WAIT_INTERVAL_MS = 80L
private const val ENTRY_QUICK_GEO_ENRICHMENT_MAX_PASSES = 3
private const val ENTRY_QUICK_GEO_ENRICHMENT_RETRY_DELAY_MS = 2_500L
private const val ENTRY_QUICK_GEO_ENRICHMENT_COMPLETION_WAIT_ATTEMPTS = 60
private const val ENTRY_QUICK_GEO_ENRICHMENT_COMPLETION_WAIT_INTERVAL_MS = 250L
