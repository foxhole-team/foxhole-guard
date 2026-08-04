package com.foxhole.guard.ui.cli.profiles

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The structured editor's contract: a field edit touches exactly one leaf of the outbound and every
 * other key — advanced, per-type or unknown — comes back out untouched.
 */
class CliProfileEditorSerializationTest {
    private val json = Json

    @Test
    fun `writing a field keeps unknown and advanced keys`() {
        val outbound = outbound(
            """
            {
              "type": "vless",
              "tag": "node",
              "server": "old.example.com",
              "server_port": 443,
              "uuid": "u-1",
              "multiplex": {"enabled": true, "protocol": "smux"},
              "tls": {"enabled": true, "server_name": "sni.example.com", "min_version": "1.3"}
            }
            """,
        )

        val edited = outbound.writeCliProtoField(field(outbound, "server"), "new.example.com")

        assertEquals("new.example.com", edited.readCliProtoField(field(edited, "server")))
        assertEquals(outbound["multiplex"], edited["multiplex"])
        assertEquals("1.3", edited["tls"]?.jsonObject?.get("min_version")?.toString()?.trim('"'))
        assertEquals("sni.example.com", edited.readCliProtoField(field(edited, "tls.server_name")))
    }

    @Test
    fun `blank value removes the leaf and prunes the container it emptied`() {
        val outbound = outbound("""{"type": "vless", "server": "a.example.com", "obfs": {"type": "salamander"}}""")

        val cleared = outbound.writeCliProtoField(
            CliProtoField("obfs.type", listOf("obfs", "type"), CliProtoKind.TEXT, CliProtoGroup.AUTH),
            "",
        )

        assertNull(cleared["obfs"])
        assertEquals("a.example.com", cleared.readCliProtoField(field(cleared, "server")))
    }

    @Test
    fun `utls and reality carry their enabled flag and vanish when cleared`() {
        val outbound = outbound("""{"type": "vless", "server": "a.example.com", "tls": {"enabled": true}}""")
        val fingerprint = field(outbound, "tls.utls.fingerprint")
        val publicKey = field(outbound, "tls.reality.public_key")

        val enabled = outbound
            .writeCliProtoField(fingerprint, "chrome")
            .writeCliProtoField(publicKey, "pbk-1")

        assertEquals("chrome", enabled.readCliProtoField(fingerprint))
        assertEquals("true", enabled.tlsChild("utls")["enabled"].toString())
        assertEquals("true", enabled.tlsChild("reality")["enabled"].toString())

        val cleared = enabled.writeCliProtoField(fingerprint, "").writeCliProtoField(publicKey, "")

        assertNull(cleared["tls"]?.jsonObject?.get("utls"))
        assertNull(cleared["tls"]?.jsonObject?.get("reality"))
        assertEquals("true", cleared["tls"]?.jsonObject?.get("enabled").toString())
    }

    @Test
    fun `switching the transport type drops the previous variant but keeps unmodeled keys`() {
        val outbound = outbound(
            """
            {
              "type": "vless",
              "server": "a.example.com",
              "transport": {"type": "ws", "path": "/ws", "headers": {"Host": "cdn.example.com"}, "max_early_data": 2048}
            }
            """,
        )

        val grpc = outbound.writeCliTransportType("grpc")

        val transport = grpc["transport"]!!.jsonObject
        assertEquals("grpc", transport["type"].toString().trim('"'))
        assertNull(transport["path"])
        assertNull(transport["headers"])
        assertEquals("2048", transport["max_early_data"].toString())
    }

    @Test
    fun `alpn and boolean fields round-trip through their json shapes`() {
        val outbound = outbound("""{"type": "trojan", "server": "a.example.com", "tls": {"insecure": true}}""")
        val alpn = field(outbound, "tls.alpn")
        val insecure = field(outbound, "tls.insecure")

        assertEquals("true", outbound.readCliProtoField(insecure))
        val withAlpn = outbound.writeCliProtoField(alpn, "h2, http/1.1")

        assertEquals("h2,http/1.1", withAlpn.readCliProtoField(alpn))
        assertEquals("""["h2","http/1.1"]""", withAlpn["tls"]!!.jsonObject["alpn"].toString())
    }

    @Test
    fun `only the fields of the outbound type are offered`() {
        val vless = cliProtoFields("vless", "").map(CliProtoField::label)
        val shadowsocks = cliProtoFields("shadowsocks", "").map(CliProtoField::label)

        assertTrue(vless.containsAll(listOf("server", "server_port", "uuid", "flow", "tls.server_name")))
        assertFalse(vless.contains("method"))
        assertTrue(shadowsocks.containsAll(listOf("method", "password")))
        assertFalse(shadowsocks.any { label -> label.startsWith("tls.") || label.startsWith("transport.") })
    }

    @Test
    fun `deleting a protocol un-references its tag from the selector`() {
        val slot = slot(
            """
            {
              "outbounds": [
                {"type": "vless", "tag": "a", "server": "a.example.com"},
                {"type": "trojan", "tag": "b", "server": "b.example.com"},
                {"type": "selector", "tag": "proxy", "default": "a", "outbounds": ["a", "b"]},
                {"type": "direct", "tag": "direct"}
              ]
            }
            """,
        )

        val trimmed = slot.withoutOutbound(0)

        val outbounds = trimmed.root.cliOutbounds()
        assertEquals(listOf("b", "proxy", "direct"), outbounds.map(JsonObject::cliOutboundTag))
        val selector = outbounds.first { it.cliOutboundType() == "selector" }
        assertEquals("""["b"]""", selector["outbounds"].toString())
        assertEquals("b", selector["default"].toString().trim('"'))
        assertTrue(trimmed.dirty)
    }

    @Test
    fun `a new protocol inherits the profile infrastructure and drops endpoints`() {
        val template = outbound(
            """
            {
              "log": {"level": "warn"},
              "dns": {"final": "dns-remote"},
              "endpoints": [{"type": "wireguard", "tag": "wg"}],
              "outbounds": [
                {"type": "vless", "tag": "a", "server": "a.example.com"},
                {"type": "selector", "tag": "proxy", "default": "a", "outbounds": ["a"]},
                {"type": "direct", "tag": "direct"},
                {"type": "block", "tag": "block"}
              ],
              "route": {"final": "proxy"}
            }
            """,
        )

        val created = cliNewProtocolConfig(template, cliBlankOutbound("tuic"))

        assertNull(created["endpoints"])
        assertEquals(template["dns"], created["dns"])
        assertEquals(template["route"], created["route"])
        assertEquals(
            listOf("tuic", "direct", "block", "proxy"),
            created.cliOutbounds().map(JsonObject::cliOutboundTag),
        )
        val selector = created.cliOutbounds().last()
        assertEquals("tuic", selector["default"].toString().trim('"'))
        assertEquals("""["tuic"]""", selector["outbounds"].toString())
    }

    private fun outbound(raw: String): JsonObject = json.parseToJsonElement(raw.trimIndent()).jsonObject

    private fun slot(raw: String): CliEditorSlot =
        CliEditorSlot(optionId = "vless", label = "vless", enabled = true, root = outbound(raw))

    private fun JsonObject.tlsChild(key: String): JsonObject = this["tls"]!!.jsonObject[key]!!.jsonObject

    private fun field(
        outbound: JsonObject,
        label: String,
    ): CliProtoField =
        cliProtoFields(outbound.cliOutboundType(), outbound.readTransportType())
            .first { it.label == label }

    private fun JsonObject.readTransportType(): String =
        this["transport"]?.jsonObject?.get("type")?.toString()?.trim('"').orEmpty()
}
