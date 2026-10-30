package com.foxhole.guard.core.sentinel.scanner

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.security.MessageDigest

internal data class ScannerYaraSource(val file: File, val sha256: String, val bytes: Int) {
    init {
        require(sha256.matches(Regex("[0-9a-f]{64}")))
        require(bytes in 1..262144)
    }
}

internal fun readOwnedScannerYaraSource(descriptor: ParcelFileDescriptor, bytes: Int, sha256: String): ByteArray {
    require(bytes in 1..262144 && sha256.matches(Regex("[0-9a-f]{64}")))
    val before = Os.fstat(descriptor.fileDescriptor)
    require(OsConstants.S_ISREG(before.st_mode) && before.st_size == bytes.toLong())
    requireScannerReadonlyRegularFd(descriptor)
    val result = ByteArray(bytes)
    ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(descriptor.fileDescriptor)).use { input ->
        var offset = 0
        while (offset < bytes) {
            val read = input.read(result, offset, bytes - offset)
            require(read > 0)
            offset += read
        }
        require(input.read() == -1)
    }
    val after = Os.fstat(descriptor.fileDescriptor)
    require(before.st_size == after.st_size && before.st_mtime == after.st_mtime && before.st_ctime == after.st_ctime)
    val digest = MessageDigest.getInstance(
        "SHA-256"
    ).digest(result).joinToString("") { "%02x".format(it.toInt() and 255) }
    require(digest == sha256)
    return result
}
