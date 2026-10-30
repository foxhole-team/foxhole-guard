package com.foxhole.core.runtime

import com.foxhole.core.model.DnsSettings
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.SecureDnsMode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

internal class ManagedQuicDnsHostnameTest : FoxCoreConfigTranslatorTestSupport() {
    @Test fun managedHostnameUsesTheSelectedRouteWithoutInventingAnUpstreamAddress() {
        for (mode in listOf(SecureDnsMode.DOQ, SecureDnsMode.DOH3)) {
            for (detour in listOf<String?>(null, "proxy")) {
                val source = managedDns(mode, "resolver.example", detour)
                val servers = source.getValue("servers").jsonArray.map { it.jsonObject }
                val remote = servers.single { it.getValue("tag").jsonPrimitive.content == DNS_REMOTE_TAG }
                assertFalse(remote.containsKey("domain_resolver"))
                assertFalse(remote.containsKey("server_ip"))
                assertFalse(servers.any { it.getValue("tag").jsonPrimitive.content == DNS_BOOTSTRAP_TAG })
                val root = legacyConfig(vless(), rootExtension = { put("dns", source) })
                val result = translateConfig(ProtocolHint.VLESS, root)
                val dns = policy(result).getValue("dns").jsonObject
                val upstream = dns.getValue("upstreams").jsonArray.single().jsonObject
                assertEquals(if (detour == null) "direct" else "primary", dns.getValue("route").jsonPrimitive.content)
                assertTlsIdentity(mode, upstream)
                assertFalse(upstream.containsKey("server_ip"))
                assertEquals(LOCAL_GUARD_DNS_SERVER_ADDRESS, dns.getValue("advertise").jsonPrimitive.content)
                assertEquals(listOf(LOCAL_GUARD_DNS_SERVER_ADDRESS), result.tunPlan.advertisedDnsServers)
            }
        }
    }

    @Test fun anExistingNumericTunAdvertiseOverrideDoesNotBecomeTheResolverTarget() {
        for (mode in listOf(SecureDnsMode.DOQ, SecureDnsMode.DOH3)) {
            val source = managedDns(mode, "resolver.example", null)
            val inbound = buildJsonObject {
                managedTun().forEach { (key, value) -> put(key, value) }
                put("route_address", JsonArray(listOf(JsonPrimitive("172.19.0.9/32"), JsonPrimitive("::/0"))))
            }
            val root = legacyConfig(vless(), inbounds = listOf(inbound), rootExtension = { put("dns", source) })
            val result = translateConfig(ProtocolHint.VLESS, root)
            val dns = policy(result).getValue("dns").jsonObject
            val upstream = dns.getValue("upstreams").jsonArray.single().jsonObject
            assertEquals("172.19.0.9", dns.getValue("advertise").jsonPrimitive.content)
            assertEquals(listOf("172.19.0.9"), result.tunPlan.advertisedDnsServers)
            assertTlsIdentity(mode, upstream)
            assertFalse(upstream.containsKey("server_ip"))
        }
    }

    @Test fun explicitImportedResolverHintsAreRefusedInsteadOfBecomingTargetAddresses() {
        for (type in listOf("quic", "h3")) {
            for (ip in listOf<String?>(null, "203.0.113.53")) {
                val remote = remote(type, ip, resolver = "owner-bootstrap")
                val resolver = buildJsonObject {
                    put("tag", "owner-bootstrap"); put("type", "udp"); put("server", "1.1.1.1")
                }
                val failure = assertThrows(FoxCoreConfigTranslationException::class.java) {
                    translate(ProtocolHint.VLESS, vless(), dnsServers = listOf(remote, resolver))
                }
                assertEquals(FoxCoreConfigRejection.POLICY_UNREPRESENTABLE, failure.rejection)
                assertEquals("$.dns.servers[0].domain_resolver", failure.path)
            }
        }
    }

    @Test fun anInvalidExplicitConnectionIpIsNotSilentlyTreatedAsAnUnpinnedHostname() {
        for (type in listOf("quic", "h3")) {
            val failure = assertThrows(FoxCoreConfigTranslationException::class.java) {
                translate(ProtocolHint.VLESS, vless(), dnsServers = listOf(remote(type, "not-an-ip")))
            }
            assertEquals(FoxCoreConfigRejection.INVALID_SHAPE, failure.rejection)
            assertEquals("$.dns.servers[0].server_ip", failure.path)
        }
    }

    @Test fun anExplicitConnectionIpKeepsTheTlsNameAndTheConfiguredHttpPath() {
        for (type in listOf("quic", "h3")) {
            val dns = policy(translate(ProtocolHint.VLESS, vless(), dnsServers = listOf(remote(type, "203.0.113.53")))).getValue("dns").jsonObject
            val upstream = dns.getValue("upstreams").jsonArray.single().jsonObject
            assertEquals("primary", dns.getValue("route").jsonPrimitive.content)
            assertEquals("203.0.113.53", upstream.getValue("server_ip").jsonPrimitive.content)
            if (type == "quic") {
                assertEquals("resolver.example", upstream.getValue("host").jsonPrimitive.content)
                assertEquals("doq", upstream.getValue("type").jsonPrimitive.content)
            } else {
                assertEquals("https://resolver.example:443/owner-dns", upstream.getValue("url").jsonPrimitive.content)
                assertEquals("doh3", upstream.getValue("type").jsonPrimitive.content)
            }
        }
    }

    @Test fun existingEncryptedModesKeepTheirGeneratedBootstrapPolicy() {
        for (mode in listOf(SecureDnsMode.DOT, SecureDnsMode.DOH)) {
            val servers = managedDns(mode, "resolver.example", "proxy").getValue("servers").jsonArray.map { it.jsonObject }
            val remote = servers.single { it.getValue("tag").jsonPrimitive.content == DNS_REMOTE_TAG }
            assertEquals(DNS_BOOTSTRAP_TAG, remote.getValue("domain_resolver").jsonPrimitive.content)
            assertTrue(servers.any { it.getValue("tag").jsonPrimitive.content == DNS_BOOTSTRAP_TAG })
        }
    }

    private fun managedDns(mode: SecureDnsMode, server: String, detour: String?): JsonObject =
        buildFoxholeDnsConfig(
            strategy = "prefer_ipv4",
            dnsSettings = DnsSettings(server = server, secureMode = mode),
            privateDnsState = null,
            remoteDetourTag = detour,
        )

    private fun remote(type: String, ip: String?, resolver: String? = null): JsonObject = buildJsonObject {
        put("tag", DNS_REMOTE_TAG); put("type", type); put("server", "resolver.example"); put("detour", "proxy")
        if (type == "h3") put("path", "/owner-dns")
        ip?.let { put("server_ip", it) }
        resolver?.let { put("domain_resolver", it) }
    }

    private fun assertTlsIdentity(mode: SecureDnsMode, upstream: JsonObject) {
        if (mode == SecureDnsMode.DOQ) {
            assertEquals("doq", upstream.getValue("type").jsonPrimitive.content)
            assertEquals("resolver.example", upstream.getValue("host").jsonPrimitive.content)
            assertEquals("853", upstream.getValue("port").jsonPrimitive.content)
        } else {
            assertEquals("doh3", upstream.getValue("type").jsonPrimitive.content)
            assertEquals("https://resolver.example:443/dns-query", upstream.getValue("url").jsonPrimitive.content)
        }
    }

    private fun vless(): JsonObject =
        primary("vless") {
            put("server", "203.0.113.10")
            put("server_port", 443)
            put("uuid", "d0cf0001-0000-4000-8000-000000000000")
            put("tls", tls())
        }

}
