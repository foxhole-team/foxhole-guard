package com.foxhole.guard.core.sentinel.apk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

class ApkRealContainerTest {
    @Test fun zeroLocalAlignmentPaddingAndUnreferencedGapsPreserveReferencedEntryIntegrity() {
        for (padding in 1..3) {
            val bytes = ownedZip(localExtra = ByteArray(padding), gap = byteArrayOf(0x50, 0x4b, 3, 4, 8))
            val input = InspectionBytes(bytes)
            val control = control()
            val entries = BoundedZipArchive(input, control).entries()
            assertEquals(2, entries.size)
            entries.forEach { ZipEntryDecoder(input, control).verify(it, retain = false) }
            assertEquals(listOf("first.bin", "second.bin"), entries.map { it.name })
        }
    }

    @Test fun centralOrNonzeroTrailingExtraPaddingIsRejected() {
        expectReason(InspectionReason.ZIP_TRUNCATED_OR_INVALID, ownedZip(centralExtra = byteArrayOf(0)))
        expectReason(InspectionReason.ZIP_TRUNCATED_OR_INVALID, ownedZip(localExtra = byteArrayOf(1)))
        expectReason(InspectionReason.ZIP_TRUNCATED_OR_INVALID, ownedZip(localExtra = byteArrayOf(0, 0, 0, 1)))
        expectReason(InspectionReason.ZIP_TRUNCATED_OR_INVALID, ownedZip(localExtra = byteArrayOf(2, 0, 4, 0, 0)))
    }

    @Test fun corruptReferencedDataAndMalformedSigningEnvelopeAreRejected() {
        val bytes = ownedZip(gap = ByteArray(17))
        val entries = BoundedZipArchive(InspectionBytes(bytes), control()).entries()
        bytes[entries.first().dataOffset] = 42
        try {
            ZipEntryDecoder(InspectionBytes(bytes), control()).verify(entries.first(), retain = false)
            fail("Corrupt referenced data was accepted")
        } catch (stopped: InspectionStopped) {
            assertEquals(InspectionReason.ZIP_CRC_MISMATCH, stopped.reason)
        }
        expectReason(InspectionReason.ZIP_TRUNCATED_OR_INVALID, ownedZip(tail = ByteArray(32)))
        val signing = ByteArray(32).apply {
            OwnedInspectionFixtures.putInt(this, 0, 24)
            OwnedInspectionFixtures.putInt(this, 8, 24)
            "APK Sig Block 42".toByteArray().copyInto(this, 16)
        }
        assertEquals(2, BoundedZipArchive(InspectionBytes(ownedZip(tail = signing)), control()).entries().size)
        assertEquals(
            2,
            BoundedZipArchive(InspectionBytes(ownedZip(tail = ByteArray(127) + signing)), control()).entries().size
        )
        signing[0] = 23
        expectReason(InspectionReason.ZIP_TRUNCATED_OR_INVALID, ownedZip(tail = signing))
    }

    @Test fun overlappingReferencedLocalRecordsAreRejectedEvenWithGaps() {
        val nested = ownedZip()
        val nestedEntries = BoundedZipArchive(InspectionBytes(nested), control()).entries()
        val outer = OwnedInspectionFixtures.zip("outer.bin" to nested, "second.bin" to byteArrayOf(7), stored = true)
        val outerEntries = BoundedZipArchive(InspectionBytes(outer), control()).entries()
        val central = OwnedInspectionFixtures.intAt(outer, outer.size - 6)
        val nameLength = (outer[central + 28].toInt() and 255) or ((outer[central + 29].toInt() and 255) shl 8)
        val extraLength = (outer[central + 30].toInt() and 255) or ((outer[central + 31].toInt() and 255) shl 8)
        val secondCentral = central + 46 + nameLength + extraLength
        val nestedSecond = nestedEntries[1]
        val nestedCentral = OwnedInspectionFixtures.intAt(nested, nested.size - 6) + 46 + "first.bin".length
        nested.copyInto(outer, secondCentral + 8, nestedCentral + 8, nestedCentral + 28)
        OwnedInspectionFixtures.putInt(outer, secondCentral + 42, outerEntries[0].dataOffset + nestedSecond.localOffset)
        expectReason(InspectionReason.ZIP_OVERLAPPING_ENTRY, outer)
    }

    @Test fun completedReferenceInspectionDisclosesUnreferencedByteLimitation() {
        val bytes = OwnedInspectionFixtures.zip("classes.dex" to OwnedInspectionFixtures.dex(), stored = true)
        val result = BoundedApkInspector().inspect(
            listOf(ApkScanInput("base", ApkSourceKind.BASE) { bytes.inputStream() })
        )
        assertEquals(InspectionStatus.COMPLETE, result.status)
        assertEquals("bounded-apk-dex-2", result.engineVersion)
        assertTrue(StaticLimitation.UNREFERENCED_CONTAINER_BYTES_NOT_ANALYZED in result.limitations)
    }

    private fun control() = ScanControl(ScanBudget(), ScanCancellation { false }, System::nanoTime)

    private fun expectReason(reason: InspectionReason, bytes: ByteArray) {
        try {
            BoundedZipArchive(InspectionBytes(bytes), control()).entries()
            fail("Malformed container was accepted")
        } catch (stopped: InspectionStopped) {
            assertEquals(reason, stopped.reason)
        }
    }

    private fun ownedZip(
        localExtra: ByteArray = byteArrayOf(),
        centralExtra: ByteArray = byteArrayOf(),
        gap: ByteArray = byteArrayOf(),
        tail: ByteArray = byteArrayOf(),
    ): ByteArray {
        val output = ByteArrayOutputStream()
        val records = listOf("first.bin", "second.bin").mapIndexed { index, name ->
            if (index == 1) output.write(gap)
            val offset = output.size()
            val encoded = name.toByteArray()
            val data = byteArrayOf((index + 1).toByte(), 9, 10)
            val crc = CRC32().apply { update(data) }.value.toInt()
            val header = ByteArray(30).apply {
                OwnedInspectionFixtures.putInt(this, 0, 0x04034b50)
                OwnedInspectionFixtures.putInt(this, 14, crc)
                OwnedInspectionFixtures.putInt(this, 18, data.size)
                OwnedInspectionFixtures.putInt(this, 22, data.size)
                OwnedInspectionFixtures.putShort(this, 26, encoded.size)
                OwnedInspectionFixtures.putShort(this, 28, localExtra.size)
            }
            output.write(header)
            output.write(encoded)
            output.write(localExtra)
            output.write(data)
            Triple(encoded, crc, offset)
        }
        output.write(tail)
        val central = output.size()
        records.forEach { (name, crc, offset) ->
            val header = ByteArray(46).apply {
                OwnedInspectionFixtures.putInt(this, 0, 0x02014b50)
                OwnedInspectionFixtures.putInt(this, 16, crc)
                OwnedInspectionFixtures.putInt(this, 20, 3)
                OwnedInspectionFixtures.putInt(this, 24, 3)
                OwnedInspectionFixtures.putShort(this, 28, name.size)
                OwnedInspectionFixtures.putShort(this, 30, centralExtra.size)
                OwnedInspectionFixtures.putInt(this, 42, offset)
            }
            output.write(header)
            output.write(name)
            output.write(centralExtra)
        }
        val size = output.size() - central
        output.write(
            ByteArray(22).apply {
                OwnedInspectionFixtures.putInt(this, 0, 0x06054b50)
                OwnedInspectionFixtures.putShort(this, 8, 2)
                OwnedInspectionFixtures.putShort(this, 10, 2)
                OwnedInspectionFixtures.putInt(this, 12, size)
                OwnedInspectionFixtures.putInt(this, 16, central)
            }
        )
        return output.toByteArray()
    }
}
