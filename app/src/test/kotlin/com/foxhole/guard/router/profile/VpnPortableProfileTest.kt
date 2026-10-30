package com.foxhole.guard.router.profile

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnPortableProfileTest {
    private val importer = VpnProfileImporter()
    private val input = "vless://11111111-1111-4111-8111-111111111111@1.1.1.1:443?security=tls&sni=example.com#Fixture\ntrojan://fixture@8.8.8.8:443?security=tls&sni=example.com#Fixture"

    @Test fun selectedProtocolSurvivesExportAndReimport() {
        val profile = importer.import(input).profiles.single()
        assertEquals(2, profile.protocolOptions.size)
        val selected = profile.withSelectedProtocol(profile.protocolOptions.last().id)
        assertEquals("TROJAN", selected.protocol)
        assertEquals(selected.protocolOptions.last().normalizedConfigJson, selected.normalizedConfigJson)
        val imported = importer.import(exportVpnProfile(selected)).profiles.single()
        assertEquals("TROJAN", imported.protocol)
        assertEquals(2, imported.protocolOptions.size)
        assertThrows(IllegalArgumentException::class.java) { profile.withSelectedProtocol("missing") }
    }

    @Test fun oldVaultPayloadDefaultsSelectedOptionWithoutGrantingConsent() {
        val profile = importer.import(input.substringBefore('\n')).profiles.single()
        val old = Json.encodeToString(VpnProfile.serializer(), profile)
        assertNull(Json.decodeFromString(VpnProfile.serializer(), old).selectedProtocolOptionId)
        assertFalse(profile.insecureTlsConsent)
    }

    @Test fun selectingOneProtocolExportsACompletePortableLinkRatherThanTheOtherOptions() {
        val profile = importer.import(input).profiles.single()
        val selected = profile.withSelectedProtocol(profile.protocolOptions.last().id)
        val link = exportVpnProfile(selected, setOf(requireNotNull(selected.selectedProtocolOptionId)))
        assertTrue(link.startsWith("trojan://"))
        assertFalse(link.contains('\n'))
        val imported = importer.import(link).profiles.single()
        assertEquals("TROJAN", imported.protocol)
        assertEquals(Json.parseToJsonElement(selected.normalizedConfigJson), Json.parseToJsonElement(imported.normalizedConfigJson))
        assertEquals(2, importer.import(exportVpnProfile(selected)).profiles.single().protocolOptions.size)
    }
}
