package com.foxhole.core.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeI2pOutboundsTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val baseConfig =
        """
        {
          "outbounds": [
            {"type": "socks", "tag": "proxy", "server": "127.0.0.1", "server_port": 10809},
            {"type": "direct", "tag": "direct"},
            {"type": "block", "tag": "block"}
          ],
          "dns": {
            "servers": [{"tag": "dns-remote", "type": "https"}],
            "rules": [{"domain_suffix": [".example"], "server": "dns-remote"}],
            "final": "dns-remote"
          },
          "route": {
            "rules": [{"inbound": ["runtime-proxy"], "action": "route", "outbound": "proxy"}],
            "final": "proxy"
          }
        }
        """.trimIndent()

    @After
    fun clearPublishedProxy() {
        I2pdSocksProxy.endpoint = null
    }

    private fun patched(
        port: Int?,
        mode: PrivateDnsMode? = null,
    ): JsonObject =
        json.parseToJsonElement(baseConfig).jsonObject.withI2pRouting(port, mode)

    @Test
    fun `null port is a no-op`() {
        val result = patched(null)
        assertFalse(result["outbounds"]!!.jsonArray.any { it.jsonObject["tag"]?.jsonPrimitive?.contentOrNull == "i2p" })
    }

    @Test
    fun `strict private dns skips the fakeip injection`() {
        val result = patched(4447, PrivateDnsMode.STRICT)
        assertNull(
            result["dns"]!!.jsonObject["servers"]!!.jsonArray.firstOrNull {
                it.jsonObject["tag"]?.jsonPrimitive?.contentOrNull == I2P_FAKEIP_DNS_TAG
            }
        )
    }

    @Test
    fun `adds i2p outbound before the terminal direct outbound`() {
        val outbounds = patched(4447)["outbounds"]!!.jsonArray
        val i2pIndex = outbounds.indexOfFirst { it.jsonObject["tag"]?.jsonPrimitive?.contentOrNull == "i2p" }
        val directIndex = outbounds.indexOfFirst { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "direct" }
        assertTrue(i2pIndex in 0 until directIndex)
        assertEquals(4447, outbounds[i2pIndex].jsonObject["server_port"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `running i2p outbound carries its per-start socks credentials`() {
        I2pdSocksProxy.endpoint =
            I2pdSocksProxyEndpoint(
                port = 4447,
                username = "foxhole",
                password = "one-time-secret",
            )

        val outbound =
            patched(4447)["outbounds"]!!.jsonArray
                .first { it.jsonObject["tag"]?.jsonPrimitive?.contentOrNull == "i2p" }
                .jsonObject

        assertEquals("foxhole", outbound["username"]!!.jsonPrimitive.content)
        assertEquals("one-time-secret", outbound["password"]!!.jsonPrimitive.content)
    }

    @Test
    fun `stale i2p credentials are not attached to another port`() {
        I2pdSocksProxy.endpoint =
            I2pdSocksProxyEndpoint(
                port = 4448,
                username = "foxhole",
                password = "stale-secret",
            )

        val outbound =
            patched(4447)["outbounds"]!!.jsonArray
                .first { it.jsonObject["tag"]?.jsonPrimitive?.contentOrNull == "i2p" }
                .jsonObject

        assertFalse(outbound.containsKey("username"))
        assertFalse(outbound.containsKey("password"))
    }

    @Test
    fun `i2p route rule is first so it wins over existing rules`() {
        val rules = patched(4447)["route"]!!.jsonObject["rules"]!!.jsonArray
        val first = rules.first().jsonObject
        assertEquals("i2p", first["outbound"]!!.jsonPrimitive.content)
        assertEquals(".i2p", first["domain_suffix"]!!.jsonArray.first().jsonPrimitive.content)
        // The original rule is preserved after it.
        assertTrue(rules.size >= 2)
    }

    @Test
    fun `fakeip server and dns rule are injected`() {
        val dns = patched(4447)["dns"]!!.jsonObject
        val fakeIp = dns["servers"]!!.jsonArray.firstOrNull {
            it.jsonObject["tag"]?.jsonPrimitive?.contentOrNull == I2P_FAKEIP_DNS_TAG
        }
        assertEquals("fakeip", fakeIp!!.jsonObject["type"]!!.jsonPrimitive.content)
        val firstRule = dns["rules"]!!.jsonArray.first().jsonObject
        assertEquals(I2P_FAKEIP_DNS_TAG, firstRule["server"]!!.jsonPrimitive.content)
        assertEquals(".i2p", firstRule["domain_suffix"]!!.jsonArray.first().jsonPrimitive.content)
    }

    @Test
    fun `re-applying is idempotent`() {
        val once = patched(4447)
        val twice = once.withI2pRouting(4447, null)
        val outbounds = twice["outbounds"]!!.jsonArray
        assertEquals(1, outbounds.count { it.jsonObject["tag"]?.jsonPrimitive?.contentOrNull == "i2p" })
    }
}
