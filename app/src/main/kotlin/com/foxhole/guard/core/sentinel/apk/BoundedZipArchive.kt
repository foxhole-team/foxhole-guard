package com.foxhole.guard.core.sentinel.apk

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

internal data class VerifiedZipEntry(
    val name: String,
    val method: Int,
    val crc32: Long,
    val compressedBytes: Int,
    val expandedBytes: Int,
    val dataOffset: Int,
    val localOffset: Int,
    val localEnd: Int,
)

internal class BoundedZipArchive(private val input: InspectionBytes, private val control: ScanControl) {
    private val invalid = InspectionReason.ZIP_TRUNCATED_OR_INVALID

    fun entries(): List<VerifiedZipEntry> {
        val end = findEnd()
        validateEnd(end)
        val count = input.u16(end + 10, invalid)
        if (count > control.budget.maxZipEntries) stopInspection(InspectionReason.ENTRY_COUNT_LIMIT)
        val centralSize = input.u32(end + 12, invalid)
        val centralOffset = input.u32(end + 16, invalid)
        rejectZip64(centralSize, centralOffset)
        var cursor = input.range(centralOffset, centralSize, invalid)
        if (centralOffset + centralSize != end.toLong()) stopInspection(invalid)
        val entries = ArrayList<VerifiedZipEntry>(count)
        val names = HashSet<String>()
        repeat(count) {
            control.checkpoint()
            input.range(cursor.toLong(), 46, invalid)
            if (input.u32(cursor, invalid) != 0x02014b50L) stopInspection(invalid)
            val nameLength = input.u16(cursor + 28, invalid)
            val extraLength = input.u16(cursor + 30, invalid)
            val commentLength = input.u16(cursor + 32, invalid)
            val length = 46L + nameLength + extraLength + commentLength
            input.range(cursor.toLong(), length, invalid)
            if (cursor + length > end) stopInspection(invalid)
            val entry = centralEntry(cursor, nameLength, extraLength)
            if (!names.add(entry.name)) stopInspection(InspectionReason.ZIP_DUPLICATE_ENTRY)
            entries.add(entry)
            cursor += length.toInt()
        }
        if (cursor.toLong() != centralOffset + centralSize) stopInspection(invalid)
        validateIntervals(entries, centralOffset.toInt())
        return entries
    }

    private fun findEnd(): Int {
        if (input.size < 22) stopInspection(invalid)
        for (offset in input.size - 22 downTo maxOf(0, input.size - 22 - 65535)) {
            if (offset % 256 == 0) control.checkpoint()
            if (input.u32(offset, invalid) == 0x06054b50L && offset + 22 + input.u16(offset + 20, invalid) == input.size) return offset
        }
        stopInspection(invalid)
    }

    private fun validateEnd(offset: Int) {
        if (input.u16(offset + 4, invalid) != 0 || input.u16(offset + 6, invalid) != 0) {
            stopInspection(InspectionReason.ZIP_FEATURE_UNSUPPORTED, InspectionStatus.UNSUPPORTED)
        }
        val onDisk = input.u16(offset + 8, invalid)
        val total = input.u16(offset + 10, invalid)
        if (total == 65535) stopInspection(InspectionReason.ZIP64_UNSUPPORTED, InspectionStatus.UNSUPPORTED)
        if (onDisk != total) stopInspection(invalid)
    }

    private fun centralEntry(offset: Int, nameLength: Int, extraLength: Int): VerifiedZipEntry {
        val flags = input.u16(offset + 8, invalid)
        val method = input.u16(offset + 10, invalid)
        validateFeatures(flags, method)
        val crc = input.u32(offset + 16, invalid)
        val compressed = input.u32(offset + 20, invalid)
        val expanded = input.u32(offset + 24, invalid)
        val localOffset = input.u32(offset + 42, invalid)
        rejectZip64(compressed, expanded, localOffset)
        if (input.u16(offset + 34, invalid) != 0) {
            stopInspection(
                InspectionReason.ZIP_FEATURE_UNSUPPORTED,
                InspectionStatus.UNSUPPORTED
            )
        }
        val mode = input.u32(offset + 38, invalid) ushr 16
        if (mode and 0xf000 == 0xa000L) {
            stopInspection(
                InspectionReason.ZIP_FEATURE_UNSUPPORTED,
                InspectionStatus.UNSUPPORTED
            )
        }
        val name = decodeName(offset + 46, nameLength, flags)
        validatePath(name)
        validateExtra(offset + 46 + nameLength, extraLength)
        if (expanded > control.budget.maxEntryBytes) stopInspection(InspectionReason.ENTRY_BYTE_LIMIT)
        if (expanded > maxOf(1, compressed) * control.budget.maxCompressionRatio) {
            stopInspection(
                InspectionReason.COMPRESSION_RATIO_LIMIT
            )
        }
        if (method == 0 && compressed != expanded) stopInspection(invalid)
        if (name.endsWith('/') && expanded != 0L) stopInspection(invalid)
        val local = input.range(localOffset, 30, invalid)
        val dataOffset = validateLocal(local, flags, method, name, crc, compressed, expanded)
        val dataEnd = input.range(dataOffset.toLong(), compressed, invalid) + compressed.toInt()
        val localEnd = if (flags and 8 != 0) validateDescriptor(dataEnd, crc, compressed, expanded) else dataEnd
        return VerifiedZipEntry(name, method, crc, compressed.toInt(), expanded.toInt(), dataOffset, local, localEnd)
    }

    private fun validateLocal(local: Int, flags: Int, method: Int, name: String, crc: Long, compressed: Long, expanded: Long): Int {
        if (input.u32(local, invalid) != 0x04034b50L || input.u16(local + 6, invalid) != flags || input.u16(local + 8, invalid) != method) {
            stopInspection(InspectionReason.ZIP_HEADER_MISMATCH)
        }
        val localCrc = input.u32(local + 14, invalid)
        val localCompressed = input.u32(local + 18, invalid)
        val localExpanded = input.u32(local + 22, invalid)
        val descriptor = flags and 8 != 0
        val matching = (localCrc == crc || descriptor && localCrc == 0L) &&
            (localCompressed == compressed || descriptor && localCompressed == 0L) &&
            (localExpanded == expanded || descriptor && localExpanded == 0L)
        if (!matching) stopInspection(InspectionReason.ZIP_HEADER_MISMATCH)
        val nameLength = input.u16(local + 26, invalid)
        val extraLength = input.u16(local + 28, invalid)
        input.range(local + 30L, nameLength.toLong() + extraLength, invalid)
        if (decodeName(local + 30, nameLength, flags) != name) stopInspection(InspectionReason.ZIP_HEADER_MISMATCH)
        validateExtra(local + 30 + nameLength, extraLength, localPadding = true)
        return local + 30 + nameLength + extraLength
    }

    private fun validateDescriptor(offset: Int, crc: Long, compressed: Long, expanded: Long): Int {
        input.range(offset.toLong(), 12, invalid)
        val signed = input.u32(offset, invalid) == 0x08074b50L
        val start = offset + if (signed) 4 else 0
        input.range(start.toLong(), 12, invalid)
        if (input.u32(start, invalid) != crc || input.u32(start + 4, invalid) != compressed || input.u32(start + 8, invalid) != expanded) {
            stopInspection(InspectionReason.ZIP_HEADER_MISMATCH)
        }
        return start + 12
    }

    private fun validateFeatures(flags: Int, method: Int) {
        if (flags and 0x2041 != 0) stopInspection(InspectionReason.ZIP_ENCRYPTED, InspectionStatus.UNSUPPORTED)
        if (method != 0 && method != 8) {
            stopInspection(
                InspectionReason.ZIP_COMPRESSION_UNSUPPORTED,
                InspectionStatus.UNSUPPORTED
            )
        }
        val allowed = if (method == 8) 0x080e else 0x0808
        if (flags and allowed.inv() != 0) {
            stopInspection(
                InspectionReason.ZIP_FEATURE_UNSUPPORTED,
                InspectionStatus.UNSUPPORTED
            )
        }
    }

    private fun rejectZip64(vararg values: Long) {
        if (values.any { it == 0xffffffffL }) {
            stopInspection(
                InspectionReason.ZIP64_UNSUPPORTED,
                InspectionStatus.UNSUPPORTED
            )
        }
    }

    private fun decodeName(offset: Int, length: Int, flags: Int): String {
        if (length !in 1..512) stopInspection(InspectionReason.ZIP_UNSAFE_PATH)
        input.range(offset.toLong(), length.toLong(), invalid)
        if (flags and 0x0800 == 0 && (offset until offset + length).any { input.bytes[it] < 0 }) {
            stopInspection(InspectionReason.ZIP_FEATURE_UNSUPPORTED, InspectionStatus.UNSUPPORTED)
        }
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(
                CodingErrorAction.REPORT
            ).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(input.bytes, offset, length)).toString()
        } catch (_: CharacterCodingException) {
            stopInspection(InspectionReason.ZIP_UNSAFE_PATH)
        }
    }

    private fun validatePath(name: String) {
        if (name.startsWith('/')) stopInspection(InspectionReason.ZIP_UNSAFE_PATH)
        val forbiddenCharacter = name.any { it == '\\' || it == ':' || it.code < 32 || it.code == 127 }
        if (forbiddenCharacter) stopInspection(InspectionReason.ZIP_UNSAFE_PATH)
        val parts = name.removeSuffix("/").split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) stopInspection(InspectionReason.ZIP_UNSAFE_PATH)
    }

    private fun validateExtra(offset: Int, length: Int, localPadding: Boolean = false) {
        var cursor = offset
        val end = offset + length
        while (cursor < end) {
            control.checkpoint()
            if (end - cursor < 4) {
                // zipalign can append zero padding to a local extra field without a TLV header.
                if (localPadding && (cursor until end).all { input.bytes[it] == 0.toByte() }) return
                stopInspection(invalid)
            }
            val kind = input.u16(cursor, invalid)
            val size = input.u16(cursor + 2, invalid)
            if (kind == 1) stopInspection(InspectionReason.ZIP64_UNSUPPORTED, InspectionStatus.UNSUPPORTED)
            if (kind == 0x7075) stopInspection(InspectionReason.ZIP_FEATURE_UNSUPPORTED, InspectionStatus.UNSUPPORTED)
            if (size > end - cursor - 4) stopInspection(invalid)
            cursor += 4 + size
        }
    }

    private fun validateIntervals(entries: List<VerifiedZipEntry>, centralOffset: Int) {
        var end = 0
        entries.sortedBy { it.localOffset }.forEach {
            control.checkpoint()
            if (it.localOffset < end || it.localEnd > centralOffset) {
                stopInspection(
                    InspectionReason.ZIP_OVERLAPPING_ENTRY
                )
            }
            end = it.localEnd
        }
        if (end != centralOffset) validateSigningBlock(end, centralOffset)
    }

    private fun validateSigningBlock(start: Int, end: Int) {
        // APK v2/v3 signing blocks are opaque here; recognizing their envelope is not signer verification.
        if (end - start < 32) stopInspection(invalid)
        val magic = "APK Sig Block 42".toByteArray(Charsets.US_ASCII)
        if (!magic.indices.all { input.bytes[end - 16 + it] == magic[it] }) stopInspection(invalid)
        val length = input.u32(end - 24, invalid)
        if (input.u32(end - 20, invalid) != 0L || length < 24 || length > end - start - 8L) stopInspection(invalid)
        val blockStart = end - length.toInt() - 8
        if (input.u32(blockStart, invalid) != length || input.u32(blockStart + 4, invalid) != 0L) {
            stopInspection(invalid)
        }
    }
}
