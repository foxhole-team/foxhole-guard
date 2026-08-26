package com.foxhole.guard.runtime

import com.foxhole.core.model.TOR_ONLY_PROFILE_ID
import com.foxhole.core.model.VpnSession
import com.foxhole.core.runtime.maxTunnelValidationGraceTimeoutMs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class RuntimeValidationProbePlan(
    val attempts: Int,
    val initialDelayMs: Long,
    val retryDelayMs: Long,
    val callTimeoutMs: Long,
    val literalCallTimeoutMs: Long,
    val totalTimeoutMs: Long,
    val controlProbeReserveMs: Long,
    val maxRuntimeProxyWarmupAttempts: Int?,
)

internal fun runtimeValidationProbePlan(session: VpnSession?): RuntimeValidationProbePlan =
    if (session.hasTorRuntime() || session.requiresVerifiedTorExitForValidation()) {
        RuntimeValidationProbePlan(
            attempts = TOR_CONNECTIVITY_PROBE_ATTEMPTS,
            initialDelayMs = TOR_CONNECTIVITY_PROBE_INITIAL_DELAY_MS,
            retryDelayMs = TOR_CONNECTIVITY_PROBE_RETRY_DELAY_MS,
            callTimeoutMs =
            if (session.requiresDirectBridgedTorConnectWindow()) {
                DIRECT_BRIDGED_TOR_CONNECTIVITY_PROBE_CALL_TIMEOUT_MS
            } else {
                TOR_CONNECTIVITY_PROBE_CALL_TIMEOUT_MS
            },
            literalCallTimeoutMs = TOR_CONNECTIVITY_LITERAL_PROBE_CALL_TIMEOUT_MS,
            totalTimeoutMs = TOR_CONNECTIVITY_PROBE_TOTAL_TIMEOUT_MS,
            controlProbeReserveMs =
            STRICT_TOR_CONTROL_PROBE_RESERVE_MS.takeIf {
                session.requiresVerifiedTorExitForValidation()
            } ?: 0L,
            maxRuntimeProxyWarmupAttempts =
            DIRECT_BRIDGED_TOR_MAX_RUNTIME_PROXY_WARMUP_ATTEMPTS.takeIf {
                session.requiresDirectBridgedTorConnectWindow()
            },
        )
    } else {
        RuntimeValidationProbePlan(
            attempts = FoxholeVpnService.CONNECTIVITY_PROBE_ATTEMPTS,
            initialDelayMs = FoxholeVpnService.CONNECTIVITY_PROBE_INITIAL_DELAY_MS,
            retryDelayMs = FoxholeVpnService.CONNECTIVITY_PROBE_RETRY_DELAY_MS,
            callTimeoutMs = FoxholeVpnService.CONNECTIVITY_PROBE_CALL_TIMEOUT_MS,
            literalCallTimeoutMs = FoxholeVpnService.CONNECTIVITY_LITERAL_PROBE_CALL_TIMEOUT_MS,
            totalTimeoutMs = defaultRuntimeValidationTotalTimeoutMs(),
            controlProbeReserveMs = 0L,
            maxRuntimeProxyWarmupAttempts = null,
        )
    }

internal fun runtimeValidationDeadlineAt(
    startedAtElapsedRealtimeMs: Long,
    totalTimeoutMs: Long,
): Long {
    require(startedAtElapsedRealtimeMs >= 0L) { "startedAtElapsedRealtimeMs must not be negative" }
    require(totalTimeoutMs > 0L) { "totalTimeoutMs must be positive" }
    return if (startedAtElapsedRealtimeMs > Long.MAX_VALUE - totalTimeoutMs) {
        Long.MAX_VALUE
    } else {
        startedAtElapsedRealtimeMs + totalTimeoutMs
    }
}

internal fun remainingRuntimeValidationMs(
    deadlineAtElapsedRealtimeMs: Long,
    nowElapsedRealtimeMs: Long,
): Long {
    require(deadlineAtElapsedRealtimeMs >= 0L) { "deadlineAtElapsedRealtimeMs must not be negative" }
    require(nowElapsedRealtimeMs >= 0L) { "nowElapsedRealtimeMs must not be negative" }
    return if (nowElapsedRealtimeMs >= deadlineAtElapsedRealtimeMs) {
        0L
    } else {
        deadlineAtElapsedRealtimeMs - nowElapsedRealtimeMs
    }
}

internal fun remainingRuntimeValidationProofMs(
    deadlineAtElapsedRealtimeMs: Long,
    nowElapsedRealtimeMs: Long,
    controlProbeReserveMs: Long,
): Long =
    (
        remainingRuntimeValidationMs(deadlineAtElapsedRealtimeMs, nowElapsedRealtimeMs) -
            controlProbeReserveMs.coerceAtLeast(0L)
        ).coerceAtLeast(0L)

internal fun boundedRuntimeValidationCallTimeoutMs(
    requestedTimeoutMs: Long,
    remainingMs: Long,
): Long = minOf(requestedTimeoutMs.coerceAtLeast(0L), remainingMs.coerceAtLeast(0L))

internal fun runtimeValidationWatchdogTimeoutMs(
    deadlineAtElapsedRealtimeMs: Long,
    nowElapsedRealtimeMs: Long,
): Long =
    remainingRuntimeValidationMs(deadlineAtElapsedRealtimeMs, nowElapsedRealtimeMs)
        .coerceAtLeast(1L)
        .let { remaining ->
            if (remaining > Long.MAX_VALUE - RUNTIME_VALIDATION_SCHEDULER_SLACK_MS) {
                Long.MAX_VALUE
            } else {
                remaining + RUNTIME_VALIDATION_SCHEDULER_SLACK_MS
            }
        }

private fun defaultRuntimeValidationTotalTimeoutMs(): Long =
    FoxholeVpnService.CONNECTIVITY_PROBE_TOTAL_TIMEOUT_MS +
        maxTunnelValidationGraceTimeoutMs()

private fun VpnSession?.hasTorRuntime(): Boolean =
    this?.profileId == TOR_ONLY_PROFILE_ID ||
        this?.configJson.hasTorOutbound()

private fun String?.hasTorOutbound(): Boolean =
    runCatching {
        if (isNullOrBlank()) {
            false
        } else {
            runtimeValidationProbePlanJson
                .parseToJsonElement(this)
                .jsonObject["outbounds"]
                ?.jsonArray
                .orEmpty()
                .map { it.jsonObject }
                .any { outbound ->
                    outbound["type"]?.jsonPrimitive?.contentOrNull.equals("tor", ignoreCase = true)
                }
        }
    }.getOrDefault(false)

private fun VpnSession?.requiresDirectBridgedTorConnectWindow(): Boolean =
    this?.profileId == TOR_ONLY_PROFILE_ID && this.configJson.hasDirectBridgedTorOutbound()

private fun String?.hasDirectBridgedTorOutbound(): Boolean =
    runCatching {
        if (isNullOrBlank()) {
            false
        } else {
            runtimeValidationProbePlanJson
                .parseToJsonElement(this)
                .jsonObject["outbounds"]
                ?.jsonArray
                .orEmpty()
                .map { it.jsonObject }
                .any { outbound ->
                    outbound["type"]?.jsonPrimitive?.contentOrNull.equals("tor", ignoreCase = true) &&
                        outbound["detour"] == null &&
                        outbound["bridges"]?.jsonArray?.isNotEmpty() == true
                }
        }
    }.getOrDefault(false)

private val runtimeValidationProbePlanJson =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

private const val TOR_CONNECTIVITY_PROBE_ATTEMPTS = 1
private const val TOR_CONNECTIVITY_PROBE_INITIAL_DELAY_MS = 2_000L
private const val TOR_CONNECTIVITY_PROBE_RETRY_DELAY_MS = 2_000L
private const val TOR_CONNECTIVITY_PROBE_CALL_TIMEOUT_MS = 12_000L
private const val DIRECT_BRIDGED_TOR_CONNECTIVITY_PROBE_CALL_TIMEOUT_MS = 80_000L
private const val DIRECT_BRIDGED_TOR_MAX_RUNTIME_PROXY_WARMUP_ATTEMPTS = 1
private const val TOR_CONNECTIVITY_LITERAL_PROBE_CALL_TIMEOUT_MS = 5_000L
private const val TOR_CONNECTIVITY_PROBE_TOTAL_TIMEOUT_MS = 120_000L
internal const val STRICT_TOR_CONTROL_PROBE_RESERVE_MS = 5_000L
internal const val RUNTIME_VALIDATION_SCHEDULER_SLACK_MS = 250L
