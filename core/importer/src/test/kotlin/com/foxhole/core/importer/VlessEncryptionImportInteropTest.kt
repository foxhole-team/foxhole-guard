package com.foxhole.core.importer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

/** Public Core049 encryption grammar and synthetic 0..31 public key bytes only. */
internal class VlessEncryptionImportInteropTest {
    private val parser = ProfileImportParser(Json)
    private val key = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })
    private val base = "vless://11111111-1111-4111-8111-111111111111@1.1.1.1:443?type=tcp&security=none"

    @Test
    fun `existing native encryption variants survive URI normalization`() {
        for (mode in listOf("native", "xorpub", "random")) {
            for (rtt in listOf("0rtt", "1rtt")) {
                val encryption = "mlkem768x25519plus.$mode.$rtt.$key"
                assertEquals(
                    encryption,
                    node("$base&encryption=$encryption#Demo").getValue("encryption").jsonPrimitive.content
                )
            }
        }
    }

    @Test
    fun `none and absent encryption keep the ordinary VLESS profile`() {
        assertFalse(node("$base#Demo").containsKey("encryption"))
        assertFalse(node("$base&encryption=none#Demo").containsKey("encryption"))
    }

    @Test
    fun `encrypted Vision remains an explicit unsupported native combination`() {
        val encryption = "mlkem768x25519plus.native.1rtt.$key"
        assertThrows(IllegalArgumentException::class.java) {
            parser.parseUserInput("$base&encryption=$encryption&flow=xtls-rprx-vision#Demo")
        }
    }

    @Test
    fun `native padding relay and ML KEM public key grammar remains intact`() {
        val mlkem = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(1184) { 7 })
        for (tail in listOf("100-35-35.$key", "$key.$mlkem", mlkem)) {
            val value = "mlkem768x25519plus.native.1rtt.$tail"
            assertEquals(value, node("$base&encryption=$value").getValue("encryption").jsonPrimitive.content)
        }
    }

    @Test
    fun `malformed encryption is refused without reflecting key or credentials`() {
        for (
        value in listOf(
            "unknown.native.1rtt.$key",
            "mlkem768x25519plus.unknown.1rtt.$key",
            "mlkem768x25519plus.native.invalid.$key",
            "mlkem768x25519plus.native.1rtt",
            "mlkem768x25519plus.native.1rtt.bad",
            "mlkem768x25519plus.native.1rtt.$key=",
            "mlkem768x25519plus.native.1rtt.${key.dropLast(1)}9",
            "mlkem768x25519plus.native.1rtt.99-35-35.$key",
            "mlkem768x25519plus.native.1rtt.100-34-35.$key",
            "mlkem768x25519plus.native.1rtt.100-65554-65554.$key",
            "mlkem768x25519plus.native.1rtt.$key.100-35-35",
        )
        ) {
            val error =
                assertThrows(IllegalArgumentException::class.java) { parser.parseUserInput("$base&encryption=$value") }
            assertFalse(error.message.orEmpty().contains(key))
            assertFalse(error.message.orEmpty().contains("11111111-1111-4111-8111-111111111111"))
        }
    }

    private fun node(value: String) = Json.parseToJsonElement(
        requireNotNull(parser.parseUserInput(value).normalizedConfigJson),
    ).jsonObject.getValue("outbounds").jsonArray.first {
        it.jsonObject["type"]?.jsonPrimitive?.content == "vless"
    }.jsonObject
}
