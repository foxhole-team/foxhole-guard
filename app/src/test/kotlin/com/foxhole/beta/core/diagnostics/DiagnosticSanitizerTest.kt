package com.foxhole.beta.core.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticSanitizerTest {
    @Test
    fun `storage normalization keeps operator-visible runtime details`() {
        val normalized =
            DiagnosticSanitizer.normalizeForStorage(
                "INFO outbound/hysteria2[test]: remote=example.org:443 ip=1.2.3.4 https://cp.cloudflare.com/generate_204\nnext line",
            )

        assertTrue(normalized.contains("example.org"))
        assertTrue(normalized.contains("1.2.3.4"))
        assertTrue(normalized.contains("https://cp.cloudflare.com/generate_204"))
        assertFalse(normalized.contains("\n"))
    }

    @Test
    fun `redacts sensitive transport and geo fields`() {
        val sanitized =
            DiagnosticSanitizer.sanitize(
                "profile_name=edge host=example.org server=1.2.3.4 domain=google.com endpoint=https://cp.cloudflare.com/generate_204 country=netherlands isp=test https://api.example.org/token?a=1 password=secret uuid=11111111-1111-1111-1111-111111111111",
            )

        assertFalse(sanitized.contains("example.org"))
        assertFalse(sanitized.contains("1.2.3.4"))
        assertFalse(sanitized.contains("google.com"))
        assertFalse(sanitized.contains("netherlands"))
        assertFalse(sanitized.contains("secret"))
        assertFalse(sanitized.contains("11111111-1111-1111-1111-111111111111"))
        assertTrue(sanitized.contains("https://[redacted]"))
        assertTrue(sanitized.contains("password=[redacted]"))
        assertTrue(sanitized.contains("profile_name=[redacted]"))
        assertTrue(sanitized.contains("domain=[redacted]"))
        assertTrue(sanitized.contains("endpoint=[redacted]"))
    }

    @Test
    fun `redacts uid and package metadata`() {
        val sanitized =
            DiagnosticSanitizer.sanitize(
                "uid=10345 package=com.bank.app user_id=2001 application=com.social.client city=moscow",
            )

        assertFalse(sanitized.contains("10345"))
        assertFalse(sanitized.contains("com.bank.app"))
        assertFalse(sanitized.contains("2001"))
        assertFalse(sanitized.contains("com.social.client"))
        assertFalse(sanitized.contains("moscow"))
        assertTrue(sanitized.contains("uid=[redacted]"))
        assertTrue(sanitized.contains("package=[redacted]"))
        assertTrue(sanitized.contains("application=[redacted]"))
    }

    @Test
    fun `strips ansi escape sequences and other xml breaking control chars`() {
        val sanitized =
            DiagnosticSanitizer.sanitize(
                "\u001B[31mERROR\u001B[0m profile_name=edge\u0007",
            )

        assertFalse(sanitized.contains("\u001B"))
        assertFalse(sanitized.contains("\u0007"))
        assertTrue(sanitized.contains("ERROR"))
        assertTrue(sanitized.contains("profile_name=[redacted]"))
    }

    @Test
    fun `redacts bare provider hostnames in plain text diagnostics`() {
        val sanitized =
            DiagnosticSanitizer.sanitize(
                "trusted subscription certificate accepted for x-sec-net.nl",
            )

        assertFalse(sanitized.contains("x-sec-net.nl"))
        assertTrue(sanitized.contains("[host]"))
    }
}
