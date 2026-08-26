package com.foxhole.core.runtime

import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.Settings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class RuntimeConfigAssemblerManagedDnsTransportTest : RuntimeConfigAssemblerTestSupport() {
    @Test
    fun `naive and shadowsocks use stream safe managed DNS`() {
        listOf(
            ProtocolCase(ProtocolHint.NAIVE, "naive"),
            ProtocolCase(ProtocolHint.SHADOWSOCKS, "shadowsocks"),
        ).forEach { case ->
            val config = assemble(case)
            val remote = remoteDns(config)

            assertEquals(case.hint.name, "https", remote["type"]!!.jsonPrimitive.content)
            assertEquals(case.hint.name, "443", remote["server_port"]!!.jsonPrimitive.content)
            assertEquals(case.hint.name, "/dns-query", remote["path"]!!.jsonPrimitive.content)
            assertEquals(case.hint.name, "proxy", remote["detour"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `udp capable protocols retain managed UDP DNS`() {
        listOf(
            ProtocolCase(ProtocolHint.VLESS, "vless"),
            ProtocolCase(ProtocolHint.WIREGUARD, "wireguard"),
            ProtocolCase(ProtocolHint.OUTLINE, "shadowsocks"),
        ).forEach { case ->
            val config = assemble(case)
            val remote = remoteDns(config)

            assertEquals(case.hint.name, "udp", remote["type"]!!.jsonPrimitive.content)
            assertEquals(case.hint.name, "53", remote["server_port"]!!.jsonPrimitive.content)
            assertEquals(case.hint.name, "proxy", remote["detour"]!!.jsonPrimitive.content)
            assertFalse(case.hint.name, remote.containsKey("path"))
        }
    }

    @Test
    fun `naive rejects non DNS UDP while shadowsocks still allows it`() {
        val naiveRejects = udpRejectRules(assemble(ProtocolCase(ProtocolHint.NAIVE, "naive")))
        val shadowsocksRejects =
            udpRejectRules(assemble(ProtocolCase(ProtocolHint.SHADOWSOCKS, "shadowsocks")))

        assertEquals(listOf("1-52", "54-65535"), naiveRejects)
        assertTrue(shadowsocksRejects.isEmpty())
    }

    private fun assemble(case: ProtocolCase): JsonObject =
        parse(
            assembler.assemble(
                baseConfigJson =
                baseConfigWithOutbounds(
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("type", case.outboundType)
                                put("tag", "proxy")
                                put("server", "node.example")
                                put("server_port", 443)
                            },
                        )
                    },
                ),
                settings = Settings(),
                activePreset = null,
                vpnProtocolHint = case.hint,
            ),
        )

    private fun remoteDns(config: JsonObject): JsonObject =
        config["dns"]!!
            .jsonObject["servers"]!!
            .jsonArray
            .map { it.jsonObject }
            .single { server -> server["tag"]?.jsonPrimitive?.content == "dns-remote" }

    private fun udpRejectRules(config: JsonObject): List<String> =
        config["route"]!!
            .jsonObject["rules"]!!
            .jsonArray
            .map { it.jsonObject }
            .filter { rule ->
                rule["network"]?.jsonPrimitive?.content == "udp" &&
                    rule["action"]?.jsonPrimitive?.content == "reject"
            }.map { rule -> rule["port_range"]!!.jsonPrimitive.content }

    private data class ProtocolCase(
        val hint: ProtocolHint,
        val outboundType: String,
    )
}
