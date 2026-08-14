package com.foxhole.guard.core.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileSessionFactoryLoggingTest {
    @Test
    fun `debug stack trace preserves shape but removes profile credentials and endpoints`() {
        val error =
            IllegalArgumentException(
                "failed vless://secret@edge.example:443 endpoint=203.0.113.8 token=private-token",
            )

        val trace = sanitizedDiagnosticStackTrace(error)

        assertTrue(trace.contains("IllegalArgumentException"))
        assertTrue(trace.contains("ProfileSessionFactoryLoggingTest"))
        assertFalse(trace.contains("vless://"))
        assertFalse(trace.contains("edge.example"))
        assertFalse(trace.contains("203.0.113.8"))
        assertFalse(trace.contains("private-token"))
    }
}
