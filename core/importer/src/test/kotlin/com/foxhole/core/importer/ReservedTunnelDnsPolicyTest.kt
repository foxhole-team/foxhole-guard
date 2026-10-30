package com.foxhole.core.importer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.InetAddress

class ReservedTunnelDnsPolicyTest {
    private val json = Json
    private val parser =
        ProfileImportParser(json, remoteHostResolver = { listOf(InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1))) })
    private val vless = "vless://11111111-1111-4111-8111-111111111111@1.1.1.1:443?security=tls&sni=example.com#Fixture"
    private val wireguard = "wireguard://fixture-private@8.8.8.8:51820?publickey=fixture-public&address=10.8.0.2%2F32&dns=10.1.2.3#Fixture"

    @Test fun reservedTagCannotHidePrivateDnsOnAVlessRoute() {
        val root = root(vless)
        listOf(null, "direct", "proxy").forEach { detour ->
            val candidate = withReservedDns(root, "10.1.2.3", detour)
            assertThrows(IllegalArgumentException::class.java) { parser.parseUserInput(candidate.toString()) }
        }
        parser.parseUserInput(withReservedDns(root, "8.8.8.8", null).toString())
    }

    @Test fun mixedSelectorNeverReceivesThePacketTunnelDnsExemption() {
        val proxy = root(vless)
        val packet = root(wireguard)
        val endpoint = (packet.getValue("endpoints") as JsonArray).single().jsonObject
        val outbounds = proxy.getValue("outbounds") as JsonArray
        val selector = outbounds.single { it.jsonObject["type"] == JsonPrimitive("selector") }.jsonObject
        val nodeTag = outbounds.first().jsonObject.getValue("tag")
        listOf(nodeTag, endpoint.getValue("tag")).forEach { selected ->
            val mixed =
                JsonObject(
                    selector + mapOf("default" to selected, "outbounds" to JsonArray(listOf(nodeTag, endpoint.getValue("tag"))))
                )
            val root =
                JsonObject(
                    proxy + mapOf(
                        "outbounds" to JsonArray(
                            outbounds.map {
                                if (it == selector) mixed else it
                            }
                        ),
                        "endpoints" to JsonArray(listOf(endpoint))
                    )
                )
            assertThrows(
                IllegalArgumentException::class.java
            ) { parser.parseUserInput(withReservedDns(root, "10.1.2.3", "proxy").toString()) }
        }
        val unusedPacket = JsonObject(proxy + ("endpoints" to JsonArray(listOf(endpoint))))
        assertThrows(
            IllegalArgumentException::class.java
        ) { parser.parseUserInput(withReservedDns(unusedPacket, "10.1.2.3", null).toString()) }
    }

    @Test fun genuineWireGuardKeepsPrivateTunnelDnsButRefusesDirectOrLoopbackMetadata() {
        val root = root(wireguard)
        val parsed = parser.parseUserInput(root.toString())
        assertEquals(
            json.parseToJsonElement(root.toString()),
            json.parseToJsonElement(requireNotNull(parsed.normalizedConfigJson))
        )
        listOf(null, "direct").forEach { detour ->
            assertThrows(
                IllegalArgumentException::class.java
            ) { parser.parseUserInput(withReservedDns(root, "10.1.2.3", detour).toString()) }
        }
        assertThrows(
            IllegalArgumentException::class.java
        ) { parser.parseUserInput(withReservedDns(root, "127.0.0.1", "proxy").toString()) }
    }

    private fun root(link: String): JsonObject = json.parseToJsonElement(
        requireNotNull(parser.parseUserInput(link).normalizedConfigJson)
    ).jsonObject

    private fun withReservedDns(root: JsonObject, address: String, detour: String?): JsonObject {
        val server =
            mutableMapOf(
                "tag" to JsonPrimitive("dns-wireguard"),
                "type" to JsonPrimitive("udp"),
                "server" to JsonPrimitive(address),
                "server_port" to JsonPrimitive(53)
            )
        detour?.let { server["detour"] = JsonPrimitive(it) }
        val dns =
            JsonObject(
                root.getValue("dns").jsonObject + mapOf("servers" to JsonArray(listOf(JsonObject(server))), "final" to JsonPrimitive("dns-wireguard"))
            )
        return JsonObject(root + ("dns" to dns))
    }
}
