package com.foxhole.core.runtime

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal interface RuntimeWakeLockHandle {
    val isHeld: Boolean

    fun acquire(timeoutMs: Long)

    fun release()
}

private class AndroidRuntimeWakeLockHandle(
    private val wakeLock: PowerManager.WakeLock,
) : RuntimeWakeLockHandle {
    override val isHeld: Boolean
        get() = wakeLock.isHeld

    override fun acquire(timeoutMs: Long) {
        wakeLock.acquire(timeoutMs)
    }

    override fun release() {
        wakeLock.release()
    }
}

class RuntimeWakeLock private constructor(
    private val diagnosticsLogger: RuntimeDiagnosticsSink,
    private val tag: String,
    private val scope: CoroutineScope? = null,
    private val shouldRemainHeld: () -> Boolean = { true },
    private val wakeLockFactory: () -> RuntimeWakeLockHandle?,
    private val elapsedRealtimeMs: () -> Long,
) {
    constructor(
        context: Context,
        diagnosticsLogger: RuntimeDiagnosticsSink,
        tag: String,
        scope: CoroutineScope? = null,
        shouldRemainHeld: () -> Boolean = { true },
    ) : this(
        diagnosticsLogger = diagnosticsLogger,
        tag = tag,
        scope = scope,
        shouldRemainHeld = shouldRemainHeld,
        wakeLockFactory = {
            context
                .getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag)
                ?.apply { setReferenceCounted(false) }
                ?.let(::AndroidRuntimeWakeLockHandle)
        },
        elapsedRealtimeMs = SystemClock::elapsedRealtime,
    )

    internal constructor(
        diagnosticsLogger: RuntimeDiagnosticsSink,
        tag: String,
        wakeLockFactory: () -> RuntimeWakeLockHandle?,
        elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime,
    ) : this(
        diagnosticsLogger = diagnosticsLogger,
        tag = tag,
        scope = null,
        shouldRemainHeld = { true },
        wakeLockFactory = wakeLockFactory,
        elapsedRealtimeMs = elapsedRealtimeMs,
    )

    private var wakeLock: RuntimeWakeLockHandle? = null
    private var watchdogJob: Job? = null
    private var acquiredAtElapsedMs: Long = 0L
    private var leaseMs: Long = DEFAULT_RUNTIME_WAKE_LOCK_TIMEOUT_MS
    private var desiredHeld = false

    @Synchronized
    fun acquire(timeoutMs: Long = DEFAULT_RUNTIME_WAKE_LOCK_TIMEOUT_MS) {
        desiredHeld = true
        val nextLeaseMs = timeoutMs.coerceAtLeast(MIN_RUNTIME_WAKE_LOCK_TIMEOUT_MS)
        leaseMs = nextLeaseMs
        val lock =
            wakeLock ?: wakeLockFactory()
                ?.also { wakeLock = it }
                ?: run {
                    desiredHeld = false
                    diagnosticsLogger.record("power", "partial wake lock unavailable")
                    return
                }
        if (!lock.isHeld) {
            acquireWakeLock(lock, nextLeaseMs, "acquired")
        }
        startWatchdog()
    }

    @Synchronized
    fun release() {
        // Clear ownership before cancelling or releasing. A watchdog refresh that was already
        // scheduled cannot observe a stale "held" intent and resurrect the lease afterwards.
        desiredHeld = false
        watchdogJob?.cancel()
        watchdogJob = null
        val lock = wakeLock ?: return
        if (lock.isHeld) {
            val heldMs = wakeLockHeldMs()
            runCatching { lock.release() }
                .onSuccess { diagnosticsLogger.record("power", "partial wake lock released held_ms=$heldMs") }
                .onFailure {
                    diagnosticsLogger.record(
                        "power",
                        "partial wake lock release failed: ${it.javaClass.simpleName}",
                    )
                }
        }
    }

    private fun startWatchdog() {
        val ownerScope = scope ?: return
        if (watchdogJob?.isActive == true) {
            return
        }
        watchdogJob =
            ownerScope.launch(Dispatchers.Default) {
                var keepWatching = true
                while (isActive && keepWatching) {
                    delay(watchdogDelayMs())
                    keepWatching = refreshWakeLockLease()
                }
            }
    }

    @Synchronized
    private fun refreshWakeLockLease(): Boolean {
        val lock = wakeLock ?: return false
        if (!desiredHeld) {
            return false
        }
        val heldMs = wakeLockHeldMs()
        RuntimeHealthMetrics.recordWakeLockWatchdog(
            owner = tag,
            heldMs = heldMs,
            diagnosticsLogger = diagnosticsLogger,
        )
        val keepWatching = shouldRemainHeld()
        if (keepWatching) {
            val event = if (lock.isHeld) "refreshed" else "recovered"
            acquireWakeLock(lock, leaseMs, event)
        } else {
            desiredHeld = false
            if (lock.isHeld) {
                diagnosticsLogger.record("power", "partial wake lock watchdog safety release held_ms=$heldMs")
                runCatching { lock.release() }
            }
        }
        return keepWatching
    }

    private fun acquireWakeLock(
        lock: RuntimeWakeLockHandle,
        timeoutMs: Long,
        event: String,
    ) {
        runCatching { lock.acquire(timeoutMs) }
            .onSuccess {
                acquiredAtElapsedMs = elapsedRealtimeMs()
                diagnosticsLogger.record("power", "partial wake lock $event lease_ms=$timeoutMs")
            }
            .onFailure {
                diagnosticsLogger.record(
                    "power",
                    "partial wake lock $event failed: ${it.javaClass.simpleName}",
                )
            }
    }

    private fun watchdogDelayMs(): Long =
        (leaseMs - WAKE_LOCK_WATCHDOG_LEASE_MARGIN_MS)
            .coerceAtLeast(MIN_RUNTIME_WAKE_LOCK_TIMEOUT_MS)
            .coerceAtMost(WAKE_LOCK_WATCHDOG_CHECK_INTERVAL_MS)

    private fun wakeLockHeldMs(): Long =
        (elapsedRealtimeMs() - acquiredAtElapsedMs).coerceAtLeast(0L)

    private companion object {
        const val MIN_RUNTIME_WAKE_LOCK_TIMEOUT_MS = 30_000L
        const val DEFAULT_RUNTIME_WAKE_LOCK_TIMEOUT_MS = 10 * 60_000L
        const val WAKE_LOCK_WATCHDOG_CHECK_INTERVAL_MS = 5 * 60_000L
        const val WAKE_LOCK_WATCHDOG_LEASE_MARGIN_MS = 60_000L
    }
}
