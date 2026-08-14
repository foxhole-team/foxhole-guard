package com.foxhole.guard.ui.cli.logs

import androidx.compose.runtime.Immutable
import com.foxhole.core.model.DiagnosticEntry
import com.foxhole.core.model.DiagnosticSeverity

/** Visual severity only; journal bytes and export sanitization stay outside this presentation model. */
internal enum class CliJournalRowTone {
    NORMAL,
    INFO,
    SUCCESS,
    WARNING,
    ERROR,
    DIM,
}

/** One stable three-column row shared by the application, network and security journals. */
@Immutable
internal data class CliJournalRow(
    val timestamp: Long,
    val eventType: String,
    val description: List<String>,
    val tone: CliJournalRowTone = CliJournalRowTone.NORMAL,
)

internal data class CliFormattedDiagnosticMessage(
    val headline: String,
    val details: List<String>,
)

/**
 * Diagnostics are persisted as a safe compact line. Structured fields become independent table
 * values on screen without changing the persisted or exported payload.
 */
internal fun formatDiagnosticMessage(message: String): CliFormattedDiagnosticMessage {
    val fields = message.journalFields(splitBullets = false)
    return CliFormattedDiagnosticMessage(
        headline = fields.firstOrNull().orEmpty().ifBlank { JOURNAL_MISSING_VALUE },
        details = fields.drop(1),
    )
}

internal fun diagnosticJournalRow(entry: DiagnosticEntry): CliJournalRow {
    val formatted = formatDiagnosticMessage(entry.message)
    return CliJournalRow(
        timestamp = entry.timestamp,
        eventType = entry.tag.trim().ifBlank { JOURNAL_MISSING_VALUE },
        description = listOf(formatted.headline) + formatted.details,
        tone = if (entry.severity == DiagnosticSeverity.FAILURE) {
            CliJournalRowTone.ERROR
        } else {
            CliJournalRowTone.NORMAL
        },
    )
}

/**
 * The raw network formatter writes `App connection: key=value • ...`. Put the prefix in the
 * event-type column and every raw field (including endpoint and port) in the description column.
 */
internal fun networkJournalRow(entry: DiagnosticEntry): CliJournalRow {
    val fields = entry.message.journalFields(splitBullets = true)
    val first = fields.firstOrNull().orEmpty()
    val separator = first.indexOf(':')
    val hasTypedPrefix = separator in 1 until first.lastIndex
    val eventType = if (hasTypedPrefix) first.substring(0, separator).trim() else entry.tag.trim()
    val firstDescription = if (hasTypedPrefix) first.substring(separator + 1).trim() else first.trim()
    val description = buildList {
        firstDescription.takeIf(String::isNotBlank)?.let(::add)
        addAll(fields.drop(1))
    }
    return CliJournalRow(
        timestamp = entry.timestamp,
        eventType = eventType.ifBlank { JOURNAL_MISSING_VALUE },
        description = description.ifEmpty { listOf(JOURNAL_MISSING_VALUE) },
        tone = if (entry.severity == DiagnosticSeverity.FAILURE) {
            CliJournalRowTone.ERROR
        } else {
            CliJournalRowTone.INFO
        },
    )
}

internal fun journalRow(
    timestamp: Long,
    eventType: String,
    description: List<String>,
    tone: CliJournalRowTone = CliJournalRowTone.NORMAL,
): CliJournalRow =
    CliJournalRow(
        timestamp = timestamp,
        eventType = eventType.trim().ifBlank { JOURNAL_MISSING_VALUE },
        description = description.map(String::trim).filter(String::isNotBlank)
            .ifEmpty { listOf(JOURNAL_MISSING_VALUE) },
        tone = tone,
    )

private fun String.journalFields(splitBullets: Boolean): List<String> =
    replace("\r\n", "\n")
        .split('\n')
        .flatMap { line -> line.split(" · ") }
        .flatMap { field -> if (splitBullets) field.split(" • ") else listOf(field) }
        .map(String::trim)
        .filter(String::isNotEmpty)

private const val JOURNAL_MISSING_VALUE = "—"
