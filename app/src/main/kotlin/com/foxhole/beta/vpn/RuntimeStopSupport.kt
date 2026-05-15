package com.foxhole.beta.vpn

import com.foxhole.beta.core.diagnostics.DiagnosticsLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal const val RUNTIME_STOP_TIMEOUT_MS = 3_000L

internal fun stopRuntimeAfterServiceDestroy(
    runtime: VpnCoreRuntime,
    diagnosticsLogger: DiagnosticsLogger,
    owner: String,
) {
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        val stopped =
            withTimeoutOrNull(RUNTIME_STOP_TIMEOUT_MS) {
                runtime.stop()
                true
            } == true
        diagnosticsLogger.record(
            "runtime",
            if (stopped) {
                "$owner runtime stop completed after service destroy"
            } else {
                "$owner runtime stop timed out after ${RUNTIME_STOP_TIMEOUT_MS}ms"
            },
        )
        RuntimeHealthMetrics.recordStopAfterDestroy(
            owner = owner,
            stopped = stopped,
            diagnosticsLogger = diagnosticsLogger,
        )
    }
}
