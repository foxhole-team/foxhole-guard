package com.foxhole.guard.ui.cli.logs

import androidx.compose.runtime.Immutable
import com.foxhole.core.model.DiagnosticEntry
import com.foxhole.core.model.DiagnosticSeverity

internal enum class CliJournalRowTone {
    NORMAL,
    INFO,
    SUCCESS,
    WARNING,
    ERROR,
    DIM,
}

@Immutable
internal data class CliJournalRow(
    val timestamp: Long,
    val eventType: String,
    val description: List<String>,
    val tone: CliJournalRowTone = CliJournalRowTone.NORMAL,
    val flagCountry: String? = null,
)

internal data class CliFormattedDiagnosticMessage(
    val headline: String,
    val details: List<String>,
)

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
        flagCountry = networkJournalCountryCode(entry.message),
    )
}

internal fun networkJournalCountryCode(message: String): String? =
    JOURNAL_COUNTRY_CODE.find(message)?.groupValues?.get(1)?.lowercase()

private val JOURNAL_COUNTRY_CODE = Regex("""country=[^\n]*?\(([A-Za-z]{2})\)""")

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
