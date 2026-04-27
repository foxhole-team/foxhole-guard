package com.foxhole.beta.core.smart

import com.foxhole.beta.core.model.SmartProfilePreference
import com.foxhole.beta.core.model.SmartProfileProtocolMemory
import com.foxhole.beta.core.profile.AutoConnectProbeCandidate
import com.foxhole.beta.core.settings.networkMemory
import kotlin.random.Random

object SmartStartController {
    const val AUTO_CONNECT_MAX_ATTEMPTS: Int = 3

    fun eligibleCandidatesForRanking(
        candidates: List<AutoConnectProbeCandidate>,
        preference: SmartProfilePreference?,
        networkFingerprint: String?,
        excludedOptionIds: Set<String>,
        subscriptionExpiresAt: Long?,
        now: Long = System.currentTimeMillis(),
    ): List<AutoConnectProbeCandidate> {
        if (subscriptionExpiresAt != null && subscriptionExpiresAt <= now) {
            return emptyList()
        }
        val scopedMemories =
            preference
                ?.networkMemory(networkFingerprint)
                ?.protocolMemories
                ?.associateBy(SmartProfileProtocolMemory::optionId)
                .orEmpty()
        val globalMemories = preference?.protocolMemories?.associateBy(SmartProfileProtocolMemory::optionId).orEmpty()
        return candidates.filter { candidate ->
            if (candidate.optionId in excludedOptionIds) {
                return@filter false
            }
            if (candidate.requiresInsecureTls && !candidate.insecureTlsConsentGranted) {
                return@filter false
            }
            val scopedMemory = scopedMemories[candidate.optionId]
            val globalMemory = globalMemories[candidate.optionId]
            !isCoolingDown(scopedMemory, globalMemory, now)
        }
    }

    fun eligibleCandidatesForFullScan(
        candidates: List<AutoConnectProbeCandidate>,
        excludedOptionIds: Set<String>,
        subscriptionExpiresAt: Long?,
        now: Long = System.currentTimeMillis(),
    ): List<AutoConnectProbeCandidate> {
        if (subscriptionExpiresAt != null && subscriptionExpiresAt <= now) {
            return emptyList()
        }
        return candidates.filter { candidate ->
            candidate.optionId !in excludedOptionIds &&
                (!candidate.requiresInsecureTls || candidate.insecureTlsConsentGranted)
        }
    }

    fun recommendedTopCandidateIds(
        rankedCandidates: List<AdaptiveProtocolCandidateScore>,
        limit: Int = AUTO_CONNECT_MAX_ATTEMPTS,
        excludeOptionIds: Set<String> = emptySet(),
    ): List<String> =
        rankedCandidates
            .asSequence()
            .map { score -> score.candidate.optionId.trim() }
            .filter(String::isNotBlank)
            .filterNot(excludeOptionIds::contains)
            .distinct()
            .take(limit.coerceAtLeast(1))
            .toList()

    fun rankedAttempts(
        rankedCandidates: List<AdaptiveProtocolCandidateScore>,
        config: AdaptiveProtocolScoringConfig = AdaptiveProtocolScoringConfig.Default,
        randomDouble: () -> Double = { Random.nextDouble() },
        randomIndex: (Int) -> Int = { bound -> Random.nextInt(bound) },
    ): List<AdaptiveProtocolCandidateScore> {
        return AdaptiveProtocolRanker.applyControlledExploration(
            rankedCandidates = rankedCandidates,
            config = config,
            randomDouble = randomDouble,
            randomIndex = randomIndex,
        )
    }

    fun <T> runUntilFirstSuccess(
        candidates: List<T>,
        maxAttempts: Int = AUTO_CONNECT_MAX_ATTEMPTS,
        attempt: (T) -> Boolean,
    ): SmartStartAttemptSummary<T> {
        require(maxAttempts > 0) { "maxAttempts must be positive" }
        val attempted = mutableListOf<T>()
        candidates.take(maxAttempts).forEach { candidate ->
            attempted += candidate
            if (attempt(candidate)) {
                return SmartStartAttemptSummary(attempted = attempted, winner = candidate)
            }
        }
        return SmartStartAttemptSummary(attempted = attempted, winner = null)
    }

    private fun isCoolingDown(
        scopedMemory: SmartProfileProtocolMemory?,
        globalMemory: SmartProfileProtocolMemory?,
        now: Long,
    ): Boolean =
        listOfNotNull(scopedMemory, globalMemory).any { memory ->
            memory.cooldownUntilAt?.let { cooldownUntilAt -> cooldownUntilAt > now } == true
        }
}

data class SmartStartAttemptSummary<T>(
    val attempted: List<T>,
    val winner: T?,
)
