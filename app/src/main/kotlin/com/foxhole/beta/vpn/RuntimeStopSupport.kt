package com.foxhole.beta.vpn

import com.foxhole.beta.core.diagnostics.DiagnosticsLogger
import com.foxhole.beta.core.model.TrafficMode
import com.foxhole.beta.core.model.VpnSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal const val RUNTIME_STOP_TIMEOUT_MS = 3_000L
internal const val RUNTIME_START_TIMEOUT_MS = 20_000L
internal const val TOR_RUNTIME_START_TIMEOUT_MS = 75_000L

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
            runCatching {
                block()
                true
            }.getOrDefault(false)
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

internal suspend fun VpnCoreRuntime.startFailClosed(
    session: VpnSession,
    host: RuntimeServiceHost,
    owner: String,
    diagnosticsLogger: DiagnosticsLogger,
    timeoutMessage: String,
    timeoutMs: Long = RUNTIME_START_TIMEOUT_MS,
): Result<Unit> {
    val startScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val deferred =
        startScope.async {
            start(session, host)
        }
    return try {
        val result =
            withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) {
                deferred.await()
            }
        if (result != null) {
            result
        } else {
            deferred.cancel()
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
    } catch (cancelled: CancellationException) {
        deferred.cancel()
        throw cancelled
    } finally {
        startScope.cancel()
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
