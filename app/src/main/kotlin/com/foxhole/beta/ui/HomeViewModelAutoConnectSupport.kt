package com.foxhole.beta.ui

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.viewModelScope
import com.foxhole.beta.R
import com.foxhole.beta.core.model.AutoConnectReasonCode
import com.foxhole.beta.core.model.ConnectionSnapshot
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.Profile
import com.foxhole.beta.core.model.TrafficSnapshot
import com.foxhole.beta.core.model.TrafficMode
import com.foxhole.beta.core.network.NetworkFingerprint
import com.foxhole.beta.core.profile.AutoConnectProbeCandidate
import com.foxhole.beta.core.profile.AutoConnectProbeResult
import com.foxhole.beta.core.profile.MultiProtocolProfileSupport
import com.foxhole.beta.core.profile.classifyAutoConnectProbeFailure
import com.foxhole.beta.core.smart.AdaptiveProtocolCandidateScore
import com.foxhole.beta.core.settings.networkMemory
import com.foxhole.beta.core.settings.smartProfilePreference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal fun HomeViewModel.onAutoConnectActiveProfileInternal() {
    val profile = uiState.value.activeProfile
    val profileId = profile?.id ?: return
    val availableCandidates =
        profile?.let {
            availableAutoConnectCandidates(it.id, it, container.networkFingerprintProvider.currentFingerprint())
        }.orEmpty()
    if (availableCandidates.isEmpty()) {
        snackbars.tryEmit(infoBanner(R.string.auto_connect_requires_multi_protocol_profile))
        return
    }
    if (!MultiProtocolProfileSupport.hasMultipleSupportedOptions(profile) && availableCandidates.size < 2) {
        snackbars.tryEmit(infoBanner(R.string.auto_connect_requires_multi_protocol_profile))
        return
    }
    if (uiState.value.settings.traffic.mode == TrafficMode.PROXY) {
        startAutoConnect(profileId)
        return
    }
    val prepareIntent = android.net.VpnService.prepare(getApplication())
    if (prepareIntent != null) {
        pendingConnectRequest =
            PendingConnectRequest(
                profileId = profileId,
                action = PendingConnectAction.AUTO_CONNECT,
            )
        requestVpnPermission.tryEmit(Unit)
    } else {
        startAutoConnect(profileId)
    }
}

internal fun HomeViewModel.requestReconnectInternal(profileId: Long) {
    if (uiState.value.settings.traffic.mode == TrafficMode.PROXY) {
        reconnect(profileId)
        return
    }
    val prepareIntent = android.net.VpnService.prepare(getApplication())
    if (prepareIntent != null) {
        pendingConnectRequest =
            PendingConnectRequest(
                profileId = profileId,
                action = PendingConnectAction.RECONNECT,
            )
        requestVpnPermission.tryEmit(Unit)
    } else {
        reconnect(profileId)
    }
}

internal fun HomeViewModel.reconnectInternal(profileId: Long) {
    viewModelScope.launch {
        if (container.connectionController.snapshot.value.state in HomeViewModel.ACTIVE_CONNECTION_STATES) {
            container.connectionController.disconnect()
            awaitDisconnectedForAutoConnect()
        }
        connectNow(profileId)
    }
}

internal fun HomeViewModel.startAutoConnectInternal(profileId: Long) {
    autoConnectJob?.cancel()
    autoConnectJob =
        viewModelScope.launch {
            try {
                val profile = container.profileRepository.getProfile(profileId) ?: error("profile not found")
                val autoConnectNetworkFingerprint = container.networkFingerprintProvider.currentFingerprint()
                val rankedCandidates = scoredAutoConnectCandidatesInternal(profileId, profile, autoConnectNetworkFingerprint)
                val candidates = rankedCandidates.map(AdaptiveProtocolCandidateScore::candidate)
                require(candidates.isNotEmpty()) { getApplication<Application>().getString(R.string.auto_connect_requires_multi_protocol_profile) }
                logAdaptiveAutoConnectRanking(
                    profileId = profileId,
                    rankedCandidates = rankedCandidates,
                    networkFingerprint = autoConnectNetworkFingerprint,
                )
                initializeAutoConnectUi(candidates)
                var previousVpnNetworkHandle = awaitDisconnectedForAutoConnect()
                val results = mutableListOf<AutoConnectProbeResult>()
                candidates.forEachIndexed { index, candidate ->
                    markAutoConnectCandidateTesting(candidate)
                    delay(HomeViewModel.AUTO_CONNECT_PROTOCOL_TRANSITION_SETTLE_MS)
                    val result =
                        probeAutoConnectCandidate(
                            profileId = profileId,
                            candidate = candidate,
                            networkFingerprint = autoConnectNetworkFingerprint?.key,
                            previousVpnNetworkHandle = previousVpnNetworkHandle,
                        )
                    results += result
                    recordAutoConnectCandidateOutcome(
                        profileId = profileId,
                        result = result,
                        networkFingerprint = autoConnectNetworkFingerprint?.key,
                        headline =
                            when {
                                result.success && result.reasonCode == AutoConnectReasonCode.LATENCY_ENDPOINT_BLOCKED ->
                                    "candidate ok with fallback"
                                result.success -> "candidate ok"
                                else -> "candidate failed"
                            },
                    )
                    markAutoConnectCandidateFinished(result)
                    if (index < candidates.lastIndex) {
                        previousVpnNetworkHandle =
                            awaitDisconnectedForAutoConnect(
                                container.connectionController.currentVpnNetworkHandle(),
                            )
                    }
                }

                val winner = MultiProtocolProfileSupport.fastestSuccessfulProbe(results)
                if (winner == null) {
                    emitError(getApplication<Application>().getString(R.string.auto_connect_failed))
                    return@launch
                }

                markAutoConnectWinner(winner)
                previousVpnNetworkHandle =
                    awaitDisconnectedForAutoConnect(
                        container.connectionController.currentVpnNetworkHandle(),
                    )
                val finalConnectStartedAt = SystemClock.elapsedRealtime()
                connectNow(
                    profileId = profileId,
                    protocolOptionId = winner.candidate.optionId,
                    statusMessage = getApplication<Application>().getString(R.string.notification_status_analysis),
                    previousVpnNetworkHandle = previousVpnNetworkHandle,
                )
                val finalSnapshot = awaitAutoConnectConnectionOutcome()
                if (finalSnapshot?.state != ConnectionState.CONNECTED) {
                    val reasonCode =
                        classifyAutoConnectProbeFailure(
                            snapshot = finalSnapshot,
                            timedOut = finalSnapshot == null,
                            vpnNetworkAvailable = finalSnapshot == null && container.connectionController.hasActiveVpnNetwork(),
                            dnsFailureMessage = getApplication<Application>().getString(R.string.error_dns_probe_failed),
                        )
                    recordAutoConnectCandidateOutcome(
                        profileId = profileId,
                        result =
                            AutoConnectProbeResult(
                                candidate = winner.candidate,
                                success = false,
                                latencyMs = (SystemClock.elapsedRealtime() - finalConnectStartedAt).coerceAtLeast(1L),
                                connectDurationMs = (SystemClock.elapsedRealtime() - finalConnectStartedAt).coerceAtLeast(1L),
                                failureReason = autoConnectFailureMessage(reasonCode, finalSnapshot?.message),
                                reasonCode = reasonCode,
                            ),
                        networkFingerprint = autoConnectNetworkFingerprint?.key,
                        headline = "winner reconnect failed",
                    )
                    emitError(
                        finalSnapshot?.message
                            ?: autoConnectFailureMessage(reasonCode, finalSnapshot?.message),
                    )
                    return@launch
                }
                val currentProfile = container.profileRepository.getProfile(profileId)
                if (currentProfile?.selectedProtocolOptionId != winner.candidate.optionId) {
                    container.profileRepository.selectProfileProtocolOption(profileId, winner.candidate.optionId)
                }
                winner.displayLatencyMs?.let { latencyMs ->
                    cacheProtocolLatency(
                        profileId = profileId,
                        optionId = winner.candidate.optionId,
                        latencyMs = latencyMs,
                    )
                } ?: markProtocolLatencyUnavailable(
                    profileId = profileId,
                    optionId = winner.candidate.optionId,
                )
                val committedAt = System.currentTimeMillis()
                recordAutoConnectCandidateOutcome(
                    profileId = profileId,
                    result =
                        winner.copy(
                            connectDurationMs = (SystemClock.elapsedRealtime() - finalConnectStartedAt).coerceAtLeast(1L),
                            validatedAt = committedAt,
                            trafficObservedAt =
                                currentTrafficObservedAt(
                                    traffic = container.connectionController.traffic.value,
                                    fallbackAt = committedAt,
                                ) ?: winner.trafficObservedAt,
                        ),
                    networkFingerprint = autoConnectNetworkFingerprint?.key,
                    headline = "winner committed",
                    markAsLastKnownGood = true,
                    countTowardOutcomeHistory = false,
                )
                emitSuccess(
                    winner.displayLatencyMs?.let { latencyMs ->
                        getApplication<Application>().getString(
                            R.string.auto_connect_success,
                            winner.candidate.displayName,
                            latencyMs,
                        )
                    } ?: getApplication<Application>().getString(
                        R.string.auto_connect_success_unavailable,
                        winner.candidate.displayName,
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                emitError(error.message ?: getApplication<Application>().getString(R.string.auto_connect_failed))
            } finally {
                autoConnectJob = null
                delay(HomeViewModel.AUTO_CONNECT_RESULT_SETTLE_MS)
                clearAutoConnectUiState()
            }
        }
}

internal suspend fun HomeViewModel.probeAutoConnectCandidateInternal(
    profileId: Long,
    candidate: AutoConnectProbeCandidate,
    networkFingerprint: String?,
    previousVpnNetworkHandle: Long? = null,
): AutoConnectProbeResult {
    val startedAt = SystemClock.elapsedRealtime()
    connectNow(
        profileId = profileId,
        protocolOptionId = candidate.optionId,
        statusMessage = getApplication<Application>().getString(R.string.notification_status_analysis),
        previousVpnNetworkHandle = previousVpnNetworkHandle,
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
        runCatching { measureAutoConnectCandidateLatency() }
            .onFailure { probeError ->
                container.diagnosticsLogger.record("auto-connect", "latency probe failed: ${probeError.message.orEmpty()}")
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
        runCatching { container.connectionController.measureCurrentConnectionLatency() }
            .onFailure { error ->
                container.diagnosticsLogger.record("auto-connect", "latency settled retry failed: ${error.message.orEmpty()}")
            }.getOrNull()
    val resolvedLatencyMs = resolveAutoConnectLatencyMeasurementResult(warmupLatencyMs, settledLatencyMs)
    container.diagnosticsLogger.record(
        "auto-connect",
        "latency settled: warmup_ms=$warmupLatencyMs settled_ms=${settledLatencyMs?.toString() ?: "unavailable"} resolved_ms=$resolvedLatencyMs",
    )
    return resolvedLatencyMs
}

internal fun HomeViewModel.autoConnectFailureMessageInternal(
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

internal suspend fun HomeViewModel.recordAutoConnectCandidateOutcomeInternal(
    profileId: Long,
    result: AutoConnectProbeResult,
    networkFingerprint: String?,
    headline: String,
    markAsLastKnownGood: Boolean = false,
    countTowardOutcomeHistory: Boolean = true,
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
        *details.toTypedArray(),
    )
}

internal suspend fun HomeViewModel.awaitAutoConnectConnectionOutcomeInternal(): ConnectionSnapshot? =
    withTimeoutOrNull(HomeViewModel.AUTO_CONNECT_CONNECTION_TIMEOUT_MS) {
        container.connectionController.snapshot.first { snapshot ->
            snapshot.state in HomeViewModel.TERMINAL_CONNECTION_STATES
        }
    } ?: awaitAutoConnectValidationGraceOutcome()

internal suspend fun HomeViewModel.awaitAutoConnectValidationGraceOutcomeInternal(): ConnectionSnapshot? {
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

internal suspend fun HomeViewModel.awaitDisconnectedForAutoConnectInternal(
    previousVpnNetworkHandle: Long? = null,
): Long? {
    val expectedPreviousVpnNetworkHandle =
        previousVpnNetworkHandle ?: container.connectionController.currentVpnNetworkHandle()
    if (container.connectionController.snapshot.value.state in HomeViewModel.ACTIVE_CONNECTION_STATES) {
        container.connectionController.disconnect()
    }
    val deadlineAt = SystemClock.elapsedRealtime() + HomeViewModel.AUTO_CONNECT_DISCONNECT_TIMEOUT_MS
    while (true) {
        val snapshot = container.connectionController.snapshot.value
        val currentVpnNetworkHandle = container.connectionController.currentVpnNetworkHandle()
        if (
            isAutoConnectDisconnectSettled(
                connectionState = snapshot.state,
                currentVpnNetworkHandle = currentVpnNetworkHandle,
                previousVpnNetworkHandle = expectedPreviousVpnNetworkHandle,
            )
        ) {
            return expectedPreviousVpnNetworkHandle
        }
        if (SystemClock.elapsedRealtime() >= deadlineAt) {
            container.diagnosticsLogger.record(
                "auto-connect",
                "vpn network teardown timed out: previous=${expectedPreviousVpnNetworkHandle ?: "none"} current=${currentVpnNetworkHandle ?: "none"}",
            )
            error("vpn network teardown timed out")
        }
        delay(HomeViewModel.AUTO_CONNECT_DISCONNECT_POLL_DELAY_MS)
    }
}

internal fun HomeViewModel.initializeAutoConnectUiInternal(candidates: List<AutoConnectProbeCandidate>) {
    autoConnectUiStateMutable.value =
        AutoConnectUiState(
            running = true,
            currentOptionId = candidates.firstOrNull()?.optionId,
            currentDisplayName = candidates.firstOrNull()?.displayName,
            options =
                candidates.map { candidate ->
                    AutoConnectProbeOptionUiState(
                        optionId = candidate.optionId,
                        displayName = candidate.displayName,
                        protocolHint = candidate.protocolHint,
                    )
                },
        )
}

internal fun HomeViewModel.markAutoConnectCandidateTestingInternal(candidate: AutoConnectProbeCandidate) {
    autoConnectUiStateMutable.value =
        autoConnectUiStateMutable.value.copy(
            running = true,
            currentOptionId = candidate.optionId,
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

internal fun HomeViewModel.markAutoConnectCandidateFinishedInternal(result: AutoConnectProbeResult) {
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

internal fun HomeViewModel.markAutoConnectWinnerInternal(result: AutoConnectProbeResult) {
    autoConnectUiStateMutable.value =
        autoConnectUiStateMutable.value.copy(
            running = true,
            currentOptionId = result.candidate.optionId,
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

internal fun HomeViewModel.clearAutoConnectUiStateInternal() {
    autoConnectUiStateMutable.value = AutoConnectUiState()
}

internal fun HomeViewModel.cancelAutoConnectInternal(clearUiOnly: Boolean) {
    autoConnectJob?.cancel()
    autoConnectJob = null
    if (clearUiOnly) {
        clearAutoConnectUiState()
    }
}

internal fun HomeViewModel.availableAutoConnectCandidatesInternal(
    profileId: Long,
    profile: Profile,
    networkFingerprint: NetworkFingerprint?,
): List<AutoConnectProbeCandidate> {
    return scoredAutoConnectCandidatesInternal(profileId, profile, networkFingerprint).map(AdaptiveProtocolCandidateScore::candidate)
}

internal fun HomeViewModel.scoredAutoConnectCandidatesInternal(
    profileId: Long,
    profile: Profile,
    networkFingerprint: NetworkFingerprint?,
): List<AdaptiveProtocolCandidateScore> {
    val excludedOptionIds = excludedAutoConnectOptionIds(profileId)
    return MultiProtocolProfileSupport
        .scoredProbeCandidates(
            profile = profile,
            preference = uiState.value.settings.smartProfilePreference(profileId),
            networkFingerprint = networkFingerprint?.key,
            networkContext = networkFingerprint,
        ).filterNot { scoredCandidate -> scoredCandidate.candidate.optionId in excludedOptionIds }
}

internal fun HomeViewModel.excludedAutoConnectOptionIdsInternal(profileId: Long): Set<String> =
    uiState.value.settings.smartProfilePreferences
        .firstOrNull { preference -> preference.profileId == profileId }
        ?.excludedProtocolOptionIds
        ?.toSet()
        .orEmpty()

internal fun HomeViewModel.cacheProtocolLatencyInternal(
    profileId: Long,
    optionId: String,
    latencyMs: Long,
) {
    val key = ProfileOptionLatencyKey(profileId, optionId)
    profileOptionLatenciesMutable.value =
        profileOptionLatenciesMutable.value + (key to latencyMs.coerceAtLeast(1L))
    profileOptionLatencyUnavailableMutable.value =
        profileOptionLatencyUnavailableMutable.value - key
}

internal fun HomeViewModel.markProtocolLatencyUnavailableInternal(
    profileId: Long,
    optionId: String,
) {
    val key = ProfileOptionLatencyKey(profileId, optionId)
    profileOptionLatenciesMutable.value =
        profileOptionLatenciesMutable.value - key
    profileOptionLatencyUnavailableMutable.value =
        profileOptionLatencyUnavailableMutable.value + key
}

internal fun HomeViewModel.clearProtocolLatencyStateInternal(
    profileId: Long? = null,
    optionId: String? = null,
) {
    fun matches(key: ProfileOptionLatencyKey): Boolean =
        (profileId == null || key.profileId == profileId) &&
            (optionId == null || key.optionId == optionId)

    profileOptionLatenciesMutable.value =
        if (profileId == null && optionId == null) {
            emptyMap()
        } else {
            profileOptionLatenciesMutable.value.filterKeys { key -> !matches(key) }
        }
    profileOptionLatencyUnavailableMutable.value =
        if (profileId == null && optionId == null) {
            emptySet()
        } else {
            profileOptionLatencyUnavailableMutable.value.filterNot(::matches).toSet()
        }
}

internal fun HomeViewModel.scheduleActiveProfileLatencyRefreshInternal() {
    val activeProfile = uiState.value.activeProfile ?: return
    val selectedOptionId = resolveDashboardLatencyOptionId(activeProfile) ?: return
    profileLatencyRefreshJob?.cancel()
    clearProtocolLatencyState(
        profileId = activeProfile.id,
        optionId = selectedOptionId,
    )
    profileLatencyRefreshJob =
        viewModelScope.launch {
            delay(HomeViewModel.CONNECTED_PROTOCOL_LATENCY_REFRESH_DELAY_MS)
            if (container.connectionController.snapshot.value.state != ConnectionState.CONNECTED || autoConnectUiStateMutable.value.running) {
                return@launch
            }
            runCatching { container.connectionController.measureCurrentConnectionLatency() }
                .onSuccess { latencyMs ->
                    cacheProtocolLatency(
                        profileId = activeProfile.id,
                        optionId = selectedOptionId,
                        latencyMs = latencyMs,
                    )
                }.onFailure { error ->
                    markProtocolLatencyUnavailable(
                        profileId = activeProfile.id,
                        optionId = selectedOptionId,
                    )
                    container.diagnosticsLogger.record("latency", "dashboard latency unavailable: ${error.message.orEmpty()}")
                }
            profileLatencyRefreshJob = null
        }
}

internal fun HomeViewModel.clearProfileLatencyRefreshInternal() {
    profileLatencyRefreshJob?.cancel()
    profileLatencyRefreshJob = null
}

internal fun HomeViewModel.rememberedAutoConnectLatency(
    profileId: Long,
    optionId: String,
    networkFingerprint: String?,
): Long? {
    val preference = uiState.value.settings.smartProfilePreference(profileId) ?: return null
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
): Long = ((rememberedLatencyMs ?: validatedConnectDurationMs).coerceAtLeast(1L) + penaltyMs.coerceAtLeast(0L)).coerceAtLeast(1L)

internal fun shouldRetryAutoConnectLatencyMeasurement(warmupLatencyMs: Long): Boolean =
    warmupLatencyMs >= HomeViewModel.AUTO_CONNECT_LATENCY_MEASUREMENT_RETRY_THRESHOLD_MS

internal fun resolveAutoConnectLatencyMeasurementResult(
    warmupLatencyMs: Long,
    settledLatencyMs: Long?,
): Long = (settledLatencyMs ?: warmupLatencyMs).coerceAtLeast(1L)

internal fun currentTrafficObservedAt(
    traffic: TrafficSnapshot,
    fallbackAt: Long,
): Long? =
    if (traffic.available || traffic.rxTotalBytes > 0L || traffic.txTotalBytes > 0L) {
        traffic.sampledAt.takeIf { it > 0L } ?: fallbackAt
    } else {
        null
    }

internal fun HomeViewModel.logAdaptiveAutoConnectRanking(
    profileId: Long,
    rankedCandidates: List<AdaptiveProtocolCandidateScore>,
    networkFingerprint: NetworkFingerprint?,
) {
    val details =
        buildList {
            add("profile_id=$profileId")
            networkFingerprint?.key?.take(12)?.let { fingerprint -> add("network_fp=$fingerprint") }
            networkFingerprint?.let { context ->
                add("transport=${context.transport}")
                add("metered=${context.isMetered}")
                add("roaming=${context.isRoaming}")
                add("private_dns=${context.privateDnsActive}")
                add("validated_upstream=${context.upstreamValidated}")
            }
            rankedCandidates.forEachIndexed { index, candidate ->
                add("rank_${index + 1}=${candidate.summary()}")
            }
        }
    container.diagnosticsLogger.recordStructured(
        "auto-connect",
        "adaptive ranking",
        *details.toTypedArray(),
    )
}

internal fun isAutoConnectDisconnectSettled(
    connectionState: ConnectionState,
    currentVpnNetworkHandle: Long?,
    previousVpnNetworkHandle: Long?,
): Boolean =
    connectionState in setOf(ConnectionState.IDLE, ConnectionState.ERROR) &&
        (previousVpnNetworkHandle == null || currentVpnNetworkHandle != previousVpnNetworkHandle)
