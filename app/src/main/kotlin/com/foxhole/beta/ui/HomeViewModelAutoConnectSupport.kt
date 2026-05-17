package com.foxhole.beta.ui

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.viewModelScope
import com.foxhole.beta.R
import com.foxhole.beta.core.model.AutoConnectReasonCode
import com.foxhole.beta.core.model.ConnectionSnapshot
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.LatencyProbeMethod
import com.foxhole.beta.core.model.Profile
import com.foxhole.beta.core.model.ProfileSourceType
import com.foxhole.beta.core.model.ProtocolHint
import com.foxhole.beta.core.model.SMART_START_PROTOCOL_TIMEOUT_DEFAULT_SECONDS
import com.foxhole.beta.core.model.SMART_START_PROTOCOL_TIMEOUT_MIN_SECONDS
import com.foxhole.beta.core.model.SMART_START_REFRESH_TIMEOUT_DEFAULT_SECONDS
import com.foxhole.beta.core.model.SMART_START_REFRESH_TIMEOUT_MIN_SECONDS
import com.foxhole.beta.core.model.SMART_START_TIMEOUT_MAX_SECONDS
import com.foxhole.beta.core.model.TrafficMode
import com.foxhole.beta.core.model.TrafficSnapshot
import com.foxhole.beta.core.model.isUdpTransport
import com.foxhole.beta.core.model.runtimeFailureCode
import com.foxhole.beta.core.network.NetworkFingerprint
import com.foxhole.beta.core.profile.AutoConnectProbeCandidate
import com.foxhole.beta.core.profile.AutoConnectProbeResult
import com.foxhole.beta.core.profile.MultiProtocolProfileSupport
import com.foxhole.beta.core.profile.classifyAutoConnectProbeFailure
import com.foxhole.beta.core.settings.networkMemory
import com.foxhole.beta.core.settings.smartProfilePreference
import com.foxhole.beta.core.settings.smartStartEnabledProtocolSetHash
import com.foxhole.beta.core.smart.AdaptiveProtocolCandidateScore
import com.foxhole.beta.core.smart.AdaptiveProtocolRanker
import com.foxhole.beta.core.smart.SmartStartController
import com.foxhole.beta.core.smart.SmartStartReplayEvent
import com.foxhole.beta.vpn.FoxholeConnectionServiceContract
import com.foxhole.beta.vpn.FoxholeVpnService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val SMART_START_SUBSCRIPTION_REFRESH_CALL_TIMEOUT_MS = 4_000L
private const val SMART_START_SUBSCRIPTION_REFRESH_MAX_ATTEMPTS = 20

internal fun HomeViewModel.onAutoConnectActiveProfileInternal() {
    val state = uiState.value
    val profile = mobileNetworkProfileOverride(state) ?: state.activeProfile
    val profileId = profile?.id ?: return
    val availableCandidates =
        profile?.let {
            availableAutoConnectCandidates(it.id, it, currentNetworkFingerprintForSmartRules())
        }.orEmpty()
    if (availableCandidates.isEmpty()) {
        snackbars.tryEmit(infoBanner(R.string.auto_connect_requires_supported_profile))
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
    val previousReconnectJob = reconnectJob
    previousReconnectJob?.cancel()
    val nextReconnectJob = viewModelScope.launch {
        previousReconnectJob?.join()
        reconnectInProgressMutable.value = true
        dashboardConnectionMetricsLoadingMutable.value = true
        try {
            cancelSmartProfileMetricsRefreshInternal(restoreConnection = false)
            if (container.connectionController.snapshot.value.state in HomeViewModel.ACTIVE_CONNECTION_STATES) {
                container.connectionController.disconnect(suppressLocalGuard = true)
                awaitDisconnectedForAutoConnect()
            }
            connectNow(profileId)
            awaitReconnectConnectionOutcome()
        } catch (cancelled: CancellationException) {
            container.diagnosticsLogger.record("connection", "manual reconnect cancelled")
            throw cancelled
        } catch (error: Throwable) {
            dashboardConnectionMetricsLoadingMutable.value = false
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
        container.diagnosticsLogger.record("connection", "manual reconnect status wait timed out")
    }
    return outcome
}

private fun ConnectionSnapshot?.isConnectedSmartStartWinner(
    profileId: Long,
    protocolOptionId: String,
): Boolean =
    this?.state == ConnectionState.CONNECTED &&
        this.profileId == profileId &&
        (this.protocolOptionId == null || this.protocolOptionId == protocolOptionId)

internal fun HomeViewModel.startAutoConnectInternal(profileId: Long) {
    autoConnectJob?.cancel()
    autoConnectUiStateMutable.value = AutoConnectUiState(running = true)
    autoConnectJob =
        viewModelScope.launch {
            var completedSmartStart = false
            try {
                var profile = container.profileRepository.getProfile(profileId) ?: error("profile not found")
                fullScanAutoConnectCandidates(profileId, profile)
                    .take(HomeViewModel.AUTO_CONNECT_MAX_ATTEMPTS)
                    .takeIf(::canStartAutoConnect)
                    ?.let(::initializeAutoConnectUi)
                profile = refreshSubscriptionBeforeSmartStartIfNeeded(profile)
                val autoConnectNetworkFingerprint = currentNetworkFingerprintForSmartRules()
                recommendedProtocolMutable.value = null
                val fullScanCandidates = fullScanAutoConnectCandidates(profileId, profile)
                require(canStartAutoConnect(fullScanCandidates)) {
                    getApplication<Application>().getString(R.string.auto_connect_requires_supported_profile)
                }
                val enabledProtocolSetHash =
                    smartStartEnabledProtocolSetHash(fullScanCandidates.map(AutoConnectProbeCandidate::optionId))
                val rankedCandidates =
                    scoredAutoConnectCandidatesInternal(profileId, profile, autoConnectNetworkFingerprint)
                when (
                    val runnerState =
                        SmartStartAutoConnectRunner.resolveState(
                            fullScanCandidates = fullScanCandidates,
                            enabledProtocolSetHash = enabledProtocolSetHash,
                            preference = uiState.value.settings.smartProfilePreference(profileId),
                            rankedCandidates = rankedCandidates,
                        )
                ) {
                    is SmartStartAutoConnectState.ColdScan ->
                        runColdSmartStartScan(
                            profileId = profileId,
                            profile = profile,
                            networkFingerprint = autoConnectNetworkFingerprint,
                            candidates = runnerState.candidates,
                            enabledProtocolSetHash = runnerState.enabledProtocolSetHash,
                        )

                    is SmartStartAutoConnectState.FastAttempts -> {
                        require(runnerState.candidates.isNotEmpty()) {
                            getApplication<Application>().getString(R.string.auto_connect_requires_supported_profile)
                        }
                        logAdaptiveAutoConnectRanking(
                            profileId = profileId,
                            rankedCandidates = runnerState.rankedCandidates,
                            networkFingerprint = autoConnectNetworkFingerprint,
                        )
                        runFastSmartStartAttempts(
                            profileId = profileId,
                            networkFingerprint = autoConnectNetworkFingerprint,
                            candidates = runnerState.candidates,
                        )
                    }
                }
                completedSmartStart = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                container.diagnosticsLogger.record(
                    "auto-connect",
                    "smart start failed code=${error.runtimeFailureCode().name.lowercase()} message=${error.message.orEmpty()}",
                )
                emitError(getApplication<Application>().getString(R.string.auto_connect_failed))
            } finally {
                container.connectionController.clearSmartStartAnalysisStatus()
                autoConnectJob = null
                delay(HomeViewModel.AUTO_CONNECT_RESULT_SETTLE_MS)
                clearAutoConnectUiState()
                if (completedSmartStart) {
                    refreshDashboardAfterSmartStartIfConnected()
                }
            }
        }
}

private fun HomeViewModel.refreshDashboardAfterSmartStartIfConnected() {
    if (container.connectionController.snapshot.value.state != ConnectionState.CONNECTED) {
        return
    }
    scheduleConnectedIpRefresh(reason = IpInfoRefreshReason.POST_CONNECT, clearExistingIp = false)
    if (dashboardVisible) {
        scheduleActiveProfileLatencyRefresh()
    }
}

private suspend fun HomeViewModel.refreshSubscriptionBeforeSmartStartIfNeeded(profile: Profile): Profile {
    val settings = uiState.value.settings.connection
    if (profile.sourceType != ProfileSourceType.SUBSCRIPTION_URL || !settings.smartStartV2RayTunSubscriptionsEnabled) {
        return profile
    }
    val attempts =
        settings.smartStartSubscriptionRetryAttempts
            .coerceAtLeast(1)
            .coerceAtMost(SMART_START_SUBSCRIPTION_REFRESH_MAX_ATTEMPTS)
    val retryDelayMs = settings.smartStartSubscriptionRetryDelaySeconds.coerceAtLeast(1).toLong() * 1000L
    val refreshBudgetMs =
        settings.smartStartRefreshSelectionTimeoutSeconds
            .coerceAtLeast(SMART_START_REFRESH_TIMEOUT_MIN_SECONDS)
            .toLong() * 1000L
    val deadlineMs = SystemClock.elapsedRealtime() + refreshBudgetMs
    repeat(attempts) { index ->
        val remainingBudgetMs = deadlineMs - SystemClock.elapsedRealtime()
        if (remainingBudgetMs <= 0L) {
            container.diagnosticsLogger.record(
                "auto-connect",
                "subscription refresh before smart start exhausted budget attempts_completed=$index, using cached profile",
            )
            return container.profileRepository.getProfile(profile.id) ?: profile
        }
        runCatching {
            container.profileRepository.refreshProfile(
                profileId = profile.id,
                callTimeoutMs = SMART_START_SUBSCRIPTION_REFRESH_CALL_TIMEOUT_MS.coerceAtMost(remainingBudgetMs),
            )
        }.onSuccess { refreshed ->
            container.diagnosticsLogger.record(
                "auto-connect",
                "subscription refreshed before smart start attempt=${index + 1}",
            )
            return refreshed
        }.onFailure { error ->
            container.diagnosticsLogger.record(
                "auto-connect",
                "subscription refresh before smart start failed attempt=${index + 1}: ${error.message.orEmpty()}",
            )
        }
        if (index < attempts - 1) {
            val remainingAfterAttemptMs = deadlineMs - SystemClock.elapsedRealtime()
            if (remainingAfterAttemptMs <= 0L) {
                container.diagnosticsLogger.record(
                    "auto-connect",
                    "subscription refresh before smart start exhausted budget after attempt=${index + 1}, using cached profile",
                )
                return container.profileRepository.getProfile(profile.id) ?: profile
            }
            delay(retryDelayMs.coerceAtMost(remainingAfterAttemptMs))
        }
    }
    container.diagnosticsLogger.record(
        "auto-connect",
        "subscription refresh before smart start exhausted attempts=$attempts, using cached profile",
    )
    return container.profileRepository.getProfile(profile.id) ?: profile
}

private suspend fun HomeViewModel.runFastSmartStartAttempts(
    profileId: Long,
    networkFingerprint: NetworkFingerprint?,
    candidates: List<AutoConnectProbeCandidate>,
) {
    initializeAutoConnectUi(candidates.take(HomeViewModel.AUTO_CONNECT_MAX_ATTEMPTS))
    var previousVpnNetworkHandle = awaitDisconnectedForAutoConnect()
    val autoConnectStartedAt = SystemClock.elapsedRealtime()
    for (candidate in candidates.take(HomeViewModel.AUTO_CONNECT_MAX_ATTEMPTS)) {
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
        if (!probe.result.success) {
            previousVpnNetworkHandle =
                awaitDisconnectedForAutoConnect(
                    container.connectionController.currentVpnNetworkHandle(),
                )
            if (
                shouldContinueAutoConnectAfterProbe(
                    success = probe.result.success,
                    timedOut = probe.timedOut,
                    remainingBudgetMs =
                    remainingAutoConnectBudgetMs(
                        startedAtElapsedMs = autoConnectStartedAt,
                        nowElapsedMs = SystemClock.elapsedRealtime(),
                        totalTimeoutMs = HomeViewModel.AUTO_CONNECT_TOTAL_TIMEOUT_MS,
                    ),
                )
            ) {
                continue
            }
            break
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
    val results = mutableListOf<AutoConnectProbeResult>()
    candidates.forEachIndexed { index, candidate ->
        markAutoConnectCandidateTesting(profileId, candidate)
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
            headline = if (result.success) "cold scan candidate ok" else "cold scan candidate failed",
        )
        if (result.success && result.displayLatencyMs == null) {
            markProtocolLatencyUnavailable(
                profileId = profileId,
                optionId = result.candidate.optionId,
            )
        } else if (!result.success) {
            markProtocolDown(
                profileId = profileId,
                optionId = result.candidate.optionId,
            )
        }
        markAutoConnectCandidateFinished(result)
        if (index < candidates.lastIndex) {
            previousVpnNetworkHandle =
                awaitDisconnectedForAutoConnect(
                    container.connectionController.currentVpnNetworkHandle(),
                )
        }
    }
    val winner = MultiProtocolProfileSupport.fastestSuccessfulProbe(results)
    if (winner != null) {
        val recommendedIds =
            recommendedProtocolIdsFromProbeResults(results)
                .ifEmpty {
                    recomputeRecommendedProtocolIds(
                        profileId = profileId,
                        candidates = candidates,
                        networkFingerprint = networkFingerprint,
                        excludeOptionIds =
                        results
                            .asSequence()
                            .filterNot(AutoConnectProbeResult::success)
                            .map { result -> result.candidate.optionId }
                            .toSet(),
                    )
                }.ifEmpty { listOf(winner.candidate.optionId) }
        container.settingsRepository.recordSmartProfileBaseline(
            profileId = profileId,
            recommendedProtocolIds = recommendedIds,
            enabledProtocolSetHash = enabledProtocolSetHash,
        )
        val currentSnapshot = container.connectionController.snapshot.value
        val winnerAlreadyConnected =
            currentSnapshot.state == ConnectionState.CONNECTED &&
                currentSnapshot.profileId == profileId &&
                currentSnapshot.protocolOptionId == winner.candidate.optionId
        if (!winnerAlreadyConnected) {
            previousVpnNetworkHandle =
                awaitDisconnectedForAutoConnect(
                    container.connectionController.currentVpnNetworkHandle(),
                )
            connectNow(
                profileId = profileId,
                protocolOptionId = winner.candidate.optionId,
                statusMessage = getApplication<Application>().getString(R.string.notification_status_analysis),
                isSmartStartConnection = true,
                previousVpnNetworkHandle = previousVpnNetworkHandle,
            )
            val outcome = awaitReconnectConnectionOutcome()
            if (!outcome.isConnectedSmartStartWinner(profileId, winner.candidate.optionId)) {
                emitError(getApplication<Application>().getString(R.string.auto_connect_failed))
                return
            }
        }
        commitAutoConnectWinner(
            profileId = profileId,
            result = winner,
            networkFingerprint = networkFingerprint?.key,
        )
        return
    }
    emitError(getApplication<Application>().getString(R.string.auto_connect_failed))
}

private suspend fun HomeViewModel.probeSmartStartCandidateWithinBudget(
    profileId: Long,
    candidate: AutoConnectProbeCandidate,
    networkFingerprint: String?,
    previousVpnNetworkHandle: Long?,
    autoConnectStartedAt: Long,
): BudgetedAutoConnectProbe {
    markAutoConnectCandidateTesting(profileId, candidate)
    delay(HomeViewModel.AUTO_CONNECT_PROTOCOL_TRANSITION_SETTLE_MS)
    val probeStartedAt = SystemClock.elapsedRealtime()
    val probeBudgetMs =
        minOf(
            autoConnectCandidateProbeTimeoutMs(
                timeoutSeconds = uiState.value.settings.connection.smartStartProtocolSelectionTimeoutSeconds,
            ),
            remainingAutoConnectBudgetMs(
                startedAtElapsedMs = autoConnectStartedAt,
                nowElapsedMs = probeStartedAt,
                totalTimeoutMs = HomeViewModel.AUTO_CONNECT_TOTAL_TIMEOUT_MS,
            ),
        )
    if (probeBudgetMs <= 0L) {
        runCatching {
            container.connectionController.disconnect(
                suppressLocalGuard = true,
                preserveSmartStartAnalysis = true,
            )
        }
        return BudgetedAutoConnectProbe(
            result =
            autoConnectWallClockTimeoutResult(
                candidate = candidate,
                startedAtElapsedMs = probeStartedAt,
                timeoutMs = probeBudgetMs,
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
            connectedAutoConnectFallbackResult(
                profileId = profileId,
                candidate = candidate,
                networkFingerprint = networkFingerprint,
                startedAtElapsedMs = probeStartedAt,
                timeoutMs = probeBudgetMs,
            )?.let { fallback ->
                return BudgetedAutoConnectProbe(result = fallback, timedOut = false)
            }
            runCatching {
                container.connectionController.disconnect(
                    suppressLocalGuard = true,
                    preserveSmartStartAnalysis = true,
                )
            }
            return BudgetedAutoConnectProbe(
                result =
                autoConnectWallClockTimeoutResult(
                    candidate = candidate,
                    startedAtElapsedMs = probeStartedAt,
                    timeoutMs = probeBudgetMs,
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
    val candidateIsStoredOption =
        currentProfile?.protocolOptions?.any { option -> option.id == result.candidate.optionId } == true
    if (candidateIsStoredOption && currentProfile?.selectedProtocolOptionId != result.candidate.optionId) {
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
    container.connectionController.clearSmartStartAnalysisStatus()
    emitSuccess(
        result.displayLatencyMs?.let { latencyMs ->
            getApplication<Application>().getString(
                R.string.auto_connect_success,
                result.candidate.displayName,
                boundedDisplayLatencyMs(latencyMs),
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
        transportPriority = uiState.value.settings.connection.smartStartTransportPriority,
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
    excludeOptionIds: Set<String> = emptySet(),
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
    return SmartStartController.recommendedTopCandidateIds(
        rankedCandidates = ranked,
        excludeOptionIds = excludeOptionIds,
    )
}

private fun recommendedProtocolIdsFromProbeResults(results: List<AutoConnectProbeResult>): List<String> =
    results
        .asSequence()
        .filter(AutoConnectProbeResult::success)
        .sortedWith(
            compareBy<AutoConnectProbeResult> { result -> result.displayLatencyMs ?: result.rankingLatencyMs }
                .thenBy { result -> result.candidate.optionId },
        )
        .map { result -> result.candidate.optionId }
        .distinct()
        .take(HomeViewModel.AUTO_CONNECT_MAX_ATTEMPTS)
        .toList()

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
    if (shouldSkipSpeedTestsOnCurrentNetwork()) {
        protocolMetricsRefreshJob = null
        dashboardConnectionMetricsLoadingMutable.value = false
        container.diagnosticsLogger.record("latency", "manual speed tests skipped: cellular or metered network")
        snackbars.tryEmit(infoBanner(R.string.network_rules_speed_tests_skipped_mobile))
        return
    }
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
                val networkFingerprint = currentNetworkFingerprintForSmartRules()
                val candidates = fullScanAutoConnectCandidates(profileId, profile)
                require(canStartAutoConnect(candidates)) {
                    getApplication<Application>().getString(R.string.auto_connect_requires_supported_profile)
                }
                val enabledProtocolSetHash =
                    smartStartEnabledProtocolSetHash(candidates.map(AutoConnectProbeCandidate::optionId))
                selectedOptionId = resolveDashboardLatencyOptionId(profile)
                recommendedProtocolMutable.value = null
                protocolMetricsRefreshingOptionIdByProfileIdMutable.value =
                    protocolMetricsRefreshingOptionIdByProfileIdMutable.value +
                    (profileId to candidates.first().optionId)
                protocolMetricsRefreshingProfileIdsMutable.value =
                    protocolMetricsRefreshingProfileIdsMutable.value + profileId
                initializeAutoConnectUi(candidates)
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
                    if (result.success) {
                        if (result.displayLatencyMs != null) {
                            cacheProtocolLatency(
                                profileId = profileId,
                                optionId = result.candidate.optionId,
                                latencyMs = result.displayLatencyMs,
                            )
                        } else {
                            markProtocolLatencyUnavailable(
                                profileId = profileId,
                                optionId = result.candidate.optionId,
                            )
                        }
                    } else {
                        markProtocolDown(profileId = profileId, optionId = result.candidate.optionId)
                    }
                    markAutoConnectCandidateFinished(result)
                    if (index < candidates.lastIndex) {
                        previousVpnNetworkHandle =
                            awaitDisconnectedForAutoConnect(
                                container.connectionController.currentVpnNetworkHandle(),
                            )
                    }
                }
                val recommendedIds =
                    recommendedProtocolIdsFromProbeResults(results)
                        .ifEmpty {
                            recomputeRecommendedProtocolIds(
                                profileId = profileId,
                                candidates = candidates,
                                networkFingerprint = networkFingerprint,
                                excludeOptionIds =
                                results
                                    .asSequence()
                                    .filterNot(AutoConnectProbeResult::success)
                                    .map { result -> result.candidate.optionId }
                                    .toSet(),
                            )
                        }
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

internal fun HomeViewModel.cancelSmartProfileMetricsRefreshInternal(restoreConnection: Boolean) {
    protocolMetricsRestoreOnCancel = restoreConnection
    if (!restoreConnection) {
        protocolMetricsRefreshingProfileIdsMutable.value = emptySet()
        protocolMetricsRefreshingOptionIdByProfileIdMutable.value = emptyMap()
        clearAutoConnectUiState()
    }
    protocolMetricsRefreshJob?.cancel()
}

private suspend fun HomeViewModel.probeAutoConnectCandidateForMetricsRefresh(
    profileId: Long,
    candidate: AutoConnectProbeCandidate,
    networkFingerprint: String?,
    previousVpnNetworkHandle: Long?,
): AutoConnectProbeResult {
    val startedAt = SystemClock.elapsedRealtime()
    val timeoutMs =
        protocolMetricsCandidateProbeTimeoutMs(
            timeoutSeconds = uiState.value.settings.connection.smartStartRefreshSelectionTimeoutSeconds,
        )
    val result =
        withTimeoutOrNull(timeoutMs) {
            probeAutoConnectCandidate(
                profileId = profileId,
                candidate = candidate,
                networkFingerprint = networkFingerprint,
                previousVpnNetworkHandle = previousVpnNetworkHandle,
            )
        }
    return result
        ?: connectedAutoConnectFallbackResult(
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
}

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

private fun HomeViewModel.autoConnectWallClockTimeoutResult(
    candidate: AutoConnectProbeCandidate,
    startedAtElapsedMs: Long,
    timeoutMs: Long,
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
    protocolOptionId == candidate.optionId ||
        (protocolOptionId == null && protocolHint == candidate.protocolHint)

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
            latencyProbeMethod = uiState.value.settings.connection.latencyProbeMethod,
        ),
        displayLatencyMs = rememberedLatencyMs,
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
    cancelAutoConnect(clearUiOnly = true)
    cancelSmartProfileMetricsRefreshInternal(restoreConnection = false)
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
        isSmartStartConnection = true,
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
                        latencyProbeMethod = uiState.value.settings.connection.latencyProbeMethod,
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
    measureAndCacheProtocolServerPing(profileId, candidate.optionId, candidate.protocolHint, networkFingerprint)
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
    protocolHint: ProtocolHint?,
    networkFingerprint: String? = null,
) {
    if (!shouldMeasureProtocolServerPing(protocolHint)) {
        container.diagnosticsLogger.record("latency", "server ping skipped: unsupported for udp transport")
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
        container.connectionController.disconnect(
            suppressLocalGuard = true,
            preserveSmartStartAnalysis = true,
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

internal fun HomeViewModel.initializeAutoConnectUiInternal(candidates: List<AutoConnectProbeCandidate>) {
    autoConnectUiStateMutable.value =
        AutoConnectUiState(
            running = true,
            currentOptionId = candidates.firstOrNull()?.optionId,
            currentProtocolHint = candidates.firstOrNull()?.protocolHint,
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

internal fun HomeViewModel.markAutoConnectCandidateTestingInternal(
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
    return scoredAutoConnectCandidatesInternal(
        profileId,
        profile,
        networkFingerprint
    ).map(AdaptiveProtocolCandidateScore::candidate)
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
        transportPriority = uiState.value.settings.connection.smartStartTransportPriority,
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
    profileOptionDownMutable.value =
        profileOptionDownMutable.value - key
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
    profileOptionDownMutable.value =
        profileOptionDownMutable.value - key
    profileOptionLatenciesMutable.value =
        profileOptionLatenciesMutable.value - key
    profileOptionLatencyUnavailableMutable.value =
        profileOptionLatencyUnavailableMutable.value + key
    markProtocolMetricsUpdated(profileId, optionId)
}

internal fun HomeViewModel.markProtocolDownInternal(
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
    profileOptionDownMutable.value =
        if (profileId == null && optionId == null) {
            emptySet()
        } else {
            profileOptionDownMutable.value.filterNot(::matches).toSet()
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

@Suppress("ReturnCount")
internal fun HomeViewModel.scheduleActiveProfileLatencyRefreshInternal() {
    val activeProfile = uiState.value.activeProfile ?: return
    val selectedOptionId =
        resolveDashboardLatencyOptionId(
            activeProfile = activeProfile,
            connection = container.connectionController.snapshot.value,
        ) ?: return
    if (shouldSkipSpeedTestsOnCurrentNetwork()) {
        profileLatencyRefreshJob?.cancel()
        profileLatencyRefreshJob = null
        dashboardConnectionMetricsLoadingMutable.value = false
        container.diagnosticsLogger.record("latency", "dashboard speed tests skipped: cellular or metered network")
        return
    }
    val selectedProtocolHint =
        activeProfile.protocolOptions
            .firstOrNull { option -> option.id == selectedOptionId }
            ?.protocolHint
    profileLatencyRefreshJob?.cancel()
    dashboardConnectionMetricsLoadingMutable.value = true
    profileLatencyRefreshJob =
        viewModelScope.launch {
            var waitingForInitialSample = true
            try {
                var nextDelayMs = HomeViewModel.CONNECTED_LATENCY_FIRST_DELAY_MS
                while (true) {
                    delay(nextDelayMs)
                    nextDelayMs = HomeViewModel.CONNECTED_LATENCY_REFRESH_INTERVAL_MS
                    val refreshTarget =
                        activeDashboardLatencyTarget(
                            activeProfileId = activeProfile.id,
                            selectedOptionId = selectedOptionId,
                            fallbackProtocolHint = selectedProtocolHint,
                        ) ?: return@launch
                    val latencyResult =
                        runCatching {
                            withTimeoutOrNull(HomeViewModel.CONNECTED_LATENCY_TIMEOUT_MS) {
                                container.connectionController.measureCurrentConnectionLatency()
                            } ?: error("dashboard latency timed out")
                        }
                    val measuredLatency = latencyResult.getOrNull()
                    if (measuredLatency != null) {
                        cacheProtocolLatency(
                            profileId = activeProfile.id,
                            optionId = selectedOptionId,
                            latencyMs = measuredLatency,
                        )
                        recordConnectedProtocolSmartStartMemory(
                            profile = refreshTarget.profile,
                            optionId = selectedOptionId,
                            protocolHint = refreshTarget.protocolHint,
                            latencyMs = measuredLatency,
                            reasonCode = null,
                            countTowardOutcomeHistory = waitingForInitialSample,
                        )
                    } else {
                        val error = latencyResult.exceptionOrNull()
                        markProtocolLatencyUnavailable(
                            profileId = activeProfile.id,
                            optionId = selectedOptionId,
                        )
                        recordConnectedProtocolSmartStartMemory(
                            profile = refreshTarget.profile,
                            optionId = selectedOptionId,
                            protocolHint = refreshTarget.protocolHint,
                            latencyMs = null,
                            reasonCode = AutoConnectReasonCode.LATENCY_ENDPOINT_BLOCKED,
                            countTowardOutcomeHistory = waitingForInitialSample,
                        )
                        container.diagnosticsLogger.record("latency", "dashboard latency unavailable: ${error?.message.orEmpty()}")
                    }
                    if (!shouldMeasureProtocolServerPing(refreshTarget.protocolHint)) {
                        container.diagnosticsLogger.record("latency", "dashboard server ping skipped: unsupported for udp transport")
                        waitingForInitialSample = clearDashboardMetricsLoadingAfterInitialSample(waitingForInitialSample)
                        continue
                    }
                    runCatching {
                        withTimeoutOrNull(HomeViewModel.CONNECTED_SERVER_PING_TIMEOUT_MS) {
                            container.connectionController.measureCurrentVpnServerPing(
                                profileId = refreshTarget.profile.id,
                                protocolOptionId = refreshTarget.optionId,
                            )
                        } ?: error("dashboard server ping timed out")
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
                            networkFingerprint = currentNetworkFingerprintForSmartRules()?.key,
                        )
                    }.onFailure { error ->
                        markProtocolServerPingUnavailableInternal(
                            profileId = activeProfile.id,
                            optionId = selectedOptionId,
                        )
                        container.diagnosticsLogger.record("latency", "dashboard server ping unavailable: ${error.message.orEmpty()}")
                    }
                    waitingForInitialSample = clearDashboardMetricsLoadingAfterInitialSample(waitingForInitialSample)
                }
            } finally {
                clearDashboardMetricsLoadingAfterInitialSample(waitingForInitialSample)
                profileLatencyRefreshJob = null
            }
        }
}

private suspend fun HomeViewModel.recordConnectedProtocolSmartStartMemory(
    profile: Profile,
    optionId: String,
    protocolHint: ProtocolHint?,
    latencyMs: Long?,
    reasonCode: AutoConnectReasonCode?,
    countTowardOutcomeHistory: Boolean,
) {
    val resolvedProtocolHint =
        protocolHint
            ?: profile.protocolOptions.firstOrNull { option -> option.id == optionId }?.protocolHint
            ?: profile.protocolHint
    if (resolvedProtocolHint in setOf(ProtocolHint.UNKNOWN, ProtocolHint.SING_BOX)) {
        return
    }
    val networkFingerprint = currentNetworkFingerprintForSmartRules()
    val recordedAt = System.currentTimeMillis()
    container.settingsRepository.recordSmartProfileProbeResult(
        profileId = profile.id,
        optionId = optionId,
        latencyMs = latencyMs,
        success = true,
        reasonCode = reasonCode,
        markAsLastKnownGood = true,
        networkFingerprint = networkFingerprint?.key,
        validatedAt = recordedAt,
        trafficObservedAt =
        currentTrafficObservedAt(
            traffic = container.connectionController.traffic.value,
            fallbackAt = recordedAt,
        ),
        countTowardOutcomeHistory = countTowardOutcomeHistory,
    )
    val candidates = fullScanAutoConnectCandidates(profile.id, profile)
    if (candidates.isEmpty()) {
        return
    }
    val recommendedIds =
        recomputeRecommendedProtocolIds(
            profileId = profile.id,
            candidates = candidates,
            networkFingerprint = networkFingerprint,
        )
    if (recommendedIds.isNotEmpty()) {
        container.settingsRepository.recordSmartProfileBaseline(
            profileId = profile.id,
            recommendedProtocolIds = recommendedIds,
            enabledProtocolSetHash = smartStartEnabledProtocolSetHash(
                candidates.map(AutoConnectProbeCandidate::optionId)
            ),
        )
    }
}

internal fun HomeViewModel.clearProfileLatencyRefreshInternal() {
    profileLatencyRefreshJob?.cancel()
    profileLatencyRefreshJob = null
    dashboardConnectionMetricsLoadingMutable.value = false
}

private fun shouldMeasureProtocolServerPing(protocolHint: ProtocolHint?): Boolean =
    protocolHint?.isUdpTransport() != true

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
    val currentProfile = uiState.value.activeProfile
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

private fun HomeViewModel.clearDashboardMetricsLoadingAfterInitialSample(waitingForInitialSample: Boolean): Boolean {
    if (waitingForInitialSample) {
        dashboardConnectionMetricsLoadingMutable.value = false
    }
    return false
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

internal fun autoConnectCandidateProbeTimeoutMs(
    timeoutSeconds: Int = SMART_START_PROTOCOL_TIMEOUT_DEFAULT_SECONDS,
): Long =
    smartStartTimeoutMs(
        timeoutSeconds = timeoutSeconds,
        minSeconds = SMART_START_PROTOCOL_TIMEOUT_MIN_SECONDS,
    ).coerceAtLeast(minimumAutoConnectCandidateProbeBudgetMs())

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
        HomeViewModel.CONNECTED_SERVER_PING_TIMEOUT_MS +
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
    (settledLatencyMs?.let { settled -> minOf(warmupLatencyMs, settled) } ?: warmupLatencyMs)
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
    profileId: Long?,
    currentVpnNetworkHandle: Long?,
    previousVpnNetworkHandle: Long?,
): Boolean =
    (
        connectionState in setOf(ConnectionState.IDLE, ConnectionState.ERROR) ||
            (connectionState == ConnectionState.CONNECTED && profileId == FoxholeVpnService.LOCAL_GUARD_PROFILE_ID)
        ) &&
        (previousVpnNetworkHandle == null || currentVpnNetworkHandle != previousVpnNetworkHandle)
