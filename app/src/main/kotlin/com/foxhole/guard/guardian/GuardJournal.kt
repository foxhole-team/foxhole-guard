package com.foxhole.guard.guardian

import com.foxhole.guard.core.security.GuardCrypto
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Base64

/**
 * Append-only, hash-chained, sealed journal (`filesDir/guard/journal/guard-*.jsonl`).
 * The write path needs only the plaintext guard public key: it works while the app
 * is locked, straight after boot, and inside receiver budgets (no Argon2, no
 * Keystore). Rotation keeps the chain intact across files; pre-checkpoint files may
 * be pruned without looking like tampering to the verifier.
 */
internal class GuardJournal(
    private val directory: File,
    private val crypto: GuardCrypto,
    private val clock: GuardClock,
    private val publicKeyProvider: () -> ByteArray?,
    private val checkpointSeqProvider: () -> Long,
    private val maxFileBytes: Long = MAX_FILE_BYTES,
    private val maxFiles: Int = MAX_FILES,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private class HeadState(
        var nextSeq: Long,
        var headHash: String,
        var lastWallClock: Long,
        var lastElapsedRealtime: Long,
        var lastBootCount: Int,
        var currentFile: File?,
    )

    private var head: HeadState? = null

    fun isActive(): Boolean = publicKeyProvider() != null

    /** Last durable envelope timestamp; readable while locked because payloads alone are sealed. */
    @Synchronized
    fun lastRecordWallClockMs(): Long? = loadHead().lastWallClock.takeIf { timestamp -> timestamp > 0L }

    /**
     * Appends one sealed event. Returns false (and writes nothing) when the guard
     * public key is unavailable, i.e. password protection is off.
     */
    @Synchronized
    fun append(event: GuardEvent): Boolean {
        val publicKey = publicKeyProvider() ?: return false
        return runCatching {
            val state = loadHead()
            if (state.nextSeq == 0L && event.type != GuardEventType.GENESIS) {
                appendRecord(state, publicKey, GuardEvent(type = GuardEventType.GENESIS, detail = randomChainId()))
            }
            appendRecord(state, publicKey, event)
            pruneCheckpointedFiles()
            true
        }.getOrElse {
            // Re-read disk after any failed write or fsync.
            head = null
            false
        }
    }

    @Synchronized
    fun deleteAll() {
        journalFiles().forEach(File::delete)
        // Factory reset must remove staged journal records too.
        directory
            .listFiles { file -> file.isFile && file.name.endsWith(STAGED_SUFFIX) }
            .orEmpty()
            .forEach(File::delete)
        head = null
    }

    private fun appendRecord(
        state: HeadState,
        publicKey: ByteArray,
        event: GuardEvent,
    ) {
        val payload = json.encodeToString(GuardEvent.serializer(), event).encodeToByteArray()
        val record =
            GuardJournalRecord(
                seq = state.nextSeq,
                prevHash = state.headHash,
                wallClock = clock.wallClockMs(),
                elapsedRealtime = clock.elapsedRealtimeMs(),
                bootCount = clock.bootCount(),
                sealed = Base64.getEncoder().encodeToString(crypto.seal(payload, publicKey)),
            )
        val line = json.encodeToString(GuardJournalRecord.serializer(), record)
        val target = targetFileFor(state, record.seq)
        FileOutputStream(target, true).use { output ->
            output.write((line + "\n").encodeToByteArray())
            // Report success only after journal evidence reaches stable storage.
            output.fd.sync()
        }
        state.nextSeq = record.seq + 1
        state.headHash = sha256Hex(line)
        state.lastWallClock = record.wallClock
        state.lastElapsedRealtime = record.elapsedRealtime
        state.lastBootCount = record.bootCount
        state.currentFile = target
    }

    private fun targetFileFor(
        state: HeadState,
        seq: Long,
    ): File {
        val current = state.currentFile
        if (current != null && current.isStillAppendable()) {
            return current
        }
        directory.mkdirs()
        val target = File(directory, fileNameFor(seq))
        // Repair a torn first record before reusing its sequence-derived filename.
        if (!target.isFile || repairTornTail(target)) {
            return target
        }
        // Preserve non-tail corruption as evidence and continue in a new file.
        return firstFreeFileFrom(seq)
    }

    /**
     * Whether the cached head file may still take another record.
     *
     * `endsWithNewline` is the same guarantee `currentFile` was chosen under, re-checked against
     * the file itself: a cached head does not prove the bytes on disk are still whole, and
     * appending behind a fragment glues the next JSON object to it. One seek, not a parse, so it
     * stays affordable on every append.
     */
    private fun File.isStillAppendable(): Boolean = isFile && length() < maxFileBytes && endsWithNewline(this)

    /**
     * Makes a file that a crash left mid-record safe to append to again, and reports whether it
     * succeeded.
     *
     * Only the trailing fragment - the bytes after the last newline, which no verifier could ever
     * parse - is dropped. If any *earlier* line fails to decode the damage is not a torn write
     * (append-only writing can only ever damage the last line), so this refuses: truncating there
     * would delete decodable records that follow the damage, which is exactly the evidence a
     * tampering report is built from.
     *
     * The rewrite is staged and renamed over the original, so the repair itself can never be the
     * thing that leaves a torn file: rename(2) either replaces the file whole or not at all, and a
     * failure anywhere leaves the original bytes untouched.
     */
    private fun repairTornTail(file: File): Boolean {
        val content = runCatching(file::readText).getOrNull() ?: return false
        val lines = content.split('\n')
        val fragment = lines.last()
        val complete = lines.dropLast(1)
        if (complete.any { line -> line.isNotBlank() && decodeRecordOrNull(line) == null }) {
            return false
        }
        if (fragment.isEmpty()) {
            return true
        }
        return writeAtomically(
            file = file,
            content = complete.filter(String::isNotBlank).joinToString(separator = "") { line -> line + "\n" },
        )
    }

    private fun writeAtomically(
        file: File,
        content: String,
    ): Boolean =
        runCatching {
            val staged = File(directory, file.name + STAGED_SUFFIX)
            FileOutputStream(staged).use { output ->
                output.write(content.encodeToByteArray())
                output.fd.sync()
            }
            check(staged.renameTo(file)) { "journal repair could not be swapped in" }
            true
        }.getOrElse {
            File(directory, file.name + STAGED_SUFFIX).delete()
            false
        }

    /** The first unused journal file name at or after [seq]; names stay ordered by first seq. */
    private fun firstFreeFileFrom(seq: Long): File {
        for (offset in 0 until MAX_FILE_NAME_PROBES) {
            val candidate = File(directory, fileNameFor(seq + offset))
            if (!candidate.exists()) {
                return candidate
            }
        }
        // Fail closed when no target filename can be validated.
        error("no free guard journal file name after seq $seq")
    }

    private fun endsWithNewline(file: File): Boolean =
        runCatching {
            val length = file.length()
            if (length == 0L) {
                return@runCatching true
            }
            RandomAccessFile(file, "r").use { access ->
                access.seek(length - 1)
                access.read() == NEWLINE_BYTE
            }
        }.getOrDefault(false)

    private fun pruneCheckpointedFiles() {
        val files = journalFiles()
        if (files.size <= maxFiles) {
            return
        }
        val checkpointSeq = checkpointSeqProvider()
        val removable = files.dropLast(maxFiles)
        for (file in removable) {
            val nextFirstSeq = files.getOrNull(files.indexOf(file) + 1)?.let(::firstSeqOf) ?: continue
            // Never prune records newer than the password-anchored checkpoint.
            if (nextFirstSeq - 1 < checkpointSeq) {
                file.delete()
            }
        }
    }

    private fun loadHead(): HeadState {
        head?.let { return it }
        // Preserve torn tails as evidence; repair only before filename reuse.
        val state = headFromFiles(journalFiles())
        head = state
        return state
    }

    /**
     * Recovers the chain head after a restart. A crash can leave the newest file holding nothing
     * but a torn fragment (the writer created it and died mid-record); the last *decodable* record
     * then lives in an older file, so the scan walks backwards until it finds one instead of
     * guessing a sequence number from the damaged file's size. Guessing produced a fabricated seq
     * and a placeholder prevHash, which permanently reported an ordinary crash as a rewritten
     * journal and corrupted the very next record's chain link.
     */
    private fun headFromFiles(files: List<File>): HeadState {
        for (file in files.asReversed()) {
            headFromFileOrNull(file)?.let { return it }
        }
        // Start a new chain; the keybox detects deletion of prior history.
        return HeadState(
            nextSeq = 0L,
            headHash = "",
            lastWallClock = 0L,
            lastElapsedRealtime = 0L,
            lastBootCount = 0,
            currentFile = null,
        )
    }

    private fun headFromFileOrNull(file: File): HeadState? {
        val content = runCatching(file::readText).getOrNull().orEmpty()
        val nonBlankLines = content.lineSequence().filter(String::isNotBlank).toList()
        val line = nonBlankLines.lastOrNull { candidate -> decodeRecordOrNull(candidate) != null }
        val record = line?.let(::decodeRecordOrNull) ?: return null
        val tailIsCompleteAndValid =
            content.endsWith('\n') &&
                nonBlankLines.lastOrNull() == line
        return HeadState(
            nextSeq = record.seq + 1,
            headHash = sha256Hex(line),
            lastWallClock = record.wallClock,
            lastElapsedRealtime = record.elapsedRealtime,
            lastBootCount = record.bootCount,
            // Continue in a new file after an incomplete or unparseable tail.
            currentFile = file.takeIf { tailIsCompleteAndValid },
        )
    }

    private fun decodeRecordOrNull(line: String): GuardJournalRecord? =
        runCatching { json.decodeFromString(GuardJournalRecord.serializer(), line) }.getOrNull()

    private fun journalFiles(): List<File> =
        directory
            .listFiles { file -> file.isFile && file.name.matches(FILE_NAME_REGEX) }
            .orEmpty()
            .sortedBy(::firstSeqOf)

    private fun randomChainId(): String = java.util.UUID.randomUUID().toString()

    companion object {
        const val MAX_FILE_BYTES = 256L * 1024L
        const val MAX_FILES = 32

        // Keep staged repairs outside the journal filename grammar.
        private const val STAGED_SUFFIX = ".repair"
        private const val MAX_FILE_NAME_PROBES = 1024
        private const val NEWLINE_BYTE = '\n'.code
        private val FILE_NAME_REGEX = Regex("""guard-\d{16}\.jsonl""")

        fun fileNameFor(firstSeq: Long): String = "guard-%016d.jsonl".format(firstSeq)

        fun firstSeqOf(file: File): Long =
            file.name
                .removePrefix("guard-")
                .removeSuffix(".jsonl")
                .toLongOrNull() ?: Long.MAX_VALUE

        fun sha256Hex(line: String): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(line.encodeToByteArray())
                .joinToString("") { byte -> "%02x".format(byte) }
    }
}
