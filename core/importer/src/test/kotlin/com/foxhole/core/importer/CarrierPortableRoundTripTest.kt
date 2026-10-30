package com.foxhole.core.importer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.URLEncoder
import java.util.Base64

/** Standard synthetic profiles only; no private server implementation fixture. */
internal class CarrierPortableRoundTripTest {
    private val parser = ProfileImportParser(Json)

    @Test
    fun `copy and import preserve encrypted VLESS with WS TLS`() {
        val encryption = "mlkem768x25519plus.random.0rtt.AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
        val uri = "vless://11111111-1111-4111-8111-111111111111@1.1.1.1:443" +
            "?type=ws&security=tls&sni=stream.example&host=stream.example&path=%2Fdemo&encryption=$encryption"
        assertRoundTrip(uri, "vless")
    }

    @Test
    fun `copy and import preserve SIP003 TCP WS TLS fields without plugin execution`() {
        val credentials =
            Base64.getUrlEncoder().withoutPadding()
                .encodeToString("aes-256-gcm:synthetic-demo-password".toByteArray(Charsets.UTF_8))
        val plugin = URLEncoder.encode("v2ray-plugin;mux=0;tls;host=plugin.example;path=/a\\;b\\=c", "UTF-8")
        assertRoundTrip("ss://$credentials@1.1.1.1:443/?plugin=$plugin", "shadowsocks")
    }

    @Test
    fun `JSON copy fallback cannot enable Vision or accept malformed encryption`() {
        val base = parse("vless://11111111-1111-4111-8111-111111111111@1.1.1.1:443?security=none")
        val value = "mlkem768x25519plus.native.1rtt.AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
        for (extra in listOf(
            mapOf("encryption" to JsonPrimitive("invalid")),
            mapOf("encryption" to JsonPrimitive(value), "flow" to JsonPrimitive("xtls-rprx-vision")),
            mapOf("encryption" to JsonPrimitive(value), "unknown_effectful" to JsonPrimitive(true)),
            mapOf(
                "encryption" to JsonPrimitive("invalid"),
                "transport" to JsonObject(mapOf("type" to JsonPrimitive("grpc"), "user_agent" to JsonPrimitive("Synthetic"))),
            ),
        )) {
            refusedCopy(mutateNode(base, "vless") { JsonObject(it + extra) })
        }
    }

    @Test
    fun `SIP003 JSON fallback keeps unsupported fields UDP and TLS weakening refused`() {
        val credential = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("aes-256-gcm:synthetic-demo-password".toByteArray(Charsets.UTF_8))
        val plugin = URLEncoder.encode("v2ray-plugin;mux=0;tls;host=plugin.example;path=/demo", "UTF-8")
        val base = parse("ss://$credential@1.1.1.1:443/?plugin=$plugin")
        refusedCopy(mutateNode(base, "shadowsocks") { JsonObject(it + ("network" to JsonPrimitive("tcp,udp"))) })
        refusedCopy(mutateNode(base, "shadowsocks") { JsonObject(it + ("mux" to JsonPrimitive(true))) })
        refusedCopy(
            mutateNode(base, "shadowsocks") {
                JsonObject(
                    it + ("tls" to JsonObject(it.getValue("tls").jsonObject + ("insecure" to JsonPrimitive(true))))
                )
            }
        )
        refusedCopy(
            mutateNode(base, "shadowsocks") {
                JsonObject(
                    it + ("transport" to JsonObject(it.getValue("transport").jsonObject + ("unknown_effectful" to JsonPrimitive(true))))
                )
            }
        )
    }

    private fun parse(value: String): JsonObject =
        Json.parseToJsonElement(requireNotNull(parser.parseUserInput(value).normalizedConfigJson)).jsonObject

    private fun refusedCopy(root: JsonObject) {
        assertThrows(PortableProfileExportFailure::class.java) {
            exportPortableConfigs("Synthetic copy", listOf(root.toString()))
        }
    }

    private fun mutateNode(root: JsonObject, type: String, mutate: (JsonObject) -> JsonObject): JsonObject =
        JsonObject(
            root + (
                "outbounds" to JsonArray(
                    root.getValue("outbounds").jsonArray.map { element ->
                        val node = element.jsonObject
                        if (node["type"]?.jsonPrimitive?.content == type) mutate(node) else node
                    }
                )
                )
        )

    private fun assertRoundTrip(uri: String, type: String) {
        val original = requireNotNull(parser.parseUserInput(uri).normalizedConfigJson)
        val copied = exportPortableConfigs("Synthetic copy", listOf(original))
        val imported = requireNotNull(parser.parseUserInput(copied).normalizedConfigJson)
        assertEquals(selectedFields(original, type), selectedFields(imported, type))
    }

    private fun selectedFields(config: String, type: String): JsonObject {
        val root = Json.parseToJsonElement(config).jsonObject
        val node = root.getValue("outbounds").jsonArray.single {
            it.jsonObject["type"]?.jsonPrimitive?.content == type
        }.jsonObject
        return JsonObject(node - "tag")
    }
}
