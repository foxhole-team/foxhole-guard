package com.foxhole.core.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

internal class XhttpTransportTranslatorTest {
    @Test
    fun `REALITY XHTTP preserves authentication and selects stream one for auto`() {
        val source = Json.parseToJsonElement("""{"type":"vless","server":"edge.example","server_port":443,"uuid":"11111111-1111-4111-8111-111111111111","tls":{"enabled":true,"server_name":"reality.example","utls":{"enabled":true,"fingerprint":"qq"},"reality":{"enabled":true,"public_key":"AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE","short_id":"aabb"}},"transport":{"type":"xhttp","mode":"auto","path":"/"}}""").jsonObject
        val native = translateFoxCoreProxyOutbound(source, "outbound")
        assertEquals(JsonPrimitive("stream-one"), native.getValue("transport").jsonObject["mode"])
        assertEquals(JsonPrimitive("xhttp"), native.getValue("transport").jsonObject["type"])
        assertEquals(JsonPrimitive("reality.example"), native.getValue("reality").jsonObject["server_name"])
        assertEquals(JsonPrimitive("qq_11_1"), native.getValue("reality").jsonObject["fingerprint"])
        assertEquals(JsonPrimitive(false), native.getValue("tls").jsonObject["enabled"])
    }

    @Test
    fun `XHTTP supports only bounded stream one fields`() {
        for (fields in listOf("\"mode\":\"packet-up\"", "\"mode\":\"stream-up\"", "\"mode\":\"auto\"", "\"mode\":\"stream-one\",\"extra\":{}", "\"mode\":\"stream-one\",\"path\":\"/bad\\r\\nInjected\"")) {
            val source = Json.parseToJsonElement("{\"type\":\"xhttp\",$fields}").jsonObject
            assertThrows(FoxCoreConfigTranslationException::class.java) { translateFoxCoreTransport(source, "transport") }
        }
        val source = Json.parseToJsonElement("""{"type":"xhttp","mode":"stream-one","path":"/a?x=1","host":"front.example"}""").jsonObject
        assertEquals(source, translateFoxCoreTransport(source, "transport"))
    }
}
