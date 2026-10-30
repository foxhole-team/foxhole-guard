package com.foxhole.guard.router.profile

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WholeVpnProfileExportTest {
    private val importer = VpnProfileImporter()
    private val input = "vless://11111111-1111-4111-8111-111111111111@1.1.1.1:443?security=tls&sni=example.com#Fixture\ntrojan://fixture@8.8.8.8:443?security=tls&sni=example.com#Fixture"

    @Test fun subscriptionCopyKeepsTheWholeSourceLinkAfterSelectingAnotherProtocol() {
        val source = "https://1.1.1.1/sub?token=fixture%2Bvalue&name=Whole%20plan"
        val imported = importer.importSubscription(input, source, false, "Whole plan").profiles.single()
        val selected = imported.withSelectedProtocol(imported.protocolOptions.last().id)

        assertEquals("TROJAN", selected.protocol)
        assertEquals(source, exportWholeVpnProfile(selected))
        assertEquals(source, importer.subscriptionUrlOrNull(exportWholeVpnProfile(selected), false))
    }

    @Test fun aLocalProfileCopiesEveryProtocolAndKeepsTheSelectedProtocolFirst() {
        val profile = importer.import(input).profiles.single()
        val selected = profile.withSelectedProtocol(profile.protocolOptions.last().id)

        val copied = importer.import(exportWholeVpnProfile(selected)).profiles.single()

        assertEquals("TROJAN", copied.protocol)
        assertEquals(selected.name, copied.name)
        assertEquals(2, copied.protocolOptions.size)
        assertEquals(
            selected.protocolOptions.map { exportVpnProfile(selected, setOf(it.id)) }.toSet(),
            copied.protocolOptions.map { exportVpnProfile(copied, setOf(it.id)) }.toSet(),
        )
    }

    @Test fun aSingleLocalProtocolCopiesACompletePortableLink() {
        val profile = importer.import(input.substringBefore('\n')).profiles.single()

        val copied = exportWholeVpnProfile(profile)

        assertTrue(copied.startsWith("vless://"))
        assertFalse(copied.contains('\n'))
        assertEquals(
            Json.parseToJsonElement(profile.normalizedConfigJson),
            Json.parseToJsonElement(importer.import(copied).profiles.single().normalizedConfigJson),
        )
    }

    @Test fun explicitPortableExportCanStillCopyOneProtocolFromASubscription() {
        val profile = importer.importSubscription(input, "https://1.1.1.1/sub", false).profiles.single()
        val selected = profile.withSelectedProtocol(profile.protocolOptions.last().id)

        val copied = exportVpnProfile(selected, setOf(requireNotNull(selected.selectedProtocolOptionId)))

        assertTrue(copied.startsWith("trojan://"))
        assertEquals("TROJAN", importer.import(copied).profiles.single().protocol)
        assertFalse(copied.contains('\n'))
    }

    @Test fun subscriptionSourceCanBeCopiedWithoutExportingItsUnavailableProtocol() {
        val source = "https://1.1.1.1/sub?token=fixture"
        val profile = importer.importSubscription(input, source, false).profiles.single()
        val unavailable = profile.copy(protocolOptions = profile.protocolOptions.map { it.copy(enabled = false) })

        assertEquals(source, exportWholeVpnProfile(unavailable))
    }

    @Test fun aBlankStoredSourceDoesNotReplaceTheCompleteProfile() {
        val profile = importer.import(input).profiles.single().copy(sourceUrl = " ")

        val copied = importer.import(exportWholeVpnProfile(profile)).profiles.single()

        assertEquals(2, copied.protocolOptions.size)
    }
}
