package com.foxhole.guard.ui.cli.logs

import android.content.ContentResolver
import android.net.Uri
import com.foxhole.core.model.DiagnosticEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val journalTimestampFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withLocale(Locale.ROOT)
        .withZone(ZoneId.systemDefault())

internal fun formatRawJournalExport(
    title: String,
    entries: List<DiagnosticEntry>,
): String =
    buildString {
        appendLine("FoxHole ${title.singleLineForJournalExport()}")
        appendLine("Exported entries: ${entries.size}")
        appendLine()
        entries.sortedBy(DiagnosticEntry::timestamp).forEachIndexed { index, entry ->
            val timestamp = journalTimestampFormatter.format(Instant.ofEpochMilli(entry.timestamp))
            val tag = entry.tag.singleLineForJournalExport().take(64)
            val message = entry.message.singleLineForJournalExport()
            append("$timestamp [$tag] $message")
            if (index != entries.lastIndex) {
                appendLine()
            }
        }
    }

private fun String.singleLineForJournalExport(): String =
    replace('\r', ' ')
        .replace('\n', ' ')
        .trim()

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
