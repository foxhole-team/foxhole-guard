package com.foxhole.beta.core.diagnostics

import com.foxhole.beta.core.model.DiagnosticsRetention
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class DiagnosticsSessionStore(
    private val journalDir: File,
    private val nowProvider: () -> Long = System::currentTimeMillis,
    private val sessionIdProvider: () -> String = { UUID.randomUUID().toString() },
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    private var currentSessionFile: File? = null
    private var lastCleanupAt = 0L

    fun loadRecentEntries(
        now: Long,
        retention: DiagnosticsRetention,
    ): List<DiagnosticEntry> {
        cleanup(now, retention)
        val cutoff = now - retention.retentionHours * 60L * 60L * 1000L
        return sessionFiles()
            .flatMap { file -> file.readPersistedEntries() }
            .filter { entry -> entry.timestamp >= cutoff }
            .takeLast(retention.maxEntries)
    }

    fun append(
        entry: DiagnosticEntry,
        retention: DiagnosticsRetention,
    ) {
        val target = writableSessionFile(entry.timestamp)
        writeLineSync(target, json.encodeToString(entry.toPersisted()))
        maybeCleanup(entry.timestamp, retention)
    }

    fun clear() {
        sessionFiles().forEach(File::delete)
        currentSessionFile = null
        lastCleanupAt = 0L
    }

    fun cleanup(
        now: Long,
        retention: DiagnosticsRetention,
    ) {
        journalDir.mkdirs()
        val cutoff = now - retention.retentionHours * 60L * 60L * 1000L
        val files = sessionFiles()
        files
            .filter { file -> file.lastModified() < cutoff }
            .forEach(File::delete)

        val remaining = sessionFiles()
        val overflow = remaining.size - MAX_SESSION_FILES
        if (overflow > 0) {
            remaining.take(overflow).forEach(File::delete)
        }
        lastCleanupAt = now
    }

    private fun writableSessionFile(now: Long): File {
        journalDir.mkdirs()
        val existing = currentSessionFile
        if (existing != null && existing.isFile && existing.length() < MAX_SESSION_FILE_BYTES) {
            return existing
        }
        val file = File(journalDir, "session-$now-${sessionIdProvider()}.jsonl")
        currentSessionFile = file
        return file
    }

    private fun maybeCleanup(
        now: Long,
        retention: DiagnosticsRetention,
    ) {
        if (now - lastCleanupAt >= CLEANUP_INTERVAL_MS) {
            cleanup(now, retention)
        }
    }

    private fun sessionFiles(): List<File> =
        journalDir
            .listFiles { file -> file.isFile && file.name.startsWith("session-") && file.name.endsWith(".jsonl") }
            ?.sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
            .orEmpty()

    private fun File.readPersistedEntries(): List<DiagnosticEntry> =
        runCatching {
            useLines(Charsets.UTF_8) { lines ->
                lines.mapNotNull { line ->
                    line
                        .takeIf(String::isNotBlank)
                        ?.let { rawLine -> runCatching { json.decodeFromString<PersistedDiagnosticEntry>(rawLine) }.getOrNull() }
                        ?.toDiagnosticEntry()
                }.toList()
            }
        }.getOrDefault(emptyList())

    private fun writeLineSync(
        file: File,
        line: String,
    ) {
        FileOutputStream(file, true).use { output ->
            output.write(line.toByteArray(Charsets.UTF_8))
            output.write('\n'.code)
            output.flush()
            output.fd.sync()
        }
    }

    private fun DiagnosticEntry.toPersisted(): PersistedDiagnosticEntry =
        PersistedDiagnosticEntry(timestamp = timestamp, tag = tag, message = message)

    private fun PersistedDiagnosticEntry.toDiagnosticEntry(): DiagnosticEntry =
        DiagnosticEntry(timestamp = timestamp, tag = tag, message = message)

    @Serializable
    private data class PersistedDiagnosticEntry(
        val timestamp: Long,
        val tag: String,
        val message: String,
    )

    private companion object {
        private const val MAX_SESSION_FILES = 16
        private const val MAX_SESSION_FILE_BYTES = 768L * 1024L
        private const val CLEANUP_INTERVAL_MS = 60L * 1000L
    }
}
