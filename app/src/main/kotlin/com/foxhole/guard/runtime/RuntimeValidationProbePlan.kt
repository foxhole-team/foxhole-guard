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
)

internal fun runtimeValidationProbePlan(session: VpnSession?): RuntimeValidationProbePlan =
    if (session.hasTorRuntime()) {
        RuntimeValidationProbePlan(
            attempts = TOR_CONNECTIVITY_PROBE_ATTEMPTS,
            initialDelayMs = TOR_CONNECTIVITY_PROBE_INITIAL_DELAY_MS,
            retryDelayMs = TOR_CONNECTIVITY_PROBE_RETRY_DELAY_MS,
            callTimeoutMs = TOR_CONNECTIVITY_PROBE_CALL_TIMEOUT_MS,
            literalCallTimeoutMs = TOR_CONNECTIVITY_LITERAL_PROBE_CALL_TIMEOUT_MS,
            totalTimeoutMs = TOR_CONNECTIVITY_PROBE_TOTAL_TIMEOUT_MS,
        )
    } else {
        RuntimeValidationProbePlan(
            attempts = FoxholeVpnService.CONNECTIVITY_PROBE_ATTEMPTS,
            initialDelayMs = FoxholeVpnService.CONNECTIVITY_PROBE_INITIAL_DELAY_MS,
            retryDelayMs = FoxholeVpnService.CONNECTIVITY_PROBE_RETRY_DELAY_MS,
            callTimeoutMs = FoxholeVpnService.CONNECTIVITY_PROBE_CALL_TIMEOUT_MS,
            literalCallTimeoutMs = FoxholeVpnService.CONNECTIVITY_LITERAL_PROBE_CALL_TIMEOUT_MS,
            totalTimeoutMs = defaultRuntimeValidationTotalTimeoutMs(),
        )
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

private val runtimeValidationProbePlanJson =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

private const val TOR_CONNECTIVITY_PROBE_ATTEMPTS = 6
private const val TOR_CONNECTIVITY_PROBE_INITIAL_DELAY_MS = 2_000L
private const val TOR_CONNECTIVITY_PROBE_RETRY_DELAY_MS = 2_000L
private const val TOR_CONNECTIVITY_PROBE_CALL_TIMEOUT_MS = 12_000L
private const val TOR_CONNECTIVITY_LITERAL_PROBE_CALL_TIMEOUT_MS = 5_000L
private const val TOR_CONNECTIVITY_PROBE_TOTAL_TIMEOUT_MS = 120_000L
