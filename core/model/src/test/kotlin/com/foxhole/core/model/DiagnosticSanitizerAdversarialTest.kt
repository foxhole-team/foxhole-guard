package com.foxhole.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticSanitizerAdversarialTest {
    @Test
    fun `aliases and credential fields redact scalar and composite values`() {
        val secrets =
            listOf(
                "package-hash-marker",
                "profile-marker",
                "option-marker",
                "network-marker",
                "username-marker",
                "user-marker",
                "mail-marker@example.test",
                "access-marker",
                "id-marker",
                "api-marker",
                "auth-marker",
                "bearer-marker",
                "cookie-marker",
                "session-marker",
                "session-array-marker",
                "history-marker",
            )
        val sanitized =
            DiagnosticSanitizer.sanitizeForExport(
                "package_hash=${secrets[0]} profile=${secrets[1]} option=${secrets[2]} " +
                    "network_fp=${secrets[3]} username=${secrets[4]} user=${secrets[5]} " +
                    "email=${secrets[6]} access_token=${secrets[7]} id_token=${secrets[8]} " +
                    "api_key=${secrets[9]} authorization=\"Bearer ${secrets[10]}\" " +
                    "bearer=${secrets[11]} cookie=${secrets[12]} session=${secrets[13]} " +
                    "sessionIds=[\"${secrets[14]}\"] history={\"event\":\"${secrets[15]}\"} safe=SAFE_ALIAS",
            )

        secrets.forEach { secret -> assertFalse("leaked $secret in $sanitized", sanitized.contains(secret)) }
        assertTrue(sanitized.contains("safe=SAFE_ALIAS"))
    }

    @Test
    fun `uri email userinfo and international hosts are redacted without eating comma reason`() {
        val sanitized =
            DiagnosticSanitizer.sanitizeForExport(
                "HTTP://alice:http-marker@web.example.test/path, reason=SAFE_REASON " +
                    "FtP://ftp.example.test/file WSS://socket.example.test/live " +
                    "//proto-user:proto-marker@relative.example.test/path " +
                    "bare-user:bare-marker@bare.example.test:443 mail-marker@example.test " +
                    "router-marker.i2p пример.рф xn--e1afmkfd.xn--p1ai",
            )

        listOf(
            "http-marker",
            "web.example.test",
            "ftp.example.test",
            "socket.example.test",
            "proto-marker",
            "relative.example.test",
            "bare-marker",
            "bare.example.test",
            "mail-marker@example.test",
            "router-marker.i2p",
            "пример.рф",
            "xn--e1afmkfd.xn--p1ai",
        ).forEach { secret -> assertFalse("leaked $secret in $sanitized", sanitized.contains(secret)) }
        assertTrue(sanitized.contains("http://[redacted], reason=SAFE_REASON"))
        assertTrue(sanitized.contains("ftp://[redacted]"))
        assertTrue(sanitized.contains("wss://[redacted]"))
        assertTrue(sanitized.contains("//[redacted]"))
    }

    @Test
    fun `nested arrays objects and escaped json redact their complete sensitive values`() {
        val sanitized =
            DiagnosticSanitizer.sanitizeForExport(
                """json={"meta":{"username":"nested-user-marker","safe":"SAFE_JSON"},"option":["option-array-marker",{"host":"nested.example.test"}],"history":[{"event":"history-json-marker"}]} payload="{\"meta\":{\"access_token\":\"escaped-token-marker\"},\"sessionIds\":[\"escaped-session-marker\"],\"history\":[{\"event\":\"escaped-history-marker\"}]}" safe=SAFE_ESCAPED""",
            )

        listOf(
            "nested-user-marker",
            "option-array-marker",
            "nested.example.test",
            "history-json-marker",
            "escaped-token-marker",
            "escaped-session-marker",
            "escaped-history-marker",
        ).forEach { secret -> assertFalse("leaked $secret in $sanitized", sanitized.contains(secret)) }
        assertTrue(sanitized.contains("SAFE_JSON"))
        assertTrue(sanitized.contains("safe=SAFE_ESCAPED"))
    }

    @Test
    fun `compact network identifiers and reserved address samples are redacted`() {
        val secrets =
            listOf(
                "::1",
                "127.1",
                "0177.0.0.1",
                "192.0.2.44",
                "2001:db8::44",
                "00112233445566778899aabbccddeeff",
                "11111111-2222-3333-4444-555555555555",
            )
        val sanitized =
            DiagnosticSanitizer.sanitizeForExport(
                "loopback=${secrets[0]} short=${secrets[1]} octal=${secrets[2]} " +
                    "reserved4=${secrets[3]} reserved6=${secrets[4]} compact=${secrets[5]} uuid=${secrets[6]}",
            )

        secrets.forEach { secret -> assertFalse("leaked $secret in $sanitized", sanitized.contains(secret)) }
        assertTrue(sanitized.contains("loopback=[ip]"))
        assertTrue(sanitized.contains("compact=[uuid]"))
    }

    @Test
    fun `diagnostic versions times ports classes and bundled files remain useful`() {
        val sanitized =
            DiagnosticSanitizer.sanitizeForExport(
                "version 1.2.3.4 time=12:34 port=443 marker=dead:beef " +
                    "exception=java.io.IOException geoip.db libbox.so archive.zip extracted",
            )

        listOf(
            "version 1.2.3.4",
            "time=12:34",
            "port=443",
            "marker=dead:beef",
            "java.io.IOException",
            "geoip.db",
            "libbox.so",
            "archive.zip",
        ).forEach { safe -> assertTrue("lost $safe in $sanitized", sanitized.contains(safe)) }
    }

    @Test
    fun `real hosts under file like top level domains are still redacted`() {
        val sanitized =
            DiagnosticSanitizer.sanitizeForExport(
                "connected to updates.vendor.so then downloads.vendor.zip safe=SAFE_HOSTS",
            )

        assertFalse(sanitized.contains("updates.vendor.so"))
        assertFalse(sanitized.contains("downloads.vendor.zip"))
        assertTrue(sanitized.contains("safe=SAFE_HOSTS"))
    }
}
