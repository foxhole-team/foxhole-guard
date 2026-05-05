package com.foxhole.beta.core.diagnostics

import com.foxhole.beta.core.model.DiagnosticsRetention
import java.time.Instant
import java.time.format.DateTimeFormatter

internal data class DiagnosticsExportMetadata(
    val generatedAt: Long,
    val appVersion: String,
    val versionCode: Int,
    val coreVersion: String,
    val androidRelease: String,
    val sdkInt: Int,
    val supportedAbis: List<String>,
    val themeMode: String,
    val locale: String,
    val trafficMode: String,
    val tunStack: String,
    val diagnosticsRetention: DiagnosticsRetention,
    val networkActivityLoggingEnabled: Boolean,
)

internal fun formatDiagnosticsExport(
    metadata: DiagnosticsExportMetadata,
    entries: List<DiagnosticEntry>,
    formatter: DateTimeFormatter,
    sanitizeMessages: Boolean = true,
): String =
    buildString {
        appendLine("Foxhole Diagnostics Export")
        appendLine("Generated At: ${formatter.format(Instant.ofEpochMilli(metadata.generatedAt))}")
        appendLine("App Version: ${metadata.appVersion} (${metadata.versionCode})")
        appendLine("Core Version: ${metadata.coreVersion}")
        appendLine("Android: ${metadata.androidRelease} (API ${metadata.sdkInt})")
        appendLine("CPU ABI: ${metadata.supportedAbis.joinToString().ifBlank { "unknown" }}")
        appendLine("Theme: ${metadata.themeMode}")
        appendLine("Locale: ${metadata.locale}")
        appendLine("Traffic Mode: ${metadata.trafficMode}")
        appendLine("Tun Stack: ${metadata.tunStack}")
        appendLine("Diagnostics Retention: ${diagnosticsRetentionLabel(metadata.diagnosticsRetention)}")
        appendLine(
            "App Network Activity Logging: ${if (metadata.networkActivityLoggingEnabled) "enabled" else "disabled"}",
        )
        appendLine()
        if (entries.isEmpty()) {
            append("No retained diagnostics entries.")
        } else {
            entries.forEachIndexed { index, entry ->
                val timestamp = formatter.format(Instant.ofEpochMilli(entry.timestamp))
                val message =
                    if (sanitizeMessages) {
                        DiagnosticSanitizer.sanitizeForExport(entry.message)
                    } else {
                        entry.message
                    }
                append("$timestamp [${entry.tag}] $message")
                if (index != entries.lastIndex) {
                    appendLine()
                }
            }
        }
    }

private fun diagnosticsRetentionLabel(value: DiagnosticsRetention): String =
    when (value) {
        DiagnosticsRetention.HOURS_6 -> "6 hours"
        DiagnosticsRetention.HOURS_24 -> "24 hours"
        DiagnosticsRetention.DAYS_2 -> "2 days"
        DiagnosticsRetention.DAYS_3 -> "3 days"
        DiagnosticsRetention.DAYS_7 -> "7 days"
        DiagnosticsRetention.DAYS_14 -> "14 days"
        DiagnosticsRetention.DAYS_30 -> "30 days"
    }
