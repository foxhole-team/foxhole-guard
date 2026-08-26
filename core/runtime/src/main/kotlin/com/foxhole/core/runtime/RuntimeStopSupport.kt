package com.foxhole.core.runtime

import com.foxhole.core.model.TrafficMode
import com.foxhole.core.model.VpnSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

internal const val RUNTIME_STOP_TIMEOUT_MS = 3_000L
internal const val RUNTIME_FORCE_KILL_TIMEOUT_MS = 1_500L
private const val RUNTIME_NATIVE_STOP_COMPLETION_MARGIN_MS = 750L
private const val RUNTIME_STOP_SUPERVISION_MARGIN_MS = 500L
const val RUNTIME_START_TIMEOUT_MS = 20_000L
const val TOR_RUNTIME_START_TIMEOUT_MS = 240_000L

fun runtimeStartTimeoutMsForSession(session: VpnSession): Long =
    if (session.torActive) {
        TOR_RUNTIME_START_TIMEOUT_MS
    } else {
        RUNTIME_START_TIMEOUT_MS
    }

data class RuntimeStopPolicy(
    val closeTunFdImmediately: Boolean = true,
    val closeServiceTimeoutMs: Long = 700L,
    val closeServerTimeoutMs: Long = 700L,
    val totalGracefulTimeoutMs: Long = 1_500L,
    val forceKillAfterTimeout: Boolean = true,
)

enum class NativeForceStopOutcome(
    val handleReleased: Boolean,
    val processPoisoned: Boolean,
) {
    NOT_ATTEMPTED(handleReleased = false, processPoisoned = false),
    RELEASED(handleReleased = true, processPoisoned = false),
    QUARANTINED(handleReleased = true, processPoisoned = true),
    FAILED(handleReleased = false, processPoisoned = true),
    CALL_TIMED_OUT(handleReleased = false, processPoisoned = true),
}

interface NativeForceStopPoisonSignal {
    val forceStopOutcome: NativeForceStopOutcome
}

class NativeForceStopPoisonedException(
    override val forceStopOutcome: NativeForceStopOutcome,
    operation: String,
) : IllegalStateException("android: native force stop poisoned process during $operation"),
    NativeForceStopPoisonSignal {
    init {
        require(forceStopOutcome.processPoisoned)
    }
}

class NativeForceStopPoisonedCancellationException(
    override val forceStopOutcome: NativeForceStopOutcome,
    operation: String,
    cause: CancellationException,
) : CancellationException("android: native force stop poisoned process during $operation"),
    NativeForceStopPoisonSignal {
    init {
        require(forceStopOutcome.processPoisoned)
        initCause(cause)
    }
}

fun Throwable?.nativeForceStopOutcomeOrNull(): NativeForceStopOutcome? {
    var current = this
    while (current != null) {
        val throwable = current
        val outcome = (throwable as? NativeForceStopPoisonSignal)?.forceStopOutcome
        if (outcome?.processPoisoned == true) return outcome
        current = throwable.cause?.takeUnless { cause -> cause === throwable }
    }
    return null
}

fun Result<*>.nativeForceStopOutcomeOrNull(): NativeForceStopOutcome? =
    exceptionOrNull().nativeForceStopOutcomeOrNull()

internal fun nativeForceStopPoisonedFailure(
    outcome: NativeForceStopOutcome,
    operation: String,
): Result<Unit> = Result.failure(NativeForceStopPoisonedException(outcome, operation))

data class RuntimeStopResult(
    val closeServiceOk: Boolean,
    val closeServerOk: Boolean,
    val tunClosed: Boolean,
    val escalatedToKill: Boolean,
    val elapsedMs: Long,
    val forceStopOutcome: NativeForceStopOutcome = NativeForceStopOutcome.NOT_ATTEMPTED,
) {
    val graceful: Boolean
        get() = closeServiceOk && closeServerOk && tunClosed && !escalatedToKill && !processPoisoned

    val processPoisoned: Boolean
        get() = forceStopOutcome.processPoisoned
}

data class RuntimeKillResult(
    val reason: String,
    val tunClosed: Boolean,
    val serverDetached: Boolean,
    val closeDetached: Boolean = false,
    val forceStopOutcome: NativeForceStopOutcome = NativeForceStopOutcome.NOT_ATTEMPTED,
) {
    val processPoisoned: Boolean
        get() = forceStopOutcome.processPoisoned
}

data class NativeRuntimeSnapshot(
    val hasEngineHandle: Boolean,
    val hasTunFileDescriptor: Boolean,
    val hasHost: Boolean,
    val hasConfig: Boolean,
    val dnsServerAddress: String?,
    val nativeGeneration: Long,
    val nativeState: RuntimeState,
    val cleanupDraining: Boolean,
    val lastStopReason: String?,
    val lastCloseDetached: Boolean,

    val masterTunFd: Int? = null,
) {
    companion object {
        val NONE =
            NativeRuntimeSnapshot(
                hasEngineHandle = false,
                hasTunFileDescriptor = false,
                hasHost = false,
                hasConfig = false,
                dnsServerAddress = null,
                nativeGeneration = 0L,
                nativeState = RuntimeState.IDLE,
                cleanupDraining = false,
                lastStopReason = null,
                lastCloseDetached = false,
                masterTunFd = null,
            )
    }
}

fun NativeRuntimeSnapshot.hasAttachedRuntimeResources(): Boolean =
    hasEngineHandle || hasTunFileDescriptor || hasHost || hasConfig

fun NativeRuntimeSnapshot.isIdleWithoutAttachedRuntimeResources(): Boolean =
    nativeState == RuntimeState.IDLE && !hasAttachedRuntimeResources()

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

internal data class RuntimeGeneration(
    val id: Long,
    val sessionId: String?,
    val mode: TrafficMode,
    val profileId: Long?,
)

internal suspend fun runBlockingRuntimeClose(
    timeoutMs: Long,
    registry: RuntimeNativeCallRegistry = RuntimeAbandonedCloseRegistry,
    block: () -> Unit,
): Boolean =
    withContext(Dispatchers.IO) {
        val outcome = CompletableFuture<Boolean>()
        val threadName = "FoxholeNativeClose-${RuntimeNativeThreadSequence.next()}"
        val lease = registry.tryAcquire(threadName) ?: return@withContext false
        try {
            Thread({
                val closed =
                    runCatching {
                        block()
                        true
                    }.getOrDefault(false)
                outcome.complete(closed)
                lease.complete()
            }, threadName).apply {
                isDaemon = true
                start()
            }
        } catch (_: Throwable) {
            lease.complete()
            return@withContext false
        }
        try {
            outcome.get(timeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            lease.markAbandonedIf { !outcome.isDone }
            false
        } catch (_: ExecutionException) {
            false
        } catch (interrupted: InterruptedException) {
            lease.markAbandonedIf { !outcome.isDone }
            Thread.currentThread().interrupt()
            false
        }
    }

internal suspend fun <T : Any> runAbandonableNativeRuntimeCall(
    operation: String,
    timeoutMs: Long,
    registry: RuntimeNativeCallRegistry = RuntimeAbandonedCloseRegistry,
    block: suspend () -> T,
): T? {
    val outcome = CompletableDeferred<Result<T>>()
    val threadName = "FoxholeNativeOp-$operation-${RuntimeNativeThreadSequence.next()}"
    val lease = registry.tryAcquire(threadName) ?: return null
    try {
        Thread({
            val settled = runCatching { runBlocking { block() } }
            outcome.complete(settled)
            lease.complete()
        }, threadName).apply {
            isDaemon = true
            start()
        }
    } catch (error: Throwable) {
        lease.complete()
        throw error
    }
    try {
        val settled = withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) { outcome.await() }
        return if (settled == null) {
            lease.markAbandonedIf { !outcome.isCompleted }
            null
        } else {
            settled.getOrThrow()
        }
    } catch (cancelled: CancellationException) {
        lease.markAbandonedIf { !outcome.isCompleted }
        throw cancelled
    }
}

internal open class RuntimeNativeCallRegistry(
    private val maxOwnedThreads: Int,
) {
    private val lock = Any()
    private val owned = LinkedHashMap<String, OwnedNativeCall>()
    private var abandonedTotal = 0L
    private var completedLateTotal = 0L
    private var rejectedTotal = 0L

    init {
        require(maxOwnedThreads > 0)
    }

    fun tryAcquire(threadName: String): Lease? =
        synchronized(lock) {
            if (owned.size >= maxOwnedThreads) {
                rejectedTotal += 1L
                null
            } else {
                check(threadName !in owned)
                owned[threadName] = OwnedNativeCall()
                Lease(this, threadName)
            }
        }

    private fun markAbandoned(threadName: String) {
        synchronized(lock) {
            val call = owned[threadName] ?: return
            if (!call.abandoned) {
                call.abandoned = true
                abandonedTotal += 1L
            }
        }
    }

    private fun complete(threadName: String) {
        synchronized(lock) {
            val call = owned.remove(threadName) ?: return
            if (call.abandoned) {
                completedLateTotal += 1L
            }
        }
    }

    fun outstandingCount(): Int = synchronized(lock) { owned.size }

    fun rejectedCount(): Long = synchronized(lock) { rejectedTotal }

    fun describe(): String =
        synchronized(lock) {
            "native_calls_owned=${owned.size} native_calls_cap=$maxOwnedThreads " +
                "abandoned=$abandonedTotal completed_late=$completedLateTotal rejected=$rejectedTotal"
        }

    internal class Lease internal constructor(
        private val registry: RuntimeNativeCallRegistry,
        private val threadName: String,
    ) {
        fun markAbandonedIf(isStillRunning: () -> Boolean) {
            if (isStillRunning()) registry.markAbandoned(threadName)
        }

        fun complete() {
            registry.complete(threadName)
        }
    }

    private class OwnedNativeCall(var abandoned: Boolean = false)
}

internal object RuntimeAbandonedCloseRegistry :
    RuntimeNativeCallRegistry(MAX_OWNED_NATIVE_CALL_THREADS)

internal const val MAX_OWNED_NATIVE_CALL_THREADS = 8

private object RuntimeNativeThreadSequence {
    private val sequence = AtomicInteger(0)

    fun next(): Int = sequence.incrementAndGet()
}

suspend fun FoxholeRuntime.startFailClosed(
    session: VpnSession,
    host: RuntimeServiceHost,
    owner: String,
    diagnosticsLogger: RuntimeDiagnosticsSink,
    timeoutMessage: String,
    timeoutMs: Long = RUNTIME_START_TIMEOUT_MS,
): Result<Unit> {
    val fencedRuntime = this as? RuntimeNativeStartFenceOwner

    val permit = fencedRuntime?.openNativeStartPermit("start:$owner")
    try {
        val result =
            runAbandonableNativeRuntimeCall(
                operation = "start",
                timeoutMs = timeoutMs,
            ) {
                if (fencedRuntime != null && permit != null) {
                    fencedRuntime.startWithNativePermit(session, host, permit)
                } else {
                    start(session, host)
                }
            }
        return if (result != null) {
            result
        } else {
            val kill =
                if (fencedRuntime != null && permit != null) {
                    fencedRuntime.abortNativeTransition(permit, "${owner}_start_timeout")
                } else {
                    forceKill("${owner}_start_timeout")
                }
            withContext(NonCancellable + Dispatchers.IO) {
                diagnosticsLogger.recordStructured(
                    "runtime",
                    "$owner runtime start timeout",
                    "sessionId=${session.correlationId}",
                    "timeout_ms=$timeoutMs",
                )

                diagnosticsLogger.recordStructured(
                    "runtime",
                    "native runtime fenced after wedged start",
                    "owner=$owner",
                    "fence_owner=${kill != null}",
                    "engine_detached=${kill?.serverDetached ?: false}",
                    "tun_closed=${kill?.tunClosed ?: false}",
                    RuntimeAbandonedCloseRegistry.describe(),
                )
            }
            kill?.forceStopOutcome
                ?.takeIf(NativeForceStopOutcome::processPoisoned)
                ?.let { outcome -> nativeForceStopPoisonedFailure(outcome, "start_timeout") }
                ?: Result.failure(IllegalStateException(timeoutMessage))
        }
    } catch (cancelled: CancellationException) {
        val kill =
            if (fencedRuntime != null && permit != null) {
                withContext(NonCancellable + Dispatchers.IO) {
                    fencedRuntime.abortNativeTransition(permit, "${owner}_start_cancelled")
                }
            } else {
                null
            }
        kill?.forceStopOutcome
            ?.takeIf(NativeForceStopOutcome::processPoisoned)
            ?.let { outcome ->
                throw NativeForceStopPoisonedCancellationException(outcome, "start_cancelled", cancelled)
            }
        throw cancelled
    }
}

suspend fun FoxholeRuntime.reloadFailClosed(
    session: VpnSession,
    host: RuntimeServiceHost,
    owner: String,
    diagnosticsLogger: RuntimeDiagnosticsSink,
    timeoutMs: Long = runtimeStartTimeoutMsForSession(session),
): Result<Unit> {
    val fencedRuntime = this as? RuntimeNativeStartFenceOwner
    val permit = fencedRuntime?.openNativeStartPermit("reload:$owner")
    try {
        val result =
            runAbandonableNativeRuntimeCall(
                operation = "reload",
                timeoutMs = timeoutMs,
            ) {
                if (fencedRuntime != null && permit != null) {
                    fencedRuntime.reloadWithNativePermit(session, host, permit)
                } else {
                    reload(session, host)
                }
            }
        if (result != null) return result
        val kill =
            if (fencedRuntime != null && permit != null) {
                fencedRuntime.abortNativeTransition(permit, "${owner}_reload_timeout")
            } else {
                forceKill("${owner}_reload_timeout")
            }
        return withContext(NonCancellable + Dispatchers.IO) {
            diagnosticsLogger.recordStructured(
                "runtime",
                "$owner runtime reload timeout",
                "sessionId=${session.correlationId}",
                "timeout_ms=$timeoutMs",
            )
            diagnosticsLogger.recordStructured(
                "runtime",
                "native runtime fenced after wedged reload",
                "owner=$owner",
                "fence_owner=${kill != null}",
                "engine_detached=${kill?.serverDetached ?: false}",
                "tun_closed=${kill?.tunClosed ?: false}",
                RuntimeAbandonedCloseRegistry.describe(),
            )
            kill?.forceStopOutcome
                ?.takeIf(NativeForceStopOutcome::processPoisoned)
                ?.let { outcome -> nativeForceStopPoisonedFailure(outcome, "reload_timeout") }
                ?: Result.failure(IllegalStateException("android: runtime reload timed out"))
        }
    } catch (cancelled: CancellationException) {
        val kill =
            if (fencedRuntime != null && permit != null) {
                withContext(NonCancellable + Dispatchers.IO) {
                    fencedRuntime.abortNativeTransition(permit, "${owner}_reload_cancelled")
                }
            } else {
                null
            }
        kill?.forceStopOutcome
            ?.takeIf(NativeForceStopOutcome::processPoisoned)
            ?.let { outcome ->
                throw NativeForceStopPoisonedCancellationException(outcome, "reload_cancelled", cancelled)
            }
        throw cancelled
    }
}

suspend fun FoxholeRuntime.stopFailClosed(
    owner: String,
    reason: String,
    diagnosticsLogger: RuntimeDiagnosticsSink,
    policy: RuntimeStopPolicy =
        RuntimeStopPolicy(
            closeTunFdImmediately = true,
            closeServiceTimeoutMs = 700L,
            closeServerTimeoutMs = 700L,
            totalGracefulTimeoutMs = RUNTIME_STOP_TIMEOUT_MS,
            forceKillAfterTimeout = true,
        ),
): RuntimeStopResult {
    val nativeStopTimeoutMs = nativeStopCallTimeoutMs(policy)
    val supervisionTimeoutMs = runtimeStopSupervisionTimeoutMs(policy)
    return withTimeoutOrNull(supervisionTimeoutMs) {
        stop(policy)
    } ?: withContext(Dispatchers.IO) {
        diagnosticsLogger.recordStructured(
            "runtime",
            "$owner runtime stop timeout",
            "reason=$reason",
            "timeout_ms=$supervisionTimeoutMs",
            "native_stop_timeout_ms=$nativeStopTimeoutMs",
            "core=${foxCoreLastStopDiagnostics()}",
        )
        val killResult = forceKill("${owner}_${reason}_stop_timeout")
        RuntimeStopResult(
            closeServiceOk = false,
            closeServerOk = false,
            tunClosed = killResult.tunClosed,
            escalatedToKill = true,
            elapsedMs = supervisionTimeoutMs,
            forceStopOutcome = killResult.forceStopOutcome,
        )
    }
}

internal fun nativeStopCallTimeoutMs(policy: RuntimeStopPolicy): Long =
    policy.totalGracefulTimeoutMs
        .coerceAtLeast(1L)
        .saturatingAdd(RUNTIME_NATIVE_STOP_COMPLETION_MARGIN_MS)

internal fun runtimeStopSupervisionTimeoutMs(policy: RuntimeStopPolicy): Long =
    nativeStopCallTimeoutMs(policy)
        .saturatingAdd(RUNTIME_FORCE_KILL_TIMEOUT_MS)
        .saturatingAdd(RUNTIME_STOP_SUPERVISION_MARGIN_MS)

private fun Long.saturatingAdd(other: Long): Long =
    if (this > Long.MAX_VALUE - other) Long.MAX_VALUE else this + other

fun stopRuntimeAfterServiceDestroy(
    scope: CoroutineScope,
    runtime: FoxholeRuntime,
    diagnosticsLogger: RuntimeDiagnosticsSink,
    owner: String,
    onStopped: suspend (RuntimeStopResult) -> Unit = {},
    onProcessPoisoned: suspend (RuntimeStopResult) -> Unit = {},
) {
    scope.launch(Dispatchers.IO) {
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
        onStopped(result)
        if (result.processPoisoned) {
            onProcessPoisoned(result)
        }
    }
}
