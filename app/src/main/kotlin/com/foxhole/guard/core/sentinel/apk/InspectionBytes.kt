package com.foxhole.guard.core.sentinel.apk

import java.io.InputStream
import java.security.MessageDigest

internal class InspectionBytes(val bytes: ByteArray, val size: Int = bytes.size) {
    fun range(offset: Long, length: Long, reason: InspectionReason = InspectionReason.DEX_INVALID_RANGE): Int {
        if (offset < 0 || length < 0) stopInspection(reason)
        if (offset > size || length > size - offset) stopInspection(reason)
        return offset.toInt()
    }

    fun u16(offset: Int, reason: InspectionReason = InspectionReason.DEX_INVALID_RANGE): Int {
        range(offset.toLong(), 2, reason)
        return (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)
    }

    fun u32(offset: Int, reason: InspectionReason = InspectionReason.DEX_INVALID_RANGE): Long =
        u16(offset, reason).toLong() or (u16(offset + 2, reason).toLong() shl 16)

    fun sha256(): String = digestHex("SHA-256", bytes, 0, size)
}

internal fun digestHex(algorithm: String, bytes: ByteArray, offset: Int, length: Int): String {
    val digest = MessageDigest.getInstance(algorithm)
    digest.update(bytes, offset, length)
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
}

internal fun readOwnedInput(stream: InputStream, control: ScanControl, onRead: (Int) -> Unit): InspectionBytes {
    val maxBytes = control.budget.maxArtifactBytes
    var buffer = ByteArray(minOf(64 * 1024, maxBytes))
    var count = 0
    while (true) {
        control.checkpoint()
        val read = if (count == maxBytes) {
            if (stream.read() != -1) stopInspection(InspectionReason.INPUT_BYTE_LIMIT)
            -1
        } else {
            if (count == buffer.size) buffer = buffer.copyOf(minOf(maxBytes, buffer.size * 2))
            readProgress(stream, buffer, count)
        }
        if (read < 0) break
        count += read
        onRead(count)
    }
    control.checkpoint()
    return InspectionBytes(buffer, count)
}

private fun readProgress(stream: InputStream, buffer: ByteArray, offset: Int): Int {
    val read = stream.read(buffer, offset, minOf(8192, buffer.size - offset))
    if (read != 0) return read
    val one = stream.read()
    if (one < 0) return -1
    buffer[offset] = one.toByte()
    return 1
}
