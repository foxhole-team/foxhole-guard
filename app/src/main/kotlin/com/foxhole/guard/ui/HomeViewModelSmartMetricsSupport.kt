package com.foxhole.guard.ui
import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.viewModelScope
import com.foxhole.core.model.ACTIVE_CONNECTION_STATES
import com.foxhole.core.model.AutoConnectReasonCode
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.Profile
import com.foxhole.core.model.ProtocolMetricEventKind
import com.foxhole.core.profile.AutoConnectProbeCandidate
import com.foxhole.core.profile.AutoConnectProbeResult
import com.foxhole.core.profile.MultiProtocolProfileSupport
import com.foxhole.core.profile.classifyAutoConnectProbeFailure
import com.foxhole.guard.R
import com.foxhole.guard.core.settings.recordSmartProfileBaseline
import com.foxhole.guard.core.settings.recordSmartProfileServerPing
import com.foxhole.guard.core.settings.smartStartEnabledProtocolSetHash
import com.foxhole.guard.runtime.runCatchingUnlessCancelled
import com.foxhole.guard.userFacingErrorMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

// Smart-profile protocol metrics: the manual refresh run, its probes, fallbacks, restore
// path, and the protocol recommendation acceptance.

private fun HomeViewModel.fullScanAutoConnectCandidates(
    profile: Profile,
): List<AutoConnectProbeCandidate> =
    MultiProtocolProfileSupport.smartStartFullScanCandidates(
        profile = profile,
        allowInsecureTlsGlobally = controlUiState.value.settings.expert.allowInsecureTls,
    )

internal fun recommendedProtocolIdsFromProbeResults(results: List<AutoConnectProbeResult>): List<String> {
    val successful = results.filter(AutoConnectProbeResult::success).distinctBy { it.candidate.optionId }
    val baseline = ProtocolTestBaseline.from(successful)
    return successful
        .sortedWith(
            compareBy<AutoConnectProbeResult> { result -> protocolTestScore(result, baseline) }
                .thenBy { result -> result.candidate.optionId },
        )
        .map { result -> result.candidate.optionId }
        .take(PROTOCOL_TEST_WINNERS)
}

private fun protocolTestScore(result: AutoConnectProbeResult, baseline: ProtocolTestBaseline): Double {
    var weightedScore = normalizedMetric(result.connectDurationMs, baseline.connectDurationMs) * CONNECT_WEIGHT
    var weight = CONNECT_WEIGHT
    result.displayLatencyMs?.let { latencyMs ->
        weightedScore += normalizedMetric(latencyMs, baseline.latencyMs) * LATENCY_WEIGHT
        weight += LATENCY_WEIGHT
    }
    result.serverPingMs?.let { pingMs ->
        weightedScore += normalizedMetric(pingMs, baseline.serverPingMs) * SERVER_PING_WEIGHT
        weight += SERVER_PING_WEIGHT
    }
    val stabilityPenalty =
        (if (result.validatedAt == null) VALIDATION_PENALTY else 0.0) +
            (if (result.trafficObservedAt == null) TRAFFIC_PENALTY else 0.0)
    return weightedScore / weight + stabilityPenalty
}

private fun normalizedMetric(value: Long, best: Long): Double =
    value.coerceAtLeast(1L).toDouble() / best.coerceAtLeast(1L).toDouble()

private data class ProtocolTestBaseline(
    val connectDurationMs: Long,
    val latencyMs: Long,
    val serverPingMs: Long,
) {
    companion object {
        fun from(results: List<AutoConnectProbeResult>) = ProtocolTestBaseline(
            connectDurationMs = results.minOfOrNull(AutoConnectProbeResult::connectDurationMs) ?: 1L,
            latencyMs = results.mapNotNull(AutoConnectProbeResult::displayLatencyMs).minOrNull() ?: 1L,
            serverPingMs = results.mapNotNull(AutoConnectProbeResult::serverPingMs).minOrNull() ?: 1L,
        )
    }
}

private const val PROTOCOL_TEST_WINNERS = 2
private const val CONNECT_WEIGHT = 0.35
private const val LATENCY_WEIGHT = 0.40
private const val SERVER_PING_WEIGHT = 0.25
private const val VALIDATION_PENALTY = 0.15
private const val TRAFFIC_PENALTY = 0.10

private fun HomeViewModel.updateRecommendedProtocolUi(
    profileId: Long,
    profile: Profile,
    recommendedIds: List<String>,
) {
    val firstRecommendedId = recommendedIds.firstOrNull() ?: return
    val displayName =
        profile.protocolOptions
            .firstOrNull { option -> option.id == firstRecommendedId }
            ?.displayName
            ?.takeIf(String::isNotBlank)
            ?: firstRecommendedId
    recommendedProtocolMutable.value =
        ProtocolRecommendationState(
            profileId = profileId,
            optionId = firstRecommendedId,
            displayName = displayName,
        )
}

private fun HomeViewModel.smartProfileMetricsRefreshBlocked(): Boolean {
    val currentSnapshot = container.connectionController.snapshot.value
    if (currentSnapshot.torActive && currentSnapshot.state in ACTIVE_CONNECTION_STATES) {
        // Testing reconnects through every protocol, which would tear down the live Tor route
        // mid-session — and the probes would measure the Tor circuit anyway.
        container.diagnosticsLogger.record("latency", "manual speed tests blocked: tor route active")
        snackbars.tryEmit(infoBanner(R.string.smart_profile_testing_blocked_tor))
        return true
    }
    if (shouldSkipSpeedTestsOnCurrentNetwork()) {
        container.diagnosticsLogger.record("latency", "manual speed tests skipped: cellular or metered network")
        snackbars.tryEmit(infoBanner(R.string.network_rules_speed_tests_skipped_mobile))
        return true
    }
    return false
}

internal fun HomeViewModel.refreshSmartProfileMetrics(profileId: Long) {
    protocolMetricsRefreshJob?.cancel()
    if (smartProfileMetricsRefreshBlocked()) {
        protocolMetricsRefreshJob = null
        setDashboardConnectionMetricsLoading(false)
        return
    }
    protocolMetricsRestoreOnCancel = true
    protocolMetricsRefreshJob =
        viewModelScope.launch {
            var selectedOptionId: String? = null
            var restoreProfileId: Long? = null
            var restoreOptionId: String? = null
            var restoredConnection = false
            var restoreFailure = false
            try {
                val initialSnapshot = container.connectionController.snapshot.value
                val initiallyActive =
                    initialSnapshot.state in ACTIVE_CONNECTION_STATES &&
                        initialSnapshot.profileId != null
                val profile = container.profileRepository.getProfile(profileId) ?: error("profile not found")
                val networkFingerprint = currentNetworkFingerprintForSmartRules()
                val candidates =
                    smartProfileMetricsAnalysisCandidates(
                        fullScanAutoConnectCandidates(profile),
                    )
                require(canStartAutoConnect(candidates)) {
                    getApplication<Application>().getString(R.string.auto_connect_requires_supported_profile)
                }
                val enabledProtocolSetHash =
                    smartStartEnabledProtocolSetHash(candidates.map(AutoConnectProbeCandidate::optionId))
                selectedOptionId = resolveDashboardLatencyOptionId(profile)
                if (initiallyActive) {
                    restoreProfileId = initialSnapshot.profileId
                    restoreOptionId =
                        if (initialSnapshot.profileId == profileId) {
                            selectedOptionId
                        } else {
                            initialSnapshot.protocolOptionId
                        }
                }
                protocolMetricsRefreshingOptionIdByProfileIdMutable.value =
                    protocolMetricsRefreshingOptionIdByProfileIdMutable.value +
                    (profileId to candidates.first().optionId)
                protocolMetricsRefreshingProfileIdsMutable.value =
                    protocolMetricsRefreshingProfileIdsMutable.value + profileId
                initializeAutoConnectUi(candidates)
                val results =
                    runSmartProfileMetricsProbes(
                        profileId = profileId,
                        candidates = candidates,
                        networkFingerprintKey = networkFingerprint?.key,
                    )
                val recommendedIds =
                    recommendedProtocolIdsFromProbeResults(results)
                if (recommendedIds.isNotEmpty()) {
                    container.settingsRepository.recordSmartProfileBaseline(
                        profileId = profileId,
                        recommendedProtocolIds = recommendedIds,
                        enabledProtocolSetHash = enabledProtocolSetHash,
                    )
                    updateRecommendedProtocolUi(profileId, profile, recommendedIds)
                }
                restoreConnectionAfterMetricsRefresh(
                    restoreProfileId = restoreProfileId,
                    restoreOptionId = restoreOptionId,
                )
                restoredConnection = true
                announceSmartProfileMetricsOutcome(
                    profileId = profileId,
                    recommendedIds = recommendedIds,
                    results = results,
                    selectedOptionId = selectedOptionId,
                )
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    if (protocolMetricsRestoreOnCancel) {
                        restoredConnection =
                            runCatching {
                                restoreConnectionAfterMetricsRefresh(
                                    restoreProfileId = restoreProfileId.takeIf { !restoredConnection },
                                    restoreOptionId = restoreOptionId,
                                )
                            }.isSuccess
                        emitSuccess(getApplication<Application>().getString(R.string.protocol_metrics_refresh_cancelled))
                    }
                }
                throw cancelled
            } catch (error: Throwable) {
                container.diagnosticsLogger.record("auto-connect", "protocol refresh failed: ${error.message.orEmpty()}")
                emitError(getApplication<Application>().getString(R.string.protocol_metrics_refresh_failed))
            } finally {
                withContext(NonCancellable) {
                    if (protocolMetricsRestoreOnCancel && restoreProfileId != null && !restoredConnection) {
                        restoreFailure =
                            runCatching {
                                restoreConnectionAfterMetricsRefresh(
                                    restoreProfileId = restoreProfileId,
                                    restoreOptionId = restoreOptionId,
                                )
                            }.isFailure
                    }
                    if (restoreFailure) {
                        emitError(getApplication<Application>().getString(R.string.protocol_metrics_restore_failed))
                    }
                    protocolMetricsRefreshingProfileIdsMutable.value =
                        protocolMetricsRefreshingProfileIdsMutable.value - profileId
                    protocolMetricsRefreshingOptionIdByProfileIdMutable.value =
                        protocolMetricsRefreshingOptionIdByProfileIdMutable.value - profileId
                    protocolMetricsRefreshJob = null
                    protocolMetricsRestoreOnCancel = true
                    delay(HomeViewModel.AUTO_CONNECT_RESULT_SETTLE_MS)
                    clearAutoConnectUiState()
                }
            }
        }
}

// Probes every candidate sequentially on a disconnected runtime, feeding each outcome into the
// latency caches and the auto-connect UI as it lands.
private suspend fun HomeViewModel.runSmartProfileMetricsProbes(
    profileId: Long,
    candidates: List<AutoConnectProbeCandidate>,
    networkFingerprintKey: String?,
): List<AutoConnectProbeResult> {
    var previousVpnNetworkHandle = awaitDisconnectedForAutoConnect()
    val results = mutableListOf<AutoConnectProbeResult>()
    candidates.forEachIndexed { index, candidate ->
        protocolMetricsRefreshingOptionIdByProfileIdMutable.value =
            protocolMetricsRefreshingOptionIdByProfileIdMutable.value + (profileId to candidate.optionId)
        markAutoConnectCandidateTesting(profileId, candidate)
        delay(HomeViewModel.AUTO_CONNECT_PROTOCOL_TRANSITION_SETTLE_MS)
        val result =
            probeAutoConnectCandidateForMetricsRefresh(
                profileId = profileId,
                candidate = candidate,
                networkFingerprint = networkFingerprintKey,
                previousVpnNetworkHandle = previousVpnNetworkHandle,
            )
        results += result
        recordAutoConnectCandidateOutcome(
            profileId = profileId,
            result = result,
            networkFingerprint = networkFingerprintKey,
            headline = if (result.success) "manual metrics probe ok" else "manual metrics probe failed",
            countTowardOutcomeHistory = false,
            affectsFailureRankingMemory = false,
        )
        recordSmartProfileMetricsProbeOutcome(profileId, result)
        markAutoConnectCandidateFinished(result)
        if (index < candidates.lastIndex) {
            previousVpnNetworkHandle =
                awaitDisconnectedForAutoConnect(
                    container.connectionController.currentVpnNetworkHandle(),
                )
        }
    }
    return results
}

private fun HomeViewModel.recordSmartProfileMetricsProbeOutcome(
    profileId: Long,
    result: AutoConnectProbeResult,
) {
    if (!result.success) {
        markProtocolDown(profileId = profileId, optionId = result.candidate.optionId)
        return
    }
    val displayLatencyMs = result.displayLatencyMs
    if (displayLatencyMs != null) {
        cacheProtocolLatency(
            profileId = profileId,
            optionId = result.candidate.optionId,
            latencyMs = displayLatencyMs,
        )
    } else {
        markProtocolLatencyUnavailable(
            profileId = profileId,
            optionId = result.candidate.optionId,
        )
    }
}

// Ends the refresh with either a "connect to the faster protocol" recommendation banner or the
// plain refreshed toast when the selected option is already the best one.
private suspend fun HomeViewModel.announceSmartProfileMetricsOutcome(
    profileId: Long,
    recommendedIds: List<String>,
    results: List<AutoConnectProbeResult>,
    selectedOptionId: String?,
) {
    val winner =
        recommendedIds
            .asSequence()
            .mapNotNull { optionId ->
                results.firstOrNull { result -> result.success && result.candidate.optionId == optionId }
            }.firstOrNull()
    if (winner != null && winner.candidate.optionId != selectedOptionId) {
        val recommendationDurationMs = 8_000L
        recommendedProtocolMutable.value =
            ProtocolRecommendationState(
                profileId = profileId,
                optionId = winner.candidate.optionId,
                displayName = winner.candidate.displayName,
            )
        snackbars.emit(
            FoxholeBannerEvent(
                message =
                getApplication<Application>().getString(
                    R.string.protocol_metrics_recommendation,
                    winner.candidate.displayName,
                ),
                tone = FoxholeBannerTone.INFO,
                actionLabel = getApplication<Application>().getString(R.string.connect),
                action = FoxholeBannerAction.ACCEPT_PROTOCOL_RECOMMENDATION,
                durationMillis = recommendationDurationMs,
                expiresAtElapsedMs = SystemClock.elapsedRealtime() + recommendationDurationMs,
            ),
        )
    } else {
        emitSuccess(getApplication<Application>().getString(R.string.protocol_metrics_refreshed))
    }
}

internal fun HomeViewModel.cancelSmartProfileMetricsRefresh(restoreConnection: Boolean = true) {
    protocolMetricsRestoreOnCancel = restoreConnection
    if (!restoreConnection) {
        protocolMetricsRefreshingProfileIdsMutable.value = emptySet()
        protocolMetricsRefreshingOptionIdByProfileIdMutable.value = emptyMap()
        setDashboardConnectionMetricsLoading(false)
        clearAutoConnectUiState()
    }
    val runningJob = protocolMetricsRefreshJob
    protocolMetricsRefreshJob = null
    runningJob?.cancel()
}

private suspend fun HomeViewModel.probeAutoConnectCandidateForMetricsRefresh(
    profileId: Long,
    candidate: AutoConnectProbeCandidate,
    networkFingerprint: String?,
    previousVpnNetworkHandle: Long?,
): AutoConnectProbeResult {
    val startedAt = SystemClock.elapsedRealtime()
    val timeoutMs = protocolMetricsCandidateProbeTimeoutMs()
    val result =
        withTimeoutOrNull(timeoutMs) {
            probeAutoConnectCandidate(
                profileId = profileId,
                candidate = candidate,
                networkFingerprint = networkFingerprint,
                previousVpnNetworkHandle = previousVpnNetworkHandle,
            )
        }
    val resolvedResult =
        result ?: connectedAutoConnectFallbackResult(
            profileId = profileId,
            candidate = candidate,
            networkFingerprint = networkFingerprint,
            startedAtElapsedMs = startedAt,
            timeoutMs = timeoutMs,
        )
            ?: protocolMetricsProbeTimeoutResult(
                profileId = profileId,
                candidate = candidate,
                startedAtElapsedMs = startedAt,
                timeoutMs = timeoutMs,
            )
    val serverPingMs =
        if (resolvedResult.success) {
            refreshActiveServerTcpPingForMetrics(
                profileId = profileId,
                candidate = candidate,
                networkFingerprint = networkFingerprint,
            )
        } else {
            null
        }
    return resolvedResult.copy(serverPingMs = serverPingMs)
}

private suspend fun HomeViewModel.refreshActiveServerTcpPingForMetrics(
    profileId: Long,
    candidate: AutoConnectProbeCandidate,
    networkFingerprint: String?,
): Long? =
    runCatchingUnlessCancelled {
        container.connectionController.measureCurrentVpnServerPing(
            profileId = profileId,
            protocolOptionId = candidate.optionId,
        )
    }.onSuccess { pingMs ->
        cacheProtocolServerPingInternal(
            profileId = profileId,
            optionId = candidate.optionId,
            pingMs = pingMs,
        )
        container.settingsRepository.recordSmartProfileServerPing(
            profileId = profileId,
            optionId = candidate.optionId,
            serverPingMs = pingMs,
            networkFingerprint = networkFingerprint,
        )
        recordProtocolMetricEventInternal(
            profileId = profileId,
            optionId = candidate.optionId,
            kind = ProtocolMetricEventKind.SERVER_PING,
            latencyMs = pingMs,
            protocol = candidate.protocolHint.name.lowercase(),
        )
        container.diagnosticsLogger.record(
            "latency",
            "server tcp ping refreshed option=${candidate.optionId} server_tcp_ping_ms=$pingMs",
        )
    }.onFailure { error ->
        markProtocolServerPingUnavailableInternal(
            profileId = profileId,
            optionId = candidate.optionId,
        )
        container.diagnosticsLogger.record(
            "latency",
            "server tcp ping unavailable option=${candidate.optionId}: ${error.message.orEmpty()}",
        )
    }.getOrNull()

private fun HomeViewModel.protocolMetricsProbeTimeoutResult(
    profileId: Long,
    candidate: AutoConnectProbeCandidate,
    startedAtElapsedMs: Long,
    timeoutMs: Long,
): AutoConnectProbeResult {
    val elapsedMs = (SystemClock.elapsedRealtime() - startedAtElapsedMs).coerceAtLeast(1L)
    runCatching {
        container.connectionController.disconnect(
            suppressLocalGuard = true,
            preserveSmartStartAnalysis = true,
            userInitiated = false,
        )
    }
    val reasonCode =
        classifyAutoConnectProbeFailure(
            snapshot = null,
            timedOut = true,
            vpnNetworkAvailable = container.connectionController.hasActiveVpnNetwork(),
            dnsFailureMessage = getApplication<Application>().getString(R.string.error_dns_probe_failed),
        )
    container.diagnosticsLogger.recordStructured(
        "auto-connect",
        "manual metrics probe timed out",
        "profile_id=$profileId",
        "option=${candidate.optionId}",
        "protocol=${candidate.protocolHint.name.lowercase()}",
        "timeout_ms=$timeoutMs",
    )
    return AutoConnectProbeResult(
        candidate = candidate,
        success = false,
        latencyMs = elapsedMs,
        rankingLatencyMs = elapsedMs,
        displayLatencyMs = null,
        connectDurationMs = elapsedMs,
        failureReason = autoConnectFailureMessage(reasonCode, null),
        reasonCode = reasonCode,
    )
}

private fun HomeViewModel.connectedAutoConnectFallbackResult(
    profileId: Long,
    candidate: AutoConnectProbeCandidate,
    networkFingerprint: String?,
    startedAtElapsedMs: Long,
    timeoutMs: Long,
): AutoConnectProbeResult? {
    val snapshot = container.connectionController.snapshot.value
    return when {
        snapshot.state != ConnectionState.CONNECTED || snapshot.profileId != profileId -> null
        !snapshot.matchesAutoConnectCandidate(candidate) -> {
            container.diagnosticsLogger.recordStructured(
                "auto-connect",
                "connected fallback ignored because runtime candidate changed",
                "profile_id=$profileId",
                "expected_option=${candidate.optionId}",
                "expected_protocol=${candidate.protocolHint.name.lowercase()}",
                "actual_option=${snapshot.protocolOptionId ?: "none"}",
                "actual_protocol=${snapshot.protocolHint?.name?.lowercase() ?: "none"}",
            )
            null
        }
        else ->
            buildConnectedAutoConnectFallbackResult(
                profileId = profileId,
                candidate = candidate,
                networkFingerprint = networkFingerprint,
                startedAtElapsedMs = startedAtElapsedMs,
                timeoutMs = timeoutMs,
            )
    }
}

private fun ConnectionSnapshot.matchesAutoConnectCandidate(candidate: AutoConnectProbeCandidate): Boolean =
    isExactSmartStartRuntimeOption(
        actualProtocolOptionId = protocolOptionId,
        expectedProtocolOptionId = candidate.optionId,
    )

internal fun isExactSmartStartRuntimeOption(
    actualProtocolOptionId: String?,
    expectedProtocolOptionId: String,
): Boolean =
    actualProtocolOptionId == expectedProtocolOptionId

private fun HomeViewModel.buildConnectedAutoConnectFallbackResult(
    profileId: Long,
    candidate: AutoConnectProbeCandidate,
    networkFingerprint: String?,
    startedAtElapsedMs: Long,
    timeoutMs: Long,
): AutoConnectProbeResult {
    val elapsedMs = (SystemClock.elapsedRealtime() - startedAtElapsedMs).coerceAtLeast(1L)
    val outcomeRecordedAt = System.currentTimeMillis()
    val rememberedLatencyMs =
        rememberedAutoConnectLatency(
            profileId = profileId,
            optionId = candidate.optionId,
            networkFingerprint = networkFingerprint,
        )
    container.diagnosticsLogger.recordStructured(
        "auto-connect",
        "candidate connected before post-validation metrics timeout",
        "profile_id=$profileId",
        "option=${candidate.optionId}",
        "protocol=${candidate.protocolHint.name.lowercase()}",
        "timeout_ms=$timeoutMs",
    )
    return AutoConnectProbeResult(
        candidate = candidate,
        success = true,
        latencyMs = elapsedMs,
        rankingLatencyMs =
        resolveAutoConnectFallbackRankingLatency(
            validatedConnectDurationMs = elapsedMs,
            rememberedLatencyMs = rememberedLatencyMs,
            penaltyMs = HomeViewModel.AUTO_CONNECT_LATENCY_FALLBACK_PENALTY_MS,
            protocolHint = candidate.protocolHint,
            latencyProbeMethod = controlUiState.value.settings.connection.latencyProbeMethod,
        ),
        displayLatencyMs = null,
        connectDurationMs = elapsedMs,
        validatedAt = outcomeRecordedAt,
        trafficObservedAt =
        currentTrafficObservedAt(
            traffic = container.connectionController.traffic.value,
            fallbackAt = outcomeRecordedAt,
        ),
        reasonCode = AutoConnectReasonCode.LATENCY_ENDPOINT_BLOCKED,
    )
}

private suspend fun HomeViewModel.restoreConnectionAfterMetricsRefresh(
    restoreProfileId: Long?,
    restoreOptionId: String?,
) {
    val previousVpnNetworkHandle =
        awaitDisconnectedForAutoConnect(
            container.connectionController.currentVpnNetworkHandle(),
        )
    if (restoreProfileId != null) {
        // The metrics-refresh scan just force-disconnected and rapid-fire re-probed every candidate
        // option, including this one -- a transient hiccup during that forced probe can leave this
        // exact option marked "down" even though the connection restored below is genuinely fine.
        // Clear that stale flag so the UI doesn't show a false red/disabled status for it.
        if (restoreOptionId != null) {
            profileOptionDownMutable.value -= ProfileOptionLatencyKey(restoreProfileId, restoreOptionId)
        }
        connectNow(
            profileId = restoreProfileId,
            protocolOptionId = restoreOptionId,
            previousVpnNetworkHandle = previousVpnNetworkHandle,
        )
    }
}

/**
 * Dismiss/expiry of the recommendation offer. The measurement it came from ages out, so once the
 * banner is gone the armed state must go with it instead of lingering for the rest of the session.
 */
internal fun HomeViewModel.onProtocolRecommendationDismissed() {
    recommendedProtocolMutable.value = null
}

internal fun HomeViewModel.onProtocolRecommendationAccepted() {
    val recommendation = recommendedProtocolMutable.value ?: return
    recommendedProtocolMutable.value = null
    cancelAutoConnect(clearUiOnly = true)
    cancelSmartProfileMetricsRefresh(restoreConnection = false)
    viewModelScope.launch {
        runCatching {
            container.profileRepository.selectProfileProtocolOption(
                profileId = recommendation.profileId,
                optionId = recommendation.optionId,
            )
            requestReconnect(recommendation.profileId)
        }.onFailure { error ->
            emitError(
                getApplication<Application>().userFacingErrorMessage(
                    error,
                    R.string.profile_update_failed,
                ),
            )
        }
    }
}
