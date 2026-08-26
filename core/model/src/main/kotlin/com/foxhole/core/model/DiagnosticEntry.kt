package com.foxhole.core.model

enum class DiagnosticSeverity {
    INFO,
    FAILURE,
}

@androidx.compose.runtime.Immutable
data class DiagnosticEntry(
    val timestamp: Long,
    val tag: String,
    val message: String,
    val severity: DiagnosticSeverity = DiagnosticSeverity.INFO,
)
