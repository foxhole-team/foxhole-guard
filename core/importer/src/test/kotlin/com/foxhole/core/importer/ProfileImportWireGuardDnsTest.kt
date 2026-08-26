package com.foxhole.core.importer

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

internal class ProfileImportWireGuardDnsTest : ProfileImportParserTestSupport() {
    @Test
    fun `the private resolver a WireGuard peer pushes survives the import`() {
        val wireGuardDns = wireGuardDnsServer(dns = "10.79.0.1")

        assertEquals("udp", wireGuardDns?.get("type")?.jsonPrimitive?.content)
        assertEquals("10.79.0.1", wireGuardDns?.get("server")?.jsonPrimitive?.content)
        assertEquals("53", wireGuardDns?.get("server_port")?.jsonPrimitive?.content)
    }

    @Test
    fun `a public resolver in a WireGuard config still survives the import`() {
        assertEquals(
            "1.1.1.1",
            wireGuardDnsServer(dns = "1.1.1.1")?.get("server")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `a loopback resolver is dropped rather than advertised back at the device`() {
        assertNull(wireGuardDnsServer(dns = "127.0.0.1"))
    }

    @Test
    fun `a hostname resolver is dropped because a TUN takes numeric addresses only`() {
        assertNull(wireGuardDnsServer(dns = "resolver.example.org"))
    }

    private fun wireGuardDnsServer(dns: String): JsonObject? {
        val parsed =
            parser.parseUserInput(
                """
                [Interface]
                PrivateKey = private
                Address = 10.0.0.2/32
                DNS = $dns

                [Peer]
                PublicKey = public
                Endpoint = wg.example.org:51820
                AllowedIPs = 0.0.0.0/0, ::/0
                """.trimIndent(),
            )
        return json
            .parseToJsonElement(checkNotNull(parsed.normalizedConfigJson))
            .jsonObject["dns"]
            ?.jsonObject
            ?.get("servers")
            ?.jsonArray
            ?.map { it.jsonObject }
            ?.firstOrNull { server -> server["tag"]?.jsonPrimitive?.content == WIREGUARD_DNS_SERVER_TAG }
    }
}
