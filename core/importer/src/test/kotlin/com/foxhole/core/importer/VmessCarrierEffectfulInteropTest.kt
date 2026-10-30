package com.foxhole.core.importer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64

/** VMess share-document carrier flags, with standard synthetic fields only. */
internal class VmessCarrierEffectfulInteropTest {
    private val parser = ProfileImportParser(Json)
    private val networks = listOf("h2", "http", "httpupgrade")

    @Test
    fun `effectful VMess mux is refused before it can disappear from a carrier`() {
        for (network in networks) refused(link(network, mux = true))
    }

    @Test
    fun `unsupported VMess header type is refused instead of becoming ordinary HTTP carrier`() {
        for (network in networks) refused(link(network, type = "http"))
    }

    @Test
    fun `harmless absent false mux and absent empty none header type keep supported carriers`() {
        for (network in networks) {
            for (mux in listOf(null, false)) {
                for (type in listOf(null, "", "none")) {
                    val root = Json.parseToJsonElement(
                        requireNotNull(parser.parseUserInput(link(network, mux, type)).normalizedConfigJson),
                    ).jsonObject
                    val node = root.getValue("outbounds").jsonArray.single {
                        it.jsonObject["type"]?.jsonPrimitive?.content == "vmess"
                    }.jsonObject
                    val transport = node.getValue("transport").jsonObject
                    assertEquals(
                        if (network == "httpupgrade") network else "http",
                        transport.getValue("type").jsonPrimitive.content
                    )
                    assertEquals("/demo", transport.getValue("path").jsonPrimitive.content)
                    assertFalse(transport.containsKey("mux"))
                }
            }
        }
    }

    @Test
    fun `recognized grpc tuning cannot silently disappear on non grpc transports`() {
        for (network in networks + listOf("tcp", "ws")) {
            for ((key, value) in grpcTuning()) {
                refused(withTuning(network, mapOf(key to value)))
            }
        }
    }

    @Test
    fun `valid grpc tuning remains available on its own transport`() {
        for (milliseconds in listOf(true, false)) {
            val fields = grpcTuning().filterKeys { key ->
                if (milliseconds) {
                    key !in setOf("idle_timeout", "health_check_timeout", "ping_timeout")
                } else {
                    key !in setOf("idle_timeout_ms", "health_check_timeout_ms", "ping_timeout")
                }
            }
            val root = Json.parseToJsonElement(
                requireNotNull(parser.parseUserInput(withTuning("grpc", fields)).normalizedConfigJson),
            ).jsonObject
            val node = root.getValue("outbounds").jsonArray.single {
                it.jsonObject["type"]?.jsonPrimitive?.content == "vmess"
            }.jsonObject
            val transport = node.getValue("transport").jsonObject
            assertEquals(JsonPrimitive("grpc"), transport["type"])
            assertEquals(JsonPrimitive("/service/Tun"), transport["rpc_path"])
            assertEquals(JsonPrimitive("Synthetic grpc agent"), transport["user_agent"])
            if (milliseconds) {
                assertEquals(JsonPrimitive(10000), transport["idle_timeout_ms"])
                assertEquals(JsonPrimitive(20000), transport["health_check_timeout_ms"])
            } else {
                assertEquals(JsonPrimitive("10s"), transport["idle_timeout"])
                assertEquals(JsonPrimitive("20s"), transport["health_check_timeout"])
            }
            assertEquals(JsonPrimitive(true), transport["permit_without_stream"])
            assertEquals(JsonPrimitive(65536), transport["initial_windows_size"])
        }
        // The share-document boundary deliberately refuses this JSON-only alias.
        refused(withTuning("grpc", mapOf("ping_timeout" to JsonPrimitive(20))))
        // Milliseconds and raw second aliases cannot coexist in one share document.
        refused(
            withTuning("grpc", mapOf("idle_timeout" to JsonPrimitive(10), "idle_timeout_ms" to JsonPrimitive(10000)))
        )
    }

    private fun grpcTuning(): Map<String, JsonPrimitive> =
        mapOf(
            "rpc_path" to JsonPrimitive("/service/Tun"),
            "user_agent" to JsonPrimitive("Synthetic grpc agent"),
            "idle_timeout" to JsonPrimitive(10),
            "idle_timeout_ms" to JsonPrimitive(10000),
            "health_check_timeout" to JsonPrimitive(20),
            "ping_timeout" to JsonPrimitive(20),
            "health_check_timeout_ms" to JsonPrimitive(20000),
            "permit_without_stream" to JsonPrimitive(true),
            "initial_windows_size" to JsonPrimitive(65536),
        )

    private fun withTuning(network: String, tuning: Map<String, JsonPrimitive>): String {
        val original = Base64.getDecoder().decode(link(network).removePrefix("vmess://"))
        val document = Json.parseToJsonElement(original.toString(Charsets.UTF_8)).jsonObject
        val updated = JsonObject(document + tuning)
        return "vmess://" + Base64.getEncoder().encodeToString(updated.toString().toByteArray(Charsets.UTF_8))
    }

    private fun refused(value: String) {
        val error = assertThrows(IllegalArgumentException::class.java) { parser.parseUserInput(value) }
        assertFalse(error.message.orEmpty().contains("11111111-1111-4111-8111-111111111111"))
        assertFalse(error.message.orEmpty().contains(value))
    }

    private fun link(network: String, mux: Boolean? = null, type: String? = "none"): String {
        val document = buildJsonObject {
            put("v", "2")
            put("ps", "Synthetic carrier")
            put("add", "1.1.1.1")
            put("port", 443)
            put("id", "11111111-1111-4111-8111-111111111111")
            put("aid", 0)
            put("scy", "auto")
            put("net", network)
            put("host", "stream.example")
            put("path", "/demo")
            put("tls", "")
            mux?.let { put("mux", it) }
            type?.let { put("type", it) }
        }
        return "vmess://" + Base64.getEncoder().encodeToString(document.toString().toByteArray(Charsets.UTF_8))
    }
}
