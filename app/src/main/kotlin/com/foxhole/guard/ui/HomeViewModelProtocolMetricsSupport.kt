package com.foxhole.guard.ui

import android.os.SystemClock
import androidx.lifecycle.viewModelScope
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.LatencyProbeMethod
import com.foxhole.core.model.Profile
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.ProtocolMetricEventKind
import com.foxhole.core.model.SMART_START_REFRESH_TIMEOUT_DEFAULT_SECONDS
import com.foxhole.core.model.SMART_START_REFRESH_TIMEOUT_MIN_SECONDS
import com.foxhole.core.model.SMART_START_TIMEOUT_MAX_SECONDS
import com.foxhole.core.model.TrafficSnapshot
import com.foxhole.core.model.isUdpTransport
import com.foxhole.core.profile.AutoConnectProbeCandidate
import com.foxhole.core.runtime.shouldPublishRuntimeProxyIpInfoToDashboard
import com.foxhole.guard.core.settings.networkMemory
import com.foxhole.guard.core.settings.recordSmartProfileProbeResult
import com.foxhole.guard.core.settings.recordSmartProfileServerPing
import com.foxhole.guard.core.settings.smartProfilePreference
import com.foxhole.guard.runtime.FoxholeVpnService
import com.foxhole.guard.runtime.runCatchingUnlessCancelled
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal fun HomeViewModel.cacheProtocolLatency(
    profileId: Long,
    optionId: String,
    latencyMs: Long,
) {
    val key = ProfileOptionLatencyKey(profileId, optionId)
    profileOptionDownMutable.value =
        profileOptionDownMutable.value - key
    profileOptionLatenciesMutable.value =
        profileOptionLatenciesMutable.value + (key to latencyMs.coerceAtLeast(1L))
    profileOptionLatencyUnavailableMutable.value =
        profileOptionLatencyUnavailableMutable.value - key
    markProtocolMetricsUpdated(profileId, optionId)
}

internal fun HomeViewModel.markProtocolLatencyUnavailable(
    profileId: Long,
    optionId: String,
) {
    val key = ProfileOptionLatencyKey(profileId, optionId)
    profileOptionDownMutable.value =
        profileOptionDownMutable.value - key
    profileOptionLatenciesMutable.value =
        profileOptionLatenciesMutable.value - key
    profileOptionLatencyUnavailableMutable.value =
        profileOptionLatencyUnavailableMutable.value + key
    markProtocolMetricsUpdated(profileId, optionId)
}

internal fun HomeViewModel.markProtocolDown(
    profileId: Long,
    optionId: String,
) {
    val key = ProfileOptionLatencyKey(profileId, optionId)
    profileOptionLatenciesMutable.value =
        profileOptionLatenciesMutable.value - key
    profileOptionLatencyUnavailableMutable.value =
        profileOptionLatencyUnavailableMutable.value - key
    profileOptionDownMutable.value =
        profileOptionDownMutable.value + key
    markProtocolMetricsUpdated(profileId, optionId)
}

internal fun HomeViewModel.cacheProtocolServerPingInternal(
    profileId: Long,
    optionId: String,
    pingMs: Long,
) {
    val key = ProfileOptionLatencyKey(profileId, optionId)
    profileOptionServerPingsMutable.value =
        profileOptionServerPingsMutable.value +
        (key to ProfileOptionServerPingState(pingMs = pingMs.coerceAtLeast(1L)))
    markProtocolMetricsUpdated(profileId, optionId)
}

internal fun HomeViewModel.cacheProtocolTunnelPingInternal(
    profileId: Long,
    optionId: String,
    pingMs: Long,
) {
    val key = ProfileOptionLatencyKey(profileId, optionId)
    profileOptionTunnelPingsMutable.value =
        profileOptionTunnelPingsMutable.value +
        (key to ProfileOptionTunnelPingState(pingMs = pingMs.coerceAtLeast(1L)))
    markProtocolMetricsUpdated(profileId, optionId)
}

internal fun HomeViewModel.markProtocolServerPingUnavailableInternal(
    profileId: Long,
    optionId: String,
) {
    val key = ProfileOptionLatencyKey(profileId, optionId)
    profileOptionServerPingsMutable.value =
        profileOptionServerPingsMutable.value +
        (key to ProfileOptionServerPingState(unavailable = true))
    markProtocolMetricsUpdated(profileId, optionId)
}

internal fun HomeViewModel.markProtocolTunnelPingUnavailableInternal(
    profileId: Long,
    optionId: String,
) {
    val key = ProfileOptionLatencyKey(profileId, optionId)
    profileOptionTunnelPingsMutable.value =
        profileOptionTunnelPingsMutable.value +
        (key to ProfileOptionTunnelPingState(unavailable = true))
    markProtocolMetricsUpdated(profileId, optionId)
}

private fun HomeViewModel.markProtocolMetricsUpdated(
    profileId: Long,
    optionId: String,
    updatedAt: Long = System.currentTimeMillis(),
) {
    profileOptionMetricsUpdatedAtMutable.value =
        profileOptionMetricsUpdatedAtMutable.value + (ProfileOptionLatencyKey(profileId, optionId) to updatedAt)
}

internal fun HomeViewModel.clearProtocolLatencyState(
    profileId: Long? = null,
    optionId: String? = null,
) {
    val clearAll = profileId == null && optionId == null

    fun matches(key: ProfileOptionLatencyKey): Boolean =
        (profileId == null || key.profileId == profileId) &&
            (optionId == null || key.optionId == optionId)

    fun <V> Map<ProfileOptionLatencyKey, V>.pruned(): Map<ProfileOptionLatencyKey, V> =
        if (clearAll) emptyMap() else filterKeys { key -> !matches(key) }

    fun Set<ProfileOptionLatencyKey>.pruned(): Set<ProfileOptionLatencyKey> =
        if (clearAll) emptySet() else filterNot(::matches).toSet()

    profileOptionLatenciesMutable.value = profileOptionLatenciesMutable.value.pruned()
    profileOptionLatencyUnavailableMutable.value = profileOptionLatencyUnavailableMutable.value.pruned()
    profileOptionDownMutable.value = profileOptionDownMutable.value.pruned()
    profileOptionServerPingsMutable.value = profileOptionServerPingsMutable.value.pruned()
    profileOptionTunnelPingsMutable.value = profileOptionTunnelPingsMutable.value.pruned()
    profileOptionMetricsUpdatedAtMutable.value = profileOptionMetricsUpdatedAtMutable.value.pruned()
}

@Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount")
internal fun HomeViewModel.scheduleActiveProfileLatencyRefresh(
    showLoading: Boolean = true,
    refreshImmediately: Boolean = false,
    clearSelectedMetrics: Boolean = false,
    requestedGeneration: Long? = null,
): Boolean {
    val refreshGeneration =
        requestedGeneration ?: run {
            postConnectLatencyRefreshJob?.cancel()
            postConnectLatencyRefreshJob = null
            postConnectLatencyRefreshGeneration = null
            profileLatencyRefreshJob?.cancel()
            profileLatencyRefreshJob = null
            profileLatencyRefreshGeneration = null
            beginDashboardLatencyRefresh()
        }
    if (!isCurrentDashboardLatencyRefresh(refreshGeneration)) {
        return false
    }
    if (!connectionMetricsUiVisible) {
        profileLatencyRefreshJob?.cancel()
        profileLatencyRefreshJob = null
        profileLatencyRefreshGeneration = null
        releaseDashboardConnectionMetricsLoading(refreshGeneration)
        return false
    }
    val connection = container.connectionController.snapshot.value
    if (connection.state != ConnectionState.CONNECTED || !connection.shouldRefreshDashboardConnectionMetrics()) {
        profileLatencyRefreshJob?.cancel()
        profileLatencyRefreshJob = null
        profileLatencyRefreshGeneration = null
        releaseDashboardConnectionMetricsLoading(refreshGeneration)
        return false
    }
    val activeProfile =
        controlUiState.value.activeProfile ?: run {
            releaseDashboardConnectionMetricsLoading(refreshGeneration)
            return false
        }
    val selectedOptionId =
        resolveDashboardLatencyOptionId(
            activeProfile = activeProfile,
            connection = connection,
        ) ?: run {
            releaseDashboardConnectionMetricsLoading(refreshGeneration)
            return false
        }
    val skipRecurringLatencyRefreshes = shouldSkipSpeedTestsOnCurrentNetwork()
    if (skipRecurringLatencyRefreshes) {
        container.diagnosticsLogger.record(
            "latency",
            "dashboard recurring latency refresh limited: cellular or metered network",
        )
    }
    val selectedProtocolHint =
        activeProfile.protocolOptions
            .firstOrNull { option -> option.id == selectedOptionId }
            ?.protocolHint
    if (
        activeDashboardLatencyTarget(
            activeProfileId = activeProfile.id,
            selectedOptionId = selectedOptionId,
            fallbackProtocolHint = selectedProtocolHint,
        ) == null
    ) {
        releaseDashboardConnectionMetricsLoading(refreshGeneration)
        return false
    }
    profileLatencyRefreshJob?.cancel()
    profileLatencyRefreshJob = null
    profileLatencyRefreshGeneration = null
    if (showLoading && !acquireDashboardConnectionMetricsLoading(refreshGeneration)) {
        return false
    }
    if (clearSelectedMetrics) {
        clearActiveProfileConnectionMetrics(
            profileId = activeProfile.id,
            optionId = selectedOptionId,
        )
    }
    val refreshJob =
        viewModelScope.launch(start = CoroutineStart.LAZY) {
            var waitingForInitialSample = showLoading
            var lastMemoryPersistAtMs = 0L
            try {
                var nextDelayMs =
                    if (refreshImmediately) {
                        0L
                    } else {
                        HomeViewModel.CONNECTED_LATENCY_FIRST_DELAY_MS
                    }
                while (true) {
                    if (nextDelayMs > 0L) {
                        delay(nextDelayMs)
                    }
                    if (!connectionMetricsUiVisible) {
                        return@launch
                    }
                    if (!isCurrentDashboardLatencyRefresh(refreshGeneration)) {
                        return@launch
                    }
                    nextDelayMs = HomeViewModel.CONNECTED_LATENCY_REFRESH_INTERVAL_MS
                    val refreshTarget =
                        activeDashboardLatencyTarget(
                            activeProfileId = activeProfile.id,
                            selectedOptionId = selectedOptionId,
                            fallbackProtocolHint = selectedProtocolHint,
                        ) ?: return@launch
                    val (latencyResult, serverPingResult) =
                        coroutineScope {
                            val latencyDeferred = async { measureConnectedDashboardPublicLatency() }
                            val serverPingDeferred =
                                async {
                                    measureConnectedServerTcpPing(
                                        activeProfileId = activeProfile.id,
                                        selectedOptionId = selectedOptionId,
                                    )
                                }
                            latencyDeferred.await() to serverPingDeferred.await()
                        }
                    if (!isCurrentDashboardLatencyRefresh(refreshGeneration)) {
                        return@launch
                    }
                    cacheDashboardPublicPing(
                        refreshTarget = refreshTarget,
                        activeProfileId = activeProfile.id,
                        selectedOptionId = selectedOptionId,
                        latencyResult = latencyResult,
                    )
                    cacheConnectedServerTcpPing(
                        activeProfileId = activeProfile.id,
                        selectedOptionId = selectedOptionId,
                        serverPingResult = serverPingResult,
                    )
                    waitingForInitialSample =
                        clearDashboardMetricsLoadingAfterInitialSample(
                            waitingForInitialSample = waitingForInitialSample,
                            refreshGeneration = refreshGeneration,
                        )
                    val nowMs = System.currentTimeMillis()
                    if (nowMs - lastMemoryPersistAtMs >= HomeViewModel.CONNECTED_METRICS_MEMORY_PERSIST_INTERVAL_MS) {
                        val persisted =
                            persistConnectedProtocolMetricsToSmartMemory(
                                profileId = activeProfile.id,
                                optionId = selectedOptionId,
                                latencyResult = latencyResult,
                                serverPingResult = serverPingResult,
                            )
                        if (persisted) {
                            lastMemoryPersistAtMs = nowMs
                        }
                    }
                    val measuredLatency = latencyResult.getOrNull()
                    if (measuredLatency == null || !shouldUseConnectedDashboardLatency(measuredLatency)) {
                        val error = latencyResult.exceptionOrNull()
                        val unavailableReason =
                            if (measuredLatency != null) {
                                "latency=${measuredLatency}ms over dashboard display limit"
                            } else {
                                error?.message.orEmpty()
                            }
                        container.diagnosticsLogger.record(
                            "latency",
                            "dashboard latency unavailable: $unavailableReason",
                        )
                    }
                    if (!shouldContinueDashboardLatencyRefreshAfterInitialSample(skipRecurringLatencyRefreshes)) {
                        container.diagnosticsLogger.record(
                            "latency",
                            "dashboard recurring latency refresh skipped after initial sample: cellular or metered network",
                        )
                        return@launch
                    }
                }
            } finally {
                clearDashboardMetricsLoadingAfterInitialSample(
                    waitingForInitialSample = waitingForInitialSample,
                    refreshGeneration = refreshGeneration,
                )
                if (profileLatencyRefreshJob === coroutineContext[Job]) {
                    profileLatencyRefreshJob = null
                    profileLatencyRefreshGeneration = null
                }
            }
        }
    profileLatencyRefreshJob = refreshJob
    profileLatencyRefreshGeneration = refreshGeneration
    refreshJob.start()
    return true
}

internal fun shouldContinueDashboardLatencyRefreshAfterInitialSample(skipSpeedTestsOnCurrentNetwork: Boolean): Boolean =
    !skipSpeedTestsOnCurrentNetwork

private suspend fun HomeViewModel.measureConnectedDashboardPublicLatency(): Result<Long> =
    runCatchingUnlessCancelled {
        withTimeoutOrNull(HomeViewModel.CONNECTED_LATENCY_TOTAL_TIMEOUT_MS) {
            container.connectionController.measureCurrentConnectionLatency(
                timeoutMs = HomeViewModel.CONNECTED_DASHBOARD_PING_TIMEOUT_MS,
            )
        } ?: error("dashboard public ping timed out")
    }

private suspend fun HomeViewModel.measureConnectedServerTcpPing(
    activeProfileId: Long,
    selectedOptionId: String,
): Result<Long> =
    runCatchingUnlessCancelled {
        withTimeoutOrNull(HomeViewModel.CONNECTED_SERVER_PING_TOTAL_TIMEOUT_MS) {
            container.connectionController.measureCurrentVpnServerPing(
                profileId = activeProfileId,
                protocolOptionId = selectedOptionId,
                timeoutMs = HomeViewModel.CONNECTED_SERVER_PING_TIMEOUT_MS,
            )
        } ?: error("server tcp ping timed out")
    }

private fun HomeViewModel.connectedTunnelProbeMeasuresVpn(): Boolean {
    val snapshot = container.connectionController.snapshot.value
    return !snapshot.torActive ||
        controlUiState.value.settings.shouldPublishRuntimeProxyIpInfoToDashboard(snapshot)
}

private suspend fun HomeViewModel.persistConnectedProtocolMetricsToSmartMemory(
    profileId: Long,
    optionId: String,
    latencyResult: Result<Long>,
    serverPingResult: Result<Long>,
): Boolean {
    val latencyMs =
        latencyResult
            .getOrNull()
            ?.takeIf { connectedTunnelProbeMeasuresVpn() && shouldUseConnectedDashboardLatency(it) }
    val serverPingMs = serverPingResult.getOrNull()
    if (latencyMs == null && serverPingMs == null) {
        return false
    }
    val networkFingerprintKey = currentNetworkFingerprintForSmartRules()?.key
    if (latencyMs != null) {
        container.settingsRepository.recordSmartProfileProbeResult(
            profileId = profileId,
            optionId = optionId,
            latencyMs = latencyMs,
            success = true,
            networkFingerprint = networkFingerprintKey,
            countTowardOutcomeHistory = false,
        )
        recordProtocolMetricEventInternal(
            profileId = profileId,
            optionId = optionId,
            kind = ProtocolMetricEventKind.PROBE_SUCCESS,
            latencyMs = latencyMs,
        )
    }
    if (serverPingMs != null) {
        container.settingsRepository.recordSmartProfileServerPing(
            profileId = profileId,
            optionId = optionId,
            serverPingMs = serverPingMs,
            networkFingerprint = networkFingerprintKey,
        )
        recordProtocolMetricEventInternal(
            profileId = profileId,
            optionId = optionId,
            kind = ProtocolMetricEventKind.SERVER_PING,
            latencyMs = serverPingMs,
        )
    }
    return true
}

private fun HomeViewModel.cacheDashboardPublicPing(
    refreshTarget: ActiveDashboardLatencyTarget,
    activeProfileId: Long,
    selectedOptionId: String,
    latencyResult: Result<Long>,
) {
    val pingMs = latencyResult.getOrNull()
    if (pingMs != null && shouldUseConnectedDashboardLatency(pingMs)) {
        cacheProtocolTunnelPingInternal(
            profileId = activeProfileId,
            optionId = selectedOptionId,
            pingMs = pingMs,
        )
        if (connectedTunnelProbeMeasuresVpn()) {
            cacheProtocolLatency(
                profileId = activeProfileId,
                optionId = selectedOptionId,
                latencyMs = pingMs,
            )
        }
        container.diagnosticsLogger.record(
            "latency",
            "dashboard public ping refreshed option=${refreshTarget.optionId.orEmpty()} tunnel_public_latency_ms=$pingMs",
        )
        return
    }
    markProtocolTunnelPingUnavailableInternal(
        profileId = activeProfileId,
        optionId = selectedOptionId,
    )
    val unavailableReason =
        if (pingMs != null) {
            "latency=${pingMs}ms over dashboard display limit"
        } else {
            latencyResult.exceptionOrNull()?.message.orEmpty()
        }
    container.diagnosticsLogger.record(
        "latency",
        "dashboard public ping unavailable: $unavailableReason",
    )
}

private fun HomeViewModel.cacheConnectedServerTcpPing(
    activeProfileId: Long,
    selectedOptionId: String,
    serverPingResult: Result<Long>,
) {
    val pingMs = serverPingResult.getOrNull()
    if (pingMs != null) {
        cacheProtocolServerPingInternal(
            profileId = activeProfileId,
            optionId = selectedOptionId,
            pingMs = pingMs,
        )
        container.diagnosticsLogger.record(
            "latency",
            "server tcp ping refreshed option=$selectedOptionId server_tcp_ping_ms=$pingMs",
        )
        return
    }
    markProtocolServerPingUnavailableInternal(
        profileId = activeProfileId,
        optionId = selectedOptionId,
    )
    container.diagnosticsLogger.record(
        "latency",
        "server tcp ping unavailable option=$selectedOptionId: ${serverPingResult.exceptionOrNull()?.message.orEmpty()}",
    )
}

private fun HomeViewModel.clearActiveProfileConnectionMetrics(
    profileId: Long,
    optionId: String,
) {
    val key = ProfileOptionLatencyKey(profileId, optionId)
    profileOptionLatenciesMutable.value = profileOptionLatenciesMutable.value - key
    profileOptionLatencyUnavailableMutable.value = profileOptionLatencyUnavailableMutable.value - key
    profileOptionDownMutable.value = profileOptionDownMutable.value - key
    profileOptionServerPingsMutable.value = profileOptionServerPingsMutable.value - key
    profileOptionTunnelPingsMutable.value = profileOptionTunnelPingsMutable.value - key
    markProtocolMetricsUpdated(profileId, optionId)
}

internal fun HomeViewModel.clearProfileLatencyRefresh() {
    postConnectLatencyRefreshJob?.cancel()
    postConnectLatencyRefreshJob = null
    postConnectLatencyRefreshGeneration = null
    profileLatencyRefreshJob?.cancel()
    profileLatencyRefreshJob = null
    profileLatencyRefreshGeneration = null
    invalidateDashboardLatencyRefresh()
}

private data class ActiveDashboardLatencyTarget(
    val profile: Profile,
    val optionId: String,
    val protocolHint: ProtocolHint?,
)

private fun HomeViewModel.activeDashboardLatencyTarget(
    activeProfileId: Long,
    selectedOptionId: String,
    fallbackProtocolHint: ProtocolHint?,
): ActiveDashboardLatencyTarget? {
    val snapshot = container.connectionController.snapshot.value
    val currentProfile = controlUiState.value.activeProfile
    val currentOptionId =
        currentProfile?.let { profile ->
            resolveDashboardLatencyOptionId(
                activeProfile = profile,
                connection = snapshot,
            )
        }
    val targetProfile = currentProfile?.takeIf { profile -> profile.id == activeProfileId }
    val connectionReady = snapshot.state == ConnectionState.CONNECTED && !autoConnectUiStateMutable.value.running
    return if (connectionReady && targetProfile != null && currentOptionId == selectedOptionId) {
        ActiveDashboardLatencyTarget(
            profile = targetProfile,
            optionId = selectedOptionId,
            protocolHint =
            targetProfile.protocolOptions
                .firstOrNull { option -> option.id == selectedOptionId }
                ?.protocolHint
                ?: fallbackProtocolHint,
        )
    } else {
        null
    }
}

private suspend fun HomeViewModel.clearDashboardMetricsLoadingAfterInitialSample(
    waitingForInitialSample: Boolean,
    refreshGeneration: Long,
): Boolean {
    if (waitingForInitialSample && protocolMetrics.isDashboardLoadingOwnedBy(refreshGeneration)) {
        val loadingStartedAt = dashboardConnectionMetricsLoadingStartedAtMs
        val remainingMs =
            if (loadingStartedAt > 0L) {
                HomeViewModel.DASHBOARD_CONNECTION_METRICS_MIN_LOADING_MS -
                    (SystemClock.elapsedRealtime() - loadingStartedAt)
            } else {
                0L
            }
        if (remainingMs > 0L) {
            withContext(NonCancellable) {
                delay(remainingMs)
            }
        }
        if (
            dashboardConnectionMetricsLoadingStartedAtMs == loadingStartedAt &&
            protocolMetrics.isDashboardLoadingOwnedBy(refreshGeneration)
        ) {
            releaseDashboardConnectionMetricsLoading(refreshGeneration)
        }
    }
    return false
}

internal fun HomeViewModel.rememberedAutoConnectLatency(
    profileId: Long,
    optionId: String,
    networkFingerprint: String?,
): Long? {
    val preference = controlUiState.value.settings.smartProfilePreference(profileId) ?: return null
    val scopedMemory =
        preference.networkMemory(networkFingerprint)?.protocolMemories?.firstOrNull { memory ->
            memory.optionId == optionId
        }
    if (scopedMemory?.lastLatencyMs != null) {
        return scopedMemory.lastLatencyMs
    }
    return preference.protocolMemories.firstOrNull { memory -> memory.optionId == optionId }?.lastLatencyMs
}

internal fun resolveAutoConnectFallbackRankingLatency(
    validatedConnectDurationMs: Long,
    rememberedLatencyMs: Long?,
    penaltyMs: Long,
    protocolHint: ProtocolHint,
    latencyProbeMethod: LatencyProbeMethod,
): Long {
    val transportPenaltyMs =
        if (protocolHint.isUdpTransport() || latencyProbeMethod == LatencyProbeMethod.ICMP) {
            0L
        } else {
            penaltyMs.coerceAtLeast(0L)
        }
    return ((rememberedLatencyMs ?: validatedConnectDurationMs).coerceAtLeast(1L) + transportPenaltyMs).coerceAtLeast(
        1L
    )
}

internal fun shouldRetryAutoConnectLatencyMeasurement(warmupLatencyMs: Long): Boolean =
    warmupLatencyMs >= HomeViewModel.AUTO_CONNECT_LATENCY_MEASUREMENT_RETRY_THRESHOLD_MS

internal fun shouldUseConnectedDashboardLatency(latencyMs: Long): Boolean =
    latencyMs in 1L..MAX_UI_LATENCY_MS

private const val MAX_UI_LATENCY_MS = 999L

internal fun remainingAutoConnectBudgetMs(
    startedAtElapsedMs: Long,
    nowElapsedMs: Long,
    totalTimeoutMs: Long,
): Long {
    require(totalTimeoutMs > 0L) { "totalTimeoutMs must be positive" }
    val elapsedMs = (nowElapsedMs - startedAtElapsedMs).coerceAtLeast(0L)
    return (totalTimeoutMs - elapsedMs).coerceAtLeast(0L)
}

internal fun shouldContinueAutoConnectAfterProbe(
    success: Boolean,
    timedOut: Boolean,
    remainingBudgetMs: Long,
): Boolean {
    if (success) {
        return false
    }
    if (!timedOut) {
        return true
    }
    return remainingBudgetMs > 0L
}

internal fun protocolMetricsCandidateProbeTimeoutMs(
    timeoutSeconds: Int = SMART_START_REFRESH_TIMEOUT_DEFAULT_SECONDS,
): Long =
    smartStartTimeoutMs(
        timeoutSeconds = timeoutSeconds,
        minSeconds = SMART_START_REFRESH_TIMEOUT_MIN_SECONDS,
    ).coerceAtLeast(minimumAutoConnectCandidateProbeBudgetMs())

internal fun minimumAutoConnectCandidateProbeBudgetMs(): Long =
    HomeViewModel.AUTO_CONNECT_CONNECTION_TIMEOUT_MS +
        HomeViewModel.AUTO_CONNECT_VALIDATION_GRACE_TIMEOUT_MS +
        HomeViewModel.AUTO_CONNECT_LATENCY_MEASUREMENT_SETTLE_MS +
        HomeViewModel.CONNECTED_LATENCY_TIMEOUT_MS * 2L +
        HomeViewModel.AUTO_CONNECT_LATENCY_MEASUREMENT_RETRY_DELAY_MS +
        1_000L

private fun smartStartTimeoutMs(
    timeoutSeconds: Int,
    minSeconds: Int,
): Long =
    timeoutSeconds
        .coerceIn(minSeconds, SMART_START_TIMEOUT_MAX_SECONDS)
        .toLong() * 1000L

internal fun canStartAutoConnect(candidates: List<AutoConnectProbeCandidate>): Boolean = candidates.isNotEmpty()

internal fun resolveAutoConnectLatencyMeasurementResult(
    warmupLatencyMs: Long,
    settledLatencyMs: Long?,
): Long =
    (settledLatencyMs ?: warmupLatencyMs)
        .coerceAtLeast(1L)

internal fun currentTrafficObservedAt(
    traffic: TrafficSnapshot,
    fallbackAt: Long,
): Long? =
    if (traffic.available || traffic.rxTotalBytes > 0L || traffic.txTotalBytes > 0L) {
        traffic.sampledAt.takeIf { it > 0L } ?: fallbackAt
    } else {
        null
    }

internal fun isAutoConnectDisconnectSettled(
    connectionState: ConnectionState,
    profileId: Long?,
    currentVpnNetworkHandle: Long?,
    previousVpnNetworkHandle: Long?,
): Boolean =
    (
        connectionState in setOf(ConnectionState.IDLE, ConnectionState.ERROR) ||
            (connectionState == ConnectionState.CONNECTED && profileId == FoxholeVpnService.LOCAL_GUARD_PROFILE_ID)
        ) &&
        (previousVpnNetworkHandle == null || currentVpnNetworkHandle != previousVpnNetworkHandle)

internal fun smartProfileMetricsAnalysisCandidates(
    fullScanCandidates: List<AutoConnectProbeCandidate>,
): List<AutoConnectProbeCandidate> =
    fullScanCandidates.distinctBy(AutoConnectProbeCandidate::optionId)
