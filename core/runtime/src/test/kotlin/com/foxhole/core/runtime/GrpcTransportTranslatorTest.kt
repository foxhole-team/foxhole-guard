package com.foxhole.core.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

internal class GrpcTransportTranslatorTest {
    @Test
    fun `empty service name is the valid grpc default`() {
        val source = Json.parseToJsonElement("""{"type":"grpc","service_name":""}""").jsonObject
        assertEquals(JsonPrimitive(""), translateFoxCoreTransport(source, "transport")["service_name"])
    }

    @Test
    fun `grpc authority mode and full service path stay independent`() {
        val source =
            Json.parseToJsonElement(
                """{"type":"grpc","service_name":"/package.Service/Call","multi_mode":true,"authority":"rpc.example:8443"}""",
            ).jsonObject
        assertEquals(source, translateFoxCoreTransport(source, "transport"))
    }

    @Test
    fun `subsecond sing box durations become exact milliseconds without truncation`() {
        val source =
            Json.parseToJsonElement(
                """{"type":"grpc","service_name":"svc","idle_timeout":"1.5s","ping_timeout":"2500ms","permit_without_stream":false}""",
            ).jsonObject
        val value = translateFoxCoreTransport(source, "transport")
        assertEquals(JsonPrimitive(1500), value["idle_timeout_ms"])
        assertEquals(JsonPrimitive(2500), value["health_check_timeout_ms"])
        assertEquals(JsonPrimitive(false), value["permit_without_stream"])
    }

    @Test
    fun `unknown effectful grpc field is refused`() {
        val source =
            Json.parseToJsonElement(
                """{"type":"grpc","service_name":"svc","unknown_grpc_field":true}""",
            ).jsonObject
        assertThrows(RuntimeException::class.java) { translateFoxCoreTransport(source, "transport") }
    }

    @Test
    fun `malformed grpc mode is refused instead of becoming false`() {
        val source = Json.parseToJsonElement("""{"type":"grpc","service_name":"svc","multi_mode":"maybe"}""").jsonObject
        assertThrows(RuntimeException::class.java) { translateFoxCoreTransport(source, "transport") }
    }

    @Test
    fun `complete grpc tuning translates to typed canonical milliseconds`() {
        val source =
            Json.parseToJsonElement(
                """{"type":"grpc","service_name":"svc","rpc_path":"/svc%2Fname/Tun","multi_mode":true,"authority":"rpc.example:8443","user_agent":"chrome","idle_timeout":"1m30.5s","health_check_timeout":"1000us","permit_without_stream":true,"initial_windows_size":131072}""",
            ).jsonObject
        val result = translateFoxCoreTransport(source, "transport")
        val expected =
            Json.parseToJsonElement(
                """{"type":"grpc","service_name":"svc","rpc_path":"/svc%2Fname/Tun","multi_mode":true,"authority":"rpc.example:8443","user_agent":"chrome","idle_timeout_ms":90500,"health_check_timeout_ms":1,"permit_without_stream":true,"initial_windows_size":131072}""",
            ).jsonObject
        assertEquals(expected, result)
    }

    @Test
    fun `timeout aliases and unsupported precision cannot hide effectful differences`() {
        for (fields in listOf(
            "\"idle_timeout\":\"1s\",\"idle_timeout_ms\":1000",
            "\"health_check_timeout\":\"1s\",\"ping_timeout\":\"2s\"",
            "\"idle_timeout\":\"1ns\""
        )) {
            val source = Json.parseToJsonElement("{\"type\":\"grpc\",\"service_name\":\"svc\",$fields}").jsonObject
            assertThrows(RuntimeException::class.java) { translateFoxCoreTransport(source, "transport") }
        }
    }

    @Test
    fun `RPC overrides reject actual controls spaces queries and fragments`() {
        for (rpc in listOf("/svc\n/Tun", "/svc /Tun", "/svc/Tun?x=1", "/svc/Tun#fragment")) {
            val source = kotlinx.serialization.json.buildJsonObject {
                put("type", JsonPrimitive("grpc"))
                put("service_name", JsonPrimitive("svc"))
                put("rpc_path", JsonPrimitive(rpc))
            }
            assertThrows(RuntimeException::class.java) { translateFoxCoreTransport(source, "transport") }
        }
    }

    @Test
    fun `user agent UTF8 is retained but controls and byte overflow are refused`() {
        val source =
            Json.parseToJsonElement(
                """{"type":"grpc","service_name":"svc","user_agent":"Пример/1"}""",
            ).jsonObject
        assertEquals(source, translateFoxCoreTransport(source, "transport"))
        for (agent in listOf("bad\nagent", "x".repeat(1025))) {
            val bad = kotlinx.serialization.json.JsonObject(source + ("user_agent" to JsonPrimitive(agent)))
            assertThrows(RuntimeException::class.java) { translateFoxCoreTransport(bad, "transport") }
        }
    }

    @Test
    fun `Unicode service metadata reaches Core without form encoding`() {
        val source = Json.parseToJsonElement("""{"type":"grpc","service_name":"服务+~*:one@two"}""").jsonObject
        assertEquals(source, translateFoxCoreTransport(source, "transport"))
    }

    @Test
    fun `service names refuse C0 and C1 controls but user agent remains opaque UTF8`() {
        for (control in listOf('\n', '\u007f', '\u0085', '\u009f')) {
            val bad = kotlinx.serialization.json.buildJsonObject {
                put("type", JsonPrimitive("grpc"))
                put("service_name", JsonPrimitive("bad${control}service"))
            }
            assertThrows(RuntimeException::class.java) { translateFoxCoreTransport(bad, "transport") }
        }
        val agent =
            Json.parseToJsonElement(
                """{"type":"grpc","service_name":"svc","user_agent":"opaque\u0085value"}""",
            ).jsonObject
        assertEquals(agent, translateFoxCoreTransport(agent, "transport"))
    }

    @Test
    fun `raw UTF8 and percent RPC path bytes are retained literally`() {
        val source =
            Json.parseToJsonElement(
                """{"type":"grpc","service_name":"original","rpc_path":"/名字%raw%2Fservice/Tun"}""",
            ).jsonObject
        assertEquals(source, translateFoxCoreTransport(source, "transport"))
    }
}
