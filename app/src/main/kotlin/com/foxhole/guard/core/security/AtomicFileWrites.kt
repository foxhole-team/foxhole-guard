package com.foxhole.guard.core.security

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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
            val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
            try {
                check(OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode)) { "sync target is not a directory" }
                Os.fsync(descriptor)
            } finally {
                Os.close(descriptor)
            }
        }
    }
}
