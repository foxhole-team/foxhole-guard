package com.foxhole.core.runtime

import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.SecureDnsMode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

internal class FoxCoreQuicDnsTransportTest : FoxCoreConfigTranslatorTestSupport() {
    @Test fun newModesKeepExistingSerializedModesAndExactSourceDefaults() {
        val modes = SecureDnsMode.entries.associateBy { it.configType }
        assertTrue(modes.keys.containsAll(setOf("udp", "tls", "https", "quic", "h3")))
        assertEquals(853, modes.getValue("quic").defaultPort)
        assertEquals(443, modes.getValue("h3").defaultPort)
    }

    @Test fun doqRetainsTlsIdentityConnectionIpAndSelectedRoute() {
        for (detour in listOf<String?>("proxy", null)) {
            val dns = translated("quic", detour)
            assertEquals(if (detour == null) "direct" else "primary", dns.getValue("route").jsonPrimitive.content)
            val upstream = dns.getValue("upstreams").jsonArray.single().jsonObject
            assertEquals("doq", upstream.getValue("type").jsonPrimitive.content)
            assertEquals("resolver.example", upstream.getValue("host").jsonPrimitive.content)
            assertEquals("853", upstream.getValue("port").jsonPrimitive.content)
            assertEquals("203.0.113.53", upstream.getValue("server_ip").jsonPrimitive.content)
        }
    }

    @Test fun doh3UsesHttpsUrlWithoutProtocolDowngrade() {
        val dns = translated("h3", "proxy")
        val upstream = dns.getValue("upstreams").jsonArray.single().jsonObject
        assertEquals("primary", dns.getValue("route").jsonPrimitive.content)
        assertEquals("doh3", upstream.getValue("type").jsonPrimitive.content)
        assertEquals("https://resolver.example:443/dns-query", upstream.getValue("url").jsonPrimitive.content)
        assertEquals("203.0.113.53", upstream.getValue("server_ip").jsonPrimitive.content)
    }

    @Test fun doqRefusesDnsPort53AndUnrepresentableExtraFields() {
        assertThrows(FoxCoreConfigTranslationException::class.java) { translated("quic", "proxy", 53) }
        assertThrows(FoxCoreConfigTranslationException::class.java) { translated("h3", "proxy", extra = "insecure") }
    }

    private fun translated(type: String, detour: String?, port: Int? = null, extra: String? = null): JsonObject {
        val remote = buildJsonObject {
            put("tag", "dns-remote"); put("type", type); put("server", "resolver.example"); put("server_ip", "203.0.113.53")
            port?.let { put("server_port", it) }
            detour?.let { put("detour", it) }
            if (type == "h3") put("path", "/dns-query")
            extra?.let { put(it, true) }
        }
        return policy(translate(ProtocolHint.VLESS, vless(), dnsServers = listOf(remote))).getValue("dns").jsonObject
    }

    private fun vless(): JsonObject =
        primary("vless") {
            put("server", "203.0.113.10")
            put("server_port", 443)
            put("uuid", "d0cf0001-0000-4000-8000-000000000000")
            put("tls", tls())
        }

}
