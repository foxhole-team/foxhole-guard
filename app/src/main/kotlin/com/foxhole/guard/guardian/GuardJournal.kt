package com.foxhole.guard.guardian

import com.foxhole.guard.core.security.GuardCrypto
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Base64

// Append-only sealed hash chain; recovery may discard only a trailing torn fragment, never valid later records.
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

    @Synchronized
    fun lastRecordWallClockMs(): Long? = loadHead().lastWallClock.takeIf { timestamp -> timestamp > 0L }

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
            head = null
            false
        }
    }

    @Synchronized
    fun deleteAll() {
        journalFiles().forEach(File::delete)
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
        if (!target.isFile || repairTornTail(target)) {
            return target
        }
        return firstFreeFileFrom(seq)
    }

    private fun File.isStillAppendable(): Boolean = isFile && length() < maxFileBytes && endsWithNewline(this)

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

    private fun firstFreeFileFrom(seq: Long): File {
        for (offset in 0 until MAX_FILE_NAME_PROBES) {
            val candidate = File(directory, fileNameFor(seq + offset))
            if (!candidate.exists()) {
                return candidate
            }
        }
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
            if (nextFirstSeq - 1 < checkpointSeq) {
                file.delete()
            }
        }
    }

    private fun loadHead(): HeadState {
        head?.let { return it }
        val state = headFromFiles(journalFiles())
        head = state
        return state
    }

    private fun headFromFiles(files: List<File>): HeadState {
        for (file in files.asReversed()) {
            headFromFileOrNull(file)?.let { return it }
        }
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
