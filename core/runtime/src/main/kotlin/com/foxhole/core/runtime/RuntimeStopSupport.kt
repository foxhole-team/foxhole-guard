package com.foxhole.core.runtime

import com.foxhole.core.model.TrafficMode
import com.foxhole.core.model.VpnSession
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

data class RuntimeStopResult(
    val closeServiceOk: Boolean,
    val closeServerOk: Boolean,
    val tunClosed: Boolean,
    val escalatedToKill: Boolean,
    val elapsedMs: Long,
) {
    val graceful: Boolean
        get() = closeServiceOk && closeServerOk && tunClosed && !escalatedToKill
}

data class RuntimeKillResult(
    val reason: String,
    val tunClosed: Boolean,
    val serverDetached: Boolean,
    val closeDetached: Boolean = false,
)

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
    /**
     * FD number of the master TUN this process holds on purpose (null = none). A master stays in
     * Java while native owns a duplicate — anything scanning /proc/self/fd for a leaked tunnel
     * must be told which descriptor is intended, or it finds the design and calls it a leak.
     */
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

/**
 * Native closes are isolated: a coroutine timeout cannot stop a JNI method that ignores
 * cancellation, and a shared executor lets one wedged call starve every later native operation.
 * Each close gets its own ONE-SHOT daemon thread; on timeout the thread is abandoned (recorded
 * in [RuntimeAbandonedCloseRegistry]) and the next close spawns fresh — the retired
 * single-threaded quarantine queued closes behind the wedged one and spuriously timed out ALL
 * of them, a dead-end only a process restart could clear.
 */
internal suspend fun runBlockingRuntimeClose(
    timeoutMs: Long,
    block: () -> Unit,
): Boolean =
    withContext(Dispatchers.IO) {
        val outcome = CompletableFuture<Boolean>()
        val threadName = "FoxholeNativeClose-${RuntimeNativeThreadSequence.next()}"
        Thread({
            val closed =
                runCatching {
                    block()
                    true
                }.getOrDefault(false)
            outcome.complete(closed)
            RuntimeAbandonedCloseRegistry.completeLate(threadName)
        }, threadName).apply {
            isDaemon = true
            start()
        }
        try {
            outcome.get(timeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            RuntimeAbandonedCloseRegistry.recordAbandoned(threadName) { outcome.isDone }
            false
        } catch (_: ExecutionException) {
            false
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

/**
 * Bounded, ABANDONABLE execution of a whole native start/reload — the containment closes get,
 * applied to the operations that actually wedge in production. A timeout around a child
 * coroutine is no bound once JNI stops returning: the parent cannot finish before its children,
 * so the timeout cancels a coroutine parked in an uncancellable native frame and then waits for
 * it anyway — forever, on the process-global native thread, swallowing every queued operation.
 * So the operation runs on its own ONE-SHOT daemon thread outside the coroutine hierarchy; the
 * caller awaits a standalone [CompletableDeferred] (a genuinely cancellable suspension) and a
 * wedged thread is simply left behind, recorded in [RuntimeAbandonedCloseRegistry].
 * Returns null when abandoned; exceptions from [block] are rethrown unchanged.
 */
internal suspend fun <T : Any> runAbandonableNativeRuntimeCall(
    operation: String,
    timeoutMs: Long,
    block: suspend () -> T,
): T? {
    val outcome = CompletableDeferred<Result<T>>()
    val threadName = "FoxholeNativeOp-$operation-${RuntimeNativeThreadSequence.next()}"
    Thread({
        val settled = runCatching { runBlocking { block() } }
        outcome.complete(settled)
        RuntimeAbandonedCloseRegistry.completeLate(threadName)
    }, threadName).apply {
        isDaemon = true
        start()
    }
    val settled = withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) { outcome.await() }
    return if (settled == null) {
        RuntimeAbandonedCloseRegistry.recordAbandoned(threadName) { outcome.isCompleted }
        null
    } else {
        settled.getOrThrow()
    }
}

/**
 * Bounded ledger of native calls whose thread outlived its timeout window. A wedged JNI frame
 * cannot be cancelled; this registry is how a later diagnosis tells "finished late" from
 * "never returned".
 */
internal object RuntimeAbandonedCloseRegistry {
    private const val MAX_TRACKED = 8
    private val lock = Any()
    private val abandoned = LinkedHashMap<String, Long>()
    private var abandonedTotal = 0L
    private var completedLateTotal = 0L

    fun recordAbandoned(
        threadName: String,
        isComplete: () -> Boolean,
    ) {
        // The call may have completed in the race window right after the timeout fired.
        if (isComplete()) {
            return
        }
        synchronized(lock) {
            abandonedTotal += 1
            abandoned[threadName] = System.currentTimeMillis()
            while (abandoned.size > MAX_TRACKED) {
                abandoned.remove(abandoned.keys.first())
            }
        }
    }

    fun completeLate(threadName: String) {
        synchronized(lock) {
            if (abandoned.remove(threadName) != null) {
                completedLateTotal += 1
            }
        }
    }

    fun outstandingCount(): Int = synchronized(lock) { abandoned.size }

    fun describe(): String =
        synchronized(lock) {
            "abandoned_closes=$abandonedTotal completed_late=$completedLateTotal outstanding=${abandoned.size}"
        }
}

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
    val result =
        runAbandonableNativeRuntimeCall(
            operation = "start",
            timeoutMs = timeoutMs,
        ) {
            start(session, host)
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
            val kill = forceKill("${owner}_start_timeout")
            diagnosticsLogger.recordStructured(
                "runtime",
                "native runtime fenced after wedged start",
                "owner=$owner",
                "engine_detached=${kill.serverDetached}",
                "tun_closed=${kill.tunClosed}",
                RuntimeAbandonedCloseRegistry.describe(),
            )
        }
        Result.failure(IllegalStateException(timeoutMessage))
    }
}

/**
 * Reload with the same fail-closed contract as [startFailClosed]. A native call cannot be cancelled
 * while it is inside JNI, so timeout fences its handle and TUN before a later runtime is created.
 */
suspend fun FoxholeRuntime.reloadFailClosed(
    session: VpnSession,
    host: RuntimeServiceHost,
    owner: String,
    diagnosticsLogger: RuntimeDiagnosticsSink,
    timeoutMs: Long = runtimeStartTimeoutMsForSession(session),
): Result<Unit> {
    val result =
        runAbandonableNativeRuntimeCall(
            operation = "reload",
            timeoutMs = timeoutMs,
        ) {
            reload(session, host)
        }
    return if (result != null) {
        result
    } else {
        withContext(NonCancellable + Dispatchers.IO) {
            diagnosticsLogger.recordStructured(
                "runtime",
                "$owner runtime reload timeout",
                "sessionId=${session.correlationId}",
                "timeout_ms=$timeoutMs",
            )
            val kill = forceKill("${owner}_reload_timeout")
            diagnosticsLogger.recordStructured(
                "runtime",
                "native runtime fenced after wedged reload",
                "owner=$owner",
                "engine_detached=${kill.serverDetached}",
                "tun_closed=${kill.tunClosed}",
                RuntimeAbandonedCloseRegistry.describe(),
            )
        }
        Result.failure(IllegalStateException("android: runtime reload timed out"))
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
    val stopTimeoutMs = policy.totalGracefulTimeoutMs.coerceAtLeast(1L)
    return withTimeoutOrNull(stopTimeoutMs) {
        stop(policy)
    } ?: withContext(Dispatchers.IO) {
        // The core's own account of the same three seconds: without it this line named only its
        // own deadline, and "stuck in the engine loop or in Tokio shutdown?" needed a device
        // round trip and a custom build to answer.
        diagnosticsLogger.recordStructured(
            "runtime",
            "$owner runtime stop timeout",
            "reason=$reason",
            "timeout_ms=$stopTimeoutMs",
            "core=${foxCoreLastStopDiagnostics()}",
        )
        val killResult = forceKill("${owner}_${reason}_stop_timeout")
        RuntimeStopResult(
            closeServiceOk = false,
            closeServerOk = false,
            tunClosed = killResult.tunClosed,
            escalatedToKill = true,
            elapsedMs = stopTimeoutMs,
        )
    }
}

/**
 * Finishes the native stop after the owning Android component is gone. [scope] must OUTLIVE the
 * caller: a service cancels its own scope in onDestroy(), which would cancel this teardown
 * before FoxCore releases the TUN. This used to mint (and leak) an uncancellable scope per call.
 */
fun stopRuntimeAfterServiceDestroy(
    scope: CoroutineScope,
    runtime: FoxholeRuntime,
    diagnosticsLogger: RuntimeDiagnosticsSink,
    owner: String,
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
    }
}
