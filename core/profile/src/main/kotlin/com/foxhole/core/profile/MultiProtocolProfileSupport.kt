package com.foxhole.core.profile

import androidx.compose.runtime.Immutable
import com.foxhole.core.model.AutoConnectReasonCode
import com.foxhole.core.model.Profile
import com.foxhole.core.model.ProfileProtocolOption
import com.foxhole.core.model.ProtocolHint

private val unsupportedProtocolHints = setOf(ProtocolHint.UNKNOWN, ProtocolHint.CUSTOM_CONFIG)

@Immutable
data class AutoConnectProbeCandidate(
    val profileId: Long,
    val optionId: String,
    val protocolHint: ProtocolHint,
    val displayName: String,
    val requiresInsecureTls: Boolean = false,
    val insecureTlsConsentGranted: Boolean = false,
)

@Immutable
data class AutoConnectProbeResult(
    val candidate: AutoConnectProbeCandidate,
    val success: Boolean,
    val latencyMs: Long,
    val rankingLatencyMs: Long = latencyMs,
    val displayLatencyMs: Long? = latencyMs,
    val serverPingMs: Long? = null,
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
        require(serverPingMs == null || serverPingMs >= 0L) { "server ping must be non-negative" }
        require(connectDurationMs >= 0L) { "connect duration must be non-negative" }
        require(success || !failureReason.isNullOrBlank()) { "failed probes must include a reason" }
        require(success || reasonCode != null) { "failed probes must include a reason code" }
    }
}

object MultiProtocolProfileSupport {
    fun supportedOptions(profile: Profile): List<ProfileProtocolOption> =
        supportedOptions(profile.protocolOptions)

    fun supportedOptions(options: List<ProfileProtocolOption>): List<ProfileProtocolOption> =
        runnableProtocolOptions(options).filter(ProfileProtocolOption::enabled)

    fun selectedOption(profile: Profile): ProfileProtocolOption? {
        val options = supportedOptions(profile).ifEmpty { runnableProtocolOptions(profile.protocolOptions) }
        return options.firstOrNull { it.id == profile.selectedProtocolOptionId }
            ?: options.firstOrNull(ProfileProtocolOption::isSelected)
            ?: options.firstOrNull()
    }

    fun hasMultipleSupportedOptions(profile: Profile?): Boolean =
        profile?.let { supportedOptions(it).size >= 2 } == true

    fun smartStartFullScanCandidates(
        profile: Profile,
        allowInsecureTlsGlobally: Boolean = false,
        excludedOptionIds: Set<String> = emptySet(),
        now: Long = System.currentTimeMillis(),
    ): List<AutoConnectProbeCandidate> {
        val subscriptionExpiresAt = profile.subscriptionExpiresAt
        if (subscriptionExpiresAt != null && subscriptionExpiresAt <= now) {
            return emptyList()
        }
        val insecureTlsConsentGranted = profile.insecureTlsConsentGranted || allowInsecureTlsGlobally
        return autoConnectOptions(profile)
            .map { option ->
                option.toProbeCandidate(
                    profileId = profile.id,
                    insecureTlsConsentGranted = insecureTlsConsentGranted,
                )
            }
            .filter { candidate ->
                candidate.optionId !in excludedOptionIds &&
                    (!candidate.requiresInsecureTls || candidate.insecureTlsConsentGranted)
            }
    }
}

private fun runnableProtocolOptions(options: List<ProfileProtocolOption>): List<ProfileProtocolOption> =
    options
        .filterNot { it.protocolHint in unsupportedProtocolHints }
        .distinctBy(ProfileProtocolOption::id)

private fun autoConnectOptions(profile: Profile): List<ProfileProtocolOption> {
    val explicitOptions = MultiProtocolProfileSupport.supportedOptions(profile)
    return when {
        explicitOptions.isNotEmpty() -> explicitOptions

        runnableProtocolOptions(profile.protocolOptions).isNotEmpty() -> emptyList()
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
