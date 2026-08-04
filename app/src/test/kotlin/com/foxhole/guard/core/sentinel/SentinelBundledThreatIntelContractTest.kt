package com.foxhole.guard.core.sentinel

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What the shipped detector seed has to be.
 *
 * The previous contract only said "no entry contains the word example or placeholder". It passed
 * for months on `{"schema":1,"packages":[],"certs":[]}` — a feed that matches nothing, behind a
 * screen that tells the user their apps were checked against known threats. So the check that
 * matters is the one that was missing: the seed has to actually contain indicators.
 *
 * The word heuristic is gone for a second reason: it is wrong. Six real stalkerware families ship
 * under `com.example.*` because their authors never changed the IDE's default application id, and
 * dropping genuine indicators to satisfy a string match is the wrong trade.
 */
class SentinelBundledThreatIntelContractTest {
    private val packageId = Regex("^[a-z][a-z0-9_]*(\\.[a-z0-9_]+)+$", RegexOption.IGNORE_CASE)
    private val sha1 = Regex("^[0-9a-f]{40}$", RegexOption.IGNORE_CASE)
    private val sha256 = Regex("^[0-9a-f]{64}$", RegexOption.IGNORE_CASE)

    private val document = Json.parseToJsonElement(assetFile().readText()).jsonObject

    private fun values(key: String): List<String> =
        document[key]?.jsonArray.orEmpty().map { element -> element.jsonPrimitive.content }

    @Test
    fun `the seed is the schema the app reads`() {
        assertEquals(2, document.getValue("schema").jsonPrimitive.content.toInt())
    }

    @Test
    fun `the seed actually carries indicators`() {
        // The exact floor is arbitrary; what it buys is that an empty or half-written build of the
        // feed fails here instead of shipping a detector that cannot fire.
        assertTrue("bundled packages: ${values("packages").size}", values("packages").size >= MINIMUM_INDICATORS)
        assertTrue("bundled sha-1 certs: ${values("certsSha1").size}", values("certsSha1").size >= MINIMUM_INDICATORS)
    }

    @Test
    fun `every entry is well formed for the matcher`() {
        values("packages").forEach { value ->
            assertTrue("not an application id: $value", packageId.matches(value))
            // The matcher lower-cases both sides, but a mixed-case seed means the generator changed
            // and nobody looked.
            assertTrue("not lower-cased: $value", value == value.lowercase())
        }
        values("certs").forEach { value ->
            assertTrue("not a SHA-256 digest: $value", sha256.matches(value))
        }
        values("certsSha1").forEach { value ->
            assertTrue("not a SHA-1 digest: $value", sha1.matches(value))
        }
    }

    @Test
    fun `the app never accuses itself`() {
        assertTrue(values("packages").none { value -> value.startsWith("com.foxhole") })
    }

    private fun assetFile(): File =
        listOf(
            File("src/main/assets/sentinel/threat-intel.json"),
            File("app/src/main/assets/sentinel/threat-intel.json"),
            File("../app/src/main/assets/sentinel/threat-intel.json"),
        ).first(File::isFile)

    private companion object {
        const val MINIMUM_INDICATORS = 100
    }
}
