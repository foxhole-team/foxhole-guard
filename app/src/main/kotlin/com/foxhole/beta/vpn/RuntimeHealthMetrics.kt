package com.foxhole.beta.vpn

import android.os.Debug
import com.foxhole.beta.BuildConfig
import com.foxhole.beta.core.diagnostics.DiagnosticsLogger
import java.io.File
import java.util.concurrent.atomic.AtomicLong

internal data class RuntimeResourceSnapshot(
    val rssKb: Long?,
    val pssKb: Long?,
    val nativeHeapKb: Long?,
    val javaHeapKb: Long,
    val threadCount: Int,
    val runtimeGeneration: Long,
    val commandQueueDepth: Int,
    val activeNetworkCallbacks: Int,
    val hasCommandServer: Boolean,
    val hasTunFileDescriptor: Boolean,
    val hasHost: Boolean,
    val hasConfig: Boolean,
    val dnsServerAddress: String?,
)

@Suppress("TooManyFunctions")
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

    fun recordResourceSnapshot(
        owner: String,
        event: String,
        runtimeGeneration: Long,
        commandQueue: RuntimeCommandQueueSnapshot,
        nativeSnapshot: NativeRuntimeSnapshot,
        activeNetworkCallbacks: Int,
        diagnosticsLogger: DiagnosticsLogger,
    ) {
        if (!BuildConfig.DEBUG && !BuildConfig.ENABLE_DIAGNOSTIC_LOGCAT) {
            return
        }
        val snapshot =
            captureResourceSnapshot(
                runtimeGeneration = runtimeGeneration,
                commandQueueDepth = commandQueue.commandQueueDepth,
                activeNetworkCallbacks = activeNetworkCallbacks,
                nativeSnapshot = nativeSnapshot,
            )
        diagnosticsLogger.recordStructured(
            "runtime-health",
            "runtime resource snapshot",
            "event=$event",
            "owner=$owner",
            "rss_kb=${snapshot.rssKb ?: "unknown"}",
            "pss_kb=${snapshot.pssKb ?: "unknown"}",
            "native_heap_kb=${snapshot.nativeHeapKb ?: "unknown"}",
            "java_heap_kb=${snapshot.javaHeapKb}",
            "threads=${snapshot.threadCount}",
            "generation=${snapshot.runtimeGeneration}",
            "command_queue_depth=${snapshot.commandQueueDepth}",
            "network_callbacks=${snapshot.activeNetworkCallbacks}",
            "native_server=${snapshot.hasCommandServer}",
            "tun_fd=${snapshot.hasTunFileDescriptor}",
            "native_host=${snapshot.hasHost}",
            "native_config=${snapshot.hasConfig}",
            snapshot.dnsServerAddress?.let { "dns=$it" },
            "queue_running=${commandQueue.running}",
            commandQueue.runningPriority?.let { "queue_priority=$it" },
            commandQueue.runningReason?.let { "queue_reason=$it" },
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

    private fun captureResourceSnapshot(
        runtimeGeneration: Long,
        commandQueueDepth: Int,
        activeNetworkCallbacks: Int,
        nativeSnapshot: NativeRuntimeSnapshot,
    ): RuntimeResourceSnapshot {
        val runtime = Runtime.getRuntime()
        return RuntimeResourceSnapshot(
            rssKb = readStatusMemoryKb("VmRSS"),
            pssKb = readPssKb(),
            nativeHeapKb = runCatching { Debug.getNativeHeapAllocatedSize() / BYTES_PER_KB }.getOrNull(),
            javaHeapKb = ((runtime.totalMemory() - runtime.freeMemory()) / BYTES_PER_KB).coerceAtLeast(0L),
            threadCount = runCatching { Thread.getAllStackTraces().size }.getOrDefault(-1),
            runtimeGeneration = runtimeGeneration,
            commandQueueDepth = commandQueueDepth.coerceAtLeast(0),
            activeNetworkCallbacks = activeNetworkCallbacks.coerceAtLeast(0),
            hasCommandServer = nativeSnapshot.hasCommandServer,
            hasTunFileDescriptor = nativeSnapshot.hasTunFileDescriptor,
            hasHost = nativeSnapshot.hasHost,
            hasConfig = nativeSnapshot.hasConfig,
            dnsServerAddress = nativeSnapshot.dnsServerAddress,
        )
    }

    private fun readPssKb(): Long? =
        runCatching {
            val memoryInfo = Debug.MemoryInfo()
            Debug.getMemoryInfo(memoryInfo)
            memoryInfo.totalPss.takeIf { it >= 0 }?.toLong()
        }.getOrNull()

    private fun readStatusMemoryKb(label: String): Long? =
        runCatching {
            File(PROC_SELF_STATUS).useLines { lines ->
                lines.firstNotNullOfOrNull { line ->
                    if (line.startsWith(label)) {
                        line
                            .substringAfter(':')
                            .trim()
                            .substringBefore(' ')
                            .toLongOrNull()
                    } else {
                        null
                    }
                }
            }
        }.getOrNull()

    private const val BYTES_PER_KB = 1024L
    private const val PROC_SELF_STATUS = "/proc/self/status"
}
