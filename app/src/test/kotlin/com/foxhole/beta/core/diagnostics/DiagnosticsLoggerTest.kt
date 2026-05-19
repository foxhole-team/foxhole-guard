package com.foxhole.beta.core.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsLoggerTest {
    @Test
    fun `live diagnostic message sanitizer removes raw profile uris when raw diagnostics disabled`() {
        val sanitized =
            liveDiagnosticMessage(
                message = "import failed raw=vless://11111111-1111-1111-1111-111111111111@edge.example.com:443?security=tls#main",
                allowRawLiveDiagnostics = false,
            )

        assertTrue(sanitized.contains("[profile-uri-redacted]"))
        assertFalse(sanitized.contains("vless://"))
        assertFalse(sanitized.contains("edge.example.com"))
        assertFalse(sanitized.contains("11111111-1111-1111-1111-111111111111"))
    }

    @Test
    fun `live diagnostic message keeps raw profile uris only when explicitly enabled`() {
        val message =
            "import failed raw=vless://11111111-1111-1111-1111-111111111111@edge.example.com:443?security=tls#main"

        val liveMessage =
            liveDiagnosticMessage(
                message = message,
                allowRawLiveDiagnostics = true,
            )

        assertTrue(liveMessage.contains("edge.example.com"))
        assertTrue(liveMessage.contains("11111111-1111-1111-1111-111111111111"))
    }
}
