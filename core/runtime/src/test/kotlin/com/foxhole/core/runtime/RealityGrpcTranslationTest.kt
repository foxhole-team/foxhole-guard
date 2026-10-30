package com.foxhole.core.runtime

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64

internal class RealityGrpcTranslationTest {
    private val uuid = "11111111-1111-4111-8111-111111111111"
    private val publicKey =
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            ByteArray(32) { (it + 1).toByte() },
        )

    @Test
    fun `Reality grpc keeps authentication and exact gun transport tuning`() {
        val transport = grpc(multi = false)
        val result = translateFoxCoreProxyOutbound(profile(transport), "outbound")
        val expectedTransport =
            buildJsonObject {
                put("type", "grpc")
                put("service_name", "svc")
                put("multi_mode", false)
                put("authority", "rpc.example:8443")
                put("user_agent", "fixture/1")
                put("rpc_path", "/svc/Tun")
                put("idle_timeout_ms", 1500)
                put("health_check_timeout_ms", 2500)
                put("permit_without_stream", false)
                put("initial_windows_size", 131072)
            }
        assertEquals(expectedTransport, result.getValue("transport"))
        assertEquals(JsonPrimitive(uuid), result.getValue("uuid"))
        assertEquals(JsonPrimitive("none"), result.getValue("packet_encoding"))
        assertFalse(result.containsKey("flow"))
        assertRealityAuthentication(result)
    }

    @Test
    fun `Reality multi stream keeps empty service and custom RPC path`() {
        val transport =
            buildJsonObject {
                put("type", "grpc")
                put("service_name", "")
                put("multi_mode", true)
                put("rpc_path", "/package.Service/Custom")
            }
        val result = translateFoxCoreProxyOutbound(profile(transport), "outbound")
        assertEquals(transport, result.getValue("transport"))
        assertRealityAuthentication(result)
    }

    @Test
    fun `malformed grpc fields are rejected at their precise schema path`() {
        val malformed =
            listOf(
                "idle_timeout" to JsonPrimitive("1ns"),
                "multi_mode" to JsonPrimitive("maybe"),
                "initial_windows_size" to JsonPrimitive(-1),
            )
        for ((field, value) in malformed) {
            val transport = JsonObject(grpc() + (field to value))
            val failure =
                assertThrows(FoxCoreConfigTranslationException::class.java) {
                    translateFoxCoreProxyOutbound(profile(transport), "outbound")
                }
            assertEquals("outbound.transport.$field", failure.path)
        }
    }

    @Test
    fun `Reality does not admit other explicit transports`() {
        for (type in listOf("ws", "http", "http2", "http_upgrade", "raw", "tcp", "gun")) {
            val transport = buildJsonObject { put("type", type) }
            val failure =
                assertThrows(FoxCoreConfigTranslationException::class.java) {
                    translateFoxCoreProxyOutbound(profile(transport), "outbound")
                }
            assertEquals(FoxCoreConfigRejection.UNSUPPORTED_TRANSPORT, failure.rejection)
            assertEquals("outbound.transport", failure.path)
        }
    }

    @Test
    fun `Vision rejects grpc while the absent raw carrier remains supported`() {
        val failure =
            assertThrows(FoxCoreConfigTranslationException::class.java) {
                translateFoxCoreProxyOutbound(
                    profile(grpc(), flow = "xtls-rprx-vision"),
                    "outbound",
                )
            }
        assertEquals(FoxCoreConfigRejection.UNSUPPORTED_TRANSPORT, failure.rejection)
        assertEquals("outbound.transport", failure.path)
        val raw =
            translateFoxCoreProxyOutbound(
                profile(transport = null, flow = "xtls-rprx-vision"),
                "outbound",
            )
        assertEquals(JsonPrimitive("raw"), raw.getValue("transport").jsonObject.getValue("type"))
        assertEquals(JsonPrimitive("xtls-rprx-vision"), raw.getValue("flow"))
        assertEquals(JsonPrimitive("xudp"), raw.getValue("packet_encoding"))
        assertRealityAuthentication(raw)
    }

    @Test
    fun `Reality TLS and typed authentication refusals remain intact`() {
        val tls = realityTls()
        val auth = tls.getValue("reality").jsonObject
        val badKey = JsonObject(auth + ("public_key" to JsonPrimitive(123)))
        val badTls =
            listOf(
                JsonObject(tls + ("insecure" to JsonPrimitive(true))),
                JsonObject(tls + ("enabled" to JsonPrimitive(false))),
                JsonObject(tls + ("reality" to badKey)),
                JsonObject(tls + ("alpn" to buildJsonArray { add(JsonPrimitive("h2")) })),
            )
        for (candidate in badTls) {
            assertThrows(FoxCoreConfigTranslationException::class.java) {
                translateFoxCoreProxyOutbound(profile(tls = candidate), "outbound")
            }
        }
    }

    @Test
    fun `ordinary TLS grpc keeps the existing explicit h2 ALPN`() {
        val tls =
            buildJsonObject {
                put("enabled", true)
                put("server_name", "peer.example")
                put("alpn", buildJsonArray { add(JsonPrimitive("h2")) })
            }
        val result = translateFoxCoreProxyOutbound(profile(tls = tls), "outbound")
        assertEquals(tls, result.getValue("tls"))
        assertFalse(result.containsKey("reality"))
    }

    private fun assertRealityAuthentication(result: JsonObject) {
        val expected =
            buildJsonObject {
                put("server_name", "peer.example")
                put("public_key", publicKey)
                put("short_id", "0102030405060708")
                put("fingerprint", "chrome_151")
            }
        assertEquals(expected, result.getValue("reality"))
        assertEquals(buildJsonObject { put("enabled", false) }, result.getValue("tls"))
    }

    private fun grpc(multi: Boolean = false): JsonObject =
        buildJsonObject {
            put("type", "grpc")
            put("service_name", "svc")
            put("multi_mode", multi)
            put("authority", "rpc.example:8443")
            put("user_agent", "fixture/1")
            put("rpc_path", "/svc/Tun")
            put("idle_timeout", "1500ms")
            put("health_check_timeout", "2.5s")
            put("permit_without_stream", false)
            put("initial_windows_size", 131072)
        }

    private fun profile(
        transport: JsonObject? = grpc(),
        flow: String? = null,
        tls: JsonObject = realityTls(),
    ): JsonObject =
        buildJsonObject {
            put("type", "vless")
            put("server", "1.1.1.1")
            put("server_port", 443)
            put("uuid", uuid)
            put("tls", tls)
            transport?.let { put("transport", it) }
            flow?.let { put("flow", it) }
        }

    private fun realityTls(): JsonObject =
        buildJsonObject {
            put("enabled", true)
            put("server_name", "peer.example")
            put(
                "utls",
                buildJsonObject {
                    put("enabled", true)
                    put("fingerprint", "chrome")
                },
            )
            put(
                "reality",
                buildJsonObject {
                    put("enabled", true)
                    put("public_key", publicKey)
                    put("short_id", "0102030405060708")
                },
            )
        }
}
