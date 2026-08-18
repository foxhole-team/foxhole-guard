package com.foxhole.guard.ui

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.viewModelScope
import com.foxhole.core.model.ACTIVE_CONNECTION_STATES
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.IpInfo
import com.foxhole.core.runtime.FoxholeVpnRuntimeBridge
import com.foxhole.core.runtime.TorProbeProxyFailure
import com.foxhole.core.runtime.TorProbeProxyUnavailableException
import com.foxhole.core.runtime.network.IpInfoFetchMode
import com.foxhole.guard.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal fun HomeViewModel.clearNetworkHandoverIpIdentity(clearDashboardIdentity: Boolean) {
    FoxholeVpnRuntimeBridge.updateDeviceIpInfo(null)
    if (clearDashboardIdentity) {
        FoxholeVpnRuntimeBridge.updateIpInfo(null)
    }
}

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
    val torIdentityProbeGeneration =
        if (reason == IpInfoRefreshReason.TOR_ROUTE) {
            beginTorIdentityProbe(retryAfterFailure = true)
        } else {
            null
        }
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
                torIdentityProbeGeneration?.let(::failTorIdentityProbe)
                container.diagnosticsLogger.recordFailure(
                    "ip",
                    "${ipInfoRefreshFailureDiagnosticLabel(fetchMode, reportFailures)}: ${error.javaClass.simpleName}: ${error.message.orEmpty()}",
                )
                if (shouldReportTorProbeStartupFailure(reason, error)) {
                    emitError(getApplication<Application>().getString(R.string.tor_ip_probe_start_failed))
                } else if (reportFailures) {
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
                    shouldScheduleTorRouteAfterPrimaryRefresh(reason, publishedInfo) &&
                    ipRefreshCoordinator.isCurrent(refreshToken)
                ) {
                    schedulePostConnectTorRouteRefreshAfterIp(reason)
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

internal fun shouldScheduleTorRouteAfterPrimaryRefresh(
    reason: IpInfoRefreshReason,
    publishedInfo: Boolean,
): Boolean = !publishedInfo && reason != IpInfoRefreshReason.TOR_ROUTE

/** Only a hard private-inbound refusal is actionable; NOT_READY is normal during Tor bootstrap. */
internal fun shouldReportTorProbeStartupFailure(
    reason: IpInfoRefreshReason,
    error: Throwable,
): Boolean {
    if (reason != IpInfoRefreshReason.TOR_ROUTE) return false
    val failure = (error as? TorProbeProxyUnavailableException)?.failure ?: return false
    return failure == TorProbeProxyFailure.START_REFUSED ||
        failure == TorProbeProxyFailure.INVALID_LOOPBACK_ADDRESS
}

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
    if (container.settingsRepository.settings.value.connection.geoOfflineMode) {
        return
    }
    if (publishedTarget == IpInfoRefreshTarget.TOR) {
        return
    }
    val publishedIp = primaryVisibleIp(publishedInfo)
    if (publishedIp == "-") {
        return
    }
    viewModelScope.launch {
        delay(ENTRY_QUICK_GEO_ENRICHMENT_DELAY_MS)
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
    val latencyRefreshGeneration = scheduleConnectedDashboardLatencyRefresh(reason)
    connectedIpRefreshJob?.cancel()
    connectedIpRefreshJob =
        viewModelScope.launch {
            connectedIpRefreshStartDelayMs(reason).takeIf { it > 0L }?.let { delay(it) }
            if (container.connectionController.snapshot.value.state != ConnectionState.CONNECTED) {
                return@launch
            }
            if (
                shouldRefreshDeviceIdentityForConnectedRoute(
                    reason = reason,
                )
            ) {
                val expectedRevision = container.connectionController.snapshot.value.upstreamNetworkRevision
                launch {
                    refreshDeviceIdentityForConnectedRoute(expectedRevision)
                }
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
                    accelerateConnectedDashboardLatencyRefresh(latencyRefreshGeneration)
                    schedulePostConnectTorRouteRefreshAfterIp(reason)
                },
            )
        }
}

private suspend fun HomeViewModel.refreshDeviceIdentityForConnectedRoute(expectedRevision: Long) {
    runCatching {
        container.connectionController.refreshDeviceIpInfo(fetchMode = IpInfoFetchMode.ENTRY_QUICK)
    }.onSuccess { info ->
        val current = container.connectionController.snapshot.value
        if (shouldPublishNetworkChangeDeviceIdentity(expectedRevision, current)) {
            FoxholeVpnRuntimeBridge.updateDeviceIpInfo(info)
            container.diagnosticsLogger.record(
                "ip",
                "connected physical identity refreshed revision=$expectedRevision",
            )
        }
    }.onFailure { error ->
        if (error is CancellationException) {
            throw error
        }
        container.diagnosticsLogger.recordFailure(
            "ip",
            "physical identity refresh failed: ${error.javaClass.simpleName}",
        )
    }
}

internal fun shouldRefreshDeviceIdentityForConnectedRoute(
    reason: IpInfoRefreshReason,
): Boolean = when (reason) {
    IpInfoRefreshReason.NETWORK_CHANGE,
    IpInfoRefreshReason.POST_CONNECT,
    IpInfoRefreshReason.RESTORED_VPN,
    -> true
    else -> false
}

internal fun shouldPublishNetworkChangeDeviceIdentity(
    expectedRevision: Long,
    current: ConnectionSnapshot,
): Boolean =
    current.state in ACTIVE_CONNECTION_STATES &&
        current.upstreamNetworkRevision == expectedRevision

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

internal fun shouldScheduleConnectedDashboardLatencyRefresh(reason: IpInfoRefreshReason): Boolean =
    reason in setOf(
        IpInfoRefreshReason.POST_CONNECT,
        IpInfoRefreshReason.RESTORED_VPN,
        IpInfoRefreshReason.NETWORK_CHANGE,
    )

private fun HomeViewModel.scheduleConnectedDashboardLatencyRefresh(reason: IpInfoRefreshReason): Long? {
    val snapshot = container.connectionController.snapshot.value
    if (
        !shouldScheduleConnectedDashboardLatencyRefresh(reason) ||
        !snapshot.shouldRefreshDashboardConnectionMetrics() ||
        autoConnectUiStateMutable.value.running
    ) {
        return null
    }
    postConnectLatencyRefreshJob?.cancel()
    postConnectLatencyRefreshJob = null
    postConnectLatencyRefreshGeneration = null
    profileLatencyRefreshJob?.cancel()
    profileLatencyRefreshJob = null
    profileLatencyRefreshGeneration = null
    val refreshGeneration = beginDashboardLatencyRefresh()
    val handoffJob =
        viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                delay(HomeViewModel.POST_CONNECT_LATENCY_AFTER_IP_DELAY_MS)
                if (
                    isCurrentDashboardLatencyRefresh(refreshGeneration) &&
                    !autoConnectUiStateMutable.value.running
                ) {
                    scheduleActiveProfileLatencyRefresh(
                        showLoading = true,
                        refreshImmediately = true,
                        requestedGeneration = refreshGeneration,
                    )
                }
            } finally {
                if (postConnectLatencyRefreshJob === coroutineContext[Job]) {
                    postConnectLatencyRefreshJob = null
                    postConnectLatencyRefreshGeneration = null
                }
            }
        }
    postConnectLatencyRefreshJob = handoffJob
    postConnectLatencyRefreshGeneration = refreshGeneration
    handoffJob.start()
    return refreshGeneration
}

private fun HomeViewModel.accelerateConnectedDashboardLatencyRefresh(refreshGeneration: Long?) {
    if (refreshGeneration == null || !isCurrentDashboardLatencyRefresh(refreshGeneration)) {
        return
    }
    if (
        profileLatencyRefreshGeneration == refreshGeneration &&
        profileLatencyRefreshJob?.isActive == true
    ) {
        return
    }
    val started =
        scheduleActiveProfileLatencyRefresh(
            showLoading = true,
            refreshImmediately = true,
            requestedGeneration = refreshGeneration,
        )
    if (
        started &&
        postConnectLatencyRefreshGeneration == refreshGeneration
    ) {
        postConnectLatencyRefreshJob?.cancel()
        postConnectLatencyRefreshJob = null
        postConnectLatencyRefreshGeneration = null
    }
}

private const val ENTRY_QUICK_GEO_ENRICHMENT_DELAY_MS = 120L
private const val ENTRY_QUICK_GEO_ENRICHMENT_WAIT_ATTEMPTS = 8
private const val ENTRY_QUICK_GEO_ENRICHMENT_WAIT_INTERVAL_MS = 80L
private const val ENTRY_QUICK_GEO_ENRICHMENT_MAX_PASSES = 3
private const val ENTRY_QUICK_GEO_ENRICHMENT_RETRY_DELAY_MS = 2_500L
private const val ENTRY_QUICK_GEO_ENRICHMENT_COMPLETION_WAIT_ATTEMPTS = 60
private const val ENTRY_QUICK_GEO_ENRICHMENT_COMPLETION_WAIT_INTERVAL_MS = 250L
