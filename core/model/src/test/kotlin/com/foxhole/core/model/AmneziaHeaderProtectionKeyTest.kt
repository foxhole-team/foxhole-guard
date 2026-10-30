package com.foxhole.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

internal class AmneziaHeaderProtectionKeyTest {
    @Test
    fun `valid keys round trip with redacted debug and zero detection`() {
        val key = AmneziaHeaderProtectionKey.parse(KEY)
        assertEquals(JsonPrimitive(KEY), key.normalizedJson())
        assertTrue(key.enabled)
        assertFalse(key.toString().contains(KEY))
        assertFalse(AmneziaHeaderProtectionKey.parse(ZERO).enabled)
        assertEquals(JsonPrimitive(KEY), AmneziaHeaderProtectionKey.parse(" $KEY ").normalizedJson())
    }

    @Test
    fun `canonical standard base64 and stored string shape are required`() {
        listOf(
            "",
            "AQID",
            KEY.dropLast(1),
            KEY.replace("yA=", "yB="),
            "00".repeat(32),
            KEY.replace('A', '_'),
        ).forEach { raw ->
            val error = assertThrows(IllegalArgumentException::class.java) { AmneziaHeaderProtectionKey.parse(raw) }
            if (raw.isNotEmpty()) assertFalse(error.message.orEmpty().contains(raw))
        }
        listOf("null", "42", "true", "[]", "{}", "\" $KEY \"").forEach { raw ->
            assertThrows(IllegalArgumentException::class.java) {
                AmneziaHeaderProtectionKey.parseNormalized(Json.parseToJsonElement(raw))
            }
        }
        assertEquals(
            JsonPrimitive(KEY),
            AmneziaHeaderProtectionKey.parseNormalized(JsonPrimitive(KEY)).normalizedJson(),
        )
    }

    @Test
    fun `nonzero keys need four nonce prefixes of at least twelve bytes`() {
        val key = AmneziaHeaderProtectionKey.parse(KEY)
        key.requireNoncePrefixes(12, 12, 12, 12)
        repeat(4) { index ->
            val sizes = intArrayOf(12, 12, 12, 12)
            sizes[index] = 11
            assertThrows(IllegalArgumentException::class.java) { key.requireNoncePrefixes(*sizes) }
        }
        AmneziaHeaderProtectionKey.parse(ZERO).requireNoncePrefixes(0, 0, 0, 0)
    }

    private companion object {
        const val KEY = "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA="
        const val ZERO = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    }
}
