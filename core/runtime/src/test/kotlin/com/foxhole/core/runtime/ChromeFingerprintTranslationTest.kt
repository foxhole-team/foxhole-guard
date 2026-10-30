package com.foxhole.core.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

internal class ChromeFingerprintTranslationTest {
    private val tls =
        Json.parseToJsonElement(
            """
            {
              "enabled": true,
              "server_name": "tls.example",
              "alpn": ["http/1.1"],
              "utls": {"enabled": true, "fingerprint": "chrome"}
            }
            """.trimIndent(),
        ).jsonObject

    @Test
    fun `ordinary Chrome TLS preserves its requested hello and HTTP one ALPN`() {
        for (name in listOf("chrome", "chrome_151")) {
            val utls =
                Json.parseToJsonElement(
                    """{"enabled":true,"fingerprint":"$name"}""",
                )
            val input = JsonObject(tls + ("utls" to utls))
            val result = translateFoxCoreTls(input, "tls", mandatory = false, allowReality = false)
            assertEquals(JsonPrimitive("chrome_151"), result.tls["fingerprint"])
            assertEquals(tls["alpn"], result.tls["alpn"])
            assertEquals(tls["server_name"], result.tls["server_name"])
            assertEquals(null, result.reality)
        }
    }

    @Test
    fun `a requested Chrome fingerprint cannot disappear when enabled uses its default`() {
        val utls = Json.parseToJsonElement("""{"fingerprint":"chrome"}""")
        val input = JsonObject(tls + ("utls" to utls))
        val result = translateFoxCoreTls(input, "tls", mandatory = false, allowReality = false)
        assertEquals(JsonPrimitive("chrome_151"), result.tls["fingerprint"])
    }

    @Test
    fun `unsupported ordinary TLS fingerprints are refused instead of substituted or dropped`() {
        for (name in listOf("qq", "firefox", "random", "unknown")) {
            val utls =
                Json.parseToJsonElement(
                    """{"enabled":true,"fingerprint":"$name"}""",
                )
            val input = JsonObject(tls + ("utls" to utls))
            val error = assertThrows(FoxCoreConfigTranslationException::class.java) {
                translateFoxCoreTls(input, "tls", mandatory = false, allowReality = false)
            }
            assertEquals(FoxCoreConfigRejection.UNSUPPORTED_SECURITY, error.rejection)
        }
        val input = JsonObject(tls - "utls")
        val result =
            translateFoxCoreTls(
                input,
                "tls",
                mandatory = false,
                allowReality = false,
            )
        assertFalse(result.tls.containsKey("fingerprint"))
    }
}
