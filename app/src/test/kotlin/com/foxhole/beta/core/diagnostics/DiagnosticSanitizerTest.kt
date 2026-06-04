package com.foxhole.beta.core.diagnostics

import org.junit.Assert.assertEquals
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
    fun `redacts raw proxy profile uris before host ip and uuid passes`() {
        val samples =
            mapOf(
                "vless" to "vless://11111111-1111-1111-1111-111111111111@edge.example.com:443?security=tls#main",
                "vmess" to "vmess://eyJhZGQiOiJlZGdlLmV4YW1wbGUuY29tIiwiaWQiOiIxMTExMTExMS0xMTExLTExMTEtMTExMS0xMTExMTExMTExMTEifQ==",
                "trojan" to "trojan://secret-password@203.0.113.10:443?sni=edge.example.com#trojan",
                "ss" to "ss://YWVzLTEyOC1nY206c2VjcmV0QGVkZ2UuZXhhbXBsZS5jb206ODM4OA==#ss",
                "hysteria" to "hysteria://secret@edge.example.com:443?insecure=0#hy",
                "hysteria2" to "hysteria2://secret@edge.example.com:443?insecure=0#hy2",
                "hy2" to "hy2://secret@edge.example.com:443?insecure=0#hy2",
                "tuic" to "tuic://11111111-1111-1111-1111-111111111111:secret@edge.example.com:443#tuic",
                "wireguard" to "wireguard://secret@198.51.100.2:51820?publickey=abc#wireguard",
            )

        samples.forEach { (scheme, uri) ->
            val sanitized = DiagnosticSanitizer.sanitize("import failed for $uri")

            assertFalse(sanitized.contains("$scheme://"))
            assertTrue(sanitized.contains("[profile-uri-redacted]"))
            assertFalse(sanitized.contains(uri))
            assertFalse(sanitized.contains("edge.example.com"))
            assertFalse(sanitized.contains("203.0.113.10"))
            assertFalse(sanitized.contains("198.51.100.2"))
            assertFalse(sanitized.contains("11111111-1111-1111-1111-111111111111"))
            assertFalse(sanitized.contains("[host]"))
            assertFalse(sanitized.contains("[ip]"))
            assertFalse(sanitized.contains("[uuid]"))
        }
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
    fun `redacts quoted key value fields with spaces`() {
        val sanitized =
            DiagnosticSanitizer.sanitizeForExport(
                "password=\"hello world\" server='edge example' note=kept",
            )

        assertFalse(sanitized.contains("hello world"))
        assertFalse(sanitized.contains("edge example"))
        assertTrue(sanitized.contains("password=[redacted]"))
        assertTrue(sanitized.contains("server=[redacted]"))
        assertTrue(sanitized.contains("note=kept"))
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
    fun `redacts reality wireguard and correlation fields outside profile uris`() {
        val sanitized =
            DiagnosticSanitizer.sanitizeForExport(
                """
                {"outbounds":[{"server":"edge.example.com","uuid":"11111111-1111-1111-1111-111111111111","password":"hunter2","privateKey":"wg-private","publicKey":"wg-public","pbk":"reality-public","sid":"abcd","shortId":"ef","serverName":"sni.example.com"}]}
                remote=1.2.3.4:443 package=com.bank.app sessionId=session-a profileId=42 address=203.0.113.4 asn=AS64500 cert=obfs-secret fingerprint=bridge-fingerprint
                """.trimIndent(),
            )

        listOf(
            "edge.example.com",
            "11111111-1111-1111-1111-111111111111",
            "hunter2",
            "wg-private",
            "wg-public",
            "reality-public",
            "abcd",
            "sni.example.com",
            "1.2.3.4",
            "com.bank.app",
            "session-a",
            "203.0.113.4",
            "AS64500",
            "obfs-secret",
            "bridge-fingerprint",
        ).forEach { rawValue ->
            assertFalse("leaked $rawValue in $sanitized", sanitized.contains(rawValue))
        }
        assertTrue(sanitized.contains("\"pbk\":\"[redacted]\""))
        assertTrue(sanitized.contains("\"sid\":\"[redacted]\""))
        assertTrue(sanitized.contains("\"shortId\":\"[redacted]\""))
        assertTrue(sanitized.contains("\"serverName\":\"[redacted]\""))
        assertTrue(sanitized.contains("sessionId=[redacted]"))
        assertTrue(sanitized.contains("address=[redacted]"))
        assertTrue(sanitized.contains("asn=[redacted]"))
        assertTrue(sanitized.contains("cert=[redacted]"))
        assertTrue(sanitized.contains("fingerprint=[redacted]"))
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

    @Test
    fun `ip info diagnostic logcat uses export sanitizer`() {
        val source = testSourceFile("core/network/IpInfoRepository.kt").readText()
        val diagnosticLogBlock =
            source.substringAfter("private fun diagnosticLog(message: String)")
                .substringBefore("private fun String.ipInfoHostLabel()")

        assertTrue(diagnosticLogBlock.contains("DiagnosticSanitizer.sanitizeForExport(message)"))
        assertFalse(diagnosticLogBlock.contains("\"[ip] \$message\""))
    }

    private fun testSourceFile(relativePath: String): java.io.File =
        listOf(
            java.io.File("src/main/kotlin/com/foxhole/beta/$relativePath"),
            java.io.File("app/src/main/kotlin/com/foxhole/beta/$relativePath"),
            java.io.File("../app/src/main/kotlin/com/foxhole/beta/$relativePath"),
        ).first { file -> file.isFile }
}
