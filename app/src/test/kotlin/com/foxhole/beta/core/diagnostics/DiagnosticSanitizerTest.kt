package com.foxhole.beta.core.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
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
                "uid=10345 package=com.bank.app packages=com.wallet,com.mail package_names=com.one|com.two user_id=2001 application=com.social.client apps=com.chat city=moscow",
            )

        assertFalse(sanitized.contains("10345"))
        assertFalse(sanitized.contains("com.bank.app"))
        assertFalse(sanitized.contains("com.wallet"))
        assertFalse(sanitized.contains("com.one"))
        assertFalse(sanitized.contains("2001"))
        assertFalse(sanitized.contains("com.social.client"))
        assertFalse(sanitized.contains("com.chat"))
        assertFalse(sanitized.contains("moscow"))
        assertTrue(sanitized.contains("uid=[redacted]"))
        assertTrue(sanitized.contains("package=[redacted]"))
        assertTrue(sanitized.contains("packages=[redacted]"))
        assertTrue(sanitized.contains("package_names=[redacted]"))
        assertTrue(sanitized.contains("application=[redacted]"))
        assertTrue(sanitized.contains("apps=[redacted]"))
    }

    @Test
    fun `redacts app connection endpoint fields`() {
        val sanitized =
            DiagnosticSanitizer.sanitize(
                "App connection: app=Chrome profileId=42 optionId=vless-main local=10.0.0.2:51234 remote=1.1.1.1:443 sourceHost=phone.lan destinationHost=cloudflare-dns.com",
            )

        assertFalse(sanitized.contains("Chrome"))
        assertFalse(sanitized.contains("42"))
        assertFalse(sanitized.contains("vless-main"))
        assertFalse(sanitized.contains("10.0.0.2"))
        assertFalse(sanitized.contains("1.1.1.1"))
        assertFalse(sanitized.contains("phone.lan"))
        assertFalse(sanitized.contains("cloudflare-dns.com"))
        assertTrue(sanitized.contains("app=[redacted]"))
        assertTrue(sanitized.contains("profileId=[redacted]"))
        assertTrue(sanitized.contains("optionId=[redacted]"))
        assertTrue(sanitized.contains("local=[redacted]"))
        assertTrue(sanitized.contains("remote=[redacted]"))
        assertTrue(sanitized.contains("sourceHost=[redacted]"))
        assertTrue(sanitized.contains("destinationHost=[redacted]"))
    }

    @Test
    fun `redacts json shaped profile and endpoint fields`() {
        val sanitized =
            DiagnosticSanitizer.sanitize(
                """{"profileId":42,"optionId":"vless-main","remote":"1.1.1.1:443","app":"Chrome"}""",
            )

        assertFalse(sanitized.contains("42"))
        assertFalse(sanitized.contains("vless-main"))
        assertFalse(sanitized.contains("1.1.1.1"))
        assertFalse(sanitized.contains("Chrome"))
        assertTrue(sanitized.contains("\"profileId\":\"[redacted]\""))
        assertTrue(sanitized.contains("\"optionId\":\"[redacted]\""))
        assertTrue(sanitized.contains("\"remote\":\"[redacted]\""))
        assertTrue(sanitized.contains("\"app\":\"[redacted]\""))
    }

    @Test
    fun `keeps runtime diagnostic fields while redacting accidental sensitive values`() {
        val sanitized =
            DiagnosticSanitizer.sanitize(
                "Runtime validation result: protocol_hint=vless dns_shape=dns-direct:platform:default:no_detour|dns-remote:https:443:proxy probe_transport=tcp validation_result=failure endpoint_refusal=true profile_id=42 option_id=vless-main server=1.1.1.1 host=edge.example.com",
            )

        assertTrue(sanitized.contains("protocol_hint=vless"))
        assertTrue(sanitized.contains("dns_shape=dns-direct:platform:default:no_detour|dns-remote:https:443:proxy"))
        assertTrue(sanitized.contains("probe_transport=tcp"))
        assertTrue(sanitized.contains("validation_result=failure"))
        assertTrue(sanitized.contains("endpoint_refusal=true"))
        assertFalse(sanitized.contains("42"))
        assertFalse(sanitized.contains("vless-main"))
        assertFalse(sanitized.contains("1.1.1.1"))
        assertFalse(sanitized.contains("edge.example.com"))
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

    @Test
    fun `persistence sanitizer redacts alternate separators and query strings`() {
        val sanitized =
            DiagnosticSanitizer.sanitizeForPersistence(
                "token: abc123 server=>edge.example.com password = hunter2 callback=https://example.com/path?token=abc&server=edge.example.com",
            )

        assertFalse(sanitized.contains("abc123"))
        assertFalse(sanitized.contains("hunter2"))
        assertFalse(sanitized.contains("edge.example.com"))
        assertFalse(sanitized.contains("example.com/path"))
        assertTrue(sanitized.contains("token: [redacted]"))
        assertTrue(sanitized.contains("server=>[redacted]"))
        assertTrue(sanitized.contains("password = [redacted]"))
        assertTrue(sanitized.contains("https://[redacted]"))
    }

    @Test
    fun `export sanitizer redacts escaped nested json fields`() {
        val sanitized =
            DiagnosticSanitizer.sanitizeForExport(
                """payload="{\"server\":\"edge.example.com\",\"password\":\"hunter2\",\"profileId\":42,\"packages\":\"com.bank.app\"}" note=kept""",
            )

        assertFalse(sanitized.contains("edge.example.com"))
        assertFalse(sanitized.contains("hunter2"))
        assertFalse(sanitized.contains("com.bank.app"))
        assertFalse(sanitized.contains("42"))
        assertTrue(sanitized.contains("""\"server\":\"[redacted]\"""))
        assertTrue(sanitized.contains("""\"password\":\"[redacted]\"""))
        assertTrue(sanitized.contains("""\"profileId\":\"[redacted]\"""))
        assertTrue(sanitized.contains("""\"packages\":\"[redacted]\"""))
        assertTrue(sanitized.contains("note=kept"))
    }

    @Test
    fun `sanitizer is idempotent across persistence and export boundaries`() {
        val first =
            DiagnosticSanitizer.sanitizeForPersistence(
                "profile_id=42 package=com.bank.app remote=1.1.1.1:443 url=https://secret.example/path",
            )
        val second = DiagnosticSanitizer.sanitizeForExport(first)

        assertEquals(first, second)
        assertFalse(second.contains("com.bank.app"))
        assertFalse(second.contains("1.1.1.1"))
        assertFalse(second.contains("secret.example"))
    }
}
