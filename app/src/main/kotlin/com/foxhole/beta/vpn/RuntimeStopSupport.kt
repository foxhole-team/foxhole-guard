package com.foxhole.beta.vpn

import com.foxhole.beta.core.diagnostics.DiagnosticsLogger
import com.foxhole.beta.core.model.TrafficMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal const val RUNTIME_STOP_TIMEOUT_MS = 3_000L

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
)

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
    val closeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val deferred =
        closeScope.async {
            block()
            true
        }
    return try {
        withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) {
            deferred.await()
        } == true
    } finally {
        if (!deferred.isCompleted) {
            deferred.cancel()
        }
        closeScope.cancel()
    }
}

internal fun stopRuntimeAfterServiceDestroy(
    runtime: VpnCoreRuntime,
    diagnosticsLogger: DiagnosticsLogger,
    owner: String,
) {
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        val policy =
            RuntimeStopPolicy(
                closeServiceTimeoutMs = 700L,
                closeServerTimeoutMs = 700L,
                totalGracefulTimeoutMs = RUNTIME_STOP_TIMEOUT_MS,
                forceKillAfterTimeout = true,
            )
        val result =
            withTimeoutOrNull(RUNTIME_STOP_TIMEOUT_MS) {
                runtime.stop(policy)
            } ?: withContext(Dispatchers.IO) {
                runtime.forceKill("${owner}_destroy_stop_timeout")
                RuntimeStopResult(
                    closeServiceOk = false,
                    closeServerOk = false,
                    tunClosed = true,
                    escalatedToKill = true,
                    elapsedMs = RUNTIME_STOP_TIMEOUT_MS,
                )
            }
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
