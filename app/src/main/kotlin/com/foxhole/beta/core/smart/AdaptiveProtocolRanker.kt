package com.foxhole.beta.core.smart

import com.foxhole.beta.core.model.AutoConnectReasonCode
import com.foxhole.beta.core.model.SmartProfilePreference
import com.foxhole.beta.core.model.SmartProfileProtocolMemory
import com.foxhole.beta.core.network.NetworkFingerprint
import com.foxhole.beta.core.profile.AutoConnectProbeCandidate
import com.foxhole.beta.core.settings.networkMemory
import java.util.Locale
import kotlin.math.roundToInt

data class AdaptiveProtocolCandidateScore(
    val candidate: AutoConnectProbeCandidate,
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

object AdaptiveProtocolRanker {
    fun scoreCandidates(
        candidates: List<AutoConnectProbeCandidate>,
        preference: SmartProfilePreference?,
        networkFingerprintKey: String? = null,
        networkContext: NetworkFingerprint? = null,
        now: Long = System.currentTimeMillis(),
    ): List<AdaptiveProtocolCandidateScore> {
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
                    )
                val networkMatchBonus = resolveNetworkMatchBonus(scopedMemory, globalMemory, networkContext)
                val latencyPenalty = resolveLatencyPenalty(scopedMemory, globalMemory)
                val recentFailurePenalty = resolveRecentFailurePenalty(scopedMemory, globalMemory, now)
                val validationFailurePenalty =
                    resolveValidationFailurePenalty(
                        scopedMemory = scopedMemory,
                        globalMemory = globalMemory,
                        privateDnsActive = networkContext?.privateDnsActive == true,
                    )
                val explorationBonus = resolveExplorationBonus(scopedMemory, globalMemory, networkContext, now)
                IndexedScore(
                    index = index,
                    score =
                        AdaptiveProtocolCandidateScore(
                            candidate = candidate,
                            score =
                                (successRate * 45.0).roundToInt() +
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
    ): Int =
        when {
            optionId == scopedLastKnownGoodOptionId -> 16 + freshnessBonus(scopedLastKnownGoodAt, now)
            optionId == globalLastKnownGoodOptionId -> 8 + freshnessBonus(globalLastKnownGoodAt, now)
            else -> 0
        }

    private fun resolveNetworkMatchBonus(
        scopedMemory: SmartProfileProtocolMemory?,
        globalMemory: SmartProfileProtocolMemory?,
        networkContext: NetworkFingerprint?,
    ): Int {
        var bonus = 0
        if (scopedMemory != null) {
            bonus += 12
        } else if (globalMemory != null) {
            bonus += 4
        }
        if (networkContext?.upstreamValidated == true && (scopedMemory?.lastValidatedAt != null || globalMemory?.lastValidatedAt != null)) {
            bonus += 4
        }
        if (networkContext?.privateDnsActive == true && scopedMemory?.lastValidatedAt != null) {
            bonus += 2
        }
        if (scopedMemory?.lastTrafficAt != null) {
            bonus += 3
        }
        return bonus
    }

    private fun resolveLatencyPenalty(
        scopedMemory: SmartProfileProtocolMemory?,
        globalMemory: SmartProfileProtocolMemory?,
    ): Int {
        val memory = scopedMemory ?: globalMemory ?: return 0
        val latencyPenalty =
            memory.lastLatencyMs
                ?.let { latencyMs ->
                    ((latencyMs.coerceAtLeast(120L) - 120L) / 40L).toInt().coerceAtMost(18)
                } ?: 0
        val connectPenalty =
            memory.lastConnectDurationMs
                ?.let { durationMs ->
                    ((durationMs.coerceAtLeast(1_200L) - 1_200L) / 250L).toInt().coerceAtMost(10)
                } ?: 0
        return latencyPenalty + connectPenalty
    }

    private fun resolveRecentFailurePenalty(
        scopedMemory: SmartProfileProtocolMemory?,
        globalMemory: SmartProfileProtocolMemory?,
        now: Long,
    ): Int {
        val memory = scopedMemory ?: globalMemory ?: return 0
        val cooldownPenalty =
            memory.cooldownUntilAt
                ?.takeIf { it > now }
                ?.let { cooldownUntil ->
                    val remainingMinutes = ((cooldownUntil - now) / 60_000L).coerceAtLeast(1L)
                    24 + minOf(remainingMinutes.toInt() * 3, 36)
                } ?: 0
        val recencyPenalty =
            memory.lastFailureAt
                ?.let { lastFailureAt ->
                    when (now - lastFailureAt) {
                        in 0..(5L * 60L * 1000L) -> 14
                        in 0..(30L * 60L * 1000L) -> 10
                        in 0..(6L * 60L * 60L * 1000L) -> 6
                        else -> 0
                    }
                } ?: 0
        return cooldownPenalty + recencyPenalty + memory.failureStreak.coerceAtMost(4) * 4
    }

    private fun resolveValidationFailurePenalty(
        scopedMemory: SmartProfileProtocolMemory?,
        globalMemory: SmartProfileProtocolMemory?,
        privateDnsActive: Boolean,
    ): Int {
        val memory = scopedMemory ?: globalMemory ?: return 0
        val lastFailureAt = memory.lastFailureAt ?: return 0
        val lastSuccessAt = memory.lastSuccessAt ?: Long.MIN_VALUE
        if (lastFailureAt < lastSuccessAt) {
            return 0
        }
        return when (memory.lastReasonCode) {
            AutoConnectReasonCode.VALIDATION_TIMEOUT -> 16
            AutoConnectReasonCode.DNS_FAILURE -> if (privateDnsActive) 18 else 12
            AutoConnectReasonCode.HANDSHAKE_TIMEOUT -> 10
            AutoConnectReasonCode.CONNECT_ERROR -> 8
            AutoConnectReasonCode.LATENCY_ENDPOINT_BLOCKED -> 4
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
    ): Int {
        val memory = scopedMemory ?: globalMemory
        if (memory?.cooldownUntilAt?.let { it > now } == true) {
            return 0
        }
        val successEvidence = memory?.successCount ?: 0
        val failureEvidence = memory?.failureCount ?: 0
        return when {
            memory == null -> if (networkContext == null) 6 else 8
            successEvidence == 0 && failureEvidence == 0 -> 6
            scopedMemory == null -> 3
            else -> 0
        }
    }

    private fun freshnessBonus(
        referenceAt: Long?,
        now: Long,
    ): Int =
        when {
            referenceAt == null -> 0
            now - referenceAt <= 24L * 60L * 60L * 1000L -> 4
            now - referenceAt <= 7L * 24L * 60L * 60L * 1000L -> 2
            else -> 0
        }

    private data class IndexedScore(
        val index: Int,
        val score: AdaptiveProtocolCandidateScore,
    )
}
