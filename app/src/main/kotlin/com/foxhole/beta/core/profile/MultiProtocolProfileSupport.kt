package com.foxhole.beta.core.profile

import com.foxhole.beta.core.model.AutoConnectReasonCode
import com.foxhole.beta.core.model.Profile
import com.foxhole.beta.core.model.ProfileProtocolOption
import com.foxhole.beta.core.model.ProtocolHint
import com.foxhole.beta.core.model.SmartProfilePreference
import com.foxhole.beta.core.model.SmartStartTransportPriority
import com.foxhole.beta.core.model.isUdpTransport
import com.foxhole.beta.core.network.NetworkFingerprint
import com.foxhole.beta.core.smart.AdaptiveProtocolCandidateScore
import com.foxhole.beta.core.smart.AdaptiveProtocolRanker
import com.foxhole.beta.core.smart.SmartStartController
import kotlin.random.Random

private val unsupportedProtocolHints = setOf(ProtocolHint.UNKNOWN, ProtocolHint.SING_BOX)

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
        transportPriority: SmartStartTransportPriority = SmartStartTransportPriority.ALL,
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
            ).withTransportPriority(transportPriority) { score -> score.candidate.protocolHint }
        val attempts = if (controlledExploration) {
            SmartStartController.rankedAttempts(
                rankedCandidates = ranked,
                randomDouble = randomDouble,
                randomIndex = randomIndex,
            )
        } else {
            ranked
        }
        return attempts.withTransportPriority(transportPriority) { score -> score.candidate.protocolHint }
    }

    fun smartStartEligibleProbeCandidates(
        profile: Profile,
        preference: SmartProfilePreference? = null,
        networkFingerprint: String? = null,
        allowInsecureTlsGlobally: Boolean = false,
        excludedOptionIds: Set<String> = emptySet(),
        transportPriority: SmartStartTransportPriority = SmartStartTransportPriority.ALL,
        now: Long = System.currentTimeMillis(),
    ): List<AutoConnectProbeCandidate> {
        val insecureTlsConsentGranted = profile.requiresInsecureTls || allowInsecureTlsGlobally
        return SmartStartController.eligibleCandidatesForRanking(
            candidates =
                autoConnectOptions(profile).map { option ->
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
        ).withTransportPriority(transportPriority) { candidate -> candidate.protocolHint }
    }

    fun smartStartFullScanCandidates(
        profile: Profile,
        allowInsecureTlsGlobally: Boolean = false,
        excludedOptionIds: Set<String> = emptySet(),
        transportPriority: SmartStartTransportPriority = SmartStartTransportPriority.ALL,
        now: Long = System.currentTimeMillis(),
    ): List<AutoConnectProbeCandidate> {
        val insecureTlsConsentGranted = profile.requiresInsecureTls || allowInsecureTlsGlobally
        return SmartStartController.eligibleCandidatesForFullScan(
            candidates =
                autoConnectOptions(profile).map { option ->
                    option.toProbeCandidate(
                        profileId = profile.id,
                        insecureTlsConsentGranted = insecureTlsConsentGranted,
                    )
                },
            excludedOptionIds = excludedOptionIds,
            subscriptionExpiresAt = profile.subscriptionExpiresAt,
            now = now,
        ).withTransportPriority(transportPriority) { candidate -> candidate.protocolHint }
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
}

internal fun <T> List<T>.withTransportPriority(
    priority: SmartStartTransportPriority,
    protocolHint: (T) -> ProtocolHint,
): List<T> =
    when (priority) {
        SmartStartTransportPriority.ALL -> this
        SmartStartTransportPriority.UDP -> sortedByTransportMatch { item -> protocolHint(item).isUdpTransport() }
        SmartStartTransportPriority.TCP -> sortedByTransportMatch { item -> !protocolHint(item).isUdpTransport() }
    }

private fun <T> List<T>.sortedByTransportMatch(matches: (T) -> Boolean): List<T> {
    if (size < 2) {
        return this
    }
    val matching = mutableListOf<T>()
    val fallback = mutableListOf<T>()
    forEach { item ->
        if (matches(item)) {
            matching += item
        } else {
            fallback += item
        }
    }
    return matching + fallback
}

private fun autoConnectOptions(profile: Profile): List<ProfileProtocolOption> {
    val explicitOptions = MultiProtocolProfileSupport.supportedOptions(profile)
    return when {
        explicitOptions.isNotEmpty() -> explicitOptions
        profile.protocolHint in unsupportedProtocolHints -> emptyList()
        else ->
            listOf(
                ProfileProtocolOption(
                    id = profile.protocolHint.name.lowercase(),
                    displayName = profile.protocolHint.name,
                    protocolHint = profile.protocolHint,
                    requiresInsecureTls = profile.requiresInsecureTls,
                    isSelected = true,
                ),
            )
    }
}

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
