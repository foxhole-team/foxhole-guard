package com.foxhole.guard.core.sentinel.dex

import com.foxhole.guard.core.sentinel.apk.InspectionBytes
import com.foxhole.guard.core.sentinel.apk.InspectionReason
import com.foxhole.guard.core.sentinel.apk.InspectionStatus
import com.foxhole.guard.core.sentinel.apk.InspectionStopped
import com.foxhole.guard.core.sentinel.apk.ScanControl
import com.foxhole.guard.core.sentinel.apk.StaticCoverage
import com.foxhole.guard.core.sentinel.apk.digestHex
import com.foxhole.guard.core.sentinel.apk.stopInspection
import java.util.zip.Adler32

internal class BoundedDexInspector {
    fun inspect(bytes: ByteArray, control: ScanControl): DexInspection {
        var version: String? = null
        return try {
            control.checkpoint()
            val input = InspectionBytes(bytes)
            version = validateHeader(input, control)
            val sections = DexSections(input, control)
            if (version.toInt() < 38 && (7 in sections.sections || 8 in sections.sections)) {
                stopInspection(
                    InspectionReason.DEX_INVALID_MAP
                )
            }
            val references = DexReferences(input, sections, control, version.toInt())
            val code = DexCodeLayout(input, sections, references, control).inspect()
            DexInspection(
                InspectionStatus.COMPLETE, null, version, references.strings.size, references.types.size,
                references.methodOwners.size, code, references.observations,
                setOf(StaticCoverage.DEX_REFERENCE_TABLES, StaticCoverage.DEX_CODE_LAYOUT),
            )
        } catch (stopped: InspectionStopped) {
            DexInspection(stopped.status, stopped.reason, version, 0, 0, 0, null, emptyList(), emptySet())
        }
    }

    private fun validateHeader(input: InspectionBytes, control: ScanControl): String {
        if (input.size >= 4 && String(input.bytes, 0, 4, Charsets.US_ASCII) == "cdex") {
            stopInspection(InspectionReason.DEX_FEATURE_UNSUPPORTED, InspectionStatus.UNSUPPORTED)
        }
        if (input.size < 8 || !"dex\n".indices.all {
                input.bytes[it] == "dex\n"[it].code.toByte()
            } || input.bytes[7] != 0.toByte()
        ) {
            stopInspection(InspectionReason.DEX_BAD_MAGIC)
        }
        val version = String(input.bytes, 4, 3, Charsets.US_ASCII)
        if (version !in setOf("035", "037", "038", "039", "040")) {
            stopInspection(
                InspectionReason.DEX_VERSION_UNSUPPORTED,
                InspectionStatus.UNSUPPORTED
            )
        }
        input.range(0, 112, InspectionReason.DEX_INVALID_HEADER)
        if (input.u32(32) != input.size.toLong()) stopInspection(InspectionReason.DEX_DECLARED_SIZE_MISMATCH)
        if (input.u32(36) != 112L) stopInspection(InspectionReason.DEX_INVALID_HEADER)
        val endian = input.u32(40)
        if (endian == 0x78563412L) stopInspection(InspectionReason.DEX_ENDIAN_UNSUPPORTED, InspectionStatus.UNSUPPORTED)
        if (endian != 0x12345678L) stopInspection(InspectionReason.DEX_INVALID_HEADER)
        val linkSize = input.u32(44)
        val linkOffset = input.u32(48)
        input.range(linkOffset, linkSize)
        if (linkSize != 0L || linkOffset != 0L) {
            stopInspection(
                InspectionReason.DEX_FEATURE_UNSUPPORTED,
                InspectionStatus.UNSUPPORTED
            )
        }
        validateHashes(input, control)
        return version
    }

    private fun validateHashes(input: InspectionBytes, control: ScanControl) {
        val checksum = Adler32()
        var offset = 12
        while (offset < input.size) {
            control.checkpoint()
            val count = minOf(8192, input.size - offset)
            checksum.update(input.bytes, offset, count)
            offset += count
        }
        if (checksum.value != input.u32(8)) stopInspection(InspectionReason.DEX_CHECKSUM_MISMATCH)
        control.checkpoint()
        val signature = digestHex("SHA-1", input.bytes, 32, input.size - 32)
        val expected = input.bytes.sliceArray(12 until 32).joinToString("") { "%02x".format(it.toInt() and 255) }
        if (signature != expected) stopInspection(InspectionReason.DEX_SIGNATURE_MISMATCH)
        control.checkpoint()
    }
}
