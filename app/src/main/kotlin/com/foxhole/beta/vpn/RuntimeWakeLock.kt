package com.foxhole.beta.vpn

import android.content.Context
import android.os.PowerManager
import androidx.core.content.getSystemService
import com.foxhole.beta.core.diagnostics.DiagnosticsLogger

internal class RuntimeWakeLock(
    private val context: Context,
    private val diagnosticsLogger: DiagnosticsLogger,
    private val tag: String,
) {
    private var wakeLock: PowerManager.WakeLock? = null

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
            val leaseMs = timeoutMs.coerceAtLeast(MIN_RUNTIME_WAKE_LOCK_TIMEOUT_MS)
            runCatching { lock.acquire(leaseMs) }
                .onSuccess { diagnosticsLogger.record("power", "partial wake lock acquired lease_ms=$leaseMs") }
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
        if (lock.isHeld) {
            runCatching { lock.release() }
                .onSuccess { diagnosticsLogger.record("power", "partial wake lock released") }
                .onFailure {
                    diagnosticsLogger.record(
                        "power",
                        "partial wake lock release failed: ${it.javaClass.simpleName}",
                    )
                }
        }
    }

    private companion object {
        const val MIN_RUNTIME_WAKE_LOCK_TIMEOUT_MS = 30_000L
        const val DEFAULT_RUNTIME_WAKE_LOCK_TIMEOUT_MS = 10 * 60_000L
    }
}
