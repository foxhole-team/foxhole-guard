package com.foxhole.guard.runtime

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal class PackagedCategorizedDnsSeedStore(private val root: File) {
    private val mutex = Mutex()

    suspend fun prepare(entry: CategorizedDnsApkEntry, readPackaged: suspend () -> ByteArray): File = mutex.withLock {
        val variant = entry.name.removePrefix(CategorizedDnsContract.NAME_PREFIX)
        require(variant in VARIANTS)
        require(entry.size in 80uL..67108864uL)
        val parent = File(root, variant)
        require(!Files.isSymbolicLink(root.toPath()) && !Files.isSymbolicLink(parent.toPath()))
        require(parent.mkdirs() || parent.isDirectory)
        val artifact = File(parent, CategorizedDnsContract.ARTIFACT_NAME)
        require(!Files.isSymbolicLink(artifact.toPath()))
        if (matches(artifact, entry)) return@withLock artifact
        currentCoroutineContext().ensureActive()
        val bytes = readPackaged()
        entry.requireArtifact(bytes)
        currentCoroutineContext().ensureActive()
        writeAtomically(parent, artifact, bytes)
        artifact
    }

    private suspend fun matches(file: File, entry: CategorizedDnsApkEntry): Boolean {
        if (!file.isFile || file.length().toULong() != entry.size) return false
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(16384)
        var count = 0L
        file.inputStream().use { input ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0 || read.toULong() > entry.size - count.toULong()) return false
                digest.update(buffer, 0, read)
                count += read
            }
        }
        return count.toULong() == entry.size && digest.digest().joinToString("") {
            "%02x".format(it.toInt() and 255)
        } == entry.sha256
    }

    private suspend fun writeAtomically(parent: File, artifact: File, bytes: ByteArray) {
        val temporary = File.createTempFile("apk-seed-", ".tmp", parent)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            currentCoroutineContext().ensureActive()
            try {
                Files.move(
                    temporary.toPath(),
                    artifact.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), artifact.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }

    private companion object {
        val VARIANTS = setOf("normal-medium", "normal-full", "pro-medium", "pro-full")
    }
}
