package com.foxhole.beta.vpn

import com.foxhole.beta.core.diagnostics.DiagnosticsLogger
import java.util.concurrent.atomic.AtomicLong

internal object RuntimeHealthMetrics {
    private val startAttempts = AtomicLong(0)
    private val startFailures = AtomicLong(0)
    private val validations = AtomicLong(0)
    private val validationFailures = AtomicLong(0)
    private val reconnects = AtomicLong(0)
    private val healthProbes = AtomicLong(0)
    private val healthProbeFailures = AtomicLong(0)
    private val wakeLockWatchdogEvents = AtomicLong(0)
    private val stopTimeouts = AtomicLong(0)

    fun recordStart(
        owner: String,
        success: Boolean,
        elapsedMs: Long,
        diagnosticsLogger: DiagnosticsLogger,
    ) {
        startAttempts.incrementAndGet()
        if (!success) {
            startFailures.incrementAndGet()
        }
        emit(
            diagnosticsLogger = diagnosticsLogger,
            event = if (success) "runtime_start_success" else "runtime_start_failure",
            owner = owner,
            extra = listOf("elapsed_ms=${elapsedMs.coerceAtLeast(0L)}"),
        )
    }

    fun recordValidation(
        owner: String,
        success: Boolean,
        elapsedMs: Long,
        diagnosticsLogger: DiagnosticsLogger,
    ) {
        validations.incrementAndGet()
        if (!success) {
            validationFailures.incrementAndGet()
        }
        emit(
            diagnosticsLogger = diagnosticsLogger,
            event = if (success) "validation_success" else "validation_failure",
            owner = owner,
            extra = listOf("elapsed_ms=${elapsedMs.coerceAtLeast(0L)}"),
        )
    }

    fun recordReconnectScheduled(
        owner: String,
        attempt: Int,
        diagnosticsLogger: DiagnosticsLogger,
    ) {
        reconnects.incrementAndGet()
        emit(
            diagnosticsLogger = diagnosticsLogger,
            event = "reconnect_scheduled",
            owner = owner,
            extra = listOf("attempt=${attempt.coerceAtLeast(0)}"),
        )
    }

    fun recordHealthProbe(
        owner: String,
        success: Boolean,
        diagnosticsLogger: DiagnosticsLogger,
    ) {
        healthProbes.incrementAndGet()
        if (!success) {
            healthProbeFailures.incrementAndGet()
        }
        emit(
            diagnosticsLogger = diagnosticsLogger,
            event = if (success) "health_probe_success" else "health_probe_failure",
            owner = owner,
        )
    }

    fun recordWakeLockWatchdog(
        owner: String,
        heldMs: Long,
        diagnosticsLogger: DiagnosticsLogger,
    ) {
        wakeLockWatchdogEvents.incrementAndGet()
        emit(
            diagnosticsLogger = diagnosticsLogger,
            event = "wake_lock_watchdog",
            owner = owner,
            extra = listOf("held_ms=${heldMs.coerceAtLeast(0L)}"),
        )
    }

    fun recordStopAfterDestroy(
        owner: String,
        stopped: Boolean,
        diagnosticsLogger: DiagnosticsLogger,
    ) {
        if (!stopped) {
            stopTimeouts.incrementAndGet()
        }
        emit(
            diagnosticsLogger = diagnosticsLogger,
            event = if (stopped) "destroy_stop_success" else "destroy_stop_timeout",
            owner = owner,
        )
    }

    private fun emit(
        diagnosticsLogger: DiagnosticsLogger,
        event: String,
        owner: String,
        extra: List<String> = emptyList(),
    ) {
        val details =
            listOf(
                "event=$event",
                "owner=$owner",
                "start_attempts=${startAttempts.get()}",
                "start_failures=${startFailures.get()}",
                "validations=${validations.get()}",
                "validation_failures=${validationFailures.get()}",
                "reconnects=${reconnects.get()}",
                "health_probes=${healthProbes.get()}",
                "health_probe_failures=${healthProbeFailures.get()}",
                "wake_lock_watchdog_events=${wakeLockWatchdogEvents.get()}",
                "stop_timeouts=${stopTimeouts.get()}",
            ) + extra
        diagnosticsLogger.record(
            tag = "runtime-health",
            message = "Runtime health: ${details.joinToString()}",
        )
    }
}
