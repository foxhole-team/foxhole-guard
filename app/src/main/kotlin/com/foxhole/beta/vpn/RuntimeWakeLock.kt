package com.foxhole.beta.vpn

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.getSystemService
import com.foxhole.beta.core.diagnostics.DiagnosticsLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal class RuntimeWakeLock(
    private val context: Context,
    private val diagnosticsLogger: DiagnosticsLogger,
    private val tag: String,
    private val scope: CoroutineScope? = null,
    private val shouldRemainHeld: () -> Boolean = { true },
) {
    private var wakeLock: PowerManager.WakeLock? = null
    private var watchdogJob: Job? = null
    private var acquiredAtElapsedMs: Long = 0L
    private var leaseMs: Long = DEFAULT_RUNTIME_WAKE_LOCK_TIMEOUT_MS

    fun acquire(timeoutMs: Long = DEFAULT_RUNTIME_WAKE_LOCK_TIMEOUT_MS) {
        val nextLeaseMs = timeoutMs.coerceAtLeast(MIN_RUNTIME_WAKE_LOCK_TIMEOUT_MS)
        leaseMs = nextLeaseMs
        val lock =
            wakeLock ?: context
                .getSystemService<PowerManager>()
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag)
                ?.apply { setReferenceCounted(false) }
                ?.also { wakeLock = it }
                ?: run {
                    diagnosticsLogger.record("power", "partial wake lock unavailable")
                    return
                }
        if (!lock.isHeld) {
            acquireWakeLock(lock, nextLeaseMs, "acquired")
        }
        startWatchdog()
    }

    fun release() {
        val lock = wakeLock ?: return
        watchdogJob?.cancel()
        watchdogJob = null
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

    private fun refreshWakeLockLease(): Boolean {
        val lock = wakeLock ?: return false
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
        } else if (lock.isHeld) {
            diagnosticsLogger.record("power", "partial wake lock watchdog safety release held_ms=$heldMs")
            runCatching { lock.release() }
        }
        return keepWatching
    }

    private fun acquireWakeLock(
        lock: PowerManager.WakeLock,
        timeoutMs: Long,
        event: String,
    ) {
        runCatching { lock.acquire(timeoutMs) }
            .onSuccess {
                acquiredAtElapsedMs = SystemClock.elapsedRealtime()
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
        (SystemClock.elapsedRealtime() - acquiredAtElapsedMs).coerceAtLeast(0L)

    private companion object {
        const val MIN_RUNTIME_WAKE_LOCK_TIMEOUT_MS = 30_000L
        const val DEFAULT_RUNTIME_WAKE_LOCK_TIMEOUT_MS = 10 * 60_000L
        const val WAKE_LOCK_WATCHDOG_CHECK_INTERVAL_MS = 5 * 60_000L
        const val WAKE_LOCK_WATCHDOG_LEASE_MARGIN_MS = 60_000L
    }
}
