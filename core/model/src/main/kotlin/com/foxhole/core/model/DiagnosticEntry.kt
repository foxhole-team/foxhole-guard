package com.foxhole.core.model

/**
 * How much a diagnostic entry is worth waking someone for.
 *
 * Two levels and no scale in between: an entry either records something that failed or it records
 * what happened. The distinction is made where the entry is written — by the code that knows it is
 * in a failure path — and never guessed afterwards from the words in the message. A screen that
 * pattern-matched "failed" once called a successful refresh (`retryableFailures=0`) critical, which
 * is exactly what a severity field exists to stop.
 */
enum class DiagnosticSeverity {
    INFO,
    FAILURE,
}

/** A single diagnostic log entry. Shared by the runtime engine and the app diagnostics layer. */
@androidx.compose.runtime.Immutable
data class DiagnosticEntry(
    val timestamp: Long,
    val tag: String,
    val message: String,
    val severity: DiagnosticSeverity = DiagnosticSeverity.INFO,
)
