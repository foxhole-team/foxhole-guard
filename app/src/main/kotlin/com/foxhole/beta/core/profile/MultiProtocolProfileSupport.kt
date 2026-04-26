package com.foxhole.beta.core.profile

import com.foxhole.beta.core.model.AutoConnectReasonCode
import com.foxhole.beta.core.model.Profile
import com.foxhole.beta.core.model.ProfileProtocolOption
import com.foxhole.beta.core.model.ProtocolHint
import com.foxhole.beta.core.model.SmartProfilePreference
import com.foxhole.beta.core.network.NetworkFingerprint
import com.foxhole.beta.core.smart.AdaptiveProtocolCandidateScore
import com.foxhole.beta.core.smart.AdaptiveProtocolRanker
import com.foxhole.beta.core.smart.SmartStartController
import kotlin.random.Random

data class AutoConnectProbeCandidate(
    val profileId: Long,
    val optionId: String,
    val protocolHint: ProtocolHint,
    val displayName: String,
    val requiresInsecureTls: Boolean = false,
    val insecureTlsConsentGranted: Boolean = false,
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
        allowInsecureTlsGlobally: Boolean = false,
        now: Long = System.currentTimeMillis(),
        excludedOptionIds: Set<String> = emptySet(),
        controlledExploration: Boolean = false,
        randomDouble: () -> Double = { Random.nextDouble() },
        randomIndex: (Int) -> Int = { bound -> Random.nextInt(bound) },
    ): List<AdaptiveProtocolCandidateScore> {
        val candidates =
            smartStartEligibleProbeCandidates(
                profile = profile,
                preference = preference,
                networkFingerprint = networkFingerprint,
                allowInsecureTlsGlobally = allowInsecureTlsGlobally,
                excludedOptionIds = excludedOptionIds,
                now = now,
            )
        val ranked =
            AdaptiveProtocolRanker.scoreCandidates(
                candidates = candidates,
                preference = preference,
                networkFingerprintKey = networkFingerprint,
                networkContext = networkContext,
                now = now,
            )
        return if (controlledExploration) {
            SmartStartController.rankedAttempts(
                rankedCandidates = ranked,
                randomDouble = randomDouble,
                randomIndex = randomIndex,
            )
        } else {
            ranked
        }
    }

    fun smartStartEligibleProbeCandidates(
        profile: Profile,
        preference: SmartProfilePreference? = null,
        networkFingerprint: String? = null,
        allowInsecureTlsGlobally: Boolean = false,
        excludedOptionIds: Set<String> = emptySet(),
        now: Long = System.currentTimeMillis(),
    ): List<AutoConnectProbeCandidate> {
        val insecureTlsConsentGranted = profile.requiresInsecureTls || allowInsecureTlsGlobally
        return SmartStartController.eligibleCandidatesForRanking(
            candidates =
                supportedOptions(profile).map { option ->
                    option.toProbeCandidate(
                        profileId = profile.id,
                        insecureTlsConsentGranted = insecureTlsConsentGranted,
                    )
                },
            preference = preference,
            networkFingerprint = networkFingerprint,
            excludedOptionIds = excludedOptionIds,
            subscriptionExpiresAt = profile.subscriptionExpiresAt,
            now = now,
        )
    }

    fun smartStartFullScanCandidates(
        profile: Profile,
        allowInsecureTlsGlobally: Boolean = false,
        excludedOptionIds: Set<String> = emptySet(),
        now: Long = System.currentTimeMillis(),
    ): List<AutoConnectProbeCandidate> {
        val insecureTlsConsentGranted = profile.requiresInsecureTls || allowInsecureTlsGlobally
        return SmartStartController.eligibleCandidatesForFullScan(
            candidates =
                supportedOptions(profile).map { option ->
                    option.toProbeCandidate(
                        profileId = profile.id,
                        insecureTlsConsentGranted = insecureTlsConsentGranted,
                    )
                },
            excludedOptionIds = excludedOptionIds,
            subscriptionExpiresAt = profile.subscriptionExpiresAt,
            now = now,
        )
    }

    fun probeCandidates(
        profile: Profile,
        preference: SmartProfilePreference? = null,
        networkFingerprint: String? = null,
        networkContext: NetworkFingerprint? = null,
        allowInsecureTlsGlobally: Boolean = false,
        now: Long = System.currentTimeMillis(),
    ): List<AutoConnectProbeCandidate> =
        scoredProbeCandidates(
            profile = profile,
            preference = preference,
            networkFingerprint = networkFingerprint,
            networkContext = networkContext,
            allowInsecureTlsGlobally = allowInsecureTlsGlobally,
            now = now,
        ).map(AdaptiveProtocolCandidateScore::candidate)

    fun fastestSuccessfulProbe(results: List<AutoConnectProbeResult>): AutoConnectProbeResult? =
        results
            .filter(AutoConnectProbeResult::success)
            .minWithOrNull(
                compareBy<AutoConnectProbeResult> { it.rankingLatencyMs }
                    .thenBy { it.candidate.optionId },
            )

    private fun ProfileProtocolOption.toProbeCandidate(
        profileId: Long,
        insecureTlsConsentGranted: Boolean,
    ): AutoConnectProbeCandidate =
        AutoConnectProbeCandidate(
            profileId = profileId,
            optionId = id,
            protocolHint = protocolHint,
            displayName = displayName.ifBlank { protocolHint.name },
            requiresInsecureTls = requiresInsecureTls,
            insecureTlsConsentGranted = insecureTlsConsentGranted,
        )
}
