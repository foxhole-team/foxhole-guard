package com.foxhole.guard.ui.cli.logs

import android.content.ContentResolver
import android.net.Uri
import com.foxhole.core.model.DiagnosticEntry
import com.foxhole.core.model.DiagnosticSanitizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// The export is machine-readable, so the locale is pinned explicitly: ofPattern would otherwise take
// the system one and the format would drift the moment a textual field (MMM/EEE) appeared. java.time
// digits are locale-independent — this guards the format, it does not fix digit shapes.
private val journalTimestampFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withLocale(Locale.ROOT)
        .withZone(ZoneId.systemDefault())
private val journalGeoSegmentRegex =
    Regex(
        """(?i)(?<![\w-])country\s*=\s*.*?(?=\s+[•·]\s+(?:rx|tx|total|profile|session)\s*=|$)""",
    )
private val journalDelimitedIdentityRegex =
    Regex(
        """(?i)(?<![\w-])(packages?|package|endpoint|city|profile|session)(\s*=\s*)[^•·\r\n]*""",
    )

/** Every SAF journal export is redacted, including the opt-in on-device network journal. */
internal fun formatSanitizedJournalExport(
    title: String,
    entries: List<DiagnosticEntry>,
): String =
    buildString {
        appendLine("FoxHole ${DiagnosticSanitizer.sanitizeForExport(title)}")
        appendLine("Exported entries: ${entries.size}")
        appendLine()
        entries.sortedBy(DiagnosticEntry::timestamp).forEachIndexed { index, entry ->
            val timestamp = journalTimestampFormatter.format(Instant.ofEpochMilli(entry.timestamp))
            val safeTag = DiagnosticSanitizer.sanitizeForExport(entry.tag).take(64)
            val safeMessage = sanitizeJournalMessage(entry.message)
            append("$timestamp [$safeTag] $safeMessage")
            if (index != entries.lastIndex) {
                appendLine()
            }
        }
    }

/**
 * Structured journal fields can contain spaces (country/city names, several packages). The core
 * sanitizer intentionally treats ordinary key/value logs token-by-token, so close those
 * journal-specific tails first and then apply the general URL/profile/IP/secret sanitizer.
 */
private fun sanitizeJournalMessage(message: String): String {
    val withoutGeo = message.replace(journalGeoSegmentRegex, "country=[redacted]")
    val withoutDelimitedIdentities =
        withoutGeo.replace(journalDelimitedIdentityRegex) { match ->
            "${match.groupValues[1]}${match.groupValues[2]}[redacted]"
        }
    return DiagnosticSanitizer.sanitizeForExport(withoutDelimitedIdentities)
}

internal suspend fun writeJournalExport(
    resolver: ContentResolver,
    uri: Uri,
    payload: String,
): Boolean =
    withContext(Dispatchers.IO) {
        runCatching {
            resolver.openOutputStream(uri, "wt")?.use { stream ->
                stream.write(payload.toByteArray(Charsets.UTF_8))
            } != null
        }.getOrDefault(false)
    }
