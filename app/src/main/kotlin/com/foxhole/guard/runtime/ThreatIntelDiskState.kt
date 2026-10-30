package com.foxhole.guard.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant

internal class ThreatIntelDiskState(private val directory: File) {
    private val committed = File(directory, COMMITTED_FILE)
    private val legacyDocument = File(directory, LEGACY_DOCUMENT)
    private val legacyStamp = File(directory, LEGACY_STAMP)

    internal data class Snapshot(val bytes: ByteArray, val generation: String?, val key: String)
    private data class Header(val generation: String, val size: Int, val hash: ByteArray) {
        val key: String
            get() = buildString {
                append(generation).append(':')
                hash.forEach { byte ->
                    val value = byte.toInt() and 255
                    append(HEX[value ushr 4]).append(HEX[value and 15])
                }
                append(':').append(size)
            }
    }

    fun generation(): String? =
        if (committed.isFile) {
            readCommittedHeader().generation
        } else {
            readLegacyGeneration()
        }

    fun key(): String? =
        if (committed.isFile) {
            readCommittedHeader().key
        } else {
            legacyDocument.takeIf(File::isFile)?.let { "legacy:${it.lastModified()}:${it.length()}" }
        }

    fun read(): Snapshot? {
        if (!committed.isFile) {
            if (!legacyDocument.isFile) return null
            val bytes = readLegacyDocument()
            return Snapshot(bytes, readLegacyGeneration(), "legacy:${legacyDocument.lastModified()}:${bytes.size}")
        }
        return FileInputStream(committed).use { file ->
            DataInputStream(file.buffered(HEADER_BUFFER_BYTES)).use { input ->
                val header = readHeader(input, file.channel.size())
                val bytes = ByteArray(header.size)
                input.readFully(bytes)
                check(MessageDigest.isEqual(header.hash, digest(bytes))) { "invalid committed threat intel digest" }
                Snapshot(bytes, header.generation, header.key)
            }
        }
    }

    suspend fun install(bytes: ByteArray, generatedAt: String): String = withContext(Dispatchers.IO) {
        require(bytes.size in 2..MAX_DOCUMENT_BYTES) { "unexpected threat intel size" }
        val incoming = Instant.parse(generatedAt)
        val stampBytes = generatedAt.toByteArray(Charsets.UTF_8)
        require(stampBytes.size in 1..MAX_STAMP_BYTES) { "invalid threat intel generation length" }
        currentCoroutineContext().ensureActive()
        check(directory.isDirectory || directory.mkdirs()) { "could not create threat intel directory" }
        val staged = File.createTempFile(".threat-intel-commit-", ".tmp", directory)
        try {
            FileOutputStream(staged).use { file ->
                val output = DataOutputStream(file)
                output.writeInt(MAGIC)
                output.writeInt(VERSION)
                output.writeInt(stampBytes.size)
                output.writeInt(bytes.size)
                output.write(digest(bytes))
                output.write(stampBytes)
                output.write(bytes)
                output.flush()
                file.fd.sync()
            }
            commitMutex.withLock {
                currentCoroutineContext().ensureActive()
                val installed = generation()?.let { runCatching { Instant.parse(it) }.getOrNull() }
                check(installed == null || incoming.isAfter(installed)) {
                    "threat intel generation is not newer than installed"
                }
                currentCoroutineContext().ensureActive()
                // One rename publishes metadata and the unchanged signed payload together.
                Files.move(
                    staged.toPath(),
                    committed.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            committed.absolutePath
        } finally {
            staged.delete()
        }
    }

    private fun readCommittedHeader(): Header = FileInputStream(committed).use { file ->
        DataInputStream(file.buffered(HEADER_BUFFER_BYTES)).use { input ->
            readHeader(input, file.channel.size())
        }
    }

    private fun readHeader(input: DataInputStream, fileSize: Long): Header {
        if (input.readInt() != MAGIC || input.readInt() != VERSION) {
            throw IOException("invalid threat intel commit header")
        }
        val stampSize = input.readInt()
        val size = input.readInt()
        if (stampSize !in 1..MAX_STAMP_BYTES || size !in 2..MAX_DOCUMENT_BYTES ||
            fileSize != HEADER_BYTES + stampSize.toLong() + size.toLong()
        ) {
            throw IOException("invalid threat intel commit length")
        }
        val hash = ByteArray(HASH_BYTES).also(input::readFully)
        val stamp = ByteArray(stampSize).also(input::readFully).toString(Charsets.UTF_8)
        Instant.parse(stamp)
        return Header(stamp, size, hash)
    }

    private fun readLegacyGeneration(): String? {
        if (!legacyStamp.isFile) return null
        require(legacyStamp.length() <= MAX_STAMP_BYTES) { "legacy threat intel generation too large" }
        return legacyStamp.readText().trim().takeIf(String::isNotEmpty)
    }

    private fun readLegacyDocument(): ByteArray = FileInputStream(legacyDocument).use { file ->
        val size = file.channel.size()
        require(size in 2L..MAX_DOCUMENT_BYTES.toLong()) { "legacy threat intel document too large" }
        ByteArray(size.toInt()).also { DataInputStream(file).readFully(it) }
    }

    private fun digest(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    internal companion object {
        const val COMMITTED_FILE = "threat-intel-committed.bin"
        private const val LEGACY_DOCUMENT = "threat-intel-remote.json"
        private const val LEGACY_STAMP = "threat-intel-remote.generated-at"
        private const val MAGIC = 0x46585449
        private const val VERSION = 1
        private const val HEX = "0123456789abcdef"
        private const val HEADER_BUFFER_BYTES = 128
        private const val HASH_BYTES = 32
        private const val HEADER_BYTES = 48L
        private const val MAX_STAMP_BYTES = 64
        private const val MAX_DOCUMENT_BYTES = 4 * 1024 * 1024
        private val commitMutex = Mutex()
    }
}
