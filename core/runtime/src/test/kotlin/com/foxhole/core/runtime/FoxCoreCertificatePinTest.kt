package com.foxhole.core.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FoxCoreCertificatePinTest {
    @Test fun certificateAndSpkiPinsUseSeparateNativeFields() {
        for ((source, target) in listOf(
            "certificate_sha256" to "pinned_certificate_sha256",
            "certificate_public_key_sha256" to "pinned_spki_sha256"
        )) {
            val tls = Json.parseToJsonElement("{\"enabled\":true,\"$source\":[\"synthetic-pin\"]}").jsonObject
            val result = translateFoxCoreTls(tls, "tls", mandatory = true, allowReality = false).tls
            assertEquals("synthetic-pin", result[target]!!.jsonPrimitive.content)
        }
    }

    @Test fun twoPinKindsAndMultipleLeafPinsAreRejectedBeforeNativeStart() {
        for (body in listOf(
            "{\"enabled\":true,\"certificate_sha256\":[\"first\",\"second\"]}",
            "{\"enabled\":true,\"certificate_sha256\":[\"leaf\"],\"certificate_public_key_sha256\":[\"spki\"]}",
        )) assertThrows(RuntimeException::class.java) {
            translateFoxCoreTls(Json.parseToJsonElement(body).jsonObject, "tls", mandatory = true, allowReality = false)
        }
    }
}
