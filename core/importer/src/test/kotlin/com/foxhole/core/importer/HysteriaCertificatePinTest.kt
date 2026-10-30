package com.foxhole.core.importer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class HysteriaCertificatePinTest {
    private val parser = ProfileImportParser(Json { ignoreUnknownKeys = false })

    @Test fun leafFingerprintIsKeptSeparateFromSpkiAndExportsAsPortableHex() {
        val pin = "07".repeat(32)
        val parsed = parser.parseUserInput("hysteria2://synthetic@1.1.1.1:443?pinSHA256=$pin")
        val root = Json.parseToJsonElement(parsed.normalizedConfigJson!!).jsonObject
        val tls = root["outbounds"]!!.jsonArray.first().jsonObject["tls"]!!.jsonObject
        assertEquals(
            Base64.getEncoder().encodeToString(ByteArray(32) { 7 }),
            tls["certificate_sha256"]!!.jsonArray.single().jsonPrimitive.content
        )
        assertFalse(tls.containsKey("certificate_public_key_sha256"))
        val copied = exportPortableConfigs("Home", listOf(root.toString()))
        assertTrue(copied.contains("pinSHA256=$pin"))
        val restored = Json.parseToJsonElement(parser.parseUserInput(copied).normalizedConfigJson!!).jsonObject
        assertEquals(tls, restored["outbounds"]!!.jsonArray.first().jsonObject["tls"]!!.jsonObject)
    }
}
