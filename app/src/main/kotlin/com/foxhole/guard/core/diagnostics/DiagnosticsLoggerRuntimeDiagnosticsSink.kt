package com.foxhole.guard.core.diagnostics

import com.foxhole.core.runtime.RuntimeDiagnosticsSink

class DiagnosticsLoggerRuntimeDiagnosticsSink(
    private val logger: DiagnosticsLogger,
) : RuntimeDiagnosticsSink {
    override fun record(tag: String, message: String) {
        logger.record(tag, message)
    }

    override fun recordStructured(
        tag: String,
        headline: String,
        vararg details: String?,
    ) {
        logger.recordStructured(tag, headline, *details)
    }

    override fun recordThrottled(
        tag: String,
        throttleKey: String,
        windowMs: Long,
        message: String,
    ) {
        logger.recordThrottled(
            tag = tag,
            throttleKey = throttleKey,
            windowMs = windowMs,
            message = message,
        )
    }
}
