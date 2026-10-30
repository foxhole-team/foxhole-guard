package com.foxhole.guard.core.sentinel.scanner

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ScannerDescriptorClaimsTest {
    private val base = ScannerDescriptorClaim("base", ScannerArtifactKind.BASE, 10)
    private val split = ScannerDescriptorClaim("split_1", ScannerArtifactKind.SPLIT, 20)

    @Test fun completeBaseAndSplitsHaveBoundedOpaqueClaims() {
        validateScannerDescriptorClaims(listOf(base, split))
    }

    @Test fun missingOrDuplicatedBaseAndLabelsAreRefused() {
        listOf(emptyList(), listOf(split), listOf(base, base), listOf(base, split.copy(label = "base"))).forEach {
            assertThrows(IllegalArgumentException::class.java) { validateScannerDescriptorClaims(it) }
        }
    }

    @Test fun cardinalityAndTotalBytesStayFinite() {
        val tooMany = listOf(base) + (1..32).map { ScannerDescriptorClaim("split_$it", ScannerArtifactKind.SPLIT, 1) }
        assertThrows(IllegalArgumentException::class.java) { validateScannerDescriptorClaims(tooMany) }
        val tooLarge = (0..4).map {
            ScannerDescriptorClaim(
                "input_$it",
                if (it == 0) ScannerArtifactKind.BASE else ScannerArtifactKind.SPLIT,
                67108864
            )
        }
        assertThrows(IllegalArgumentException::class.java) { validateScannerDescriptorClaims(tooLarge) }
        assertThrows(IllegalArgumentException::class.java) { base.copy(bytes = Long.MAX_VALUE) }
        assertThrows(IllegalArgumentException::class.java) { base.copy(label = "../../outside") }
    }

    @Test fun onlyCapturedApplicationUidMaySendControlMessages() {
        assertTrue(scannerSenderIsOwner(11630, 11630))
        assertTrue(scannerSenderIsOwner(1011630, 1011630))
        assertFalse(scannerSenderIsOwner(-1, 11630))
        assertFalse(scannerSenderIsOwner(2000, 11630))
        assertFalse(scannerSenderIsOwner(11631, 11630))
        assertFalse(scannerSenderIsOwner(99001, 99001))
        assertFalse(scannerSenderIsOwner(90001, 90001))
    }
}
