package com.foxhole.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticSanitizerTest {
    @Test
    fun `secrets never survive an export`() {
        val sanitized =
            DiagnosticSanitizer.sanitizeForExport(
                "import raw=vless://3f2b1a55-1111-2222-3333-444455556666@example.com:443?sni=cdn.example.org " +
                    "server=10.0.0.7 password=hunter2 country=DE package_name=org.telegram.messenger",
            )

        assertFalse(sanitized.contains("vless://"))
        assertFalse(sanitized.contains("example.com"))
        assertFalse(sanitized.contains("hunter2"))
        assertFalse(sanitized.contains("10.0.0.7"))
        assertFalse(sanitized.contains("org.telegram.messenger"))
        assertFalse(sanitized.contains("DE"))
    }

    @Test
    fun `the debugging context survives an export`() {
        val sanitized =
            DiagnosticSanitizer.sanitizeForExport(
                "session ended mode=tunnel result=error reason=handshake_timeout " +
                    "sourceType=subscription_url protocol=vless options=3 " +
                    "import parse failed: SerializationException: unexpected token",
            )

        assertTrue(sanitized.contains("mode=tunnel"))
        assertTrue(sanitized.contains("result=error"))
        assertTrue(sanitized.contains("reason=handshake_timeout"))
        assertTrue(sanitized.contains("protocol=vless"))
        assertTrue(sanitized.contains("options=3"))
        assertTrue(sanitized.contains("SerializationException"))
    }

    @Test
    fun `bundled file names are not mistaken for hostnames`() {
        val sanitized =
            DiagnosticSanitizer.sanitizeForExport(
                "geoip.db download failed; libbox.so loaded; config.json parse error; tor.log rotated",
            )

        assertTrue(sanitized.contains("geoip.db"))
        assertTrue(sanitized.contains("libbox.so"))
        assertTrue(sanitized.contains("config.json"))
        assertTrue(sanitized.contains("tor.log"))
        assertFalse(sanitized.contains("[host]"))
    }

    @Test
    fun `a real hostname is still redacted`() {
        val sanitized = DiagnosticSanitizer.sanitizeForExport("resolver reached dns.example.org")

        assertFalse(sanitized.contains("dns.example.org"))
        assertTrue(sanitized.contains("[host]"))
    }

    @Test
    fun `storage normalization strips control characters without redacting`() {
        assertEquals(
            "runtime started",
            DiagnosticSanitizer.normalizeForStorage("\u001B[32mruntime\nstarted\u0000"),
        )
    }
}
