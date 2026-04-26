package com.foxhole.beta.ui

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.viewModelScope
import com.foxhole.beta.R
import com.foxhole.beta.core.model.AutoConnectReasonCode
import com.foxhole.beta.core.model.ConnectionSnapshot
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.Profile
import com.foxhole.beta.core.model.ProtocolHint
import com.foxhole.beta.core.model.TrafficSnapshot
import com.foxhole.beta.core.model.TrafficMode
import com.foxhole.beta.core.network.NetworkFingerprint
import com.foxhole.beta.core.profile.AutoConnectProbeCandidate
import com.foxhole.beta.core.profile.AutoConnectProbeResult
import com.foxhole.beta.core.profile.MultiProtocolProfileSupport
import com.foxhole.beta.core.profile.classifyAutoConnectProbeFailure
import com.foxhole.beta.core.smart.AdaptiveProtocolCandidateScore
import com.foxhole.beta.core.smart.AdaptiveProtocolRanker
import com.foxhole.beta.core.smart.SmartStartController
import com.foxhole.beta.core.smart.SmartStartReplayEvent
import com.foxhole.beta.core.settings.needsSmartStartColdScan
import com.foxhole.beta.core.settings.networkMemory
import com.foxhole.beta.core.settings.smartProfilePreference
import com.foxhole.beta.core.settings.smartStartEnabledProtocolSetHash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
                val fullScanCandidates = fullScanAutoConnectCandidates(profileId, profile)
                require(fullScanCandidates.isNotEmpty()) {
                    getApplication<Application>().getString(R.string.auto_connect_requires_multi_protocol_profile)
                }
                val enabledProtocolSetHash = smartStartEnabledProtocolSetHash(fullScanCandidates.map(AutoConnectProbeCandidate::optionId))
                val preference = uiState.value.settings.smartProfilePreference(profileId)
                if (preference?.needsSmartStartColdScan(enabledProtocolSetHash) != false) {
                    runColdSmartStartScan(
                        profileId = profileId,
                        profile = profile,
                        networkFingerprint = autoConnectNetworkFingerprint,
                        candidates = fullScanCandidates,
                        enabledProtocolSetHash = enabledProtocolSetHash,
                    )
                    return@launch
                }
                val rankedCandidates =
                    scoredAutoConnectCandidatesInternal(profileId, profile, autoConnectNetworkFingerprint)
                val recommendedIds = preference.recommendedProtocolIds
                val attempts =
                    smartStartAttemptCandidates(
                        rankedCandidates = rankedCandidates,
                        recommendedIds = recommendedIds,
                    )
                require(attempts.isNotEmpty()) {
                    getApplication<Application>().getString(R.string.auto_connect_requires_multi_protocol_profile)
                }
                logAdaptiveAutoConnectRanking(
                    profileId = profileId,
                    rankedCandidates = rankedCandidates,
                    networkFingerprint = autoConnectNetworkFingerprint,
                )
                runFastSmartStartAttempts(
                    profileId = profileId,
                    networkFingerprint = autoConnectNetworkFingerprint,
                    candidates = attempts.map(AdaptiveProtocolCandidateScore::candidate),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                container.diagnosticsLogger.record("auto-connect", "smart start failed: ${error.message.orEmpty()}")
                emitError(getApplication<Application>().getString(R.string.auto_connect_failed))
            } finally {
                autoConnectJob = null
                delay(HomeViewModel.AUTO_CONNECT_RESULT_SETTLE_MS)
                clearAutoConnectUiState()
            }
        }
}

private suspend fun HomeViewModel.runFastSmartStartAttempts(
    profileId: Long,
    networkFingerprint: NetworkFingerprint?,
    candidates: List<AutoConnectProbeCandidate>,
) {
    initializeAutoConnectUi(candidates.take(HomeViewModel.AUTO_CONNECT_MAX_ATTEMPTS))
    var previousVpnNetworkHandle = awaitDisconnectedForAutoConnect()
    val autoConnectStartedAt = SystemClock.elapsedRealtime()
    candidates.take(HomeViewModel.AUTO_CONNECT_MAX_ATTEMPTS).forEach { candidate ->
        val probe = probeSmartStartCandidateWithinBudget(
            profileId = profileId,
            candidate = candidate,
            networkFingerprint = networkFingerprint?.key,
            previousVpnNetworkHandle = previousVpnNetworkHandle,
            autoConnectStartedAt = autoConnectStartedAt,
        )
        recordAutoConnectCandidateOutcome(
            profileId = profileId,
            result = probe.result,
            networkFingerprint = networkFingerprint?.key,
            headline = autoConnectProbeHeadline(probe.result),
        )
        markAutoConnectCandidateFinished(probe.result)
        if (probe.timedOut) {
            awaitDisconnectedForAutoConnect(container.connectionController.currentVpnNetworkHandle())
            emitError(getApplication<Application>().getString(R.string.auto_connect_failed))
            return
        }
        if (!probe.result.success) {
            previousVpnNetworkHandle =
                awaitDisconnectedForAutoConnect(
                    container.connectionController.currentVpnNetworkHandle(),
                )
            return@forEach
        }
        commitAutoConnectWinner(
            profileId = profileId,
            result = probe.result,
            networkFingerprint = networkFingerprint?.key,
        )
        return
    }
    emitError(getApplication<Application>().getString(R.string.auto_connect_failed))
}

private suspend fun HomeViewModel.runColdSmartStartScan(
    profileId: Long,
    profile: Profile,
    networkFingerprint: NetworkFingerprint?,
    candidates: List<AutoConnectProbeCandidate>,
    enabledProtocolSetHash: String,
) {
    val initialScores = rankedScoresForCandidates(profileId, candidates, networkFingerprint)
    logAdaptiveAutoConnectRanking(
        profileId = profileId,
        rankedCandidates = initialScores,
        networkFingerprint = networkFingerprint,
    )
    initializeAutoConnectUi(candidates)
    var previousVpnNetworkHandle = awaitDisconnectedForAutoConnect()
    val autoConnectStartedAt = SystemClock.elapsedRealtime()
    val results = mutableListOf<AutoConnectProbeResult>()
    var completedFullScan = true
    for (candidate in candidates) {
        val remainingBudgetMs =
            remainingAutoConnectBudgetMs(
                startedAtElapsedMs = autoConnectStartedAt,
                nowElapsedMs = SystemClock.elapsedRealtime(),
                totalTimeoutMs = HomeViewModel.AUTO_CONNECT_TOTAL_TIMEOUT_MS,
            )
        if (remainingBudgetMs <= 0L) {
            completedFullScan = false
            break
        }
        val probe =
            probeSmartStartCandidateWithinBudget(
                profileId = profileId,
                candidate = candidate,
                networkFingerprint = networkFingerprint?.key,
                previousVpnNetworkHandle = previousVpnNetworkHandle,
                autoConnectStartedAt = autoConnectStartedAt,
            )
        results += probe.result
        recordAutoConnectCandidateOutcome(
            profileId = profileId,
            result = probe.result,
            networkFingerprint = networkFingerprint?.key,
            headline = if (probe.timedOut) "cold scan candidate timed out" else "cold scan candidate probed",
        )
        markAutoConnectCandidateFinished(probe.result)
        previousVpnNetworkHandle =
            awaitDisconnectedForAutoConnect(
                container.connectionController.currentVpnNetworkHandle(),
            )
        if (probe.timedOut) {
            completedFullScan = false
            break
        }
    }
    val recommendedIds =
        recomputeRecommendedProtocolIds(
            profileId = profileId,
            candidates = candidates,
            networkFingerprint = networkFingerprint,
        )
    if (completedFullScan && recommendedIds.isNotEmpty()) {
        container.settingsRepository.recordSmartProfileBaseline(
            profileId = profileId,
            recommendedProtocolIds = recommendedIds,
            enabledProtocolSetHash = enabledProtocolSetHash,
        )
        updateRecommendedProtocolUi(profileId, profile, recommendedIds)
    }
    val winner =
        recommendedIds
            .asSequence()
            .mapNotNull { optionId ->
                results.firstOrNull { result -> result.success && result.candidate.optionId == optionId }
            }.firstOrNull()
            ?: MultiProtocolProfileSupport.fastestSuccessfulProbe(results)
    if (winner == null) {
        emitError(getApplication<Application>().getString(R.string.auto_connect_failed))
        return
    }
    val finalProbe =
        probeSmartStartCandidateWithinBudget(
            profileId = profileId,
            candidate = winner.candidate,
            networkFingerprint = networkFingerprint?.key,
            previousVpnNetworkHandle = previousVpnNetworkHandle,
            autoConnectStartedAt = autoConnectStartedAt,
        )
    recordAutoConnectCandidateOutcome(
        profileId = profileId,
        result = finalProbe.result,
        networkFingerprint = networkFingerprint?.key,
        headline = if (finalProbe.timedOut) "cold scan winner timed out" else autoConnectProbeHeadline(finalProbe.result),
    )
    markAutoConnectCandidateFinished(finalProbe.result)
    if (!finalProbe.result.success) {
        emitError(getApplication<Application>().getString(R.string.auto_connect_failed))
        return
    }
    commitAutoConnectWinner(
        profileId = profileId,
        result = finalProbe.result,
        networkFingerprint = networkFingerprint?.key,
    )
}

private suspend fun HomeViewModel.probeSmartStartCandidateWithinBudget(
    profileId: Long,
    candidate: AutoConnectProbeCandidate,
    networkFingerprint: String?,
    previousVpnNetworkHandle: Long?,
    autoConnectStartedAt: Long,
): BudgetedAutoConnectProbe {
    markAutoConnectCandidateTesting(candidate)
    delay(HomeViewModel.AUTO_CONNECT_PROTOCOL_TRANSITION_SETTLE_MS)
    val probeStartedAt = SystemClock.elapsedRealtime()
    val probeBudgetMs =
        minOf(
            HomeViewModel.PROTOCOL_METRICS_PROBE_TIMEOUT_MS,
            remainingAutoConnectBudgetMs(
                startedAtElapsedMs = autoConnectStartedAt,
                nowElapsedMs = probeStartedAt,
                totalTimeoutMs = HomeViewModel.AUTO_CONNECT_TOTAL_TIMEOUT_MS,
            ),
        )
    if (probeBudgetMs <= 0L) {
        runCatching { container.connectionController.disconnect() }
        return BudgetedAutoConnectProbe(
            result =
                autoConnectWallClockTimeoutResult(
                    candidate = candidate,
                    startedAtElapsedMs = probeStartedAt,
                ),
            timedOut = true,
        )
    }
    val result =
        withTimeoutOrNull(probeBudgetMs) {
            probeAutoConnectCandidate(
                profileId = profileId,
                candidate = candidate,
                networkFingerprint = networkFingerprint,
                previousVpnNetworkHandle = previousVpnNetworkHandle,
            )
        } ?: run {
            runCatching { container.connectionController.disconnect() }
            return BudgetedAutoConnectProbe(
                result =
                    autoConnectWallClockTimeoutResult(
                        candidate = candidate,
                        startedAtElapsedMs = probeStartedAt,
                    ),
                timedOut = true,
            )
        }
    return BudgetedAutoConnectProbe(result = result, timedOut = false)
}

private suspend fun HomeViewModel.commitAutoConnectWinner(
    profileId: Long,
    result: AutoConnectProbeResult,
    networkFingerprint: String?,
) {
    markAutoConnectWinner(result)
    val currentProfile = container.profileRepository.getProfile(profileId)
    if (currentProfile?.selectedProtocolOptionId != result.candidate.optionId) {
        container.profileRepository.selectProfileProtocolOption(profileId, result.candidate.optionId)
    }
    result.displayLatencyMs?.let { latencyMs ->
        cacheProtocolLatency(
            profileId = profileId,
            optionId = result.candidate.optionId,
            latencyMs = latencyMs,
        )
    } ?: markProtocolLatencyUnavailable(
        profileId = profileId,
        optionId = result.candidate.optionId,
    )
    val committedAt = System.currentTimeMillis()
    recordAutoConnectCandidateOutcome(
        profileId = profileId,
        result =
            result.copy(
                validatedAt = committedAt,
                trafficObservedAt =
                    currentTrafficObservedAt(
                        traffic = container.connectionController.traffic.value,
                        fallbackAt = committedAt,
                    ) ?: result.trafficObservedAt,
            ),
        networkFingerprint = networkFingerprint,
        headline = "winner committed",
        markAsLastKnownGood = true,
        countTowardOutcomeHistory = false,
    )
    emitSuccess(
        result.displayLatencyMs?.let { latencyMs ->
            getApplication<Application>().getString(
                R.string.auto_connect_success,
                result.candidate.displayName,
                latencyMs,
            )
        } ?: getApplication<Application>().getString(
            R.string.auto_connect_success_unavailable,
            result.candidate.displayName,
        ),
    )
}

private fun HomeViewModel.fullScanAutoConnectCandidates(
    profileId: Long,
    profile: Profile,
): List<AutoConnectProbeCandidate> =
    MultiProtocolProfileSupport.smartStartFullScanCandidates(
        profile = profile,
        allowInsecureTlsGlobally = uiState.value.settings.expert.allowInsecureTls,
        excludedOptionIds = excludedAutoConnectOptionIds(profileId),
    )

private fun HomeViewModel.rankedScoresForCandidates(
    profileId: Long,
    candidates: List<AutoConnectProbeCandidate>,
    networkFingerprint: NetworkFingerprint?,
): List<AdaptiveProtocolCandidateScore> =
    AdaptiveProtocolRanker.scoreCandidates(
        candidates = candidates,
        preference = uiState.value.settings.smartProfilePreference(profileId),
        networkFingerprintKey = networkFingerprint?.key,
        networkContext = networkFingerprint,
    )

private suspend fun HomeViewModel.recomputeRecommendedProtocolIds(
    profileId: Long,
    candidates: List<AutoConnectProbeCandidate>,
    networkFingerprint: NetworkFingerprint?,
): List<String> {
    val preference = container.settingsRepository.current().smartProfilePreference(profileId)
    val ranked =
        AdaptiveProtocolRanker.scoreCandidates(
            candidates = candidates,
            preference = preference,
            networkFingerprintKey = networkFingerprint?.key,
            networkContext = networkFingerprint,
        )
    logAdaptiveAutoConnectRanking(
        profileId = profileId,
        rankedCandidates = ranked,
        networkFingerprint = networkFingerprint,
    )
    return SmartStartController.recommendedTopCandidateIds(ranked)
}

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

private fun smartStartAttemptCandidates(
    rankedCandidates: List<AdaptiveProtocolCandidateScore>,
    recommendedIds: List<String>,
): List<AdaptiveProtocolCandidateScore> {
    val scoresById = rankedCandidates.associateBy { score -> score.candidate.optionId }
    val recommended =
        recommendedIds
            .mapNotNull(scoresById::get)
            .distinctBy { score -> score.candidate.optionId }
    val fallback =
        rankedCandidates.filterNot { score ->
            recommended.any { selected -> selected.candidate.optionId == score.candidate.optionId }
        }
    return (recommended + fallback)
        .distinctBy { score -> score.candidate.optionId }
        .take(HomeViewModel.AUTO_CONNECT_MAX_ATTEMPTS)
}

private fun autoConnectProbeHeadline(result: AutoConnectProbeResult): String =
    when {
        result.success && result.reasonCode == AutoConnectReasonCode.LATENCY_ENDPOINT_BLOCKED ->
            "candidate ok with fallback"
        result.success -> "candidate ok"
        else -> "candidate failed"
    }

private data class BudgetedAutoConnectProbe(
    val result: AutoConnectProbeResult,
    val timedOut: Boolean,
)

internal fun HomeViewModel.refreshSmartProfileMetricsInternal(profileId: Long) {
    protocolMetricsRefreshJob?.cancel()
    protocolMetricsRestoreOnCancel = true
    protocolMetricsRefreshJob =
        viewModelScope.launch {
            var initiallyActive = false
            var selectedOptionId: String? = null
            var restoredConnection = false
            var restoreFailure = false
            try {
                initiallyActive =
                    container.connectionController.snapshot.value.state in HomeViewModel.ACTIVE_CONNECTION_STATES &&
                    container.connectionController.snapshot.value.profileId == profileId
                val profile = container.profileRepository.getProfile(profileId) ?: error("profile not found")
                val networkFingerprint = container.networkFingerprintProvider.currentFingerprint()
                val candidates = fullScanAutoConnectCandidates(profileId, profile)
                require(candidates.isNotEmpty()) {
                    getApplication<Application>().getString(R.string.auto_connect_requires_multi_protocol_profile)
                }
                val enabledProtocolSetHash = smartStartEnabledProtocolSetHash(candidates.map(AutoConnectProbeCandidate::optionId))
                selectedOptionId = resolveDashboardLatencyOptionId(profile)
                recommendedProtocolMutable.value = null
                protocolMetricsRefreshingProfileIdsMutable.value =
                    protocolMetricsRefreshingProfileIdsMutable.value + profileId
                initializeAutoConnectUi(candidates)
                var previousVpnNetworkHandle = awaitDisconnectedForAutoConnect()
                val results = mutableListOf<AutoConnectProbeResult>()
                candidates.forEachIndexed { index, candidate ->
                    markAutoConnectCandidateTesting(candidate)
                    delay(HomeViewModel.AUTO_CONNECT_PROTOCOL_TRANSITION_SETTLE_MS)
                    val result =
                        probeAutoConnectCandidateForMetricsRefresh(
                            profileId = profileId,
                            candidate = candidate,
                            networkFingerprint = networkFingerprint?.key,
                            previousVpnNetworkHandle = previousVpnNetworkHandle,
                        )
                    results += result
                    recordAutoConnectCandidateOutcome(
                        profileId = profileId,
                        result = result,
                        networkFingerprint = networkFingerprint?.key,
                        headline = if (result.success) "manual metrics probe ok" else "manual metrics probe failed",
                        countTowardOutcomeHistory = false,
                        affectsFailureRankingMemory = false,
                    )
                    markAutoConnectCandidateFinished(result)
                    if (index < candidates.lastIndex) {
                        previousVpnNetworkHandle =
                            awaitDisconnectedForAutoConnect(
                                container.connectionController.currentVpnNetworkHandle(),
                        )
                    }
                }
                val recommendedIds =
                    recomputeRecommendedProtocolIds(
                        profileId = profileId,
                        candidates = candidates,
                        networkFingerprint = networkFingerprint,
                    )
                if (recommendedIds.isNotEmpty()) {
                    container.settingsRepository.recordSmartProfileBaseline(
                        profileId = profileId,
                        recommendedProtocolIds = recommendedIds,
                        enabledProtocolSetHash = enabledProtocolSetHash,
                    )
                    updateRecommendedProtocolUi(profileId, profile, recommendedIds)
                }
                restoreConnectionAfterMetricsRefresh(
                    profileId = profileId,
                    selectedOptionId = selectedOptionId,
                    initiallyActive = initiallyActive,
                )
                restoredConnection = true
                val winner =
                    recommendedIds
                        .asSequence()
                        .mapNotNull { optionId ->
                            results.firstOrNull { result -> result.success && result.candidate.optionId == optionId }
                        }.firstOrNull()
                        ?: MultiProtocolProfileSupport.fastestSuccessfulProbe(results)
                if (winner != null && winner.candidate.optionId != selectedOptionId) {
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
                            durationMillis = 8_000L,
                        ),
                    )
                } else {
                    emitSuccess(getApplication<Application>().getString(R.string.protocol_metrics_refreshed))
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    if (protocolMetricsRestoreOnCancel) {
                        restoredConnection =
                            runCatching {
                                restoreConnectionAfterMetricsRefresh(
                                    profileId = profileId,
                                    selectedOptionId = selectedOptionId,
                                    initiallyActive = initiallyActive && !restoredConnection,
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
                    if (protocolMetricsRestoreOnCancel && initiallyActive && !restoredConnection) {
                        restoreFailure =
                            runCatching {
                                restoreConnectionAfterMetricsRefresh(
                                    profileId = profileId,
                                    selectedOptionId = selectedOptionId,
                                    initiallyActive = true,
                                )
                            }.isFailure
                    }
                    if (restoreFailure) {
                        emitError(getApplication<Application>().getString(R.string.protocol_metrics_restore_failed))
                    }
                    protocolMetricsRefreshingProfileIdsMutable.value =
                        protocolMetricsRefreshingProfileIdsMutable.value - profileId
                    protocolMetricsRefreshJob = null
                    protocolMetricsRestoreOnCancel = true
                    delay(HomeViewModel.AUTO_CONNECT_RESULT_SETTLE_MS)
                    clearAutoConnectUiState()
                }
            }
        }
}

internal fun HomeViewModel.cancelSmartProfileMetricsRefreshInternal(restoreConnection: Boolean) {
    protocolMetricsRestoreOnCancel = restoreConnection
    protocolMetricsRefreshJob?.cancel()
}

private suspend fun HomeViewModel.probeAutoConnectCandidateForMetricsRefresh(
    profileId: Long,
    candidate: AutoConnectProbeCandidate,
    networkFingerprint: String?,
    previousVpnNetworkHandle: Long?,
): AutoConnectProbeResult {
    val startedAt = SystemClock.elapsedRealtime()
    val result =
        withTimeoutOrNull(HomeViewModel.PROTOCOL_METRICS_PROBE_TIMEOUT_MS) {
            probeAutoConnectCandidate(
                profileId = profileId,
                candidate = candidate,
                networkFingerprint = networkFingerprint,
                previousVpnNetworkHandle = previousVpnNetworkHandle,
            )
        }
    if (result != null) {
        return result
    }
    val elapsedMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(1L)
    runCatching { container.connectionController.disconnect() }
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
        "timeout_ms=${HomeViewModel.PROTOCOL_METRICS_PROBE_TIMEOUT_MS}",
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

private fun HomeViewModel.autoConnectWallClockTimeoutResult(
    candidate: AutoConnectProbeCandidate,
    startedAtElapsedMs: Long,
): AutoConnectProbeResult {
    val elapsedMs = (SystemClock.elapsedRealtime() - startedAtElapsedMs).coerceAtLeast(1L)
    val reasonCode =
        classifyAutoConnectProbeFailure(
            snapshot = null,
            timedOut = true,
            vpnNetworkAvailable = container.connectionController.hasActiveVpnNetwork(),
            dnsFailureMessage = getApplication<Application>().getString(R.string.error_dns_probe_failed),
        )
    container.diagnosticsLogger.recordStructured(
        "auto-connect",
        "auto-connect wall clock timed out",
        "option=${candidate.optionId}",
        "protocol=${candidate.protocolHint.name.lowercase()}",
        "timeout_ms=${HomeViewModel.AUTO_CONNECT_TOTAL_TIMEOUT_MS}",
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

private suspend fun HomeViewModel.restoreConnectionAfterMetricsRefresh(
    profileId: Long,
    selectedOptionId: String?,
    initiallyActive: Boolean,
) {
    val previousVpnNetworkHandle =
        awaitDisconnectedForAutoConnect(
            container.connectionController.currentVpnNetworkHandle(),
        )
    if (initiallyActive) {
        connectNow(
            profileId = profileId,
            protocolOptionId = selectedOptionId,
            previousVpnNetworkHandle = previousVpnNetworkHandle,
        )
    }
}

internal fun HomeViewModel.onProtocolRecommendationAcceptedInternal() {
    val recommendation = recommendedProtocolMutable.value ?: return
    recommendedProtocolMutable.value = null
    viewModelScope.launch {
        runCatching {
            container.profileRepository.selectProfileProtocolOption(
                profileId = recommendation.profileId,
                optionId = recommendation.optionId,
            )
            requestReconnect(recommendation.profileId)
        }.onFailure { error ->
            emitError(error.message ?: getApplication<Application>().getString(R.string.profile_update_failed))
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
    measureAndCacheProtocolServerPing(profileId, candidate.optionId, networkFingerprint)
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

private suspend fun HomeViewModel.measureAndCacheProtocolServerPing(
    profileId: Long,
    optionId: String,
    networkFingerprint: String? = null,
) {
    val profile = uiState.value.profiles.firstOrNull { it.id == profileId }
    val protocolHint = profile?.protocolOptions?.firstOrNull { option -> option.id == optionId }?.protocolHint
    if (!shouldMeasureProtocolServerPing(protocolHint)) {
        markProtocolServerPingUnavailableInternal(
            profileId = profileId,
            optionId = optionId,
        )
        container.diagnosticsLogger.record("latency", "server ping skipped for udp transport")
        return
    }
    runCatching {
        container.connectionController.measureCurrentVpnServerPing(
            profileId = profileId,
            protocolOptionId = optionId,
        )
    }.onSuccess { pingMs ->
        cacheProtocolServerPingInternal(
            profileId = profileId,
            optionId = optionId,
            pingMs = pingMs,
        )
        container.settingsRepository.recordSmartProfileServerPing(
            profileId = profileId,
            optionId = optionId,
            serverPingMs = pingMs,
            networkFingerprint = networkFingerprint,
        )
    }.onFailure { error ->
        markProtocolServerPingUnavailableInternal(
            profileId = profileId,
            optionId = optionId,
        )
        container.diagnosticsLogger.record("latency", "server ping unavailable: ${error.message.orEmpty()}")
    }
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
    container.diagnosticsLogger.recordSmartStartReplay(
        event =
            SmartStartReplayEvent(
                timestamp = System.currentTimeMillis(),
                profileId = profileId,
                optionId = result.candidate.optionId,
                protocol = result.candidate.protocolHint.name.lowercase(),
                outcome = if (result.success) "ok" else "down",
                rankingLatencyMs = result.rankingLatencyMs,
                connectDurationMs = result.connectDurationMs,
                reasonCode = result.reasonCode?.wireCode,
                networkFingerprintPrefix = networkFingerprint?.take(12),
            ),
        enabled = uiState.value.settings.expert.smartStartReplayLogging,
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
    return MultiProtocolProfileSupport.scoredProbeCandidates(
        profile = profile,
        preference = uiState.value.settings.smartProfilePreference(profileId),
        networkFingerprint = networkFingerprint?.key,
        networkContext = networkFingerprint,
        allowInsecureTlsGlobally = uiState.value.settings.expert.allowInsecureTls,
        excludedOptionIds = excludedOptionIds,
        controlledExploration = true,
    )
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
    markProtocolMetricsUpdated(profileId, optionId)
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

private fun HomeViewModel.markProtocolMetricsUpdated(
    profileId: Long,
    optionId: String,
    updatedAt: Long = System.currentTimeMillis(),
) {
    profileOptionMetricsUpdatedAtMutable.value =
        profileOptionMetricsUpdatedAtMutable.value + (ProfileOptionLatencyKey(profileId, optionId) to updatedAt)
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
    profileOptionServerPingsMutable.value =
        if (profileId == null && optionId == null) {
            emptyMap()
        } else {
            profileOptionServerPingsMutable.value.filterKeys { key -> !matches(key) }
        }
    profileOptionMetricsUpdatedAtMutable.value =
        if (profileId == null && optionId == null) {
            emptyMap()
        } else {
            profileOptionMetricsUpdatedAtMutable.value.filterKeys { key -> !matches(key) }
        }
}

internal fun HomeViewModel.scheduleActiveProfileLatencyRefreshInternal() {
    val activeProfile = uiState.value.activeProfile ?: return
    val selectedOptionId = resolveDashboardLatencyOptionId(activeProfile) ?: return
    val selectedProtocolHint =
        activeProfile.protocolOptions
            .firstOrNull { option -> option.id == selectedOptionId }
            ?.protocolHint
    profileLatencyRefreshJob?.cancel()
    clearProtocolLatencyState(
        profileId = activeProfile.id,
        optionId = selectedOptionId,
    )
    profileLatencyRefreshJob =
        viewModelScope.launch {
            try {
                var nextDelayMs = HomeViewModel.CONNECTED_PROTOCOL_LATENCY_REFRESH_DELAY_MS
                while (true) {
                    delay(nextDelayMs)
                    nextDelayMs = HomeViewModel.CONNECTED_PROTOCOL_LATENCY_REFRESH_INTERVAL_MS
                    if (container.connectionController.snapshot.value.state != ConnectionState.CONNECTED || autoConnectUiStateMutable.value.running) {
                        return@launch
                    }
                    val currentProfile = uiState.value.activeProfile ?: return@launch
                    if (currentProfile.id != activeProfile.id) {
                        return@launch
                    }
                    val currentOptionId = resolveDashboardLatencyOptionId(currentProfile) ?: return@launch
                    if (currentOptionId != selectedOptionId) {
                        return@launch
                    }
                    val currentProtocolHint =
                        currentProfile.protocolOptions
                            .firstOrNull { option -> option.id == currentOptionId }
                            ?.protocolHint
                            ?: selectedProtocolHint
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
                    if (!shouldMeasureProtocolServerPing(currentProtocolHint)) {
                        markProtocolServerPingUnavailableInternal(
                            profileId = activeProfile.id,
                            optionId = selectedOptionId,
                        )
                        container.diagnosticsLogger.record("latency", "dashboard server ping skipped for udp transport")
                        continue
                    }
                    runCatching {
                        container.connectionController.measureCurrentVpnServerPing(
                            profileId = activeProfile.id,
                            protocolOptionId = selectedOptionId,
                        )
                    }.onSuccess { pingMs ->
                        cacheProtocolServerPingInternal(
                            profileId = activeProfile.id,
                            optionId = selectedOptionId,
                            pingMs = pingMs,
                        )
                        container.settingsRepository.recordSmartProfileServerPing(
                            profileId = activeProfile.id,
                            optionId = selectedOptionId,
                            serverPingMs = pingMs,
                            networkFingerprint = container.networkFingerprintProvider.currentFingerprint()?.key,
                        )
                    }.onFailure { error ->
                        markProtocolServerPingUnavailableInternal(
                            profileId = activeProfile.id,
                            optionId = selectedOptionId,
                        )
                        container.diagnosticsLogger.record("latency", "dashboard server ping unavailable: ${error.message.orEmpty()}")
                    }
                }
            } finally {
                profileLatencyRefreshJob = null
            }
        }
}

internal fun HomeViewModel.clearProfileLatencyRefreshInternal() {
    profileLatencyRefreshJob?.cancel()
    profileLatencyRefreshJob = null
}

private fun shouldMeasureProtocolServerPing(protocolHint: ProtocolHint?): Boolean =
    protocolHint !in setOf(ProtocolHint.HYSTERIA2, ProtocolHint.WIREGUARD)

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

internal fun remainingAutoConnectBudgetMs(
    startedAtElapsedMs: Long,
    nowElapsedMs: Long,
    totalTimeoutMs: Long,
): Long {
    require(totalTimeoutMs > 0L) { "totalTimeoutMs must be positive" }
    val elapsedMs = (nowElapsedMs - startedAtElapsedMs).coerceAtLeast(0L)
    return (totalTimeoutMs - elapsedMs).coerceAtLeast(0L)
}

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
                add("schema=${context.schema}")
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
