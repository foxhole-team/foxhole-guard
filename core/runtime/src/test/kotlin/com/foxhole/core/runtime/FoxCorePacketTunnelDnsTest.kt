package com.foxhole.core.runtime

import com.foxhole.core.model.FoxCoreDnsRuleSetBootstrap
import com.foxhole.core.model.ProtocolHint
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

internal class FoxCorePacketTunnelDnsTest : FoxCoreConfigTranslatorTestSupport() {
    @Test
    fun `a WireGuard profile advertises its own resolver and asks the engine to intercept nothing`() {
        val result =
            translate(
                hint = ProtocolHint.WIREGUARD,
                primary = wireGuard(),
                dnsServers = managedDnsServers() + wireGuardDnsServer("10.17.0.1"),
            )
        val dns = engine(result).getValue("dns").jsonObject

        assertEquals(setOf("mode"), dns.keys)
        assertEquals("real_ip", dns.getValue("mode").jsonPrimitive.content)

        assertEquals(listOf("10.17.0.1"), result.tunPlan.advertisedDnsServers)

        assertEquals(dns, policy(result).getValue("dns").jsonObject)
    }

    @Test
    fun `a WireGuard profile carrying no resolver is refused instead of resolving outside the tunnel`() {
        val failure =
            assertThrows(FoxCoreConfigTranslationException::class.java) {
                translate(
                    hint = ProtocolHint.WIREGUARD,
                    primary = wireGuard(),
                    dnsServers = managedDnsServers(),
                )
            }

        assertEquals(FoxCoreConfigRejection.PACKET_TUNNEL_DNS_MISSING, failure.rejection)
        assertEquals("$.dns.servers", failure.path)

        assertNotNull(failure.explanation)
        assertFalse(failure.message.orEmpty().contains(FOXHOLE_REMOTE_DNS_SERVER))
    }

    @Test
    fun `a WireGuard resolver named by hostname is refused rather than silently dropped`() {
        val failure =
            assertThrows(FoxCoreConfigTranslationException::class.java) {
                translate(
                    hint = ProtocolHint.WIREGUARD,
                    primary = wireGuard(),
                    dnsServers = managedDnsServers() + wireGuardDnsServer("resolver.example"),
                )
            }

        assertEquals(FoxCoreConfigRejection.PACKET_TUNNEL_DNS_MISSING, failure.rejection)
    }

    @Test
    fun `a DNS filter left switched on does not stop a WireGuard profile from starting`() {
        val result =
            translate(
                hint = ProtocolHint.WIREGUARD,
                primary = wireGuard(),
                dnsServers = managedDnsServers() + wireGuardDnsServer("10.17.0.1"),
                dnsRules = listOf(adGuardBlockRule()),
                dnsRuleSetBootstrap = null,
            )
        val dns = engine(result).getValue("dns").jsonObject

        assertFalse(dns.containsKey("rule_sets"))
        assertFalse(dns.containsKey("blocklist"))
        assertEquals(listOf("10.17.0.1"), result.tunPlan.advertisedDnsServers)
    }

    @Test
    fun `a WireGuard profile ignores which managed resolver the app selected as final`() {
        val result =
            translate(
                hint = ProtocolHint.WIREGUARD,
                primary = wireGuard(),
                dnsServers = managedDnsServers() + wireGuardDnsServer("10.17.0.1"),
                dnsFinal = DNS_DIRECT_TAG,
            )

        assertEquals(listOf("10.17.0.1"), result.tunPlan.advertisedDnsServers)
        assertEquals(setOf("mode"), engine(result).getValue("dns").jsonObject.keys)
    }

    @Test
    fun `a proxy profile keeps intercepting and filtering DNS exactly as before`() {
        val bootstrap =
            FoxCoreDnsRuleSetBootstrap(
                name = DNS_ADGUARD_RULE_SET_TAG,
                artifactPath = "/private/adguard.fhds",
                artifactSha256 = "a".repeat(64),
                publicKeyBase64 = "AQID",
            )
        val result =
            translate(
                hint = ProtocolHint.VLESS,
                primary = vless(),
                dnsRules = listOf(adGuardBlockRule()),
                dnsRuleSetBootstrap = bootstrap,
            )
        val dns = engine(result).getValue("dns").jsonObject

        assertEquals(FOXHOLE_REMOTE_DNS_SERVER, dns.getValue("advertise").jsonPrimitive.content)
        assertEquals("real_ip", dns.getValue("mode").jsonPrimitive.content)
        assertEquals("primary", dns.getValue("route").jsonPrimitive.content)
        assertEquals(1, dns.getValue("upstreams").jsonArray.size)
        assertEquals(
            DNS_ADGUARD_RULE_SET_TAG,
            dns.getValue("rule_sets").jsonArray.single().jsonObject.getValue("name").jsonPrimitive.content,
        )
        assertTrue(result.tunPlan.advertisedDnsServers.isNotEmpty())
    }

    private fun wireGuard(): JsonObject =
        primary("wireguard") {
            put("private_key", WIREGUARD_KEY)
            put("address", JsonArray(listOf(JsonPrimitive("10.17.0.2/32"))))
            put("mtu", 1420)
            put(
                "peers",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("address", "198.51.100.20")
                            put("port", 51820)
                            put("public_key", WIREGUARD_KEY)
                            put("allowed_ips", JsonArray(listOf(JsonPrimitive("0.0.0.0/0"))))
                        },
                    )
                },
            )
        }

    private fun vless(): JsonObject =
        primary("vless") {
            put("server", "203.0.113.10")
            put("server_port", 443)
            put("uuid", "d0cf0001-0000-4000-8000-000000000000")
            put("tls", tls())
        }

    private fun adGuardBlockRule(): JsonObject =
        buildJsonObject {
            put("rule_set", JsonArray(listOf(JsonPrimitive(DNS_ADGUARD_RULE_SET_TAG))))
            put("action", "predefined")
            put("rcode", "NXDOMAIN")
        }

    private companion object {
        const val WIREGUARD_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    }
}
