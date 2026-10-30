package com.foxhole.core.importer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class BootstrapDnsProfileTest {
    private val lookedUp = mutableListOf<String>()
    private val parser = ProfileImportParser(
        Json,
        remoteHostResolver = { name ->
            lookedUp += name
            require(name != "group.example") { "bootstrap group queried through the system resolver" }
            listOf(InetAddress.getByAddress(byteArrayOf(203.toByte(), 0, 113, 10)))
        }
    )
    private val base = Json.parseToJsonElement(
        requireNotNull(
            parser.parseUserInput(
                "vless://11111111-1111-4111-8111-111111111111@203.0.113.10:443?security=tls&sni=tls.example#Fixture"
            ).normalizedConfigJson
        )
    ).jsonObject
    private val policy = Json.parseToJsonElement(
        """{"domains":["GROUP.Example."],"upstreams":[{"type":"doh","url":"https://resolver.example:18443/dns-query","server_ip":"203.0.113.53"}],"timeout_ms":3000,"max_candidates":8}"""
    ).jsonObject

    @Test
    fun groupBootstrapImportsWithoutSystemDnsAndSharesLosslesslyAsJson() {
        lookedUp.clear()
        val parsed = parser.parseUserInput(withPolicy(policy).toString())
        val root = Json.parseToJsonElement(requireNotNull(parsed.normalizedConfigJson)).jsonObject
        assertEquals(
            "group.example",
            (root.getValue("bootstrap_dns").jsonObject.getValue("domains") as JsonArray).single().jsonPrimitive.content
        )
        assertFalse(lookedUp.contains("group.example"))
        assertEquals(base.getValue("dns"), root.getValue("dns"))
        val copied = exportPortableConfigs("Fixture", listOf(root.toString()))
        assertTrue(copied.startsWith("{"))
        assertEquals(
            root.getValue("bootstrap_dns"),
            Json.parseToJsonElement(
                requireNotNull(
                    parser.parseUserInput(copied).normalizedConfigJson
                )
            ).jsonObject.getValue("bootstrap_dns")
        )
    }

    @Test
    fun oversizedSubscriptionIsRejectedBeforeJsonDecoding() {
        for (raw in listOf(
            "{".repeat(MAX_PORTABLE_BYTES + 1),
            "{\"padding\":\"" + "я".repeat(MAX_PORTABLE_BYTES / 2) + "\"}"
        )) {
            val failure = assertThrows(IllegalArgumentException::class.java) {
                parser.parseSubscriptionProfiles(raw, "Fixture")
            }
            assertEquals("profile payload is too large", failure.message)
        }
    }

    @Test
    fun normalClashJsonSubscriptionStillUsesItsExistingImporter() {
        val raw = """{"proxies":[{"name":"Fixture","type":"vless","server":"203.0.113.10","port":443,"uuid":"11111111-1111-4111-8111-111111111111"}]}"""
        assertEquals(1, parser.parseSubscriptionProfiles(raw, "Fixture").profiles.size)
    }

    @Test
    fun subscriptionKeepsProfileScopedBootstrapMetadata() {
        val imported = parser.parseSubscriptionProfiles(withPolicy(policy).toString(), "Fixture")
        assertEquals(1, imported.profiles.size)
        assertTrue(
            Json.parseToJsonElement(
                imported.profiles.single().normalizedConfigJson
            ).jsonObject.containsKey("bootstrap_dns")
        )
    }

    @Test
    fun malformedOrUnsupportedMetadataCannotDisappearDuringImport() {
        val invalid = listOf(
            """{"domains":["*.example"],"upstreams":[{"type":"udp","address":"203.0.113.53:53"}]}""",
            """{"domains":["group.example","GROUP.EXAMPLE."],"upstreams":[{"type":"udp","address":"203.0.113.53:53"}]}""",
            """{"domains":["foreign.example"],"upstreams":[{"type":"udp","address":"203.0.113.53:53"}]}""",
            """{"domains":["group.example"],"upstreams":[{"type":"udp","address":"resolver.example:53"}]}""",
            """{"domains":["group.example"],"upstreams":[{"type":"dot","host":"resolver.example","port":1853}]}""",
            """{"domains":["group.example"],"upstreams":[{"type":"doh","url":"https://user:pass@resolver.example/dns-query","server_ip":"203.0.113.53"}]}""",
            """{"domains":["group.example"],"upstreams":[{"type":"doh","url":"https://resolver.example/dns-query","server_ip":"203.0.113.53","insecure":true}]}""",
            """{"domains":["group.example"],"upstreams":[{"type":"doq","address":"203.0.113.53:853"}]}""",
            """{"domains":["group.example"],"upstreams":[{"type":"udp","address":"203.0.113.53:53"}],"timeout_ms":10001}""",
            """{"domains":["group.example"],"upstreams":[{"type":"udp","address":"203.0.113.53:53"}],"max_candidates":9}"""
        )
        invalid.forEach { raw ->
            assertThrows(RuntimeException::class.java) {
                parser.parseUserInput(withPolicy(Json.parseToJsonElement(raw).jsonObject).toString())
            }
        }
    }

    @Test
    fun explicitNodeIpCannotBypassAConfiguredBootstrapGroup() {
        val root = withPolicy(policy)
        val nodes = (root.getValue("outbounds") as JsonArray).map { element ->
            val node = element.jsonObject
            if (node["type"] == JsonPrimitive("vless")) {
                JsonObject(node + ("server_ip" to JsonPrimitive("203.0.113.10")))
            } else {
                node
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            parser.parseUserInput(JsonObject(root + ("outbounds" to JsonArray(nodes))).toString())
        }
    }

    @Test
    fun ownedPrivateResolversAreAllowedButInvalidEndpointsAreRefused() {
        for (address in listOf("127.0.0.1:5353", "192.168.1.1:53", "[fd00::1]:53")) {
            val value =
                JsonObject(
                    policy + (
                        "upstreams" to Json.parseToJsonElement(
                            """[{"type":"udp","address":"$address"}]"""
                        )
                        )
                )
            assertTrue(
                parser.parseUserInput(withPolicy(value).toString()).normalizedConfigJson!!.contains("bootstrap_dns")
            )
        }
        for (address in listOf(
            "0.0.0.0:53",
            "255.255.255.255:53",
            "224.0.0.1:53",
            "[::]:53",
            "[ff02::1]:53",
            "[fe80::1%1]:53"
        )) {
            val value =
                JsonObject(
                    policy + (
                        "upstreams" to Json.parseToJsonElement(
                            """[{"type":"udp","address":"$address"}]"""
                        )
                        )
                )
            assertThrows(RuntimeException::class.java) { parser.parseUserInput(withPolicy(value).toString()) }
        }
        for (type in listOf("doq", "doh3", "dnscrypt", "odoh")) {
            val value =
                JsonObject(
                    policy + (
                        "upstreams" to Json.parseToJsonElement(
                            """[{"type":"$type","address":"203.0.113.53:853"}]"""
                        )
                        )
                )
            assertThrows(RuntimeException::class.java) { parser.parseUserInput(withPolicy(value).toString()) }
        }
    }

    @Test
    fun selectingOneNodeProjectsOnlyItsGroupWhileWholeExportKeepsBoth() {
        val first = (withPolicy(policy).getValue("outbounds") as JsonArray).first {
            it.jsonObject["type"] == JsonPrimitive("vless")
        }.jsonObject
        val second =
            JsonObject(
                first + mapOf(
                    "tag" to JsonPrimitive("second-node"),
                    "server" to JsonPrimitive("other.example")
                )
            )
        val nodes = (withPolicy(policy).getValue("outbounds") as JsonArray).map { element ->
            val node = element.jsonObject
            if (node["type"] == JsonPrimitive("selector")) {
                JsonObject(
                    node + mapOf(
                        "outbounds" to JsonArray(listOf(first.getValue("tag"), second.getValue("tag"))),
                        "default" to second.getValue("tag")
                    )
                )
            } else {
                node
            }
        } + second
        val twoGroups =
            JsonObject(
                policy + (
                    "domains" to JsonArray(
                        listOf(
                            JsonPrimitive("group.example"),
                            JsonPrimitive("other.example")
                        )
                    )
                    )
            )
        val root = JsonObject(withPolicy(twoGroups) + ("outbounds" to JsonArray(nodes)))
        val selected = parser.parseUserInput(root.toString())
        assertEquals(2, selected.protocolOptions.size)
        val one =
            parser.parseUserInput(
                exportPortableConfigs(
                    "Fixture",
                    listOf(requireNotNull(selected.normalizedConfigJson))
                )
            )
        val oneRoot = Json.parseToJsonElement(requireNotNull(one.normalizedConfigJson)).jsonObject
        assertEquals(
            listOf("other.example"),
            (oneRoot.getValue("bootstrap_dns").jsonObject.getValue("domains") as JsonArray).map {
                it.jsonPrimitive.content
            }
        )
        val whole =
            parser.parseUserInput(
                exportPortableConfigs("Fixture", selected.protocolOptions.map { it.normalizedConfigJson })
            )
        assertEquals(2, whole.protocolOptions.size)
        assertEquals(
            setOf("group.example", "other.example"),
            whole.protocolOptions.flatMap { option ->
                (
                    Json.parseToJsonElement(option.normalizedConfigJson).jsonObject
                        .getValue("bootstrap_dns").jsonObject.getValue("domains") as JsonArray
                    )
                    .map { it.jsonPrimitive.content }
            }.toSet()
        )
    }

    private fun withPolicy(value: JsonObject): JsonObject {
        val nodes = (base.getValue("outbounds") as JsonArray).map { element ->
            val node = element.jsonObject
            if (node["type"] == JsonPrimitive("vless")) {
                JsonObject(node + ("server" to JsonPrimitive("group.example")))
            } else {
                node
            }
        }
        return JsonObject(base + mapOf("outbounds" to JsonArray(nodes), "bootstrap_dns" to value))
    }
    @Test fun quicBootstrapTransportsKeepVerifiedIdentityAndExplicitIp() {
        for (raw in listOf(
            """{"type":"doq","host":"resolver.example","port":853,"server_ip":"203.0.113.53"}""",
            """{"type":"doh3","url":"https://resolver.example:18443/dns-query","server_ip":"2001:db8::53"}"""
        )) {
            val upstream = Json.parseToJsonElement(raw).jsonObject
            val value = JsonObject(policy + ("upstreams" to JsonArray(listOf(upstream))))
            val parsed = parser.parseUserInput(withPolicy(value).toString())
            val normalized = Json.parseToJsonElement(requireNotNull(parsed.normalizedConfigJson)).jsonObject
            val retained = (normalized.getValue("bootstrap_dns").jsonObject.getValue("upstreams") as JsonArray).single().jsonObject
            assertEquals(upstream.getValue("type"), retained.getValue("type"))
            assertEquals(upstream.getValue("server_ip"), retained.getValue("server_ip"))
            assertEquals(JsonPrimitive(false), retained.getValue("insecure"))
        }
    }

    @Test fun quicBootstrapCannotDisableVerificationOrHideItsDestination() {
        for (raw in listOf(
            """{"type":"doq","host":"resolver.example","port":53,"server_ip":"203.0.113.53"}""",
            """{"type":"doq","host":"resolver.example","port":853,"server_ip":"203.0.113.53","insecure":true}""",
            """{"type":"doq","host":"resolver.example","port":853}""",
            """{"type":"doh3","url":"http://resolver.example/dns-query","server_ip":"203.0.113.53"}""",
            """{"type":"doh3","url":"https://resolver.example/dns-query?redirect=1","server_ip":"203.0.113.53"}""",
            """{"type":"doh3","url":"https://resolver.example/dns-query","server_ip":"0.0.0.0"}"""
        )) {
            val value = JsonObject(policy + ("upstreams" to JsonArray(listOf(Json.parseToJsonElement(raw)))))
            assertThrows(RuntimeException::class.java) { parser.parseUserInput(withPolicy(value).toString()) }
        }
    }

}
