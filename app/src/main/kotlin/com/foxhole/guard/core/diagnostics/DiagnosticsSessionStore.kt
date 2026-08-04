package com.foxhole.guard.core.diagnostics
import com.foxhole.core.model.DiagnosticEntry
import com.foxhole.core.model.DiagnosticSanitizer
import com.foxhole.core.model.RetentionPolicy
import com.foxhole.guard.core.sentinel.AndroidKeystoreFileCipher
import com.foxhole.guard.core.sentinel.FileCipher
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

internal class DiagnosticsSessionStore(
    private val journalDir: File,
    private val sessionIdProvider: () -> String = { UUID.randomUUID().toString() },
    private val fileCipher: FileCipher = AndroidKeystoreFileCipher("foxhole.diagnostics.journal"),
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    private var currentSessionFile: File? = null
    private var currentSessionEntries: MutableList<DiagnosticEntry>? = null
    private var lastCleanupAt = 0L

    @Synchronized
    fun loadRecentEntries(
        now: Long,
        retention: RetentionPolicy,
    ): List<DiagnosticEntry> {
        cleanup(now, retention)
        val cutoff = retention.cutoffOrNull(now) ?: Long.MIN_VALUE
        return sessionFiles()
            .flatMap { file ->
                runCatching { file.readPersistedEntries() }
                    .getOrElse { error -> listOf(journalReadFailureEntry(now, file, error)) }
            }
            .filter { entry -> entry.timestamp >= cutoff }
            .takeLast(retention.maxEntries)
    }

    @Synchronized
    fun append(
        entry: DiagnosticEntry,
        retention: RetentionPolicy,
    ) {
        appendAll(listOf(entry), retention)
    }

    @Synchronized
    fun appendAll(
        entries: List<DiagnosticEntry>,
        retention: RetentionPolicy,
    ) {
        if (entries.isEmpty()) {
            return
        }
        val now = entries.last().timestamp
        val target = writableSessionFile(now)
        val sessionEntries =
            runCatching { cachedEntriesFor(target) }
                .getOrElse { error ->
                    currentSessionFile = null
                    currentSessionEntries = null
                    val replacement = writableSessionFile(now)
                    val replacementEntries =
                        (listOf(journalReadFailureEntry(now, target, error)) + entries)
                            .sanitizePersistedEntries()
                            .takeLast(retention.maxEntries)
                            .toMutableList()
                    currentSessionEntries = replacementEntries
                    writeEntriesSync(replacement, replacementEntries)
                    maybeCleanup(now, retention)
                    return
                }
        sessionEntries += entries.sanitizePersistedEntries()
        val retained = sessionEntries.takeLast(retention.maxEntries).toMutableList()
        currentSessionEntries = retained
        writeEntriesSync(target, retained)
        maybeCleanup(now, retention)
    }

    /** Drops every persisted entry with [tag] (the per-journal manual clear). */
    @Synchronized
    fun removeTag(tag: String) {
        sessionFiles().forEach { file ->
            val entries = runCatching { file.readPersistedEntries() }.getOrNull() ?: return@forEach
            val kept = entries.filterNot { entry -> entry.tag == tag }
            if (kept.size == entries.size) {
                return@forEach
            }
            if (kept.isEmpty()) {
                file.delete()
            } else {
                writeEntriesSync(file, kept)
            }
            if (file == currentSessionFile) {
                currentSessionEntries = kept.toMutableList()
            }
        }
    }

    @Synchronized
    fun clear() {
        sessionFiles(includeLegacy = true).forEach(File::delete)
        currentSessionFile = null
        currentSessionEntries = null
        lastCleanupAt = 0L
    }

    @Synchronized
    fun cleanup(
        now: Long,
        retention: RetentionPolicy,
    ) {
        journalDir.mkdirs()
        val cutoff = retention.cutoffOrNull(now) ?: Long.MIN_VALUE
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
        currentSessionEntries = mutableListOf()
        return file
    }

    private fun cachedEntriesFor(file: File): MutableList<DiagnosticEntry> {
        if (file == currentSessionFile) {
            currentSessionEntries?.let { return it }
        }
        val entries =
            if (file.exists()) {
                file.readPersistedEntries().toMutableList()
            } else {
                mutableListOf()
            }
        currentSessionFile = file
        currentSessionEntries = entries
        return entries
    }

    private fun maybeCleanup(
        now: Long,
        retention: RetentionPolicy,
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
                    ?.let { rawLine ->
                        runCatching {
                            json.decodeFromString<PersistedDiagnosticEntry>(
                                rawLine
                            )
                        }.getOrNull()
                    }
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

    private fun List<DiagnosticEntry>.sanitizePersistedEntries(): List<DiagnosticEntry> =
        map { entry -> entry.copy(message = DiagnosticSanitizer.sanitizeForPersistence(entry.message)) }

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
