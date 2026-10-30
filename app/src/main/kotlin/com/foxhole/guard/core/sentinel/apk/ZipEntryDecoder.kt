package com.foxhole.guard.core.sentinel.apk

import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater

internal class ZipEntryDecoder(private val input: InspectionBytes, private val control: ScanControl) {
    fun verify(entry: VerifiedZipEntry, retain: Boolean): ByteArray? {
        control.reserveExpanded(entry.expandedBytes.toLong())
        val crc = CRC32()
        val output = if (retain) ByteArray(entry.expandedBytes) else null
        if (entry.method == 0) {
            var offset = 0
            while (offset < entry.expandedBytes) {
                control.checkpoint()
                val count = minOf(8192, entry.expandedBytes - offset)
                crc.update(input.bytes, entry.dataOffset + offset, count)
                output?.let {
                    input.bytes.copyInto(
                        it,
                        offset,
                        entry.dataOffset + offset,
                        entry.dataOffset + offset + count
                    )
                }
                offset += count
            }
        } else {
            inflate(entry, output, crc)
        }
        control.checkpoint()
        if (crc.value != entry.crc32) stopInspection(InspectionReason.ZIP_CRC_MISMATCH)
        return output
    }

    private fun inflate(entry: VerifiedZipEntry, output: ByteArray?, crc: CRC32) {
        val inflater = Inflater(true)
        val scratch = ByteArray(8192)
        var expanded = 0
        try {
            inflater.setInput(input.bytes, entry.dataOffset, entry.compressedBytes)
            while (!inflater.finished()) {
                control.checkpoint()
                val count = inflater.inflate(scratch)
                if (count > entry.expandedBytes - expanded) stopInspection(InspectionReason.ZIP_DEFLATE_INVALID)
                if (count == 0 && !inflater.finished()) stopInspection(InspectionReason.ZIP_DEFLATE_INVALID)
                crc.update(scratch, 0, count)
                output?.let { scratch.copyInto(it, expanded, 0, count) }
                expanded += count
            }
            if (expanded != entry.expandedBytes || inflater.bytesRead != entry.compressedBytes.toLong()) {
                stopInspection(
                    InspectionReason.ZIP_DEFLATE_INVALID
                )
            }
        } catch (_: DataFormatException) {
            stopInspection(InspectionReason.ZIP_DEFLATE_INVALID)
        } finally {
            inflater.end()
        }
    }
}
