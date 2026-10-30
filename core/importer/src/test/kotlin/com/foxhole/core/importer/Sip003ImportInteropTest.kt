package com.foxhole.core.importer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.URLEncoder
import java.util.Base64

/** Standard SIP002/SIP003 parameters with synthetic credentials; no server implementation fixture. */
internal class Sip003ImportInteropTest {
    private val parser = ProfileImportParser(Json)

    @Test
    fun `v2ray plugin WS TLS share profile becomes a native compatible TCP carrier`() {
        val node = node("v2ray-plugin;mode=websocket;mux=0;tls;host=plugin.example;path=/ss-demo")
        assertEquals("aes-256-gcm", node.getValue("method").jsonPrimitive.content)
        assertEquals("synthetic-demo-password", node.getValue("password").jsonPrimitive.content)
        assertEquals("tcp", node.getValue("network").jsonPrimitive.content)
        val transport = node.getValue("transport").jsonObject
        assertEquals("ws", transport.getValue("type").jsonPrimitive.content)
        assertEquals("/ss-demo", transport.getValue("path").jsonPrimitive.content)
        assertEquals("plugin.example", transport.getValue("headers").jsonObject.getValue("Host").jsonPrimitive.content)
        val tls = node.getValue("tls").jsonObject
        assertEquals("true", tls.getValue("enabled").jsonPrimitive.content)
        assertEquals("plugin.example", tls.getValue("server_name").jsonPrimitive.content)
        assertFalse(node.containsKey("plugin"))
        assertFalse(node.containsKey("plugin_opts"))
    }

    @Test
    fun `unknown executable and effectful plugin options remain explicit refusals`() {
        for (
        plugin in listOf(
            "unowned-plugin;mux=0;tls;host=plugin.example",
            "v2ray-plugin;mode=quic;mux=0;tls;host=plugin.example",
            "v2ray-plugin;mode=websocket;mux=0;tls;host=plugin.example;unknown_effectful=1",
        )
        ) {
            assertThrows(IllegalArgumentException::class.java) { parser.parseUserInput(share(plugin)) }
        }
    }

    @Test
    fun `upstream default multiplexing and nonzero multiplexing are not silently disabled`() {
        for (mux in listOf("", ";mux=1", ";mux=2", ";mux=-1", ";mux=false")) {
            assertThrows(IllegalArgumentException::class.java) {
                parser.parseUserInput(share("v2ray-plugin;tls$mux"))
            }
        }
    }

    @Test
    fun `presence TLS defaults and escaped plugin option values retain their meaning`() {
        val defaults = node("v2ray-plugin;mux=0;tls=false")
        assertEquals("true", defaults.getValue("tls").jsonObject.getValue("enabled").jsonPrimitive.content)
        assertEquals(
            "cloudfront.com",
            defaults.getValue("tls").jsonObject.getValue("server_name").jsonPrimitive.content
        )
        assertEquals("/", defaults.getValue("transport").jsonObject.getValue("path").jsonPrimitive.content)
        val escaped = node("v2ray-plugin;mux=0;path=/a\\;b\\=c\\\\d")
        assertEquals("/a;b=c\\d", escaped.getValue("transport").jsonObject.getValue("path").jsonPrimitive.content)
        assertEquals("false", escaped.getValue("tls").jsonObject.getValue("enabled").jsonPrimitive.content)
        assertEquals("tcp", escaped.getValue("network").jsonPrimitive.content)
    }

    @Test
    fun `invalid escapes duplicates and bounded unsafe fields are rejected without credentials`() {
        for (
        plugin in listOf(
            "v2ray-plugin;mux=0;path=/bad\\q",
            "v2ray-plugin;mux=0;path=/bad\\",
            "v2ray-plugin;mux=0;mux=0",
            "v2ray-plugin;mux=0;host=",
            "v2ray-plugin;mux=0;host=bad host",
            "v2ray-plugin;mux=0;host=bad/host",
            "v2ray-plugin;mux=0;path=/bad=delimiter",
            "v2ray-plugin;mux=0;path=relative",
            "v2ray-plugin;mux=0;path=/fragment#tail",
            "v2ray-plugin;mux=0;host=${"a".repeat(256)}",
            "v2ray-plugin;mux=0;path=/${"a".repeat(2048)}",
            "v2ray-plugin;mux=0;tls=${"a".repeat(8192)}",
        )
        ) {
            val error = assertThrows(IllegalArgumentException::class.java) { parser.parseUserInput(share(plugin)) }
            assertFalse(error.message.orEmpty().contains("synthetic-demo-password"))
            assertFalse(error.message.orEmpty().contains(plugin))
        }
    }

    @Test
    fun `SIP003 never enables UDP and duplicate outer options cannot mask a requested mode`() {
        val plugin = "v2ray-plugin;mux=0"
        assertEquals("tcp", parse(share(plugin) + "&udp=false").getValue("network").jsonPrimitive.content)
        for (suffix in listOf("&udp=true", "&udp=invalid", "&plugin=v2ray-plugin", "&extra_effectful=1")) {
            assertThrows(IllegalArgumentException::class.java) { parser.parseUserInput(share(plugin) + suffix) }
        }
    }

    @Test
    fun `VMess HTTP H2 and HTTPUpgrade carriers retain host and path with native default method`() {
        for (network in listOf("http", "h2", "httpupgrade")) {
            val config = buildJsonObject {
                put("v", "2")
                put("ps", "Demo carrier")
                put("add", "1.1.1.1")
                put("port", 443)
                put("id", "11111111-1111-4111-8111-111111111111")
                put("aid", 0)
                put("scy", "auto")
                put("net", network)
                put("type", "none")
                put("host", "stream.example")
                put("path", "/stream")
                put("tls", "")
            }
            val value = "vmess://" + Base64.getEncoder().encodeToString(config.toString().toByteArray())
            val node = parse(value, "vmess")
            val transport = node.getValue("transport").jsonObject
            assertEquals(
                if (network == "httpupgrade") network else "http",
                transport.getValue("type").jsonPrimitive.content
            )
            assertEquals("/stream", transport.getValue("path").jsonPrimitive.content)
            val host = transport.getValue("host")
            assertEquals(
                "stream.example",
                if (network == "httpupgrade") host.jsonPrimitive.content else host.jsonArray.single().jsonPrimitive.content
            )
            assertFalse(transport.containsKey("method"))
        }
    }

    private fun node(plugin: String) = parse(share(plugin))

    private fun parse(value: String, type: String = "shadowsocks") =
        Json.parseToJsonElement(
            requireNotNull(parser.parseUserInput(value).normalizedConfigJson),
        ).jsonObject.getValue("outbounds").jsonArray.first {
            it.jsonObject["type"]?.jsonPrimitive?.content == type
        }.jsonObject

    private fun share(plugin: String): String {
        val credential =
            Base64.getUrlEncoder().withoutPadding()
                .encodeToString("aes-256-gcm:synthetic-demo-password".toByteArray(Charsets.UTF_8))
        val encodedPlugin = URLEncoder.encode(plugin, "UTF-8").replace("+", "%20")
        return "ss://$credential@1.1.1.1:443/?plugin=$encodedPlugin"
    }
}
