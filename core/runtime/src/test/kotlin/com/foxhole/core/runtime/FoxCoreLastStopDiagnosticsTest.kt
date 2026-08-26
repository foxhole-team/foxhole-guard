package com.foxhole.core.runtime

import org.junit.Assert.assertTrue
import org.junit.Test

class FoxCoreLastStopDiagnosticsTest {
    @Test
    fun `a missing native library is an answer, not an exception`() {
        val diagnostics = foxCoreLastStopDiagnostics()

        assertTrue("expected a reason, got: $diagnostics", diagnostics.startsWith("unavailable:"))
    }

    @Test
    fun `the answer is never blank, because a blank field reads as a missing one`() {
        assertTrue(foxCoreLastStopDiagnostics().isNotBlank())
    }
}
