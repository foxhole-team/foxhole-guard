package com.foxhole.guard.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CategorizedDnsManifestTest {
    private val fixture = """{"schema":3,"name":"foxhole-dns-categories-normal-medium","format":"foxhole-dns-fst-v1","sequence":1,"generated_at_unix":1000,"expires_at_unix":2000,"key_sha256":"${"a".repeat(
        64
    )}","categories":["ads"],"tiers":{"trackers":"normal","malicious":"medium"},"tagged_input_sha256":"${"b".repeat(
        64
    )}","sources":[{"id":"owned","repo":"https://example.invalid/owned","commit":"${"c".repeat(
        40
    )}","license":"CC0-1.0","inputs":[{"path":"owned.txt","sha256":"${"d".repeat(
        64
    )}"}],"categories":["ads"]}],"artifact":{"file":"dns-categories.fhds","size":100,"sha256":"${"e".repeat(
        64
    )}","block_entries":1,"allow_entries":0,"category_entries":{"malicious":0,"telemetry":0,"trackers":0,"ads":1}},"compatibility":{"core_schema":1,"category_schema":1}}"""

    @Test
    fun validClosedCanonicalManifestPreservesVariantAndAvailability() {
        val manifest = parse(bytes())
        assertEquals("normal-medium", manifest.tiers.variant())
        assertEquals(listOf("ads"), manifest.categories)
        assertEquals(1uL, manifest.sequence)
    }

    @Test
    fun unknownFieldsAtRootAndNestedObjectsAreRefused() {
        listOf(
            fixture.replace("\"schema\":3", "\"unknown\":true,\"schema\":3"),
            fixture.replace("\"core_schema\":1", "\"unknown\":1,\"core_schema\":1")
        ).forEach { input ->
            assertThrows(IllegalArgumentException::class.java) { parse(bytes(input)) }
        }
    }

    @Test
    fun duplicateKeysAndNonCanonicalWhitespaceAreRefused() {
        val canonical = bytes().decodeToString()
        assertThrows(IllegalArgumentException::class.java) { parse((canonical + "\n").encodeToByteArray()) }
        assertThrows(IllegalArgumentException::class.java) {
            parse(canonical.replace("\"schema\":3", "\"schema\":3,\"schema\":3").encodeToByteArray())
        }
    }

    @Test
    fun numericStringsFloatsAndExponentsAreRefused() {
        listOf("\"1\"", "1.0", "1e0", "-1", "18446744073709551616").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                parse(bytes(fixture.replace("\"sequence\":1", "\"sequence\":$value")))
            }
        }
    }

    @Test
    fun fullUnsignedSequenceHasNoFloatOrSignedWrapping() {
        assertEquals(
            ULong.MAX_VALUE,
            parse(bytes(fixture.replace("\"sequence\":1", "\"sequence\":18446744073709551615"))).sequence
        )
    }

    @Test
    fun tierNameMismatchAndUnknownTierAreRefused() {
        listOf(
            fixture.replace("normal-medium", "pro-medium"),
            fixture.replace("\"trackers\":\"normal\"", "\"trackers\":\"unknown\"")
        )
            .forEach { input -> assertThrows(IllegalArgumentException::class.java) { parse(bytes(input)) } }
    }

    @Test
    fun unknownDuplicateCategoriesAndFalseCountsAreRefused() {
        listOf(
            fixture.replace("[\"ads\"]", "[\"ads\",\"ads\"]"),
            fixture.replace("[\"ads\"]", "[\"unknown\"]"),
            fixture.replace("\"ads\":1", "\"ads\":0"),
            fixture.replace("\"ads\":1", "\"ads\":2")
        ).forEach { input ->
            assertThrows(IllegalArgumentException::class.java) { parse(bytes(input)) }
        }
    }

    @Test
    fun expiredFutureAndWrongPinnedKeyAreRefused() {
        assertThrows(
            IllegalArgumentException::class.java
        ) { CategorizedDnsContract.parseVerifiedManifest(bytes(), 2000uL, "a".repeat(64)) }
        assertThrows(
            IllegalArgumentException::class.java
        ) { CategorizedDnsContract.parseVerifiedManifest(bytes(), 300uL, "a".repeat(64)) }
        assertThrows(
            IllegalArgumentException::class.java
        ) { CategorizedDnsContract.parseVerifiedManifest(bytes(), 1500uL, "0".repeat(64)) }
    }

    @Test
    fun escapedUnicodeMetadataCannotPassAsciiContract() {
        assertThrows(IllegalArgumentException::class.java) { parse(bytes(fixture.replace("CC0-1.0", "caf\\u00e9"))) }
    }

    @Test
    fun canonicalGoldenEscapesAndUnsignedBoundaryMatchPython() {
        val element = JsonObject(
            mapOf(
                "integer" to JsonPrimitive(ULong.MAX_VALUE),
                "escaped" to JsonPrimitive("quote\"backslash\\"),
            )
        )
        assertEquals(
            "{\"escaped\":\"quote\\\"backslash\\\\\",\"integer\":18446744073709551615}\n",
            canonicalAsciiJson(element) + "\n"
        )
    }

    private fun bytes(text: String = fixture): ByteArray =
        (canonicalAsciiJson(Json.parseToJsonElement(text).jsonObject) + "\n").encodeToByteArray()

    private fun parse(bytes: ByteArray) = CategorizedDnsContract.parseVerifiedManifest(bytes, 1500uL, "a".repeat(64))
}
