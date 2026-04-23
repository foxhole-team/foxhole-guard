package com.foxhole.beta.core.profile

import com.foxhole.beta.core.model.AutoConnectReasonCode
import com.foxhole.beta.core.model.Profile
import com.foxhole.beta.core.model.ProfileProtocolOption
import com.foxhole.beta.core.model.ProtocolHint
import com.foxhole.beta.core.model.SmartProfilePreference
import com.foxhole.beta.core.network.NetworkFingerprint
import com.foxhole.beta.core.smart.AdaptiveProtocolCandidateScore
import com.foxhole.beta.core.smart.AdaptiveProtocolRanker

data class AutoConnectProbeCandidate(
    val profileId: Long,
    val optionId: String,
    val protocolHint: ProtocolHint,
    val displayName: String,
)

data class AutoConnectProbeResult(
    val candidate: AutoConnectProbeCandidate,
    val success: Boolean,
    val latencyMs: Long,
    val rankingLatencyMs: Long = latencyMs,
    val displayLatencyMs: Long? = latencyMs,
    val connectDurationMs: Long = latencyMs,
    val validatedAt: Long? = null,
    val trafficObservedAt: Long? = null,
    val failureReason: String? = null,
    val reasonCode: AutoConnectReasonCode? = null,
) {
    init {
        require(latencyMs >= 0L) { "latency must be non-negative" }
        require(rankingLatencyMs >= 0L) { "ranking latency must be non-negative" }
        require(displayLatencyMs == null || displayLatencyMs >= 0L) { "display latency must be non-negative" }
        require(connectDurationMs >= 0L) { "connect duration must be non-negative" }
        require(success || !failureReason.isNullOrBlank()) { "failed probes must include a reason" }
        require(success || reasonCode != null) { "failed probes must include a reason code" }
    }
}

object MultiProtocolProfileSupport {
    private val unsupportedProtocolHints = setOf(ProtocolHint.UNKNOWN, ProtocolHint.SING_BOX)

    fun supportedOptions(profile: Profile): List<ProfileProtocolOption> =
        supportedOptions(profile.protocolOptions)

    fun supportedOptions(options: List<ProfileProtocolOption>): List<ProfileProtocolOption> =
        options
            .filterNot { it.protocolHint in unsupportedProtocolHints }
            .distinctBy(ProfileProtocolOption::id)

    fun selectedOption(profile: Profile): ProfileProtocolOption? {
        val options = supportedOptions(profile)
        return options.firstOrNull { it.id == profile.selectedProtocolOptionId }
            ?: options.firstOrNull(ProfileProtocolOption::isSelected)
            ?: options.firstOrNull()
    }

    fun hasMultipleSupportedOptions(profile: Profile?): Boolean =
        profile?.let { supportedOptions(it).size >= 2 } == true

    fun scoredProbeCandidates(
        profile: Profile,
        preference: SmartProfilePreference? = null,
        networkFingerprint: String? = null,
        networkContext: NetworkFingerprint? = null,
        now: Long = System.currentTimeMillis(),
    ): List<AdaptiveProtocolCandidateScore> {
        val candidates =
            supportedOptions(profile).map { option ->
                AutoConnectProbeCandidate(
                    profileId = profile.id,
                    optionId = option.id,
                    protocolHint = option.protocolHint,
                    displayName = option.displayName.ifBlank { option.protocolHint.name },
                )
            }
        return AdaptiveProtocolRanker.scoreCandidates(
            candidates = candidates,
            preference = preference,
            networkFingerprintKey = networkFingerprint,
            networkContext = networkContext,
            now = now,
        )
    }

    fun probeCandidates(
        profile: Profile,
        preference: SmartProfilePreference? = null,
        networkFingerprint: String? = null,
        networkContext: NetworkFingerprint? = null,
        now: Long = System.currentTimeMillis(),
    ): List<AutoConnectProbeCandidate> =
        scoredProbeCandidates(
            profile = profile,
            preference = preference,
            networkFingerprint = networkFingerprint,
            networkContext = networkContext,
            now = now,
        ).map(AdaptiveProtocolCandidateScore::candidate)

    fun fastestSuccessfulProbe(results: List<AutoConnectProbeResult>): AutoConnectProbeResult? =
        results
            .filter(AutoConnectProbeResult::success)
            .minWithOrNull(
                compareBy<AutoConnectProbeResult> { it.rankingLatencyMs }
                    .thenBy { it.candidate.optionId },
            )
}
