package com.foxhole.guard.core.sentinel

import com.foxhole.core.model.ThreatIndicatorKind
import com.foxhole.core.model.ThreatIntelDocument
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SentinelBundledThreatIntelContractTest {
    private val packageId = Regex("^[a-z][a-z0-9_]*(\\.[a-z0-9_]+)+$", RegexOption.IGNORE_CASE)
    private val sha1 = Regex("^[0-9a-f]{40}$", RegexOption.IGNORE_CASE)
    private val sha256 = Regex("^[0-9a-f]{64}$", RegexOption.IGNORE_CASE)
    private val hostname = Regex("^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+$")
    private val ipv4 = Regex("^(?:\\d{1,3}\\.){3}\\d{1,3}$")
    private val ipv6 = Regex("^[0-9a-f:]{3,45}$")

    private val document = Json.parseToJsonElement(assetFile().readText()).jsonObject

    private fun values(key: String): List<String> =
        document[key]?.jsonArray.orEmpty().map { element -> element.jsonPrimitive.content }

    private fun indicatorKinds(): Map<String, String> =
        document["indicatorKinds"]
            ?.jsonObject
            .orEmpty()
            .mapValues { (_, element) -> element.jsonPrimitive.content }

    @Test
    fun `the seed is a schema the app reads`() {
        val schema = document.getValue("schema").jsonPrimitive.content.toInt()
        assertTrue("unsupported seed schema: $schema", ThreatIntelDocument.supportsSchema(schema))
    }

    @Test
    fun `the seed never invents an indicator kind`() {
        indicatorKinds().forEach { (indicator, kind) ->
            assertTrue(
                "unknown indicator kind for $indicator: $kind",
                ThreatIndicatorKind.entries.any { known -> known.name == kind },
            )
            assertTrue(
                "kind for an indicator that is not in the seed: $indicator",
                indicator in values("domains") || indicator in values("ips"),
            )
        }
    }

    @Test
    fun `the seed actually carries indicators`() {
        assertTrue("bundled packages: ${values("packages").size}", values("packages").size >= MINIMUM_INDICATORS)
        assertTrue("bundled sha-1 certs: ${values("certsSha1").size}", values("certsSha1").size >= MINIMUM_INDICATORS)
        assertTrue("bundled domains: ${values("domains").size}", values("domains").size >= MINIMUM_INDICATORS)
        assertTrue("bundled ips: ${values("ips").size}", values("ips").isNotEmpty())
    }

    @Test
    fun `every entry is well formed for the matcher`() {
        values("packages").forEach { value ->
            assertTrue("not an application id: $value", packageId.matches(value))
            assertTrue("not lower-cased: $value", value == value.lowercase())
        }
        values("certs").forEach { value ->
            assertTrue("not a SHA-256 digest: $value", sha256.matches(value))
        }
        values("certsSha1").forEach { value ->
            assertTrue("not a SHA-1 digest: $value", sha1.matches(value))
        }
        values("domains").forEach { value ->
            assertTrue("not a bare hostname: $value", hostname.matches(value))
        }
        values("ips").forEach { value ->
            val literal = ipv4.matches(value) || (value.contains(':') && ipv6.matches(value))
            assertTrue("not a literal address: $value", literal)
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
