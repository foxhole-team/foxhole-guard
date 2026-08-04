package com.foxhole.core.model

/** A single diagnostic log entry. Shared by the runtime engine and the app diagnostics layer. */
@androidx.compose.runtime.Immutable
data class DiagnosticEntry(
    val timestamp: Long,
    val tag: String,
    val message: String,
)
