package com.foxhole.guard.core.sentinel.apk

import com.foxhole.guard.core.sentinel.dex.DexObservationKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.CancellationException

class BoundedApkInspectorTest {
    @Test fun baseAndAllSplitMultidexEntriesHaveSeparateCoverageAndDigests() {
        val base = apk("classes.dex", "classes2.dex")
        val split = apk("classes.dex", "classes3.dex")
        val result = BoundedApkInspector().inspect(
            listOf(input("base", base), input("split", split, ApkSourceKind.SPLIT))
        )
        assertEquals(InspectionStatus.COMPLETE, result.status)
        assertTrue(result.coverage.complete)
        assertEquals(4, result.dexEntries.size)
        assertEquals(2, result.artifacts.size)
        assertTrue(result.artifacts.all { it.sha256?.length == 64 && it.inputComplete })
        assertEquals(
            4,
            result.dexEntries.sumOf {
                it.inspection.observations.single { o -> o.kind == DexObservationKind.RUNTIME_EXEC_REFERENCE }.count
            }
        )
        assertTrue(result.sourceFingerprint?.length == 64)
        assertNotEquals(result.sourceFingerprint, inspect(base).sourceFingerprint)
    }

    @Test fun onlyFullyVerifiedInputCanBeReusable() {
        val valid = inspect(apk("classes.dex"))
        val broken = inspect(apk("classes.dex").dropLast(3).toByteArray())
        assertTrue(valid.cacheEligible)
        assertFalse(broken.cacheEligible)
        assertFalse(broken.coverage.complete)
        assertTrue(broken.issues.any { it.reason == InspectionReason.ZIP_TRUNCATED_OR_INVALID })
    }

    @Test fun traversalAndDuplicateNamesCannotConfuseCoverage() {
        val traversal = inspect(OwnedInspectionFixtures.zip("../classes.dex" to OwnedInspectionFixtures.dex()))
        assertTrue(traversal.issues.any { it.reason == InspectionReason.ZIP_UNSAFE_PATH })
        val duplicate = OwnedInspectionFixtures.zip(
            "classes.dex" to OwnedInspectionFixtures.dex(),
            "classee.dex" to OwnedInspectionFixtures.dex(),
            stored = true
        )
        val from = "classee.dex".toByteArray()
        val to = "classes.dex".toByteArray()
        for (i in 0..duplicate.size - from.size) {
            if (duplicate.copyOfRange(i, i + from.size).contentEquals(from)) to.copyInto(duplicate, i)
        }
        assertTrue(inspect(duplicate).issues.any { it.reason == InspectionReason.ZIP_DUPLICATE_ENTRY })
    }

    @Test fun centralAndLocalNamesMustAgree() {
        val bytes = OwnedInspectionFixtures.zip("classes.dex" to OwnedInspectionFixtures.dex(), stored = true)
        bytes[30] = 'x'.code.toByte()
        assertTrue(inspect(bytes).issues.any { it.reason == InspectionReason.ZIP_HEADER_MISMATCH })
    }

    @Test fun crcAndDeflateTruncationNeverProduceCompleteCoverage() {
        val stored = OwnedInspectionFixtures.zip("classes.dex" to OwnedInspectionFixtures.dex(), stored = true)
        val nameLength = 11
        val extraLength = stored[28].toInt() and 255
        stored[30 + nameLength + extraLength + 50] = 27
        assertTrue(inspect(stored).issues.any { it.reason == InspectionReason.ZIP_CRC_MISMATCH })
        val deflated = apk("classes.dex")
        val central = OwnedInspectionFixtures.centralOffset(deflated)
        OwnedInspectionFixtures.putInt(deflated, central + 20, 1)
        assertFalse(inspect(deflated).cacheEligible)
    }

    @Test fun entryExpansionAndArchiveBudgetsAreEnforcedBeforeAllocation() {
        val bytes = apk("classes.dex")
        assertTrue(
            inspect(bytes, ScanBudget(maxArtifactBytes = 100)).issues.any {
                it.reason == InspectionReason.INPUT_BYTE_LIMIT
            }
        )
        assertTrue(
            inspect(bytes, ScanBudget(maxEntryBytes = 100)).issues.any {
                it.reason == InspectionReason.ENTRY_BYTE_LIMIT
            }
        )
        val many = apk("classes.dex", "classes2.dex")
        assertTrue(
            inspect(many, ScanBudget(maxZipEntries = 1)).issues.any { it.reason == InspectionReason.ENTRY_COUNT_LIMIT }
        )
        val expansion = OwnedInspectionFixtures.zip("assets/owned.bin" to ByteArray(32_000))
        assertTrue(
            inspect(expansion, ScanBudget(maxCompressionRatio = 5)).issues.any {
                it.reason == InspectionReason.COMPRESSION_RATIO_LIMIT
            }
        )
        assertTrue(
            inspect(many, ScanBudget(maxTotalExpandedBytes = 400)).issues.any {
                it.reason == InspectionReason.TOTAL_EXPANDED_BYTE_LIMIT
            }
        )
    }

    @Test fun unsupportedZipFeaturesHaveTypedOutcomes() {
        val encrypted = apk("classes.dex")
        val central = OwnedInspectionFixtures.centralOffset(encrypted)
        OwnedInspectionFixtures.putShort(encrypted, 6, 0x0809)
        OwnedInspectionFixtures.putShort(encrypted, central + 8, 0x0809)
        assertTrue(inspect(encrypted).issues.any { it.reason == InspectionReason.ZIP_ENCRYPTED })
        val zip64 = apk("classes.dex")
        OwnedInspectionFixtures.putInt(zip64, OwnedInspectionFixtures.centralOffset(zip64) + 24, -1)
        assertTrue(inspect(zip64).issues.any { it.reason == InspectionReason.ZIP64_UNSUPPORTED })
    }

    @Test fun dexMagicVersionEndianSizesAndOverflowAreChecked() {
        val badMagic = OwnedInspectionFixtures.dex().apply { this[0] = 0 }
        assertDexReason(badMagic, InspectionReason.DEX_BAD_MAGIC)
        assertDexReason(OwnedInspectionFixtures.dex("041"), InspectionReason.DEX_VERSION_UNSUPPORTED)
        val endian = OwnedInspectionFixtures.dex().apply { OwnedInspectionFixtures.putInt(this, 40, 0x78563412) }
        assertDexReason(OwnedInspectionFixtures.sealDex(endian), InspectionReason.DEX_ENDIAN_UNSUPPORTED)
        val declared = OwnedInspectionFixtures.dex().apply { OwnedInspectionFixtures.putInt(this, 32, Int.MAX_VALUE) }
        assertDexReason(OwnedInspectionFixtures.sealDex(declared), InspectionReason.DEX_DECLARED_SIZE_MISMATCH)
        val count = OwnedInspectionFixtures.dex().apply { OwnedInspectionFixtures.putInt(this, 56, -1) }
        assertDexReason(OwnedInspectionFixtures.sealDex(count), InspectionReason.DEX_TABLE_LIMIT)
        val offset = OwnedInspectionFixtures.dex().apply { OwnedInspectionFixtures.putInt(this, 60, -4) }
        assertDexReason(OwnedInspectionFixtures.sealDex(offset), InspectionReason.DEX_INVALID_RANGE)
    }

    @Test fun dexBadIndexStringAndMapCannotBecomeReferences() {
        val index = OwnedInspectionFixtures.dex().apply { OwnedInspectionFixtures.putInt(this, 124, 999) }
        assertDexReason(OwnedInspectionFixtures.sealDex(index), InspectionReason.DEX_INVALID_INDEX)
        val string = OwnedInspectionFixtures.dex().apply {
            val at = OwnedInspectionFixtures.intAt(this, 112)
            this[at] = 127
        }
        assertDexReason(OwnedInspectionFixtures.sealDex(string), InspectionReason.DEX_INVALID_STRING)
        val map = OwnedInspectionFixtures.dex().apply {
            val at = OwnedInspectionFixtures.intAt(this, 52)
            OwnedInspectionFixtures.putInt(this, at + 4 + 12 + 8, 0)
        }
        assertDexReason(OwnedInspectionFixtures.sealDex(map), InspectionReason.DEX_INVALID_MAP)
    }

    @Test fun checksumFailureIsAnIncompleteInputNotSignerEvidence() {
        val dex = OwnedInspectionFixtures.dex().apply { this[8] = 0 }
        assertDexReason(dex, InspectionReason.DEX_CHECKSUM_MISMATCH)
    }

    @Test fun cancellationBeforeOpenAndDuringReadClosesOwnedStream() {
        var opens = 0
        val cancelled = ApkScanInput("base", ApkSourceKind.BASE) {
            opens++
            ByteArrayInputStream(apk("classes.dex"))
        }
        val first = BoundedApkInspector().inspect(listOf(cancelled), cancellation = ScanCancellation { true })
        assertEquals(0, opens)
        assertTrue(first.issues.any { it.reason == InspectionReason.CANCELLED })
        var reads = 0
        var closed = false
        val stream = object : ByteArrayInputStream(apk("classes.dex")) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                reads++
                return super.read(bytes, offset, minOf(length, 16))
            }
            override fun close() {
                closed = true
                super.close()
            }
        }
        val result = BoundedApkInspector().inspect(
            listOf(
                ApkScanInput("base", ApkSourceKind.BASE) {
                    stream
                }
            ),
            cancellation = ScanCancellation { reads >= 2 }
        )
        assertTrue(closed)
        assertFalse(result.cacheEligible)
        assertTrue(result.issues.any { it.reason == InspectionReason.CANCELLED })
    }

    @Test fun deadlineAndOperationBudgetUseTypedPartialResults() {
        val bytes = apk("classes.dex")
        var ticks = 0L
        val inspector = BoundedApkInspector(nanoTime = { ticks.also { ticks += 2_000_000 } })
        assertTrue(
            inspector.inspect(listOf(input("base", bytes)), ScanBudget(maxElapsedMillis = 1)).issues.any {
                it.reason == InspectionReason.DEADLINE_REACHED
            }
        )
        assertTrue(inspect(bytes, ScanBudget(maxWorkUnits = 3)).issues.any { it.reason == InspectionReason.WORK_LIMIT })
    }

    @Test fun missingSplitAndThrownCancellationAreNotOrdinaryCleanResults() {
        val inputs =
            listOf(
                input("base", apk("classes.dex")),
                ApkScanInput("split", ApkSourceKind.SPLIT) {
                    throw IOException("owned missing fixture")
                }
            )
        val result = BoundedApkInspector().inspect(inputs)
        assertEquals(InspectionStatus.PARTIAL, result.status)
        assertFalse(result.cacheEligible)
        assertEquals(1, result.dexEntries.size)
        val thrown = BoundedApkInspector().inspect(
            listOf(
                ApkScanInput("base", ApkSourceKind.BASE) {
                    throw CancellationException()
                }
            )
        )
        assertTrue(thrown.issues.any { it.reason == InspectionReason.CANCELLED })
    }

    @Test fun noDexAndAdditionalDexLocationsAreExplicitInconclusiveCoverage() {
        val noDex = inspect(OwnedInspectionFixtures.zip("lib/arm64-v8a/owned.so" to byteArrayOf(1, 2)))
        assertEquals(InspectionStatus.INCONCLUSIVE, noDex.status)
        assertTrue(noDex.issues.any { it.reason == InspectionReason.NO_DEX_PRESENT })
        val packed = inspect(OwnedInspectionFixtures.zip("assets/payload.dex" to OwnedInspectionFixtures.dex()))
        assertTrue(packed.issues.any { it.reason == InspectionReason.NONSTANDARD_DEX_LOCATION })
        assertFalse(packed.cacheEligible)
    }

    @Test fun sourceIdsAreClosedLabelsAndDuplicateInputsAreRejectedBeforeOpen() {
        var opens = 0
        val source = ApkScanInput("base", ApkSourceKind.BASE) {
            opens++
            ByteArrayInputStream(apk("classes.dex"))
        }
        val result = BoundedApkInspector().inspect(listOf(source, source))
        assertEquals(0, opens)
        assertFalse(result.cacheEligible)
        assertTrue(result.issues.any { it.reason == InspectionReason.INVALID_INPUT_SET })
    }

    @Test fun sdkD8OwnedBenignCodeAndExceptionHandlersAreInspected() {
        val result = inspect(OwnedInspectionFixtures.zip("classes.dex" to d8Dex()))
        assertEquals(result.issues.toString(), InspectionStatus.COMPLETE, result.status)
        val dex = result.dexEntries.single().inspection
        assertTrue(requireNotNull(dex.code).codeItems > 0)
        assertTrue(requireNotNull(dex.code).instructionUnits > 0)
        assertTrue(dex.observations.isEmpty())
    }

    @Test fun codeRangeAndClassDataOffsetsCannotOverflow() {
        val code = d8Dex()
        val codeOffset = mapOffset(code, 0x2001)
        OwnedInspectionFixtures.putInt(code, codeOffset + 12, -1)
        assertDexReason(OwnedInspectionFixtures.sealDex(code), InspectionReason.DEX_INVALID_RANGE)
        val data = d8Dex()
        val classes = OwnedInspectionFixtures.intAt(data, 100)
        OwnedInspectionFixtures.putInt(data, classes + 24, -4)
        assertDexReason(OwnedInspectionFixtures.sealDex(data), InspectionReason.DEX_INVALID_RANGE)
    }

    @Test fun malformedUtf8AndBoundedStringsAreTyped() {
        val malformed = OwnedInspectionFixtures.dex()
        val start = OwnedInspectionFixtures.intAt(malformed, 112)
        malformed[start + 1] = 0xf0.toByte()
        assertDexReason(OwnedInspectionFixtures.sealDex(malformed), InspectionReason.DEX_INVALID_STRING)
        val result = inspect(apk("classes.dex"), ScanBudget(maxStringChars = 3))
        assertTrue(result.issues.any { it.reason == InspectionReason.DEX_STRING_LIMIT })
    }

    @Test fun invalidContainerNeverClaimsZipCoverage() {
        val result = inspect(apk("classes.dex").dropLast(3).toByteArray())
        assertFalse(StaticCoverage.ZIP_ENTRY_INTEGRITY in result.coverage.completed)
    }

    @Test fun fatalCallerErrorIsNotConvertedToOrdinaryUnavailable() {
        val fatal = AssertionError("owned fatal fixture")
        try {
            BoundedApkInspector().inspect(listOf(ApkScanInput("base", ApkSourceKind.BASE) { throw fatal }))
            throw AssertionError("expected fatal error")
        } catch (error: AssertionError) {
            assertTrue(error === fatal)
        }
    }

    @Test fun primitiveMethodOwnerAndInvalidMemberNameAreRejected() {
        val owner = OwnedInspectionFixtures.dex()
        val methods = OwnedInspectionFixtures.intAt(owner, 92)
        OwnedInspectionFixtures.putShort(owner, methods, 1)
        assertDexReason(OwnedInspectionFixtures.sealDex(owner), InspectionReason.DEX_INVALID_INDEX)
        val member = OwnedInspectionFixtures.dex()
        val name = OwnedInspectionFixtures.intAt(member, 112 + 2 * 4)
        member[name + 1] = '.'.code.toByte()
        assertDexReason(OwnedInspectionFixtures.sealDex(member), InspectionReason.DEX_INVALID_STRING)
    }

    @Test fun compactDexIsExplicitlyUnsupportedAndClassicVersionsAreBounded() {
        val compact = OwnedInspectionFixtures.dex().apply { "cdex".toByteArray().copyInto(this) }
        assertDexReason(compact, InspectionReason.DEX_FEATURE_UNSUPPORTED)
        for (version in listOf("035", "037", "038", "039", "040")) {
            val result = inspect(OwnedInspectionFixtures.zip("classes.dex" to OwnedInspectionFixtures.dex(version)))
            assertEquals(version, InspectionStatus.COMPLETE, result.status)
            assertEquals(version, result.dexEntries.single().inspection.version)
        }
    }

    @Test fun zeroProgressCallerStreamAndAggregateSplitBudgetsTerminate() {
        var closed = false
        val zero = object : java.io.InputStream() {
            override fun read(): Int = 1
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int = 0
            override fun close() { closed = true }
        }
        val result = BoundedApkInspector().inspect(
            listOf(
                ApkScanInput("base", ApkSourceKind.BASE) {
                    zero
                }
            ),
            ScanBudget(maxWorkUnits = 5)
        )
        assertTrue(closed)
        assertTrue(result.issues.any { it.reason == InspectionReason.WORK_LIMIT })
        assertTrue(result.artifacts.single().inputBytes > 0)
        val split = BoundedApkInspector().inspect(
            listOf(input("base", apk("classes.dex")), input("split", apk("classes.dex"), ApkSourceKind.SPLIT)),
            ScanBudget(maxZipEntries = 1)
        )
        assertFalse(split.cacheEligible)
        assertTrue(split.issues.any { it.reason == InspectionReason.ENTRY_COUNT_LIMIT })
    }

    @Test fun ownedMutatedDexAndZipCorpusIsBoundedAndDoesNotEscapeParserErrors() {
        val random = java.util.Random(20261003)
        repeat(200) {
            val bytes = d8Dex()
            val offset = 32 + random.nextInt(bytes.size - 32)
            bytes[offset] = random.nextInt(256).toByte()
            val result = inspect(OwnedInspectionFixtures.zip("classes.dex" to OwnedInspectionFixtures.sealDex(bytes)))
            assertTrue(result.dexEntries.size <= 1)
            assertTrue(result.issues.size <= 2)
            assertEquals(result.status == InspectionStatus.COMPLETE, result.cacheEligible)
        }
        repeat(200) {
            val bytes = apk("classes.dex")
            bytes[random.nextInt(bytes.size)] = random.nextInt(256).toByte()
            val result = inspect(bytes)
            assertTrue(result.artifacts.size <= 1)
            assertTrue(result.dexEntries.size <= 1)
            assertEquals(result.status == InspectionStatus.COMPLETE, result.cacheEligible)
        }
    }

    private fun d8Dex(): ByteArray = requireNotNull(javaClass.getResourceAsStream("/sentinel-owned/classes.dex")).use {
        it.readBytes()
    }

    private fun mapOffset(bytes: ByteArray, kind: Int): Int {
        val start = OwnedInspectionFixtures.intAt(bytes, 52)
        val count = OwnedInspectionFixtures.intAt(bytes, start)
        for (index in 0 until count) {
            val at = start + 4 + index * 12
            if ((bytes[at].toInt() and 255) or ((bytes[at + 1].toInt() and 255) shl 8) == kind) {
                return OwnedInspectionFixtures.intAt(
                    bytes,
                    at + 8
                )
            }
        }
        throw AssertionError("owned fixture missing map kind")
    }

    private fun assertDexReason(bytes: ByteArray, reason: InspectionReason) {
        val result = inspect(OwnedInspectionFixtures.zip("classes.dex" to bytes))
        assertFalse(result.cacheEligible)
        assertTrue("Expected $reason but got ${result.issues}", result.issues.any { it.reason == reason })
    }

    private fun apk(vararg names: String): ByteArray = OwnedInspectionFixtures.zip(
        *names.map {
            it to OwnedInspectionFixtures.dex()
        }.toTypedArray()
    )
    private fun input(
        id: String,
        bytes: ByteArray,
        kind: ApkSourceKind = ApkSourceKind.BASE
    ): ApkScanInput = ApkScanInput(
        id,
        kind
    ) {
        ByteArrayInputStream(bytes)
    }
    private fun inspect(
        bytes: ByteArray,
        budget: ScanBudget = ScanBudget()
    ): ApkInspection = BoundedApkInspector().inspect(
        listOf(input("base", bytes)),
        budget
    )
}
