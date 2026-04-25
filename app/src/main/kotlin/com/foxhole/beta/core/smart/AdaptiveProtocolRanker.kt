package com.foxhole.beta.core.smart

import com.foxhole.beta.core.model.AutoConnectReasonCode
import com.foxhole.beta.core.model.SmartProfilePreference
import com.foxhole.beta.core.model.SmartProfileProtocolMemory
import com.foxhole.beta.core.network.NetworkFingerprint
import com.foxhole.beta.core.profile.AutoConnectProbeCandidate
import com.foxhole.beta.core.settings.networkMemory
import java.util.Locale
import kotlin.random.Random
import kotlin.math.roundToInt

data class AdaptiveProtocolCandidateScore(
    val candidate: AutoConnectProbeCandidate,
    val scoringVersion: Int,
    val score: Int,
    val successRate: Double,
    val lastKnownGoodBonus: Int,
    val networkMatchBonus: Int,
    val latencyPenalty: Int,
    val recentFailurePenalty: Int,
    val validationFailurePenalty: Int,
    val explorationBonus: Int,
) {
    fun summary(): String =
        buildString {
            append(candidate.optionId)
            append(": v=")
            append(scoringVersion)
            append(": score=")
            append(score)
            append(" sr=")
            append(String.format(Locale.ROOT, "%.2f", successRate))
            append(" lkg=+")
            append(lastKnownGoodBonus)
            append(" net=+")
            append(networkMatchBonus)
            append(" lat=-")
            append(latencyPenalty)
            append(" fail=-")
            append(recentFailurePenalty)
            append(" val=-")
            append(validationFailurePenalty)
            append(" explore=+")
            append(explorationBonus)
        }
}

data class AdaptiveProtocolScoringConfig(
    val version: Int = CURRENT_VERSION,
    val successRateWeight: Double = 45.0,
    val scopedLastKnownGoodBonus: Int = 16,
    val globalLastKnownGoodBonus: Int = 8,
    val recentFreshnessBonus: Int = 4,
    val weeklyFreshnessBonus: Int = 2,
    val scopedMemoryBonus: Int = 12,
    val globalMemoryBonus: Int = 4,
    val upstreamValidatedBonus: Int = 4,
    val privateDnsValidatedBonus: Int = 2,
    val recentTrafficBonus: Int = 3,
    val latencyBaselineMs: Long = 120L,
    val latencyStepMs: Long = 40L,
    val latencyPenaltyMax: Int = 18,
    val connectBaselineMs: Long = 1_200L,
    val connectStepMs: Long = 250L,
    val connectPenaltyMax: Int = 10,
    val cooldownBasePenalty: Int = 24,
    val cooldownPenaltyPerMinute: Int = 3,
    val cooldownPenaltyMax: Int = 36,
    val failureStreakPenalty: Int = 4,
    val failureStreakPenaltyMax: Int = 4,
    val recentFailurePenalty: Int = 14,
    val warmFailurePenalty: Int = 10,
    val staleFailurePenalty: Int = 6,
    val validationTimeoutPenalty: Int = 16,
    val dnsFailurePenalty: Int = 12,
    val privateDnsFailurePenalty: Int = 18,
    val handshakeTimeoutPenalty: Int = 10,
    val connectErrorPenalty: Int = 8,
    val latencyEndpointBlockedPenalty: Int = 4,
    val noMemoryExplorationBonus: Int = 6,
    val networkNoMemoryExplorationBonus: Int = 8,
    val unscopedMemoryExplorationBonus: Int = 3,
    val controlledExplorationEpsilon: Double = 0.05,
) {
    init {
        require(version > 0) { "version must be positive" }
        require(successRateWeight > 0.0) { "successRateWeight must be positive" }
        require(latencyBaselineMs >= 0) { "latencyBaselineMs must not be negative" }
        require(latencyStepMs > 0) { "latencyStepMs must be positive" }
        require(connectBaselineMs >= 0) { "connectBaselineMs must not be negative" }
        require(connectStepMs > 0) { "connectStepMs must be positive" }
        require(controlledExplorationEpsilon in 0.0..1.0) { "controlledExplorationEpsilon must be between 0 and 1" }
    }

    companion object {
        const val CURRENT_VERSION: Int = 1
        val Default = AdaptiveProtocolScoringConfig()
    }
}

object AdaptiveProtocolRanker {
    fun scoreCandidates(
        candidates: List<AutoConnectProbeCandidate>,
        preference: SmartProfilePreference?,
        networkFingerprintKey: String? = null,
        networkContext: NetworkFingerprint? = null,
        now: Long = System.currentTimeMillis(),
        config: AdaptiveProtocolScoringConfig = AdaptiveProtocolScoringConfig.Default,
    ): List<AdaptiveProtocolCandidateScore> {
        // Score order is intentionally dominated by durable success evidence, then local network fit,
        // then recent failure/cooldown safety. Exploration only breaks weak or unseen candidates.
        val effectiveFingerprintKey = networkContext?.key ?: networkFingerprintKey
        val scopedPreference = preference?.networkMemory(effectiveFingerprintKey)
        val scopedMemories = scopedPreference?.protocolMemories?.associateBy(SmartProfileProtocolMemory::optionId).orEmpty()
        val globalMemories = preference?.protocolMemories?.associateBy(SmartProfileProtocolMemory::optionId).orEmpty()
        return candidates
            .mapIndexed { index, candidate ->
                val scopedMemory = scopedMemories[candidate.optionId]
                val globalMemory = globalMemories[candidate.optionId]
                val successRate = resolveSuccessRate(scopedMemory, globalMemory)
                val lastKnownGoodBonus =
                    resolveLastKnownGoodBonus(
                        optionId = candidate.optionId,
                        scopedLastKnownGoodOptionId = scopedPreference?.lastKnownGoodOptionId,
                        globalLastKnownGoodOptionId = preference?.lastKnownGoodOptionId,
                        scopedLastKnownGoodAt = scopedPreference?.lastKnownGoodAt,
                        globalLastKnownGoodAt = preference?.lastKnownGoodAt,
                        now = now,
                        config = config,
                    )
                val networkMatchBonus = resolveNetworkMatchBonus(scopedMemory, globalMemory, networkContext, config)
                val latencyPenalty = resolveLatencyPenalty(scopedMemory, globalMemory, config)
                val recentFailurePenalty = resolveRecentFailurePenalty(scopedMemory, globalMemory, now, config)
                val validationFailurePenalty =
                    resolveValidationFailurePenalty(
                        scopedMemory = scopedMemory,
                        globalMemory = globalMemory,
                        privateDnsActive = networkContext?.privateDnsActive == true,
                        config = config,
                    )
                val explorationBonus = resolveExplorationBonus(scopedMemory, globalMemory, networkContext, now, config)
                IndexedScore(
                    index = index,
                    score =
                        AdaptiveProtocolCandidateScore(
                            candidate = candidate,
                            scoringVersion = config.version,
                            score =
                                (successRate * config.successRateWeight).roundToInt() +
                                    lastKnownGoodBonus +
                                    networkMatchBonus -
                                    latencyPenalty -
                                    recentFailurePenalty -
                                    validationFailurePenalty +
                                    explorationBonus,
                            successRate = successRate,
                            lastKnownGoodBonus = lastKnownGoodBonus,
                            networkMatchBonus = networkMatchBonus,
                            latencyPenalty = latencyPenalty,
                            recentFailurePenalty = recentFailurePenalty,
                            validationFailurePenalty = validationFailurePenalty,
                            explorationBonus = explorationBonus,
                        ),
                )
            }.sortedWith(
                compareByDescending<IndexedScore> { it.score.score }
                    .thenBy(IndexedScore::index),
            ).map(IndexedScore::score)
    }

    fun applyControlledExploration(
        rankedCandidates: List<AdaptiveProtocolCandidateScore>,
        config: AdaptiveProtocolScoringConfig = AdaptiveProtocolScoringConfig.Default,
        randomDouble: () -> Double = { Random.nextDouble() },
        randomIndex: (Int) -> Int = { bound -> Random.nextInt(bound) },
    ): List<AdaptiveProtocolCandidateScore> {
        if (rankedCandidates.size < 2 || randomDouble() >= config.controlledExplorationEpsilon) {
            return rankedCandidates
        }
        val exploredIndex = randomIndex(rankedCandidates.size - 1).coerceIn(0, rankedCandidates.lastIndex - 1) + 1
        val explored = rankedCandidates[exploredIndex]
        return listOf(explored) + rankedCandidates.filterIndexed { index, _ -> index != exploredIndex }
    }

    private fun resolveSuccessRate(
        scopedMemory: SmartProfileProtocolMemory?,
        globalMemory: SmartProfileProtocolMemory?,
    ): Double {
        val memory = scopedMemory ?: globalMemory
        val successEvidence = maxOf(memory?.successCount ?: 0, if (memory?.lastSuccessAt != null) 1 else 0)
        val failureEvidence = maxOf(memory?.failureCount ?: 0, if (memory?.lastFailureAt != null) 1 else 0)
        return (successEvidence + 1.0) / (successEvidence + failureEvidence + 2.0)
    }

    private fun resolveLastKnownGoodBonus(
        optionId: String,
        scopedLastKnownGoodOptionId: String?,
        globalLastKnownGoodOptionId: String?,
        scopedLastKnownGoodAt: Long?,
        globalLastKnownGoodAt: Long?,
        now: Long,
        config: AdaptiveProtocolScoringConfig,
    ): Int =
        when {
            optionId == scopedLastKnownGoodOptionId -> config.scopedLastKnownGoodBonus + freshnessBonus(scopedLastKnownGoodAt, now, config)
            optionId == globalLastKnownGoodOptionId -> config.globalLastKnownGoodBonus + freshnessBonus(globalLastKnownGoodAt, now, config)
            else -> 0
        }

    private fun resolveNetworkMatchBonus(
        scopedMemory: SmartProfileProtocolMemory?,
        globalMemory: SmartProfileProtocolMemory?,
        networkContext: NetworkFingerprint?,
        config: AdaptiveProtocolScoringConfig,
    ): Int {
        var bonus = 0
        if (scopedMemory != null) {
            bonus += config.scopedMemoryBonus
        } else if (globalMemory != null) {
            bonus += config.globalMemoryBonus
        }
        if (networkContext?.upstreamValidated == true && (scopedMemory?.lastValidatedAt != null || globalMemory?.lastValidatedAt != null)) {
            bonus += config.upstreamValidatedBonus
        }
        if (networkContext?.privateDnsActive == true && scopedMemory?.lastValidatedAt != null) {
            bonus += config.privateDnsValidatedBonus
        }
        if (scopedMemory?.lastTrafficAt != null) {
            bonus += config.recentTrafficBonus
        }
        return bonus
    }

    private fun resolveLatencyPenalty(
        scopedMemory: SmartProfileProtocolMemory?,
        globalMemory: SmartProfileProtocolMemory?,
        config: AdaptiveProtocolScoringConfig,
    ): Int {
        val memory = scopedMemory ?: globalMemory ?: return 0
        val latencyPenalty =
            memory.lastLatencyMs
                ?.let { latencyMs ->
                    ((latencyMs.coerceAtLeast(config.latencyBaselineMs) - config.latencyBaselineMs) / config.latencyStepMs)
                        .toInt()
                        .coerceAtMost(config.latencyPenaltyMax)
                } ?: 0
        val connectPenalty =
            memory.lastConnectDurationMs
                ?.let { durationMs ->
                    ((durationMs.coerceAtLeast(config.connectBaselineMs) - config.connectBaselineMs) / config.connectStepMs)
                        .toInt()
                        .coerceAtMost(config.connectPenaltyMax)
                } ?: 0
        return latencyPenalty + connectPenalty
    }

    private fun resolveRecentFailurePenalty(
        scopedMemory: SmartProfileProtocolMemory?,
        globalMemory: SmartProfileProtocolMemory?,
        now: Long,
        config: AdaptiveProtocolScoringConfig,
    ): Int {
        val memory = scopedMemory ?: globalMemory ?: return 0
        val cooldownPenalty =
            memory.cooldownUntilAt
                ?.takeIf { it > now }
                ?.let { cooldownUntil ->
                    val remainingMinutes = ((cooldownUntil - now) / 60_000L).coerceAtLeast(1L)
                    config.cooldownBasePenalty +
                        minOf(remainingMinutes.toInt() * config.cooldownPenaltyPerMinute, config.cooldownPenaltyMax)
                } ?: 0
        val recencyPenalty =
            memory.lastFailureAt
                ?.let { lastFailureAt ->
                    when (now - lastFailureAt) {
                        in 0..(5L * 60L * 1000L) -> config.recentFailurePenalty
                        in 0..(30L * 60L * 1000L) -> config.warmFailurePenalty
                        in 0..(6L * 60L * 60L * 1000L) -> config.staleFailurePenalty
                        else -> 0
                    }
                } ?: 0
        return cooldownPenalty +
            recencyPenalty +
            memory.failureStreak.coerceAtMost(config.failureStreakPenaltyMax) * config.failureStreakPenalty
    }

    private fun resolveValidationFailurePenalty(
        scopedMemory: SmartProfileProtocolMemory?,
        globalMemory: SmartProfileProtocolMemory?,
        privateDnsActive: Boolean,
        config: AdaptiveProtocolScoringConfig,
    ): Int {
        val memory = scopedMemory ?: globalMemory ?: return 0
        val lastFailureAt = memory.lastFailureAt ?: return 0
        val lastSuccessAt = memory.lastSuccessAt ?: Long.MIN_VALUE
        if (lastFailureAt < lastSuccessAt) {
            return 0
        }
        return when (memory.lastReasonCode) {
            AutoConnectReasonCode.VALIDATION_TIMEOUT -> config.validationTimeoutPenalty
            AutoConnectReasonCode.DNS_FAILURE -> if (privateDnsActive) config.privateDnsFailurePenalty else config.dnsFailurePenalty
            AutoConnectReasonCode.HANDSHAKE_TIMEOUT -> config.handshakeTimeoutPenalty
            AutoConnectReasonCode.CONNECT_ERROR -> config.connectErrorPenalty
            AutoConnectReasonCode.LATENCY_ENDPOINT_BLOCKED -> config.latencyEndpointBlockedPenalty
            AutoConnectReasonCode.RESTORED_LAST_GOOD,
            null,
            -> 0
        }
    }

    private fun resolveExplorationBonus(
        scopedMemory: SmartProfileProtocolMemory?,
        globalMemory: SmartProfileProtocolMemory?,
        networkContext: NetworkFingerprint?,
        now: Long,
        config: AdaptiveProtocolScoringConfig,
    ): Int {
        val memory = scopedMemory ?: globalMemory
        if (memory?.cooldownUntilAt?.let { it > now } == true) {
            return 0
        }
        val successEvidence = memory?.successCount ?: 0
        val failureEvidence = memory?.failureCount ?: 0
        return when {
            memory == null -> if (networkContext == null) config.noMemoryExplorationBonus else config.networkNoMemoryExplorationBonus
            successEvidence == 0 && failureEvidence == 0 -> config.noMemoryExplorationBonus
            scopedMemory == null -> config.unscopedMemoryExplorationBonus
            else -> 0
        }
    }

    private fun freshnessBonus(
        referenceAt: Long?,
        now: Long,
        config: AdaptiveProtocolScoringConfig,
    ): Int =
        when {
            referenceAt == null -> 0
            now - referenceAt <= 24L * 60L * 60L * 1000L -> config.recentFreshnessBonus
            now - referenceAt <= 7L * 24L * 60L * 60L * 1000L -> config.weeklyFreshnessBonus
            else -> 0
        }

    private data class IndexedScore(
        val index: Int,
        val score: AdaptiveProtocolCandidateScore,
    )
}
