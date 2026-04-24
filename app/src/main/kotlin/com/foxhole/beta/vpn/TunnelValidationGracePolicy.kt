package com.foxhole.beta.vpn

import com.foxhole.beta.core.model.ProtocolHint

internal data class TunnelValidationGracePolicy(
    val attempts: Int,
    val initialDelayMs: Long,
    val retryDelayMs: Long,
    val callTimeoutMs: Long,
    val totalTimeoutMs: Long,
)

internal const val CONNECTIVITY_PROBE_GRACE_MAX_TIMEOUT_MS = 24_000L

internal fun selectTunnelValidationGracePolicy(
    protocolHint: ProtocolHint?,
    evidence: TunnelValidationEvidence?,
): TunnelValidationGracePolicy? {
    if (evidence?.hasSuccessfulTunnelActivity != true || evidence.fatalRuntimeMessage != null) {
        return null
    }
    return when (protocolHint) {
        ProtocolHint.HYSTERIA2,
        ProtocolHint.SHADOWSOCKS,
        ProtocolHint.WIREGUARD,
        ProtocolHint.OUTLINE ->
            TunnelValidationGracePolicy(
                attempts = 4,
                initialDelayMs = 1_500L,
                retryDelayMs = 1_500L,
                callTimeoutMs = 5_000L,
                totalTimeoutMs = CONNECTIVITY_PROBE_GRACE_MAX_TIMEOUT_MS,
            )

        else ->
            TunnelValidationGracePolicy(
                attempts = 2,
                initialDelayMs = 1_000L,
                retryDelayMs = 1_000L,
                callTimeoutMs = 3_500L,
                totalTimeoutMs = 8_000L,
            )
    }
}

internal fun maxTunnelValidationGraceTimeoutMs(protocolHint: ProtocolHint?): Long =
    when (protocolHint) {
        ProtocolHint.HYSTERIA2,
        ProtocolHint.SHADOWSOCKS,
        ProtocolHint.WIREGUARD,
        ProtocolHint.OUTLINE -> CONNECTIVITY_PROBE_GRACE_MAX_TIMEOUT_MS

        else -> 8_000L
    }
