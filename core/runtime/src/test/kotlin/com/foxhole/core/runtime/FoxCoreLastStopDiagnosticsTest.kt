package com.foxhole.core.runtime

import org.junit.Assert.assertTrue
import org.junit.Test

class FoxCoreLastStopDiagnosticsTest {
    /**
     * The accessor runs inside the teardown path, on the branch that has already timed out. If it
     * could throw, a diagnostic added to explain one failure would become a second one on top of
     * it — and on the JVM there is no native library at all, so this is the branch every unit test
     * host takes.
     */
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
