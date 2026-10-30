package com.foxhole.guard.core.sentinel.scanner

import com.foxhole.guard.sentinel.nativeengine.NativeYaraReason
import com.foxhole.guard.sentinel.nativeengine.NativeYaraReport
import com.foxhole.guard.sentinel.nativeengine.NativeYaraStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScannerYaraCoverageTest {
    private fun report() = NativeYaraReport(
        1, NativeYaraStatus.COMPLETE, NativeYaraReason.SCANNED, "owned", true,
        100, 100, 1, false, 0, emptyList(), emptyList(),
    )

    @Test fun everyExpectedVerifiedDexMustHaveCompleteEvaluation() {
        assertTrue(completeVerifiedDexYaraCoverage(true, 2, listOf(report(), report())))
        assertFalse(completeVerifiedDexYaraCoverage(true, 2, listOf(report())))
    }

    @Test fun incompleteStaticPackageNeverBecomesCompleteYaraCoverage() {
        assertFalse(completeVerifiedDexYaraCoverage(false, 1, listOf(report())))
        assertFalse(completeVerifiedDexYaraCoverage(true, 0, emptyList()))
    }

    @Test fun timeoutCancellationAndPartialCoverageStayIncomplete() {
        for (status in listOf(NativeYaraStatus.TIMEOUT, NativeYaraStatus.CANCELLED, NativeYaraStatus.PARTIAL)) {
            assertFalse(
                completeVerifiedDexYaraCoverage(
                    true,
                    1,
                    listOf(report().copy(status = status, coverageComplete = false))
                )
            )
        }
    }

    @Test fun compilationIsNotACompletedScanEvenWithoutMatches() {
        assertFalse(
            completeVerifiedDexYaraCoverage(
                true,
                1,
                listOf(report().copy(reason = NativeYaraReason.COMPILED, coverageComplete = false))
            )
        )
        assertTrue(report().matches.isEmpty())
    }
}
