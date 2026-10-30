package com.foxhole.core.importer

import com.foxhole.core.model.ProtocolHint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLEncoder

class PortableProfileInteropTest {
    private val json = Json { prettyPrint = true }
    private val parser = ProfileImportParser(json)
    private val vless = "vless://11111111-1111-4111-8111-111111111111@1.1.1.1:443?security=tls&sni=example.com#Fixture"
    private val trojan = "trojan://fixture%2Bpassword@8.8.8.8:443?security=tls&sni=example.com#Fixture"
    private fun parse(value: String) = parser.parseUserInput(value, allowPrivateOutboundHosts = true)

    @Test fun officialWrapperPreservesInnerEscapesAndSubscription() {
        val direct = parse(trojan)
        assertEquals(direct.normalizedConfigJson, parse("v2raytun://import/$trojan").normalizedConfigJson)
        val encoded = URLEncoder.encode(trojan, "UTF-8").replace("+", "%20")
        assertEquals(direct.normalizedConfigJson, parse("v2raytun://import/$encoded").normalizedConfigJson)
        assertEquals(
            "https://1.1.1.1/sub?token=a%2Bb",
            parse("v2raytun://import/https://1.1.1.1/sub?token=a%2Bb").sourceUrl
        )
        assertThrows(IllegalArgumentException::class.java) { parse("v2raytun://import/v2raytun://import/$vless") }
        assertThrows(IllegalArgumentException::class.java) { parse("v2raytun://other/$vless") }
    }

    @Test fun guardClipboardJsonAndPrettyConcatenationKeepEveryProtocol() {
        val first = requireNotNull(parse(vless).normalizedConfigJson)
        val second = requireNotNull(parse(trojan).normalizedConfigJson)
        assertEquals(
            json.parseToJsonElement(first),
            json.parseToJsonElement(requireNotNull(parse(first).normalizedConfigJson))
        )
        val grouped = parse("$first\n$second")
        assertEquals(listOf(ProtocolHint.VLESS, ProtocolHint.TROJAN), grouped.protocolOptions.map { it.protocolHint })
        assertEquals(grouped.protocolOptions.first().id, grouped.selectedProtocolOptionId)
        assertEquals(
            json.parseToJsonElement(second),
            json.parseToJsonElement(grouped.protocolOptions.last().normalizedConfigJson)
        )
        assertThrows(IllegalArgumentException::class.java) { parse("$first\nmalformed") }
        assertThrows(IllegalArgumentException::class.java) { parse("[".repeat(65) + "]".repeat(65)) }
    }

    @Test fun portableLinksRoundTripWithoutDroppingSecurityOrRoutePolicy() {
        val original = requireNotNull(parse(vless).normalizedConfigJson)
        val exported = exportPortableConfigs("Fixture", listOf(original))
        assertTrue(exported.startsWith("vless://"))
        val copy = parse(exported)
        assertEquals(ProtocolHint.VLESS, copy.protocolHint)
        val root = json.parseToJsonElement(original).jsonObject
        val changed = root.toMutableMap().apply { put("route", json.parseToJsonElement("{\"final\":\"direct\"}")) }
        assertThrows(IllegalArgumentException::class.java) {
            exportPortableConfigs("Fixture", listOf(kotlinx.serialization.json.JsonObject(changed).toString()))
        }
    }

    @Test fun combinedStandardLinksKeepProtocolChoices() {
        val grouped = parse("$vless\n$trojan")
        assertEquals(2, grouped.protocolOptions.size)
        val exported = exportPortableConfigs("Fixture", grouped.protocolOptions.map { it.normalizedConfigJson })
        assertEquals(
            listOf(ProtocolHint.VLESS, ProtocolHint.TROJAN),
            parse(exported).protocolOptions.map { it.protocolHint }
        )
    }

    @Test fun canonicalJsonRetainsTlsConsentAndPrivateHostRefusal() {
        val insecure = parser.parseUserInput(trojan, allowPrivateOutboundHosts = true, allowInsecureTls = true)
        val raw = requireNotNull(insecure.normalizedConfigJson)
        // Consent is checked against the normalized TLS object, never inferred from its source format.
        val unsafe = raw.replace("\"enabled\": true", "\"insecure\": true, \"enabled\": true")
        assertThrows(
            IllegalArgumentException::class.java
        ) { parser.parseUserInput(unsafe, allowPrivateOutboundHosts = true) }
        val privateRaw = requireNotNull(parse(vless).normalizedConfigJson).replace("1.1.1.1", "127.0.0.1")
        assertThrows(IllegalArgumentException::class.java) { parser.parseUserInput(privateRaw) }
    }

    @Test fun ordinaryPortableProtocolsAndTlsTransportsRoundTrip() {
        val vmess = java.util.Base64.getEncoder().encodeToString(
            """{"v":"2","ps":"Fixture","add":"1.1.1.1","port":"443","id":"11111111-1111-4111-8111-111111111111","aid":"0","scy":"auto","tls":"tls","sni":"example.com","net":"ws","path":"/fixture","host":"example.com"}""".toByteArray()
        )
        val samples = listOf(
            vless,
            vless.replace("security=tls", "security=reality&pbk=fixture-public-key&sid=1234&fp=chrome_151"),
            vless.replace(
                "security=tls",
                "security=tls&type=grpc&serviceName=fixture&alpn=h2&min_version=1.2&max_version=1.3&ech=false"
            ),
            trojan, "vmess://$vmess",
            "ss://YWVzLTI1Ni1nY206Zml4dHVyZQ@1.1.1.1:8388#Fixture",
            "hysteria2://fixture@1.1.1.1:443?sni=example.com&insecure=1&obfs=salamander&obfs-password=test&up_mbps=10&down_mbps=20&alpn=h3#Fixture",
            "tuic://11111111-1111-4111-8111-111111111111:fixture@1.1.1.1:443?sni=example.com&congestion_control=bbr&udp_relay_mode=native#Fixture",
            "anytls://fixture@1.1.1.1:443?sni=example.com#Fixture",
            "naive://user:fixture%2Bpassword@1.1.1.1:443?sni=example.com&quic=false&udp_over_tcp=true&ech=false#Fixture",
            "wireguard://fixture-private@1.1.1.1:51820?publickey=fixture-public&address=10.8.0.2%2F32&allowed_ips=0.0.0.0%2F0&keepalive=25#Fixture"
        )
        samples.forEach { sample ->
            val profile = parser.parseUserInput(sample, allowPrivateOutboundHosts = true, allowInsecureTls = true)
            val exported = exportPortableConfigs("Fixture", listOf(requireNotNull(profile.normalizedConfigJson)))
            assertEquals(
                profile.protocolHint,
                parser.parseUserInput(exported, allowPrivateOutboundHosts = true, allowInsecureTls = true).protocolHint
            )
        }
    }

    @Test fun unknownSecurityFieldsAndCustomDnsCannotDisappearDuringExport() {
        val original = requireNotNull(parse(vless).normalizedConfigJson)
        val unknown = original.replace(
            "\"server_name\": \"example.com\"",
            "\"server_name\": \"example.com\", \"certificate_path\": \"fixture-cert.pem\""
        )
        assertNotEquals(original, unknown)
        assertThrows(PortableProfileExportFailure::class.java) { exportPortableConfigs("Fixture", listOf(unknown)) }
        val customDns = original.replace("prefer_ipv4", "prefer_ipv6")
        assertThrows(PortableProfileExportFailure::class.java) { exportPortableConfigs("Fixture", listOf(customDns)) }
    }

    @Test fun malformedNestedWrappersAndOversizeInputsAreRefused() {
        listOf(
            "v2raytun://import/v2raytun%3A%2F%2Fimport%2F" + java.net.URLEncoder.encode(vless, "UTF-8"),
            "v2raytun://import/%GG",
            "v2raytun://import/",
            "x".repeat(4 * 1024 * 1024 + 1)
        )
            .forEach { input -> assertThrows(IllegalArgumentException::class.java) { parse(input) } }
    }

    @Test fun partiallyEncodedNestedWrapperCannotUseEmbeddedLinkFallback() {
        assertThrows(
            IllegalArgumentException::class.java
        ) { parse("v2raytun://import/v2raytun%3A%2F%2Fimport%2F$vless") }
        assertThrows(IllegalArgumentException::class.java) {
            parse("v2raytun://import/%E2%80%8Bv2raytun%3A%2F%2Fimport%2F" + java.net.URLEncoder.encode(vless, "UTF-8"))
        }
    }

    @Test fun materializedProtocolChoicesHaveAnAggregateByteBudget() {
        val links = (1..8).joinToString("\n") { index -> vless.replace("111111111111@", "11111111111$index@") }
        val roots = links.lines().map {
            json.parseToJsonElement(
                requireNotNull(parse(it).normalizedConfigJson)
            ).jsonObject
        }
        val first = roots.first()
        val nodes = roots.map { (it.getValue("outbounds") as kotlinx.serialization.json.JsonArray).first().jsonObject }
        val tags = kotlinx.serialization.json.JsonArray(nodes.map { it.getValue("tag") })
        val infrastructure = (
            first.getValue(
                "outbounds"
            ) as kotlinx.serialization.json.JsonArray
            ).drop(1).map { element ->
            val objectValue = element.jsonObject
            if (objectValue["type"] == kotlinx.serialization.json.JsonPrimitive("selector")) {
                kotlinx.serialization.json.JsonObject(objectValue + ("outbounds" to tags))
            } else {
                objectValue
            }
        }
        val root = kotlinx.serialization.json.JsonObject(
            first + ("outbounds" to kotlinx.serialization.json.JsonArray(nodes + infrastructure))
        )
        val large = kotlinx.serialization.json.JsonObject(
            root + (
                "log" to kotlinx.serialization.json.buildJsonObject {
                    put("level", kotlinx.serialization.json.JsonPrimitive("x".repeat(800_000)))
                }
                )
        ).toString()
        assertTrue(large.toByteArray().size < 1024 * 1024)
        assertThrows(IllegalArgumentException::class.java) { parse(large) }
    }

    @Test
    fun wireGuardDnsSurvivesPortableExportWithoutWeakeningDnsPolicy() {
        val base = "wireguard://fixture-private@1.1.1.1:51820?publickey=fixture-public&address=10.8.0.2%2F32&allowed_ips=0.0.0.0%2F0#Fixture"
        listOf("1.1.1.1", "1.1.1.1:5353", "[2606:4700:4700::1111]:5353", "10.8.0.1").forEach { dns ->
            val input = base.replace("#", "&dns=${URLEncoder.encode(dns, "UTF-8")}#")
            val original = requireNotNull(parse(input).normalizedConfigJson)
            val exported = exportPortableConfigs("Fixture", listOf(original))
            assertTrue(java.net.URI(exported).rawQuery.split('&').any { it.startsWith("dns=") })
            assertEquals(
                json.parseToJsonElement(original),
                json.parseToJsonElement(requireNotNull(parse(exported).normalizedConfigJson))
            )
            val customPolicy = original.replace("prefer_ipv4", "prefer_ipv6")
            assertThrows(
                PortableProfileExportFailure::class.java
            ) { exportPortableConfigs("Fixture", listOf(customPolicy)) }
        }
    }

    @Test
    fun explicitDialledIpOverridesCannotBypassPublicHostPolicy() {
        val strict =
            ProfileImportParser(
                json,
                remoteHostResolver = { listOf(java.net.InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1))) }
            )
        val root = json.parseToJsonElement(requireNotNull(parse(vless).normalizedConfigJson)).jsonObject
        listOf("127.0.0.1", "10.0.0.1", "::1").forEach { address ->
            val outbounds = root.getValue("outbounds") as kotlinx.serialization.json.JsonArray
            val overridden = kotlinx.serialization.json.JsonObject(
                outbounds.first().jsonObject + ("server_ip" to kotlinx.serialization.json.JsonPrimitive(address))
            )
            val candidate = kotlinx.serialization.json.JsonObject(
                root + ("outbounds" to kotlinx.serialization.json.JsonArray(listOf(overridden) + outbounds.drop(1)))
            ).toString()
            assertThrows(IllegalArgumentException::class.java) { strict.parseUserInput(candidate) }
            assertTrue(
                strict.parseUserInput(
                    candidate,
                    allowPrivateOutboundHosts = true
                ).normalizedConfigJson!!.contains("server_ip")
            )
            val dns = root.getValue("dns").jsonObject
            val servers = dns.getValue("servers") as kotlinx.serialization.json.JsonArray
            val pinnedDns = kotlinx.serialization.json.JsonObject(
                servers.last().jsonObject + ("server_ip" to kotlinx.serialization.json.JsonPrimitive(address))
            )
            val dnsCandidate = kotlinx.serialization.json.JsonObject(
                root + ("dns" to kotlinx.serialization.json.JsonObject(dns + ("servers" to kotlinx.serialization.json.JsonArray(servers.dropLast(1) + pinnedDns))))
            ).toString()
            assertThrows(IllegalArgumentException::class.java) { strict.parseUserInput(dnsCandidate) }
        }
    }

    @Test
    fun canonicalTlsRejectsRetiredAliasesWhileXrayConversionRetainsItsContract() {
        val root = json.parseToJsonElement(requireNotNull(parse(vless).normalizedConfigJson)).jsonObject
        val nodes = root.getValue("outbounds") as kotlinx.serialization.json.JsonArray
        val node = nodes.first().jsonObject
        val tls = node.getValue("tls").jsonObject
        listOf("allowInsecure", "allow_insecure", "Insecure").forEach { key ->
            listOf(false, true).forEach { unsafe ->
                val aliasTls = kotlinx.serialization.json.JsonObject(
                    tls + (key to kotlinx.serialization.json.JsonPrimitive(unsafe))
                )
                val aliasNode = kotlinx.serialization.json.JsonObject(node + ("tls" to aliasTls))
                val candidate = kotlinx.serialization.json.JsonObject(
                    root + ("outbounds" to kotlinx.serialization.json.JsonArray(listOf(aliasNode) + nodes.drop(1)))
                ).toString()
                assertThrows(
                    IllegalArgumentException::class.java
                ) { parser.parseUserInput(candidate, allowPrivateOutboundHosts = true, allowInsecureTls = true) }
            }
        }
        val canonicalTls = kotlinx.serialization.json.JsonObject(
            tls + ("insecure" to kotlinx.serialization.json.JsonPrimitive(true))
        )
        val canonicalNode = kotlinx.serialization.json.JsonObject(node + ("tls" to canonicalTls))
        val canonical = kotlinx.serialization.json.JsonObject(
            root + ("outbounds" to kotlinx.serialization.json.JsonArray(listOf(canonicalNode) + nodes.drop(1)))
        ).toString()
        assertThrows(IllegalArgumentException::class.java) { parse(canonical) }
        parser.parseUserInput(canonical, allowPrivateOutboundHosts = true, allowInsecureTls = true)
        val xray = """{"outbounds":[{"protocol":"vless","settings":{"vnext":[{"address":"1.1.1.1","port":443,"users":[{"id":"11111111-1111-4111-8111-111111111111","encryption":"none"}]}]},"streamSettings":{"security":"tls","tlsSettings":{"serverName":"example.com","allowInsecure":true}}}]}"""
        assertThrows(IllegalArgumentException::class.java) { parse(xray) }
        val converted = parser.parseUserInput(xray, allowPrivateOutboundHosts = true, allowInsecureTls = true)
        val convertedNode = (
            json.parseToJsonElement(
                requireNotNull(converted.normalizedConfigJson)
            ).jsonObject.getValue("outbounds") as kotlinx.serialization.json.JsonArray
            ).first().jsonObject
        assertEquals(
            kotlinx.serialization.json.JsonPrimitive(true),
            convertedNode.getValue("tls").jsonObject["insecure"]
        )
    }
}
