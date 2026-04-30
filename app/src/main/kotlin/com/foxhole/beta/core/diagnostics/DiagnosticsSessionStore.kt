package com.foxhole.beta.core.diagnostics

import com.foxhole.beta.core.model.DiagnosticsRetention
import com.foxhole.beta.core.security.AndroidKeystoreFileCipher
import com.foxhole.beta.core.security.FileCipher
import java.io.File
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class DiagnosticsSessionStore(
    private val journalDir: File,
    private val nowProvider: () -> Long = System::currentTimeMillis,
    private val sessionIdProvider: () -> String = { UUID.randomUUID().toString() },
    private val fileCipher: FileCipher = AndroidKeystoreFileCipher("foxhole.diagnostics.journal"),
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
            .flatMap { file ->
                runCatching { file.readPersistedEntries() }
                    .getOrElse { error -> listOf(journalReadFailureEntry(now, file, error)) }
            }
            .filter { entry -> entry.timestamp >= cutoff }
            .takeLast(retention.maxEntries)
    }

    fun append(
        entry: DiagnosticEntry,
        retention: DiagnosticsRetention,
    ) {
        val target = writableSessionFile(entry.timestamp)
        val existingEntries =
            if (target.exists()) {
                runCatching { target.readPersistedEntries() }
                    .getOrElse { error ->
                        currentSessionFile = null
                        val replacement = writableSessionFile(entry.timestamp)
                        writeEntriesSync(
                            replacement,
                            listOf(
                                journalReadFailureEntry(entry.timestamp, target, error),
                                entry,
                            ),
                        )
                        maybeCleanup(entry.timestamp, retention)
                        return
                    }
            } else {
                emptyList()
            }
        writeEntriesSync(target, existingEntries + entry)
        maybeCleanup(entry.timestamp, retention)
    }

    fun clear() {
        sessionFiles(includeLegacy = true).forEach(File::delete)
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
        val file = File(journalDir, "session-$now-${sessionIdProvider()}.jsonl.enc")
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

    private fun sessionFiles(includeLegacy: Boolean = true): List<File> =
        journalDir
            .listFiles { file ->
                file.isFile &&
                    file.name.startsWith("session-") &&
                    (file.name.endsWith(".jsonl.enc") || (includeLegacy && file.name.endsWith(".jsonl")))
            }
            ?.sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
            .orEmpty()

    private fun File.readPersistedEntries(): List<DiagnosticEntry> =
        readPersistedEntriesUnsafe()

    private fun File.readPersistedEntriesUnsafe(): List<DiagnosticEntry> {
        val text =
            if (name.endsWith(".jsonl.enc")) {
                fileCipher.readBytes(this).toString(Charsets.UTF_8)
            } else {
                readText(Charsets.UTF_8)
            }
        return text
            .lineSequence()
            .mapNotNull { line ->
                line
                    .takeIf(String::isNotBlank)
                    ?.let { rawLine -> runCatching { json.decodeFromString<PersistedDiagnosticEntry>(rawLine) }.getOrNull() }
                    ?.toDiagnosticEntry()
            }.toList()
            .also { entries ->
                if (name.endsWith(".jsonl") && entries.isNotEmpty()) {
                    val encryptedFile = File(parentFile, "$name.enc")
                    writeEntriesSync(encryptedFile, entries)
                    delete()
                }
            }
    }

    private fun journalReadFailureEntry(
        now: Long,
        file: File,
        error: Throwable,
    ): DiagnosticEntry =
        DiagnosticEntry(
            timestamp = now,
            tag = DIAGNOSTICS_TAG,
            message = "diagnostics journal read failed file=${file.name} error=${error.javaClass.simpleName}",
        )

    private fun writeEntriesSync(
        file: File,
        entries: List<DiagnosticEntry>,
    ) {
        val payload =
            entries
                .joinToString(separator = "\n", postfix = "\n") { entry ->
                    json.encodeToString(entry.toPersisted())
                }
                .toByteArray(Charsets.UTF_8)
        fileCipher.writeBytesAtomic(file, payload)
    }

    private fun DiagnosticEntry.toPersisted(): PersistedDiagnosticEntry =
        PersistedDiagnosticEntry(timestamp = timestamp, tag = tag, message = message)

    private fun PersistedDiagnosticEntry.toDiagnosticEntry(): DiagnosticEntry =
        DiagnosticEntry(
            timestamp = timestamp,
            tag = tag,
            message = DiagnosticSanitizer.sanitizeForPersistence(message),
        )

    @Serializable
    private data class PersistedDiagnosticEntry(
        val timestamp: Long,
        val tag: String,
        val message: String,
    )

    private companion object {
        private const val DIAGNOSTICS_TAG = "diagnostics"
        private const val MAX_SESSION_FILES = 16
        private const val MAX_SESSION_FILE_BYTES = 768L * 1024L
        private const val CLEANUP_INTERVAL_MS = 60L * 1000L
    }
}
