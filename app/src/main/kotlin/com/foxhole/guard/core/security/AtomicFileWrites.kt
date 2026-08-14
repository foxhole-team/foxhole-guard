package com.foxhole.guard.core.security

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Same temp-file + fsync + ATOMIC_MOVE discipline as AndroidKeystoreFileCipher, for
 * files that are not Keystore-encrypted (the keybox must stay openable by password
 * alone, surviving a Keystore wipe).
 */
internal object AtomicFileWrites {
    fun writeBytesAtomic(
        file: File,
        payload: ByteArray,
    ) {
        val parent = file.parentFile?.apply { mkdirs() } ?: error("target file must have a parent directory")
        val tempFile = File.createTempFile("${file.name}.", ".tmp", parent)
        try {
            FileOutputStream(tempFile).use { stream ->
                stream.write(payload)
                stream.fd.sync()
            }
            moveReplacingTarget(tempFile, file)
            syncDirectoryBestEffort(parent)
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    private fun moveReplacingTarget(
        source: File,
        target: File,
    ) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun syncDirectoryBestEffort(directory: File) {
        runCatching {
            FileOutputStream(directory, true).use { stream -> stream.fd.sync() }
        }
    }
}
