package com.foxhole.core.importer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.InetAddress
import java.util.Base64

internal class HappTransportImportTest {
    private val parser = ProfileImportParser(Json, remoteHostResolver = { listOf(InetAddress.getByName("8.8.8.8")) })
    private val publicKey = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { (it + 1).toByte() })
    private val reality = "security=reality&sni=reality.example&pbk=$publicKey&sid=aabb"
    private val base = "vless://11111111-1111-4111-8111-111111111111@edge.example:443?"

    @Test
    fun `Happ synthetic twenty two node subscription preserves every format`() {
        val kemKey = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(1184) { 7 })
        val lines = buildList {
            repeat(15) { add("${base.replace("edge.example", "tcp$it.example")}$reality&type=tcp&encryption=none&flow=xtls-rprx-vision&fp=qq#tcp$it") }
            add("$base$reality&type=tcp&flow=xtls-rprx-vision&fp=chrome&headerType=none#tcpchrome")
            repeat(4) { add("${base.replace("edge.example", "grpc$it.example")}$reality&type=grpc&mode=gun&serviceName=svc&encryption=none&fp=${if (it == 3) "firefox" else "qq"}#grpc$it") }
            add("$base$reality&type=xhttp&mode=auto&encryption=none&fp=qq#xhttp")
            add("${base}type=ws&security=tls&sni=tls.example&host=front.example&path=%2Fws&alpn=http%2F1.1&fp=chrome&encryption=mlkem768x25519plus.native.0rtt.$publicKey.$kemKey#encryptedws")
        }
        val result = parser.parseSubscriptionProfiles(lines.joinToString("\n"), "synthetic")
        assertEquals(22, result.profiles.size)
        val outbounds = result.profiles.map { profile ->
            Json.parseToJsonElement(profile.normalizedConfigJson).jsonObject.getValue("outbounds").jsonArray
                .first { it.jsonObject["type"]?.jsonPrimitive?.content == "vless" }.jsonObject
        }
        assertEquals(16, outbounds.count { it["flow"]?.jsonPrimitive?.content == "xtls-rprx-vision" })
        val xhttp = outbounds.single { it["transport"]?.jsonObject?.get("type")?.jsonPrimitive?.content == "xhttp" }
        assertEquals("stream-one", xhttp.getValue("transport").jsonObject.getValue("mode").jsonPrimitive.content)
        assertEquals("/", xhttp.getValue("transport").jsonObject.getValue("path").jsonPrimitive.content)
        assertEquals(4, outbounds.count { it["transport"]?.jsonObject?.get("type")?.jsonPrimitive?.content == "grpc" })
        assertEquals(1, outbounds.count { it.containsKey("encryption") })
    }

    @Test
    fun `XHTTP modes and extra settings fail closed when the native carrier cannot implement them`() {
        for (suffix in listOf("mode=packet-up", "mode=stream-up", "mode=auto&extra=%7B%7D")) {
            assertThrows(IllegalArgumentException::class.java) {
                parser.parseUserInput("$base$reality&type=xhttp&fp=qq&$suffix")
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            parser.parseUserInput("${base}type=xhttp&security=tls&mode=auto")
        }
    }
}
