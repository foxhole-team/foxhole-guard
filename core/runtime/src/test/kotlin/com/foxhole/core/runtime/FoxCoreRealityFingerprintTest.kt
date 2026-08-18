package com.foxhole.core.runtime

import com.foxhole.core.model.ProtocolHint
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

internal class FoxCoreRealityFingerprintTest : FoxCoreConfigTranslatorTestSupport() {
    @Test
    fun `every implemented parrot reaches the core config under its own name`() {
        val expected =
            mapOf(
                "chrome" to "chrome_151",
                "chrome_151" to "chrome_151",
                "chrome_133" to "chrome_133",
                "chrome_131" to "chrome_131",
                "edge" to "edge_85",
                "edge_85" to "edge_85",
                "safari" to "safari_26_3",
                "safari_26_3" to "safari_26_3",
                "ios" to "ios_14",
                "ios_14" to "ios_14",
                "qq" to "qq_11_1",
                "qq_11_1" to "qq_11_1",
                "firefox" to "firefox_153",
                "firefox_153" to "firefox_153",
                "firefox_148" to "firefox_148",
                "randomized" to "randomized",
            )
        for ((requested, profile) in expected) {
            assertEquals(
                "fp=$requested must reach the core as $profile",
                profile,
                realityFingerprintFor(requested),
            )
        }
    }

    @Test
    fun `a firefox profile does not reach the core as chrome`() {
        val firefox = realityFingerprintFor("firefox")
        assertNotEquals("chrome_151", firefox)
        assertNotEquals("chrome", firefox)
        assertEquals("firefox_153", firefox)
        assertNotEquals(firefox, realityFingerprintFor("chrome"))
    }

    @Test
    fun `a bare browser name does not resolve to a superseded build`() {
        for (superseded in listOf("chrome_133", "chrome_131", "firefox_148")) {
            assertNotEquals(superseded, realityFingerprintFor(superseded.substringBefore('_')))
        }
    }

    @Test
    fun `random resolves to one modern parrot and keeps it`() {
        val first = realityFingerprintFor("random")
        assertTrue(
            first,
            first in setOf("chrome_151", "firefox_153", "edge_85", "safari_26_3", "ios_14"),
        )
        repeat(4) { assertEquals(first, realityFingerprintFor("random")) }
    }

    @Test
    fun `a parrot the core cannot write is refused rather than substituted`() {
        for (requested in listOf("360", "android", "hellogolang", "chrome_999")) {
            val error =
                assertThrows(
                    "fp=$requested must be refused",
                    FoxCoreConfigTranslationException::class.java,
                ) {
                    realityFingerprintFor(requested)
                }
            assertEquals(FoxCoreConfigRejection.UNSUPPORTED_SECURITY, error.rejection)
        }
    }

    private fun realityFingerprintFor(requested: String): String {
        val translated = translate(ProtocolHint.VLESS, realityVless(requested))
        val outbound =
            engine(translated)
                .getValue("outbound")
                .jsonObject
                .getValue("members")
                .jsonArray
                .single()
                .jsonObject
                .getValue("outbound")
                .jsonObject
        return outbound
            .getValue("reality")
            .jsonObject
            .getValue("fingerprint")
            .jsonPrimitive
            .content
    }

    private fun realityVless(fingerprint: String): JsonObject =
        primary("vless") {
            put("server", "203.0.113.10")
            put("server_port", 443)
            put("uuid", CONTRACT_UUID)
            put(
                "tls",
                tls {
                    put(
                        "utls",
                        buildJsonObject {
                            put("enabled", true)
                            put("fingerprint", fingerprint)
                        },
                    )
                    put(
                        "reality",
                        buildJsonObject {
                            put("enabled", true)
                            put("public_key", REALITY_PUBLIC_KEY)
                            put("short_id", "0000000000000001")
                        },
                    )
                },
            )
        }

    private companion object {
        const val CONTRACT_UUID = "d0cf0001-0000-4000-8000-000000000000"
        const val REALITY_PUBLIC_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA01"
    }
}
