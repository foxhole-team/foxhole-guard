package com.foxhole.beta.vpn

import com.foxhole.beta.core.diagnostics.DiagnosticsLogger
import com.foxhole.beta.core.model.TrafficMode
import com.foxhole.beta.core.model.VpnSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal const val RUNTIME_STOP_TIMEOUT_MS = 3_000L
internal const val RUNTIME_START_TIMEOUT_MS = 20_000L
internal const val TOR_RUNTIME_START_TIMEOUT_MS = 240_000L

internal fun runtimeStartTimeoutMsForSession(session: VpnSession): Long =
    if (session.profileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID || session.configJson.hasTorOutbound()) {
        TOR_RUNTIME_START_TIMEOUT_MS
    } else {
        RUNTIME_START_TIMEOUT_MS
    }

private fun String.hasTorOutbound(): Boolean =
    runCatching {
        runtimeStopJson
            .parseToJsonElement(this)
            .jsonObject["outbounds"]
            ?.jsonArray
            .orEmpty()
            .map { it.jsonObject }
            .any { outbound ->
                outbound["type"]?.jsonPrimitive?.contentOrNull == "tor" ||
                    outbound["tag"]?.jsonPrimitive?.contentOrNull == "tor-over-vpn"
            }
    }.getOrDefault(false)

private val runtimeStopJson =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

data class RuntimeStopPolicy(
    val closeTunFdImmediately: Boolean = true,
    val closeServiceTimeoutMs: Long = 700L,
    val closeServerTimeoutMs: Long = 700L,
    val totalGracefulTimeoutMs: Long = 1_500L,
    val forceKillAfterTimeout: Boolean = true,
)

data class RuntimeStopResult(
    val closeServiceOk: Boolean,
    val closeServerOk: Boolean,
    val tunClosed: Boolean,
    val escalatedToKill: Boolean,
    val elapsedMs: Long,
) {
    val graceful: Boolean
        get() = closeServiceOk && closeServerOk && !escalatedToKill
}

data class RuntimeKillResult(
    val reason: String,
    val tunClosed: Boolean,
    val serverDetached: Boolean,
    val closeDetached: Boolean = false,
)

data class NativeRuntimeSnapshot(
    val hasCommandServer: Boolean,
    val hasTunFileDescriptor: Boolean,
    val hasHost: Boolean,
    val hasConfig: Boolean,
    val dnsServerAddress: String?,
    val nativeGeneration: Long,
    val nativeState: RuntimeState,
    val cleanupUnresolved: Boolean,
    val lastStopReason: String?,
    val lastCloseDetached: Boolean,
) {
    companion object {
        val NONE =
            NativeRuntimeSnapshot(
                hasCommandServer = false,
                hasTunFileDescriptor = false,
                hasHost = false,
                hasConfig = false,
                dnsServerAddress = null,
                nativeGeneration = 0L,
                nativeState = RuntimeState.IDLE,
                cleanupUnresolved = false,
                lastStopReason = null,
                lastCloseDetached = false,
            )
    }
}

internal const val RUNTIME_CLEANUP_UNRESOLVED_MESSAGE =
    "Runtime cleanup is unresolved; restart FoxHole before reconnecting"

internal class RuntimeCleanupUnresolvedException :
    IllegalStateException(RUNTIME_CLEANUP_UNRESOLVED_MESSAGE)

enum class RuntimeState {
    IDLE,
    PREPARING,
    STARTING,
    VALIDATING,
    RUNNING,
    RELOADING,
    STOPPING,
    KILLING,
    RESTARTING,
    ERROR,
}

data class RuntimeGeneration(
    val id: Long,
    val sessionId: String?,
    val mode: TrafficMode,
    val profileId: Long?,
)

data class RuntimeSupervisorSnapshot(
    val state: RuntimeState = RuntimeState.IDLE,
    val generation: RuntimeGeneration? = null,
    val native: NativeRuntimeSnapshot? = null,
    val message: String? = null,
)

internal suspend fun runBlockingRuntimeClose(
    timeoutMs: Long,
    block: () -> Unit,
): Boolean {
    return withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) {
        withContext(RuntimeNativeCallDispatcher.dispatcher) {
            runCatching {
                block()
                true
            }.getOrDefault(false)
        }
    } == true
}

internal suspend fun VpnCoreRuntime.startFailClosed(
    session: VpnSession,
    host: RuntimeServiceHost,
    owner: String,
    diagnosticsLogger: DiagnosticsLogger,
    timeoutMessage: String,
    timeoutMs: Long = RUNTIME_START_TIMEOUT_MS,
): Result<Unit> {
    val result =
        withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) {
            withContext(RuntimeNativeCallDispatcher.dispatcher) {
                start(session, host)
            }
        }
    return if (result != null) {
        result
    } else {
        withContext(NonCancellable + Dispatchers.IO) {
            diagnosticsLogger.recordStructured(
                "runtime",
                "$owner runtime start timeout",
                "sessionId=${session.correlationId}",
                "timeout_ms=$timeoutMs",
            )
            forceKill("${owner}_start_timeout")
        }
        Result.failure(IllegalStateException(timeoutMessage))
    }
}

internal suspend fun VpnCoreRuntime.stopFailClosed(
    owner: String,
    reason: String,
    diagnosticsLogger: DiagnosticsLogger,
): RuntimeStopResult {
    val policy =
        RuntimeStopPolicy(
            closeTunFdImmediately = true,
            closeServiceTimeoutMs = 700L,
            closeServerTimeoutMs = 700L,
            totalGracefulTimeoutMs = RUNTIME_STOP_TIMEOUT_MS,
            forceKillAfterTimeout = true,
        )
    return withTimeoutOrNull(RUNTIME_STOP_TIMEOUT_MS) {
        stop(policy)
    } ?: withContext(Dispatchers.IO) {
        diagnosticsLogger.recordStructured(
            "runtime",
            "$owner runtime stop timeout",
            "reason=$reason",
            "timeout_ms=$RUNTIME_STOP_TIMEOUT_MS",
        )
        val killResult = forceKill("${owner}_${reason}_stop_timeout")
        RuntimeStopResult(
            closeServiceOk = false,
            closeServerOk = false,
            tunClosed = killResult.tunClosed,
            escalatedToKill = true,
            elapsedMs = RUNTIME_STOP_TIMEOUT_MS,
        )
    }
}

internal fun stopRuntimeAfterServiceDestroy(
    runtime: VpnCoreRuntime,
    diagnosticsLogger: DiagnosticsLogger,
    owner: String,
) {
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        val result = runtime.stopFailClosed(owner = owner, reason = "destroy", diagnosticsLogger = diagnosticsLogger)
        diagnosticsLogger.record(
            "runtime",
            if (result.graceful) {
                "$owner runtime stop completed after service destroy elapsed_ms=${result.elapsedMs}"
            } else {
                "$owner runtime stop escalated after service destroy elapsed_ms=${result.elapsedMs}"
            },
        )
        RuntimeHealthMetrics.recordStopAfterDestroy(
            owner = owner,
            stopped = result.graceful,
            diagnosticsLogger = diagnosticsLogger,
        )
    }
}
