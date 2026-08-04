package com.foxhole.core.runtime

data class TunnelValidationGracePolicy(
    val attempts: Int,
    val initialDelayMs: Long,
    val retryDelayMs: Long,
    val callTimeoutMs: Long,
    val totalTimeoutMs: Long,
)

const val CONNECTIVITY_PROBE_GRACE_MAX_TIMEOUT_MS = 8_000L

fun selectTunnelValidationGracePolicy(
    evidence: TunnelValidationEvidence?,
): TunnelValidationGracePolicy? {
    if (evidence?.hasSuccessfulTunnelActivity != true || evidence.fatalRuntimeMessage != null) {
        return null
    }
    return TunnelValidationGracePolicy(
        attempts = 2,
        initialDelayMs = 1_000L,
        retryDelayMs = 1_000L,
        callTimeoutMs = 3_500L,
        totalTimeoutMs = CONNECTIVITY_PROBE_GRACE_MAX_TIMEOUT_MS,
    )
}

fun maxTunnelValidationGraceTimeoutMs(): Long = CONNECTIVITY_PROBE_GRACE_MAX_TIMEOUT_MS
