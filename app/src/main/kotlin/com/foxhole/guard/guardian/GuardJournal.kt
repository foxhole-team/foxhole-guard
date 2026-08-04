package com.foxhole.guard.guardian

import com.foxhole.guard.core.security.GuardCrypto
import kotlinx.serialization.json.Json
import java.io.File
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
        val state = loadHead()
        if (state.nextSeq == 0L && event.type != GuardEventType.GENESIS) {
            appendRecord(state, publicKey, GuardEvent(type = GuardEventType.GENESIS, detail = randomChainId()))
        }
        appendRecord(state, publicKey, event)
        pruneCheckpointedFiles()
        return true
    }

    @Synchronized
    fun deleteAll() {
        journalFiles().forEach(File::delete)
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
        target.appendText(line + "\n")
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
        if (current != null && current.isFile && current.length() < maxFileBytes) {
            return current
        }
        directory.mkdirs()
        return File(directory, fileNameFor(seq))
    }

    private fun pruneCheckpointedFiles() {
        val files = journalFiles()
        if (files.size <= maxFiles) {
            return
        }
        val checkpointSeq = checkpointSeqProvider()
        val removable = files.dropLast(maxFiles)
        for (file in removable) {
            val nextFirstSeq = files.getOrNull(files.indexOf(file) + 1)?.let(::firstSeqOf) ?: continue
            // Only prune when every record in the file precedes the password-anchored
            // checkpoint; anything newer must stay for the verifier.
            if (nextFirstSeq - 1 < checkpointSeq) {
                file.delete()
            }
        }
    }

    private fun loadHead(): HeadState {
        head?.let { return it }
        val newest = journalFiles().lastOrNull()
        val state =
            if (newest == null) {
                HeadState(
                    nextSeq = 0L,
                    headHash = "",
                    lastWallClock = 0L,
                    lastElapsedRealtime = 0L,
                    lastBootCount = 0,
                    currentFile = null,
                )
            } else {
                headFromFile(newest)
            }
        head = state
        return state
    }

    private fun headFromFile(newest: File): HeadState {
        var lastLine: String? = null
        newest.useLines { lines ->
            lines.forEach { line ->
                if (line.isNotBlank() && decodeRecordOrNull(line) != null) {
                    lastLine = line
                }
            }
        }
        val line = lastLine
        val record = line?.let(::decodeRecordOrNull)
        if (line == null || record == null) {
            // Unreadable tail: never overwrite history, chain a fresh file after it.
            return HeadState(
                nextSeq = guessNextSeqAfter(newest),
                headHash = UNREADABLE_TAIL_HASH,
                lastWallClock = 0L,
                lastElapsedRealtime = 0L,
                lastBootCount = 0,
                currentFile = null,
            )
        }
        return HeadState(
            nextSeq = record.seq + 1,
            headHash = sha256Hex(line),
            lastWallClock = record.wallClock,
            lastElapsedRealtime = record.elapsedRealtime,
            lastBootCount = record.bootCount,
            currentFile = newest,
        )
    }

    private fun guessNextSeqAfter(file: File): Long {
        val firstSeq = firstSeqOf(file)
        val roughLines = (file.length() / MIN_PLAUSIBLE_LINE_BYTES).coerceAtLeast(1)
        return firstSeq + roughLines + 1
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
        const val UNREADABLE_TAIL_HASH = "unreadable-tail"
        private const val MIN_PLAUSIBLE_LINE_BYTES = 64L
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
