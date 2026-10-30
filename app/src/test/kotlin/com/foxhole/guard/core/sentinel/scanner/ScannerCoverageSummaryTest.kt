package com.foxhole.guard.core.sentinel.scanner

import com.foxhole.guard.core.sentinel.apk.StaticLimitation
import org.junit.Assert.assertEquals
import org.junit.Test

class ScannerCoverageSummaryTest {
    @Test fun verifiedYaraEvaluationRemovesOnlyTheMissingEngineLimitation() {
        val limits = StaticLimitation.entries.toSet()
        assertEquals(limits - StaticLimitation.NO_YARA_ENGINE, scannerSummaryLimitations(limits, true))
    }

    @Test fun missingOrIncompleteYaraKeepsTheMissingEngineLimitation() {
        val limits = StaticLimitation.entries.toSet()
        assertEquals(limits, scannerSummaryLimitations(limits, false))
    }
}
