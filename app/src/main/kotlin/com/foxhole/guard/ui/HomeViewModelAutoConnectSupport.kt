package com.foxhole.guard.ui
import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.viewModelScope
import com.foxhole.core.model.ACTIVE_CONNECTION_STATES
import com.foxhole.core.model.AutoConnectReasonCode
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.ProtocolMetricEventKind
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.profile.AutoConnectProbeCandidate
import com.foxhole.core.profile.AutoConnectProbeResult
import com.foxhole.core.profile.classifyAutoConnectProbeFailure
import com.foxhole.guard.R
import com.foxhole.guard.core.settings.recordSmartProfileProbeResult
import com.foxhole.guard.runtime.FoxholeConnectionServiceContract
import com.foxhole.guard.runtime.currentVpnNetworkHandle
import com.foxhole.guard.runtime.runCatchingUnlessCancelled
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// Reconnect entry points and the Smart Start auto-connect engine: candidate probes,
// outcome recording, disconnect stabilization, and the auto-connect UI state.

internal fun HomeViewModel.requestReconnect(profileId: Long) {
    controlUiState.value.profiles.firstOrNull { profile -> profile.id == profileId }?.let { profile ->
        if (maybePromptStartTcpVpnWhileTorOnlyActive(profile, null)) {
            return
        }
    }
    clearRouteModeRestartPrompt()
    clearProtocolSwitchRevert()
    if (controlUiState.value.settings.traffic.mode == TrafficMode.PROXY) {
        reconnect(profileId)
        return
    }
    val prepareIntent = android.net.VpnService.prepare(getApplication())
    if (prepareIntent != null) {
        enqueueVpnPermissionRequest(
            PendingConnectRequest(
                profileId = profileId,
                action = PendingConnectAction.RECONNECT,
            ),
        )
    } else {
        reconnect(profileId)
    }
}

internal fun HomeViewModel.reconnect(profileId: Long) {
    clearRouteModeRestartPrompt()
    clearProtocolSwitchRevert()
    val previousReconnectJob = reconnectJob
    previousReconnectJob?.cancel()
    val nextReconnectJob = viewModelScope.launch {
        previousReconnectJob?.join()
        reconnectInProgressMutable.value = true
        try {
            cancelSmartProfileMetricsRefresh(restoreConnection = false)
            if (container.connectionController.snapshot.value.state in ACTIVE_CONNECTION_STATES) {
                container.connectionController.disconnect(suppressLocalGuard = true, userInitiated = false)
                awaitDisconnectedForAutoConnect()
            }
            connectNow(profileId)
            awaitReconnectConnectionOutcome()
        } catch (cancelled: CancellationException) {
            container.diagnosticsLogger.record("connection", "manual reconnect cancelled")
            throw cancelled
        } catch (error: Throwable) {
            emitError(runtimeConnectionFailureMessage(error))
        } finally {
            reconnectInProgressMutable.value = false
            if (reconnectJob == coroutineContext[Job]) {
                reconnectJob = null
            }
        }
    }
    reconnectJob = nextReconnectJob
}

private suspend fun HomeViewModel.awaitReconnectConnectionOutcome(): ConnectionSnapshot? {
    val outcome =
        withTimeoutOrNull(HomeViewModel.AUTO_CONNECT_CONNECTION_TIMEOUT_MS) {
            container.connectionController.snapshot.first { snapshot ->
                snapshot.state in HomeViewModel.TERMINAL_CONNECTION_STATES
            }
        }
    if (outcome == null) {
        container.diagnosticsLogger.recordFailure("connection", "manual reconnect status wait timed out")
        container.connectionController.disconnect(suppressLocalGuard = true, userInitiated = false)
        error("Connection timed out")
    }
    return outcome
}

internal suspend fun HomeViewModel.probeAutoConnectCandidate(
    profileId: Long,
    candidate: AutoConnectProbeCandidate,
    networkFingerprint: String?,
    previousVpnNetworkHandle: Long? = null,
    protocolTestTrafficFreeze: Boolean = false,
    replaceActiveTunnel: Boolean = false,
): AutoConnectProbeResult {
    val startedAt = SystemClock.elapsedRealtime()
    connectNow(
        profileId = profileId,
        protocolOptionId = candidate.optionId,
        statusMessage = getApplication<Application>().getString(R.string.notification_status_analysis),
        isSmartStartConnection = true,
        previousVpnNetworkHandle = previousVpnNetworkHandle,
        // The metrics session refreshes the subscription once before it snapshots candidates.
        subscriptionRefreshPrepared = true,
        protocolTestTrafficFreeze = protocolTestTrafficFreeze,
        replaceActiveTunnel = replaceActiveTunnel,
    )
    val snapshot = awaitAutoConnectConnectionOutcome()
    if (snapshot?.state != ConnectionState.CONNECTED) {
        val reasonCode =
            classifyAutoConnectProbeFailure(
                snapshot = snapshot,
                timedOut = snapshot == null,
                vpnNetworkAvailable = snapshot == null && container.connectionController.hasActiveVpnNetwork(),
                dnsFailureMessage = getApplication<Application>().getString(R.string.error_dns_probe_failed),
            )
        return AutoConnectProbeResult(
            candidate = candidate,
            success = false,
            latencyMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(1L),
            connectDurationMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(1L),
            failureReason = autoConnectFailureMessage(reasonCode, snapshot?.message),
            reasonCode = reasonCode,
        )
    }
    val validatedConnectDurationMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(1L)
    val outcomeRecordedAt = System.currentTimeMillis()
    val trafficObservedAt =
        currentTrafficObservedAt(
            traffic = container.connectionController.traffic.value,
            fallbackAt = outcomeRecordedAt,
        )
    val measuredLatency =
        runCatchingUnlessCancelled { measureAutoConnectCandidateLatency() }
            .onFailure { probeError ->
                container.diagnosticsLogger.record(
                    "auto-connect",
                    "latency probe failed: ${probeError.message.orEmpty()}"
                )
            }.getOrElse { error ->
                val rememberedLatencyMs =
                    rememberedAutoConnectLatency(
                        profileId = profileId,
                        optionId = candidate.optionId,
                        networkFingerprint = networkFingerprint,
                    )
                return AutoConnectProbeResult(
                    candidate = candidate,
                    success = true,
                    latencyMs = validatedConnectDurationMs,
                    rankingLatencyMs =
                    resolveAutoConnectFallbackRankingLatency(
                        validatedConnectDurationMs = validatedConnectDurationMs,
                        rememberedLatencyMs = rememberedLatencyMs,
                        penaltyMs = HomeViewModel.AUTO_CONNECT_LATENCY_FALLBACK_PENALTY_MS,
                        protocolHint = candidate.protocolHint,
                        latencyProbeMethod = controlUiState.value.settings.connection.latencyProbeMethod,
                    ),
                    displayLatencyMs = null,
                    connectDurationMs = validatedConnectDurationMs,
                    validatedAt = outcomeRecordedAt,
                    trafficObservedAt = trafficObservedAt,
                    reasonCode = AutoConnectReasonCode.LATENCY_ENDPOINT_BLOCKED,
                )
            }
    cacheProtocolLatency(
        profileId = profileId,
        optionId = candidate.optionId,
        latencyMs = measuredLatency,
    )
    return AutoConnectProbeResult(
        candidate = candidate,
        success = true,
        latencyMs = measuredLatency,
        connectDurationMs = validatedConnectDurationMs,
        validatedAt = outcomeRecordedAt,
        trafficObservedAt = trafficObservedAt,
    )
}

internal suspend fun HomeViewModel.measureAutoConnectCandidateLatency(): Long {
    delay(HomeViewModel.AUTO_CONNECT_LATENCY_MEASUREMENT_SETTLE_MS)
    val warmupLatencyMs = container.connectionController.measureCurrentConnectionLatency()
    if (!shouldRetryAutoConnectLatencyMeasurement(warmupLatencyMs)) {
        return warmupLatencyMs
    }
    delay(HomeViewModel.AUTO_CONNECT_LATENCY_MEASUREMENT_RETRY_DELAY_MS)
    val settledLatencyMs =
        runCatchingUnlessCancelled { container.connectionController.measureCurrentConnectionLatency() }
            .onFailure { error ->
                container.diagnosticsLogger.record(
                    "auto-connect",
                    "latency settled retry failed: ${error.message.orEmpty()}"
                )
            }.getOrNull()
    val resolvedLatencyMs = resolveAutoConnectLatencyMeasurementResult(warmupLatencyMs, settledLatencyMs)
    container.diagnosticsLogger.record(
        "auto-connect",
        "latency settled: warmup_ms=$warmupLatencyMs settled_ms=${settledLatencyMs?.toString() ?: "unavailable"} resolved_ms=$resolvedLatencyMs",
    )
    return resolvedLatencyMs
}

internal fun HomeViewModel.autoConnectFailureMessage(
    reasonCode: AutoConnectReasonCode,
    snapshotMessage: String?,
): String =
    when (reasonCode) {
        AutoConnectReasonCode.HANDSHAKE_TIMEOUT ->
            getApplication<Application>().getString(R.string.auto_connect_probe_timeout)
        AutoConnectReasonCode.VALIDATION_TIMEOUT ->
            getApplication<Application>().getString(R.string.auto_connect_ip_unavailable)
        AutoConnectReasonCode.DNS_FAILURE ->
            snapshotMessage ?: getApplication<Application>().getString(R.string.error_dns_probe_failed)
        AutoConnectReasonCode.CONNECT_ERROR ->
            snapshotMessage ?: getApplication<Application>().getString(R.string.auto_connect_failed)
        AutoConnectReasonCode.LATENCY_ENDPOINT_BLOCKED,
        AutoConnectReasonCode.RESTORED_LAST_GOOD ->
            snapshotMessage ?: getApplication<Application>().getString(R.string.auto_connect_failed)
    }

internal suspend fun HomeViewModel.recordAutoConnectCandidateOutcome(
    profileId: Long,
    result: AutoConnectProbeResult,
    networkFingerprint: String?,
    headline: String,
    markAsLastKnownGood: Boolean = false,
    countTowardOutcomeHistory: Boolean = true,
    affectsFailureRankingMemory: Boolean = true,
) {
    container.settingsRepository.recordSmartProfileProbeResult(
        profileId = profileId,
        optionId = result.candidate.optionId,
        latencyMs = result.displayLatencyMs,
        success = result.success,
        reasonCode = result.reasonCode,
        markAsLastKnownGood = markAsLastKnownGood,
        networkFingerprint = networkFingerprint,
        connectDurationMs = result.connectDurationMs,
        validatedAt = result.validatedAt,
        trafficObservedAt = result.trafficObservedAt,
        countTowardOutcomeHistory = countTowardOutcomeHistory,
        affectsFailureRankingMemory = affectsFailureRankingMemory,
    )
    recordProtocolMetricEventInternal(
        profileId = profileId,
        optionId = result.candidate.optionId,
        kind =
        if (result.success) {
            ProtocolMetricEventKind.PROBE_SUCCESS
        } else {
            ProtocolMetricEventKind.PROBE_FAILURE
        },
        latencyMs = result.displayLatencyMs,
        protocol = result.candidate.protocolHint.name.lowercase(),
        reasonCode = result.reasonCode?.name,
    )
    val details =
        buildList {
            add("profile_id=$profileId")
            add("option=${result.candidate.optionId}")
            add("protocol=${result.candidate.protocolHint.name.lowercase()}")
            add("outcome=${if (result.success) "ok" else "down"}")
            add("latency_ms=${result.displayLatencyMs?.toString() ?: "unavailable"}")
            add("ranking_latency_ms=${result.rankingLatencyMs}")
            add("connect_duration_ms=${result.connectDurationMs}")
            add("validated=${result.validatedAt != null}")
            add("traffic=${result.trafficObservedAt != null}")
            networkFingerprint?.take(12)?.let { fingerprint -> add("network_fp=$fingerprint") }
            result.reasonCode?.wireCode?.let { code -> add("reason=$code") }
            result.failureReason?.takeIf(String::isNotBlank)?.let { message -> add("message=$message") }
        }
    container.diagnosticsLogger.recordStructured(
        "auto-connect",
        headline,
        details,
    )
}

internal suspend fun HomeViewModel.awaitAutoConnectConnectionOutcome(): ConnectionSnapshot? =
    withTimeoutOrNull(HomeViewModel.AUTO_CONNECT_CONNECTION_TIMEOUT_MS) {
        container.connectionController.snapshot.first { snapshot ->
            snapshot.state in HomeViewModel.TERMINAL_CONNECTION_STATES
        }
    } ?: awaitAutoConnectValidationGraceOutcome()

internal suspend fun HomeViewModel.awaitAutoConnectValidationGraceOutcome(): ConnectionSnapshot? {
    val snapshot = container.connectionController.snapshot.value
    if (
        !shouldAwaitAutoConnectValidationGrace(
            connectionState = snapshot.state,
            vpnNetworkAvailable = container.connectionController.hasActiveVpnNetwork(),
        )
    ) {
        return null
    }
    container.diagnosticsLogger.record("auto-connect", "waiting for tunnel validation grace window")
    return withTimeoutOrNull(HomeViewModel.AUTO_CONNECT_VALIDATION_GRACE_TIMEOUT_MS) {
        container.connectionController.snapshot.first { candidate ->
            candidate.state in HomeViewModel.TERMINAL_CONNECTION_STATES
        }
    }
}

internal suspend fun HomeViewModel.awaitDisconnectedForAutoConnect(
    previousVpnNetworkHandle: Long? = null,
): Long? {
    val expectedPreviousVpnNetworkHandle =
        previousVpnNetworkHandle ?: container.connectionController.currentVpnNetworkHandle()
    if (container.connectionController.snapshot.value.state in ACTIVE_CONNECTION_STATES) {
        container.connectionController.disconnect(
            suppressLocalGuard = true,
            preserveSmartStartAnalysis = true,
            userInitiated = false,
        )
    }
    val deadlineAt = SystemClock.elapsedRealtime() + HomeViewModel.AUTO_CONNECT_DISCONNECT_TIMEOUT_MS
    while (true) {
        val snapshot = container.connectionController.snapshot.value
        val currentVpnNetworkHandle = container.connectionController.currentVpnNetworkHandle()
        if (
            isAutoConnectDisconnectSettled(
                connectionState = snapshot.state,
                profileId = snapshot.profileId,
                currentVpnNetworkHandle = currentVpnNetworkHandle,
                previousVpnNetworkHandle = expectedPreviousVpnNetworkHandle,
            )
        ) {
            return expectedPreviousVpnNetworkHandle
        }
        if (SystemClock.elapsedRealtime() >= deadlineAt) {
            container.diagnosticsLogger.record(
                "auto-connect",
                "vpn network teardown timed out; force stabilizing before next candidate: previous=${expectedPreviousVpnNetworkHandle ?: "none"} current=${currentVpnNetworkHandle ?: "none"}",
            )
            forceStabilizeAutoConnectDisconnect(expectedPreviousVpnNetworkHandle)
            return expectedPreviousVpnNetworkHandle
        }
        delay(HomeViewModel.AUTO_CONNECT_DISCONNECT_POLL_DELAY_MS)
    }
}

private suspend fun HomeViewModel.forceStabilizeAutoConnectDisconnect(previousVpnNetworkHandle: Long?) {
    val app = getApplication<Application>()
    val mode =
        FoxholeConnectionServiceContract.serviceMode(
            snapshot = container.connectionController.snapshot.value,
            fallbackMode = container.settingsRepository.current().traffic.mode,
        )
    FoxholeConnectionServiceContract.startForegroundService(
        context = app,
        mode = mode,
        action = FoxholeConnectionServiceContract.ACTION_DISCONNECT,
        suppressLocalGuard = true,
        preserveSmartStartAnalysis = true,
    )
    val deadlineAt = SystemClock.elapsedRealtime() + HomeViewModel.AUTO_CONNECT_DISCONNECT_FORCE_STABILIZE_TIMEOUT_MS
    while (SystemClock.elapsedRealtime() < deadlineAt) {
        val snapshot = container.connectionController.snapshot.value
        val currentVpnNetworkHandle = container.connectionController.currentVpnNetworkHandle()
        if (
            isAutoConnectDisconnectSettled(
                connectionState = snapshot.state,
                profileId = snapshot.profileId,
                currentVpnNetworkHandle = currentVpnNetworkHandle,
                previousVpnNetworkHandle = previousVpnNetworkHandle,
            )
        ) {
            container.diagnosticsLogger.record(
                "auto-connect",
                "vpn network teardown stabilized after forced disconnect",
            )
            return
        }
        delay(HomeViewModel.AUTO_CONNECT_DISCONNECT_POLL_DELAY_MS)
    }
    container.diagnosticsLogger.record(
        "auto-connect",
        "vpn network teardown still not settled after forced disconnect; next candidate will exclude stale handle",
    )
}

internal fun HomeViewModel.initializeAutoConnectUi(candidates: List<AutoConnectProbeCandidate>) {
    autoConnectUiStateMutable.value =
        AutoConnectUiState(
            running = true,
            currentOptionId = candidates.firstOrNull()?.optionId,
            currentProtocolHint = candidates.firstOrNull()?.protocolHint,
            currentDisplayName = candidates.firstOrNull()?.displayName,
            options = candidates.mapIndexed { index, candidate ->
                AutoConnectProbeOptionUiState(
                    optionId = candidate.optionId,
                    displayName = candidate.displayName,
                    protocolHint = candidate.protocolHint,
                    status = if (index == 0) {
                        AutoConnectProbeStatus.TESTING
                    } else {
                        AutoConnectProbeStatus.PENDING
                    },
                )
            },
        )
}

internal fun HomeViewModel.markAutoConnectCandidateTesting(
    profileId: Long,
    candidate: AutoConnectProbeCandidate,
) {
    clearProtocolProbeStatus(profileId = profileId, optionId = candidate.optionId)
    autoConnectUiStateMutable.value =
        autoConnectUiStateMutable.value.copy(
            running = true,
            currentOptionId = candidate.optionId,
            currentProtocolHint = candidate.protocolHint,
            currentDisplayName = candidate.displayName,
            options =
            autoConnectUiStateMutable.value.options.map { option ->
                if (option.optionId == candidate.optionId) {
                    option.copy(
                        status = AutoConnectProbeStatus.TESTING,
                        latencyMs = null,
                        latencyUnavailable = false,
                    )
                } else {
                    option
                }
            },
        )
}

private fun HomeViewModel.clearProtocolProbeStatus(
    profileId: Long,
    optionId: String,
) {
    val key = ProfileOptionLatencyKey(profileId, optionId)
    profileOptionDownMutable.value =
        profileOptionDownMutable.value - key
    profileOptionLatencyUnavailableMutable.value =
        profileOptionLatencyUnavailableMutable.value - key
}

internal fun HomeViewModel.markAutoConnectCandidateFinished(result: AutoConnectProbeResult) {
    autoConnectUiStateMutable.value =
        autoConnectUiStateMutable.value.copy(
            options =
            autoConnectUiStateMutable.value.options.map { option ->
                if (option.optionId == result.candidate.optionId) {
                    option.copy(
                        status =
                        if (result.success) {
                            AutoConnectProbeStatus.SUCCESS
                        } else {
                            AutoConnectProbeStatus.FAILED
                        },
                        latencyMs = result.displayLatencyMs,
                        latencyUnavailable = result.success && result.displayLatencyMs == null,
                    )
                } else {
                    option
                }
            },
        )
}

internal fun HomeViewModel.markAutoConnectWinner(result: AutoConnectProbeResult) {
    autoConnectUiStateMutable.value =
        autoConnectUiStateMutable.value.copy(
            running = true,
            currentOptionId = result.candidate.optionId,
            currentProtocolHint = result.candidate.protocolHint,
            currentDisplayName = result.candidate.displayName,
            options =
            autoConnectUiStateMutable.value.options.map { option ->
                if (option.optionId == result.candidate.optionId) {
                    option.copy(
                        status = AutoConnectProbeStatus.WINNER,
                        latencyMs = result.displayLatencyMs,
                        latencyUnavailable = result.success && result.displayLatencyMs == null,
                    )
                } else {
                    option
                }
            },
        )
}

internal fun HomeViewModel.clearAutoConnectUiState() {
    autoConnectUiStateMutable.value = AutoConnectUiState()
}

internal fun HomeViewModel.cancelAutoConnect(clearUiOnly: Boolean) {
    val runningJob = autoConnectJob
    autoConnectJob = null
    runningJob?.cancel()
    if (clearUiOnly) {
        container.connectionController.clearSmartStartAnalysisStatus()
        clearAutoConnectUiState()
    }
}
