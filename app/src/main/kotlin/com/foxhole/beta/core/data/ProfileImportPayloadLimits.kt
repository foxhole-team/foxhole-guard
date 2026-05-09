package com.foxhole.beta.core.data

import java.io.ByteArrayOutputStream
import java.io.InputStream

internal const val MAX_LOCAL_PROFILE_IMPORT_BYTES = MAX_SUBSCRIPTION_BYTES

internal class ProfileImportPayloadTooLargeException(
    maxBytes: Long = MAX_LOCAL_PROFILE_IMPORT_BYTES,
) : IllegalArgumentException("profile import payload too large: max $maxBytes bytes")

internal fun InputStream.readLocalProfileImportUtf8Capped(
    maxBytes: Long = MAX_LOCAL_PROFILE_IMPORT_BYTES,
): String {
    require(maxBytes > 0L) { "maxBytes must be positive" }
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var totalBytes = 0L
    while (true) {
        val remaining = maxBytes + 1L - totalBytes
        if (remaining <= 0L) {
            throw ProfileImportPayloadTooLargeException(maxBytes)
        }
        val read = read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
        if (read == -1) {
            break
        }
        totalBytes += read
        if (totalBytes > maxBytes) {
            throw ProfileImportPayloadTooLargeException(maxBytes)
        }
        output.write(buffer, 0, read)
    }
    return output.toByteArray().toString(Charsets.UTF_8)
}

internal fun requireLocalProfileImportWithinLimit(rawInput: String): String {
    if (localProfileImportByteCount(rawInput) > MAX_LOCAL_PROFILE_IMPORT_BYTES) {
        throw ProfileImportPayloadTooLargeException()
    }
    return rawInput
}

internal fun localProfileImportByteCount(rawInput: String): Long =
    rawInput.utf8ByteCountCapped(MAX_LOCAL_PROFILE_IMPORT_BYTES + 1L)

private fun String.utf8ByteCountCapped(limit: Long): Long {
    var bytes = 0L
    var index = 0
    while (index < length) {
        val char = this[index]
        bytes +=
            when {
                char.code <= 0x7F -> 1L
                char.code <= 0x7FF -> 2L
                char.isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate() -> {
                    index += 1
                    4L
                }
                else -> 3L
            }
        if (bytes > limit) {
            return bytes
        }
        index += 1
    }
    return bytes
}
