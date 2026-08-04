package com.foxhole.guard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildConfigDiagnosticsTest {
    @Test
    fun `debug logcat diagnostics and strict mode are separate flags`() {
        assertTrue(BuildConfig.ENABLE_DIAGNOSTIC_LOGCAT)
        assertFalse(BuildConfig.ENABLE_STRICT_MODE)
    }
}
