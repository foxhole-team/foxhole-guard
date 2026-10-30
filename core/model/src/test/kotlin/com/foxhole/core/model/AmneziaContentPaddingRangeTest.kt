package com.foxhole.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

internal class AmneziaContentPaddingRangeTest {
    @Test
    fun `a singleton preserves the uint32 maximum as a JSON number`() {
        val range = AmneziaContentPaddingRange.parse("4294967295")
        assertEquals(4_294_967_295L, range.lower)
        assertEquals(range.lower, range.upper)
        assertEquals("4294967295", range.normalizedJson().content)
        assertFalse(range.normalizedJson().isString)
    }

    @Test
    fun `inclusive ranges stay exact and equal bounds become a numeric singleton`() {
        val range = AmneziaContentPaddingRange.parse(" 00000-4294967295 ")
        assertEquals(0L, range.lower)
        assertEquals(4_294_967_295L, range.upper)
        assertEquals(JsonPrimitive("0-4294967295"), range.normalizedJson())
        assertEquals(JsonPrimitive(65_536L), AmneziaContentPaddingRange.parse("65536-65536").normalizedJson())
    }

    @Test
    fun `only the all zero range disables the policy`() {
        assertTrue(AmneziaContentPaddingRange.parse("0").disabled)
        assertTrue(AmneziaContentPaddingRange.parse("0-0").disabled)
        assertFalse(AmneziaContentPaddingRange.parse("0-1").disabled)
    }

    @Test
    fun `invalid or unordered ranges are refused without narrowing`() {
        listOf(
            "", " ", "-1", "+1", "1.0", "1e2", "4294967296", "18446744073709551615",
            "2-1", "0-4294967296", "1-", "-1-2", "1-2-3", "1 -2", "١",
        ).forEach { raw ->
            assertThrows(raw, IllegalArgumentException::class.java) { AmneziaContentPaddingRange.parse(raw) }
        }
    }

    @Test
    fun `stored JSON accepts only a numeric singleton or a range string`() {
        assertEquals(
            JsonPrimitive(4_294_967_295L),
            AmneziaContentPaddingRange.parseNormalized(JsonPrimitive(4_294_967_295L)).normalizedJson(),
        )
        assertEquals(
            JsonPrimitive("65536-4294967295"),
            AmneziaContentPaddingRange.parseNormalized(JsonPrimitive("65536-4294967295")).normalizedJson(),
        )
        listOf(
            "\"32\"", "true", "null", "32.0", "1e2", "-1", "4294967296", "[]", "{}", "\" 1-2 \"",
        ).forEach { raw ->
            assertThrows(raw, IllegalArgumentException::class.java) {
                AmneziaContentPaddingRange.parseNormalized(Json.parseToJsonElement(raw))
            }
        }
    }
}
