package com.foxhole.core.runtime

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
import org.junit.Assert.assertTrue
import org.junit.Test

internal class FoxCoreConfigTranslatorProtocolsTest : FoxCoreConfigTranslatorTestSupport() {
    @Test
    fun `every typed app protocol translates to the matching FoxCore outbound`() {
        val cases = protocolCases()
        assertEquals(
            ProtocolHint.entries.filterNot {
                it in
                    setOf(
                        ProtocolHint.CUSTOM_CONFIG,
                        ProtocolHint.UNKNOWN,
                        ProtocolHint.TOR,
                        ProtocolHint.LOCAL_GUARD,
                    )
            },
            cases.map(ProtocolCase::hint),
        )

        cases.forEach { case ->
            val result =
                translate(
                    hint = case.hint,
                    primary = case.source,
                    dnsServers = case.dnsServers ?: managedDnsServers(),
                )
            val engine = engine(result)
            val outbound = engine.getValue("outbound").jsonObject
            val translated =
                if (case.hint == ProtocolHint.WIREGUARD) {
                    assertEquals(case.hint.name, "wireguard", outbound.type())
                    outbound
                } else {
                    assertEquals(case.hint.name, "selector", outbound.type())
                    val members = outbound.getValue("members").jsonArray
                    assertEquals(case.hint.name, 1, members.size)
                    assertEquals(case.hint.name, "member-1", outbound.getValue("default").jsonPrimitive.content)
                    members.single().jsonObject.getValue("outbound").jsonObject
                }

            assertEquals(case.hint.name, case.targetType, translated.type())
            case.verify(translated)
            assertEquals(case.hint.name, 1, engine.getValue("schema_version").jsonPrimitive.content.toInt())
            assertEquals(case.hint.name, setOf("schema_version", "outbound", "tun", "dns", "traffic"), engine.keys)
            assertFalse(case.hint.name, engine.containsLegacyKey("server_port"))
            assertFalse(case.hint.name, engine.containsLegacyKey("tag"))
            assertFalse(case.hint.name, engine.containsKey("inbounds"))
            assertFalse(case.hint.name, engine.containsKey("endpoints"))
        }
    }

    @Test
    fun `migration emits the Android TUN plan and transfers the managed control proxy`() {
        val result =
            translate(
                hint = ProtocolHint.VLESS,
                primary = vless(),
                expectedPolicyRevision = 41L,
                inbounds = listOf(managedTun(), managedRuntimeInbound(port = 28_811)),
            )
        val targetEngine = engine(result)
        val targetTun = targetEngine.getValue("tun").jsonObject
        val controlProxy =
            targetEngine
                .getValue("runtime")
                .jsonObject
                .getValue("control_proxy")
                .jsonObject

        assertEquals(1500, result.tunPlan.mtu)
        assertEquals("172.19.0.1", result.tunPlan.ipv4Address)
        assertEquals(30, result.tunPlan.ipv4PrefixLength)
        assertEquals("fdfe:dcba:9876::1", result.tunPlan.ipv6Address)
        assertEquals(126, result.tunPlan.ipv6PrefixLength)
        assertEquals("172.19.0.1", targetTun.getValue("ipv4").jsonPrimitive.content)
        assertEquals("fdfe:dcba:9876::1", targetTun.getValue("ipv6").jsonPrimitive.content)
        assertEquals("41", policy(result).getValue("expected_revision").jsonPrimitive.content)
        assertEquals(28_811, controlProxy.getValue("http_port").jsonPrimitive.content.toInt())
        assertEquals("foxhole-runtime", controlProxy.getValue("username").jsonPrimitive.content)
        assertEquals(
            "ephemeral-test-password",
            controlProxy.getValue("password").jsonPrimitive.content,
        )
    }

    @Test
    fun `vless vision defaults an omitted packet encoding to xudp`() {
        val translated =
            engine(
                translate(
                    hint = ProtocolHint.VLESS,
                    primary =
                    primary("vless") {
                        putServer()
                        put("uuid", CONTRACT_UUID)
                        put("flow", "xtls-rprx-vision")
                        put("tls", tls())
                    },
                ),
            ).getValue("outbound")
                .jsonObject
                .getValue("members")
                .jsonArray
                .single()
                .jsonObject
                .getValue("outbound")
                .jsonObject

        assertEquals("xudp", translated.getValue("packet_encoding").jsonPrimitive.content)
    }

    @Suppress("LongMethod")
    private fun protocolCases(): List<ProtocolCase> =
        listOf(
            ProtocolCase(ProtocolHint.VLESS, vless(), "vless") { outbound ->
                assertEquals("xudp", outbound.getValue("packet_encoding").jsonPrimitive.content)
                val transport = outbound.getValue("transport").jsonObject
                assertEquals("websocket", transport.type())
                assertEquals("edge.example", transport.getValue("host").jsonPrimitive.content)
            },
            ProtocolCase(
                hint = ProtocolHint.TROJAN,
                source =
                primary("trojan") {
                    putServer()
                    put("password", "trojan-contract-password")
                    put("tls", tls())
                    put(
                        "transport",
                        buildJsonObject {
                            put("type", "httpupgrade")
                            put("path", "/upgrade")
                            put("host", "edge.example")
                        },
                    )
                },
                targetType = "trojan",
            ) { outbound ->
                assertEquals("http_upgrade", outbound.getValue("transport").jsonObject.type())
                assertTrue(outbound.getValue("tls").jsonObject.getValue("enabled").jsonPrimitive.content.toBoolean())
            },
            ProtocolCase(
                hint = ProtocolHint.SHADOWSOCKS,
                source = shadowsocks(),
                targetType = "shadowsocks",
            ) { outbound ->
                assertEquals("aes-256-gcm", outbound.getValue("method").jsonPrimitive.content)
                assertTrue(outbound.getValue("udp").jsonPrimitive.content.toBoolean())
            },
            ProtocolCase(
                hint = ProtocolHint.WIREGUARD,
                source =
                primary("wireguard") {
                    put("private_key", ZERO_WIREGUARD_KEY)
                    put("address", stringArray("10.17.0.2/32"))
                    put("mtu", 1420)
                    put(
                        "peers",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("address", "198.51.100.20")
                                    put("port", 51820)
                                    put("public_key", ONE_WIREGUARD_KEY)
                                    put("pre_shared_key", TWO_WIREGUARD_KEY)
                                    put("allowed_ips", stringArray("0.0.0.0/0"))
                                    put("persistent_keepalive_interval", 25)
                                    put(
                                        "reserved",
                                        buildJsonArray {
                                            add(JsonPrimitive(1))
                                            add(JsonPrimitive(2))
                                            add(JsonPrimitive(3))
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
                targetType = "wireguard",

                dnsServers = managedDnsServers() + wireGuardDnsServer(),
            ) { outbound ->
                assertEquals("198.51.100.20", outbound.getValue("server").jsonPrimitive.content)
                assertEquals("25", outbound.getValue("persistent_keepalive_s").jsonPrimitive.content)
                assertEquals(3, outbound.getValue("reserved").jsonArray.size)
            },
            ProtocolCase(
                hint = ProtocolHint.HYSTERIA2,
                source =
                primary("hysteria2") {
                    put("server", "203.0.113.31")
                    put("server_ports", stringArray("443", "8443-8445"))
                    put("hop_interval", "15s")
                    put("password", "hysteria-contract-password")
                    put("tls", tls())
                    put(
                        "obfs",
                        buildJsonObject {
                            put("type", "salamander")
                            put("password", "hysteria-obfs-contract-password")
                        },
                    )
                },
                targetType = "hysteria2",
            ) { outbound ->
                assertEquals("15000", outbound.getValue("hop_interval_ms").jsonPrimitive.content)
                assertEquals(2, outbound.getValue("server_ports").jsonArray.size)
                assertEquals("salamander", outbound.getValue("obfs").jsonObject.type())
            },
            ProtocolCase(
                hint = ProtocolHint.VMESS,
                source =
                primary("vmess") {
                    putServer()
                    put("uuid", CONTRACT_UUID)
                    put("alter_id", 0)
                    put("security", "auto")
                    put("tls", tls())
                    put(
                        "transport",
                        buildJsonObject {
                            put("type", "grpc")
                            put("service_name", "foxhole.contract")
                            put("authority", "edge.example")
                        },
                    )
                },
                targetType = "vmess",
            ) { outbound ->
                assertEquals("auto", outbound.getValue("cipher").jsonPrimitive.content)
                assertEquals("grpc", outbound.getValue("transport").jsonObject.type())
                assertEquals("0", outbound.getValue("alter_id").jsonPrimitive.content)
            },
            ProtocolCase(
                hint = ProtocolHint.OUTLINE,
                source = shadowsocks(),
                targetType = "shadowsocks",
            ) { outbound ->
                assertEquals("aes-256-gcm", outbound.getValue("method").jsonPrimitive.content)
            },
            ProtocolCase(
                hint = ProtocolHint.NAIVE,
                source =
                primary("naive") {
                    putServer()
                    put("username", "contract-user")
                    put("password", "naive-contract-password")
                    put(
                        "tls",
                        tls {
                            put("alpn", stringArray("h2"))
                        },
                    )
                },
                targetType = "naive",
            ) { outbound ->
                assertTrue(outbound.getValue("padding").jsonPrimitive.content.toBoolean())
                assertEquals(
                    listOf("h2"),
                    outbound.getValue("tls").jsonObject.getValue("alpn").jsonArray.map {
                        it.jsonPrimitive.content
                    },
                )
            },
            ProtocolCase(
                hint = ProtocolHint.TUIC,
                source =
                primary("tuic") {
                    putServer()
                    put("uuid", CONTRACT_UUID)
                    put("password", "tuic-contract-password")
                    put("congestion_control", "new_reno")
                    put("udp_relay_mode", "quic")
                    put("network", "tcp,udp")
                    put("heartbeat", "10s")
                    put("idle_timeout", "30s")
                    put("tls", tls())
                },
                targetType = "tuic",
            ) { outbound ->
                assertEquals("new_reno", outbound.getValue("congestion_control").jsonPrimitive.content)
                assertTrue(outbound.getValue("tcp").jsonPrimitive.content.toBoolean())
                assertTrue(outbound.getValue("udp").jsonPrimitive.content.toBoolean())
                assertFalse(outbound.getValue("zero_rtt_handshake").jsonPrimitive.content.toBoolean())
            },
            ProtocolCase(
                hint = ProtocolHint.ANYTLS,
                source =
                primary("anytls") {
                    putServer()
                    put("password", "anytls-contract-password")
                    put("idle_session_check_interval", "20s")
                    put("idle_session_timeout", "45s")
                    put("min_idle_session", 2)
                    put(
                        "tls",
                        tls {
                            put("min_version", "1.3")
                        },
                    )
                },
                targetType = "anytls",
            ) { outbound ->
                assertEquals("20000", outbound.getValue("idle_session_check_interval_ms").jsonPrimitive.content)
                assertEquals("45000", outbound.getValue("idle_session_timeout_ms").jsonPrimitive.content)
                assertEquals("2", outbound.getValue("min_idle_session").jsonPrimitive.content)
            },
        )

    private fun vless(): JsonObject =
        primary("vless") {
            putServer()
            put("uuid", CONTRACT_UUID)
            put("packet_encoding", "xudp")
            put("tls", tls())
            put(
                "transport",
                buildJsonObject {
                    put("type", "ws")
                    put("path", "/contract")
                    put(
                        "headers",
                        buildJsonObject {
                            put("Host", "edge.example")
                            put("X-Contract", "foxcore")
                        },
                    )
                },
            )
        }

    private fun shadowsocks(): JsonObject =
        primary("shadowsocks") {
            putServer()
            put("method", "aes-256-gcm")
            put("password", "shadowsocks-contract-password")
        }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putServer() {
        put("server", "203.0.113.10")
        put("server_port", 443)
    }

    private fun stringArray(vararg values: String): JsonArray =
        JsonArray(values.map(::JsonPrimitive))

    private fun JsonObject.type(): String = getValue("type").jsonPrimitive.content

    private fun JsonObject.containsLegacyKey(key: String): Boolean =
        entries.any { (name, value) ->
            name == key ||
                when (value) {
                    is JsonObject -> value.containsLegacyKey(key)
                    is JsonArray -> value.any { element -> element is JsonObject && element.containsLegacyKey(key) }
                    else -> false
                }
        }

    private data class ProtocolCase(
        val hint: ProtocolHint,
        val source: JsonObject,
        val targetType: String,

        val dnsServers: List<JsonObject>? = null,
        val verify: (JsonObject) -> Unit,
    )

    private companion object {
        const val CONTRACT_UUID = "d0cf0001-0000-4000-8000-000000000000"
        const val ZERO_WIREGUARD_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        const val ONE_WIREGUARD_KEY = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="
        const val TWO_WIREGUARD_KEY = "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI="
    }
}
