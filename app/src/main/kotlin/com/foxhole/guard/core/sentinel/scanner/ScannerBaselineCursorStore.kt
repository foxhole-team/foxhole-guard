package com.foxhole.guard.core.sentinel.scanner

import com.foxhole.guard.core.sentinel.FileCipher
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
private data class ScannerBaselineDocument(val epoch: String, val packages: List<String>, val next: Int = 0)

internal data class ScannerBaselineClaim(val epoch: String, val offset: Int, val packages: List<String>)

internal class ScannerBaselineCursorStore(private val file: File, private val cipher: FileCipher) {
    private val lock = Any()
    private val json = Json { encodeDefaults = true }

    fun next(epoch: String, packages: List<String>): List<String> = synchronized(lock) {
        require(epoch.length in 1..128)
        val previous = read()
        val document = if (previous?.epoch == epoch) {
            previous
        } else {
            require(packages.size <= 4096)
            require(packages.all { it.length <= 255 && it.matches(Regex("[A-Za-z0-9_]+(?:[.][A-Za-z0-9_]+)+")) })
            ScannerBaselineDocument(epoch, packages.distinct()).also(::write)
        }
        document.packages.drop(document.next).take(8)
    }

    fun claim(epoch: String, packages: List<String>): ScannerBaselineClaim = synchronized(lock) {
        val chunk = next(epoch, packages)
        val document = requireNotNull(read())
        ScannerBaselineClaim(epoch, document.next, chunk)
    }

    fun acknowledge(claim: ScannerBaselineClaim): Boolean = synchronized(lock) {
        val document = read() ?: return@synchronized false
        val chunk = document.packages.drop(document.next).take(8)
        val currentOwner = claim.epoch == document.epoch && claim.offset == document.next
        if (!currentOwner || claim.packages != chunk || chunk.isEmpty()) {
            return@synchronized false
        }
        write(document.copy(next = document.next + chunk.size))
        true
    }

    fun advance(epoch: String, count: Int) = synchronized(lock) {
        val document = requireNotNull(read())
        require(document.epoch == epoch && count in 1..8 && count <= document.packages.size - document.next)
        write(document.copy(next = document.next + count))
    }

    fun clear() = synchronized(lock) {
        check(!file.exists() || file.delete()) { "Scanner cursor cleanup failed" }
    }

    private fun read(): ScannerBaselineDocument? {
        if (!file.exists()) return null
        require(file.isFile && file.length() in 1..1048576)
        val bytes = cipher.readBytes(file)
        require(bytes.size <= 1048576)
        val document = json.decodeFromString(ScannerBaselineDocument.serializer(), bytes.decodeToString())
        require(
            document.epoch.length in 1..128 && document.packages.size <= 4096 && document.next in 0..document.packages.size
        )
        require(document.packages.toSet().size == document.packages.size)
        require(document.packages.all { it.length <= 255 && it.matches(Regex("[A-Za-z0-9_]+(?:[.][A-Za-z0-9_]+)+")) })
        return document
    }

    private fun write(document: ScannerBaselineDocument) {
        cipher.writeBytesAtomic(
            file,
            json.encodeToString(ScannerBaselineDocument.serializer(), document).encodeToByteArray()
        )
    }
}
