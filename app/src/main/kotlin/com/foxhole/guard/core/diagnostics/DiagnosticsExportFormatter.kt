package com.foxhole.guard.core.diagnostics
import com.foxhole.core.model.DiagnosticEntry
import com.foxhole.core.model.DiagnosticSanitizer
import com.foxhole.core.model.RetentionPolicy
import com.foxhole.core.model.RetentionPreset
import java.time.Instant
import java.time.format.DateTimeFormatter

internal fun formatSanitizedAboutDiagnosticsExport(
    metadata: DiagnosticsExportMetadata,
    entries: List<DiagnosticEntry>,
    formatter: DateTimeFormatter,
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
                val tag = DiagnosticSanitizer.sanitizeForExport(entry.tag)
                val message = DiagnosticSanitizer.sanitizeForExport(entry.message)
                append("$timestamp [$tag] $message")
                if (index != entries.lastIndex) {
                    appendLine()
                }
            }
        }
    }

private fun diagnosticsRetentionLabel(value: RetentionPolicy): String =
    when (value.preset) {
        RetentionPreset.DAY -> "1 day"
        RetentionPreset.WEEK -> "7 days"
        RetentionPreset.MONTH -> "31 days"
        RetentionPreset.FOREVER -> "forever"
        RetentionPreset.CUSTOM -> "${value.normalizedCustomDays()} days"
    }
