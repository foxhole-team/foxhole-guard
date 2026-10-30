package com.foxhole.core.importer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress
import java.util.Base64

class DirectNodeProtocolIsolationTest {
    private val json = Json
    private val parser = ProfileImportParser(
        json,
        remoteHostResolver = { listOf(InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1))) }
    )
    private val vless = "vless://11111111-1111-4111-8111-111111111111@1.1.1.1:443?security=tls&sni=example.com#Fixture"

    @Test fun groupedLinksKeepTheExactSingleProtocolConfigurationsUsedByRuntime() {
        val vmess = "vmess://" + Base64.getEncoder().encodeToString("""{"v":"2","ps":"Fixture","add":"1.1.1.1","port":"443","id":"11111111-1111-4111-8111-111111111111","tls":"tls","sni":"example.com"}""".toByteArray())
        val wg = "wireguard://fixture-private@8.8.8.8:51820?publickey=fixture-public&address=10.8.0.2%2F32&dns=10.1.2.3#Fixture"
        listOf(listOf(vless, vmess, wg), listOf(wg, vless, vmess)).forEach { links ->
            val expected = links.map {
                json.parseToJsonElement(
                    requireNotNull(parser.parseUserInput(it).normalizedConfigJson)
                )
            }
            val hints = links.map { parser.parseUserInput(it).protocolHint }
            val grouped = parser.parseUserInput(links.joinToString("\n"))
            assertEquals(hints, grouped.protocolOptions.map { it.protocolHint })
            assertEquals(expected, grouped.protocolOptions.map { json.parseToJsonElement(it.normalizedConfigJson) })
            assertEquals(expected.first(), json.parseToJsonElement(requireNotNull(grouped.normalizedConfigJson)))
            assertEquals(grouped.protocolOptions.first().id, grouped.selectedProtocolOptionId)
            assertEquals(3, grouped.nodesCount)
        }
    }

    @Test fun canonicalDocumentsKeepTheirCustomPoliciesInsteadOfBeingRebuiltAsLinks() {
        val root = json.parseToJsonElement(requireNotNull(parser.parseUserInput(vless).normalizedConfigJson)).jsonObject
        val dns = JsonObject(root.getValue("dns").jsonObject + ("strategy" to JsonPrimitive("prefer_ipv6")))
        val route = root.getValue("route").jsonObject
        val rules = (route.getValue("rules") as JsonArray) + JsonObject(mapOf("domain_suffix" to JsonArray(listOf(JsonPrimitive("fixture.example"))), "outbound" to JsonPrimitive("direct")))
        val custom = JsonObject(
            root + mapOf("dns" to dns, "route" to JsonObject(route + ("rules" to JsonArray(rules))))
        )
        assertEquals(
            custom,
            json.parseToJsonElement(requireNotNull(parser.parseUserInput(custom.toString()).normalizedConfigJson))
        )
    }
}
