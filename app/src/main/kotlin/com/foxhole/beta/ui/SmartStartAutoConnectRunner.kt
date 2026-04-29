package com.foxhole.beta.ui

import com.foxhole.beta.core.model.SmartProfilePreference
import com.foxhole.beta.core.profile.AutoConnectProbeCandidate
import com.foxhole.beta.core.smart.AdaptiveProtocolCandidateScore
import com.foxhole.beta.core.settings.needsSmartStartColdScan

internal sealed interface SmartStartAutoConnectState {
    val candidates: List<AutoConnectProbeCandidate>

    data class ColdScan(
        override val candidates: List<AutoConnectProbeCandidate>,
        val enabledProtocolSetHash: String,
    ) : SmartStartAutoConnectState

    data class FastAttempts(
        override val candidates: List<AutoConnectProbeCandidate>,
        val rankedCandidates: List<AdaptiveProtocolCandidateScore>,
    ) : SmartStartAutoConnectState
}

internal object SmartStartAutoConnectRunner {
    fun resolveState(
        fullScanCandidates: List<AutoConnectProbeCandidate>,
        enabledProtocolSetHash: String,
        preference: SmartProfilePreference?,
        rankedCandidates: List<AdaptiveProtocolCandidateScore>,
    ): SmartStartAutoConnectState {
        if (preference?.needsSmartStartColdScan(enabledProtocolSetHash) != false) {
            return SmartStartAutoConnectState.ColdScan(
                candidates = fullScanCandidates.take(HomeViewModel.AUTO_CONNECT_MAX_ATTEMPTS),
                enabledProtocolSetHash = enabledProtocolSetHash,
            )
        }
        return SmartStartAutoConnectState.FastAttempts(
            candidates =
                attemptCandidates(
                    rankedCandidates = rankedCandidates,
                    recommendedIds = preference.recommendedProtocolIds,
                ).map(AdaptiveProtocolCandidateScore::candidate),
            rankedCandidates = rankedCandidates,
        )
    }

    private fun attemptCandidates(
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
}
