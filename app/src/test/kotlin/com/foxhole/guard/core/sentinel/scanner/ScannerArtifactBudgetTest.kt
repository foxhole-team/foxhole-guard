package com.foxhole.guard.core.sentinel.scanner

import org.junit.Assert.assertThrows
import org.junit.Test

class ScannerArtifactBudgetTest {
    @Test fun onlyEconomyAndBalancedCeilingsAreAdmitted() {
        requireScannerArtifactBudget(16 * 1024 * 1024)
        requireScannerArtifactBudget(64 * 1024 * 1024)
        listOf(-1, 0, 1, 32 * 1024 * 1024, 128 * 1024 * 1024, Int.MAX_VALUE).forEach { bytes ->
            assertThrows(IllegalArgumentException::class.java) { requireScannerArtifactBudget(bytes) }
        }
    }
}
