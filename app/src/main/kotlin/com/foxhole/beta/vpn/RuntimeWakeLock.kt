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
            val nextLeaseMs = timeoutMs.coerceAtLeast(MIN_RUNTIME_WAKE_LOCK_TIMEOUT_MS)
            leaseMs = nextLeaseMs
            runCatching { lock.acquire(nextLeaseMs) }
                .onSuccess {
                    acquiredAtElapsedMs = SystemClock.elapsedRealtime()
                    diagnosticsLogger.record("power", "partial wake lock acquired lease_ms=$nextLeaseMs")
                    startWatchdog()
                }
                .onFailure {
                    diagnosticsLogger.record(
                        "power",
                        "partial wake lock acquire failed: ${it.javaClass.simpleName}",
                    )
                }
        }
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
                    delay((leaseMs - WAKE_LOCK_WATCHDOG_LEASE_MARGIN_MS).coerceAtLeast(MIN_RUNTIME_WAKE_LOCK_TIMEOUT_MS))
                    keepWatching = refreshWakeLockLease()
                }
            }
    }

    private fun refreshWakeLockLease(): Boolean {
        var keepWatching = false
        val lock = wakeLock
        if (lock?.isHeld == true) {
            val heldMs = wakeLockHeldMs()
            RuntimeHealthMetrics.recordWakeLockWatchdog(
                owner = tag,
                heldMs = heldMs,
                diagnosticsLogger = diagnosticsLogger,
            )
            keepWatching = shouldRemainHeld()
            if (keepWatching) {
                diagnosticsLogger.record("power", "partial wake lock watchdog refreshed held_ms=$heldMs")
                runCatching { lock.acquire(leaseMs) }
            } else {
                diagnosticsLogger.record("power", "partial wake lock watchdog safety release held_ms=$heldMs")
                runCatching { lock.release() }
            }
        }
        return keepWatching
    }

    private fun wakeLockHeldMs(): Long =
        (SystemClock.elapsedRealtime() - acquiredAtElapsedMs).coerceAtLeast(0L)

    private companion object {
        const val MIN_RUNTIME_WAKE_LOCK_TIMEOUT_MS = 30_000L
        const val DEFAULT_RUNTIME_WAKE_LOCK_TIMEOUT_MS = 10 * 60_000L
        const val WAKE_LOCK_WATCHDOG_LEASE_MARGIN_MS = 60_000L
    }
}
