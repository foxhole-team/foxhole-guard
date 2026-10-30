package com.foxhole.core.runtime

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

/** Standard carrier/config parameters only; no private server code or executable plugins. */
internal class ClientCarrierTranslationTest {
    private val encryption = "mlkem768x25519plus.native.1rtt.AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"

    @Test
    fun `SS WS TLS carrier reaches existing native transport without enabling UDP`() {
        val source = buildJsonObject {
            put("type", "shadowsocks")
            put("server", "1.1.1.1")
            put("server_port", 443)
            put("method", "aes-256-gcm")
            put("password", "synthetic-demo-password")
            put("network", "tcp")
            put(
                "transport",
                buildJsonObject {
                    put("type", "ws")
                    put("path", "/ss-demo")
                    put("headers", buildJsonObject { put("Host", "plugin.example") })
                }
            )
            put(
                "tls",
                buildJsonObject {
                    put("enabled", true)
                    put("server_name", "plugin.example")
                    put("insecure", false)
                }
            )
        }
        val result = translateFoxCoreProxyOutbound(source, "outbound")
        assertEquals(JsonPrimitive(false), result.getValue("udp"))
        assertEquals(JsonPrimitive("websocket"), result.getValue("transport").jsonObject.getValue("type"))
        assertEquals(JsonPrimitive("/ss-demo"), result.getValue("transport").jsonObject.getValue("path"))
        assertEquals(JsonPrimitive(true), result.getValue("tls").jsonObject.getValue("enabled"))
        assertEquals(JsonPrimitive("plugin.example"), result.getValue("tls").jsonObject.getValue("server_name"))
        assertEquals(source.getValue("method"), result.getValue("method"))
        assertEquals(source.getValue("password"), result.getValue("password"))
    }

    @Test
    fun `VLESS encryption reaches existing native outbound unchanged`() {
        val result = translateFoxCoreProxyOutbound(vless(encryption), "outbound")
        assertEquals(JsonPrimitive(encryption), result.getValue("encryption"))
        assertFalse(result.containsKey("flow"))
    }

    @Test
    fun `none and absent encryption are omitted from native outbound`() {
        for (value in listOf(null, "none")) {
            assertFalse(translateFoxCoreProxyOutbound(vless(value), "outbound").containsKey("encryption"))
        }
    }

    @Test
    fun `VLESS encrypted Vision is refused without a downgrade`() {
        val source = JsonObject(
            vless(encryption) + buildJsonObject {
                put("flow", "xtls-rprx-vision")
                put("tls", buildJsonObject { put("enabled", true) })
            }
        )
        assertThrows(FoxCoreConfigTranslationException::class.java) {
            translateFoxCoreProxyOutbound(source, "outbound")
        }
    }

    @Test
    fun `SS stream carriers cannot retain default or explicit UDP`() {
        for (network in listOf(null, "tcp,udp")) {
            val source = buildJsonObject {
                put("type", "shadowsocks")
                put("server", "1.1.1.1")
                put("server_port", 443)
                put("method", "aes-256-gcm")
                put("password", "synthetic-demo-password")
                network?.let { put("network", it) }
                put("transport", buildJsonObject { put("type", "ws") })
            }
            assertThrows(FoxCoreConfigTranslationException::class.java) {
                translateFoxCoreProxyOutbound(source, "outbound")
            }
        }
    }

    @Test
    fun `runtime refuses malformed encryption without echoing the public key`() {
        val malformed = "mlkem768x25519plus.native.1rtt.${encryption.substringAfterLast('.')}="
        val error = assertThrows(FoxCoreConfigTranslationException::class.java) {
            translateFoxCoreProxyOutbound(vless(malformed), "outbound")
        }
        assertFalse(error.message.orEmpty().contains(malformed))
    }

    private fun vless(value: String?) = buildJsonObject {
        put("type", "vless")
        put("server", "1.1.1.1")
        put("server_port", 443)
        put("uuid", "11111111-1111-4111-8111-111111111111")
        value?.let { put("encryption", it) }
    }
}
