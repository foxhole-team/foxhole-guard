package com.foxhole.guard.ui.cli

import com.foxhole.guard.core.sentinel.AndroidKeystoreFileCipher
import com.foxhole.guard.core.sentinel.FileCipher
import com.foxhole.guard.ui.cli.home.CliLineTone
import com.foxhole.guard.ui.cli.home.CliTerminalLine
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The home terminal's journal on disk.
 *
 * The terminal is what the user reads to answer "why did it reconnect while the phone was in my
 * pocket", and the retention row in settings promises the lines live for as long as it says. Until
 * now they lived only in the composition: a rotation, a system theme or font-size change, a
 * language change or the process being reclaimed in the background reset the journal to its boot
 * lines. Nothing but expiry — or the user clearing local data — may drop a line, so the journal is
 * persisted.
 *
 * Storage is the diagnostics journal's, deliberately: the same [FileCipher] (Android-Keystore-backed
 * AES), the same one-JSON-record-per-line layout, the same "retention applied on write and on read"
 * rule. A journal of everything the app narrated about the user's connections is exactly as
 * sensitive as the diagnostics one, so it gets the same envelope rather than a second, weaker one.
 *
 * Retention is enforced twice on purpose. On write, so a shortened retention takes effect at once
 * instead of waiting for a read; on read, so lines that expired while the app was not running never
 * reach the screen even if the file was not rewritten since.
 *
 * Every failure path is silent and empty: an unreadable or truncated journal is deleted and the
 * terminal starts clean. A corrupt log file must never be able to stop the app from opening.
 */
internal class CliTerminalStore(
    private val file: File,
    private val fileCipher: FileCipher = AndroidKeystoreFileCipher(KEYSTORE_ALIAS),
    private val maxLines: Int = MAX_PERSISTED_LINES,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    // The journal as last written/read. Held so an append does not decrypt the whole file again.
    private var cached: List<CliTerminalLine>? = null

    /**
     * The retained journal, oldest first, at most [limit] lines — the tail, because the terminal
     * shows the most recent lines and holds fewer of them than the file keeps.
     */
    @Synchronized
    fun load(
        nowMs: Long,
        retentionHours: Int,
        limit: Int,
    ): List<CliTerminalLine> {
        val retained = readAll().retain(nowMs, retentionHours)
        cached = retained
        return retained.takeLast(limit)
    }

    /**
     * Adds lines the terminal has just printed, then applies retention to the whole journal.
     *
     * Takes only the new lines rather than the terminal's visible list: the file keeps the full
     * retention window while the screen keeps its own much shorter window, and rewriting the file
     * from the screen would silently truncate the journal to what happens to be on screen.
     */
    @Synchronized
    fun append(
        newLines: List<CliTerminalLine>,
        nowMs: Long,
        retentionHours: Int,
    ) {
        if (newLines.isEmpty()) {
            return
        }
        val merged = (cached ?: readAll()) + newLines
        val retained = merged.retain(nowMs, retentionHours)
        cached = retained
        write(retained)
    }

    /** Drops the journal entirely — the user's explicit clear, and the factory reset. */
    @Synchronized
    fun clear() {
        cached = emptyList()
        runCatching { file.delete() }
    }

    private fun List<CliTerminalLine>.retain(
        nowMs: Long,
        retentionHours: Int,
    ): List<CliTerminalLine> {
        val cutoffMs = nowMs - CliTerminalPrefs.normalizedHours(retentionHours) * MS_PER_HOUR
        return filter { line -> line.timestampMs >= cutoffMs }.takeLast(maxLines)
    }

    private fun readAll(): List<CliTerminalLine> {
        cached?.let { return it }
        if (!file.isFile) {
            return emptyList()
        }
        return runCatching {
            fileCipher
                .readBytes(file)
                .toString(Charsets.UTF_8)
                .lineSequence()
                .filter(String::isNotBlank)
                .mapNotNull { line ->
                    runCatching { json.decodeFromString<PersistedTerminalLine>(line) }.getOrNull()
                }
                .map(PersistedTerminalLine::toLine)
                .toList()
        }.getOrElse {
            // Wrong key after a keystore reset, a half-written file, a foreign file: start clean
            // rather than fail the first frame of the app.
            runCatching { file.delete() }
            emptyList()
        }
    }

    private fun write(lines: List<CliTerminalLine>) {
        runCatching {
            file.parentFile?.mkdirs()
            if (lines.isEmpty()) {
                file.delete()
                return
            }
            val payload = lines
                .joinToString(separator = "\n", postfix = "\n") { line ->
                    json.encodeToString(line.toPersisted())
                }
                .toByteArray(Charsets.UTF_8)
            fileCipher.writeBytesAtomic(file, payload)
        }
    }

    /**
     * [CliTerminalLine.id] is deliberately not persisted: it is a LazyColumn key handed out by the
     * running [com.foxhole.guard.ui.cli.home.CliTerminalState], and restoring stale ids would
     * collide with the ones the new session issues.
     */
    private fun CliTerminalLine.toPersisted(): PersistedTerminalLine =
        PersistedTerminalLine(
            timestampMs = timestampMs,
            text = text,
            tone = tone,
            prompt = prompt,
            flagCountry = flagCountry,
            value = value,
            valueTone = valueTone,
            packages = packages,
        )

    @Serializable
    private data class PersistedTerminalLine(
        val timestampMs: Long,
        val text: String,
        // Defaulted so a journal written before a tone existed still reads as an ordinary line.
        val tone: CliLineTone = CliLineTone.PLAIN,
        val prompt: Boolean = false,
        val flagCountry: String? = null,
        val value: String? = null,
        val valueTone: CliLineTone = tone,
        val packages: List<String> = emptyList(),
    ) {
        fun toLine(): CliTerminalLine =
            CliTerminalLine(
                timestampMs = timestampMs,
                text = text,
                tone = tone,
                prompt = prompt,
                flagCountry = flagCountry,
                value = value,
                valueTone = valueTone,
                packages = packages,
            )
    }

    companion object {
        /** The journal's own directory under `filesDir`; a factory reset deletes it. */
        const val JOURNAL_DIR_NAME = "cli-terminal-journal"
        const val JOURNAL_FILE_NAME = "terminal.jsonl.enc"

        private const val KEYSTORE_ALIAS = "foxhole.cli.terminal.journal"

        /**
         * Disk cap that holds whatever the retention window says. At the 30-day maximum a busy
         * install narrates a few hundred lines a day, so this is roughly a week of the worst case
         * and a few hundred kilobytes — the window is a time bound, this is the safety bound.
         */
        private const val MAX_PERSISTED_LINES = 4_000

        private const val MS_PER_HOUR = 3_600_000L
    }
}
