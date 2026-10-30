package com.foxhole.guard.router.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnProfileImporterTest {
    private val importer = VpnProfileImporter()

    @Test
    fun `Outline refresh keeps the original dynamic key and its display title`() {
        val source = "ssconf://example.com/opaque%2Fkey#My%20VPN"
        val body = """{"server":"1.1.1.1","server_port":8388,"method":"aes-256-gcm","password":"00123"}"""
        val result = importer.importSubscription(body, source, false).profiles.single()
        assertEquals(source, result.sourceUrl)
        assertEquals("My VPN", result.name)
        assertEquals("OUTLINE", result.protocol)
        assertEquals(source, exportWholeVpnProfile(result))
    }

    @Test
    fun `VLESS imports without exposing config in model text`() {
        val profile = importer.import("vless://11111111-1111-4111-8111-111111111111@1.1.1.1:443?security=tls&sni=example.com#Example").profiles.single()
        assertEquals("VLESS", profile.protocol)
        assertEquals("Example", profile.name)
        assertFalse(profile.toString().contains("11111111-1111"))
        assertFalse(profile.toString().contains("example.com"))
    }

    @Test
    fun `unsupported and malformed inputs have value-free failures`() {
        val failure = assertThrows(ProfileImportFailure::class.java) { importer.import("tor://secret@private.example") }
        assertEquals("profile_import_failed", failure.message)
        assertThrows(ProfileImportFailure::class.java) { importer.import("x".repeat(1024 * 1024 + 1)) }
    }

    @Test
    fun `private outbound addresses remain refused`() {
        assertThrows(ProfileImportFailure::class.java) {
            importer.import("vless://11111111-1111-4111-8111-111111111111@127.0.0.1:443?security=tls&sni=example.com")
        }
    }

    @Test
    fun `accepted insecure consent remains scoped to its imported profile`() {
        val input = "trojan://fixture-password@1.1.1.1:443?allowInsecure=1&sni=example.com"
        assertThrows(InsecureTlsConsentRequired::class.java) { importer.import(input) }
        assertTrue(importer.import(input, allowInsecureTls = true).profiles.single().insecureTlsConsent)
        assertFalse(importer.import(input.replace("allowInsecure=1", "allowInsecure=0"), allowInsecureTls = true)
            .profiles.single().insecureTlsConsent)
    }

    @Test
    fun `subscription inspection cannot silently drop an insecure node before consent`() {
        val body = "trojan://fixture-password@1.1.1.1:443?allowInsecure=1&sni=example.com#TLS"
        val source = "https://example.com/subscription"
        assertThrows(InsecureTlsConsentRequired::class.java) { importer.importSubscription(body, source, false) }
        val first = importer.importSubscription(body, source, true).profiles.single()
        val refreshed = importer.importSubscription(body, source, first.insecureTlsConsent).profiles.single()
        assertTrue(refreshed.insecureTlsConsent)
        assertEquals(first.id, refreshed.id)
    }
}
