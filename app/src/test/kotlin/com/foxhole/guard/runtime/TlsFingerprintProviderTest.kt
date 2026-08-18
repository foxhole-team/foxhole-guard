package com.foxhole.guard.runtime

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TlsFingerprintProviderTest {
    private val json = Json { explicitNulls = false }

    @Test
    fun `falls back to the built-in tables when nothing has been downloaded`() {
        val provider =
            TlsFingerprintProvider(
                installedTables = { null },
                bundledBytes = { tableBytes("bundled_profile") },
                json = json,
            )

        val tables = provider.tables()

        assertNotNull(tables)
        assertEquals(listOf("bundled_profile"), tables?.profiles?.map(TlsFingerprintProfile::name))
        assertFalse(provider.isUsingDownloadedTables())
    }

    @Test
    fun `prefers the downloaded tables once a verified set is installed`() {
        val provider =
            TlsFingerprintProvider(
                installedTables = { tables("downloaded_profile") },
                bundledBytes = { tableBytes("bundled_profile") },
                json = json,
            )

        assertEquals(listOf("downloaded_profile"), provider.tables()?.profiles?.map(TlsFingerprintProfile::name))
        assertTrue(provider.isUsingDownloadedTables())
    }

    @Test
    fun `falls back to the built-in tables when the downloaded profile no longer hashes to its digest`() {
        val rewritten =
            tables("downloaded_profile").let { set ->
                set.copy(
                    profiles =
                    set.profiles.map { profile ->
                        profile.copy(fingerprint = buildJsonObject { put("alpn", "rewritten") })
                    },
                )
            }
        val provider =
            TlsFingerprintProvider(
                installedTables = { rewritten },
                bundledBytes = { tableBytes("bundled_profile") },
                json = json,
            )

        assertEquals(listOf("bundled_profile"), provider.tables()?.profiles?.map(TlsFingerprintProfile::name))
        assertFalse(provider.isUsingDownloadedTables())
    }

    @Test
    fun `falls back to the built-in tables when reading the downloaded set throws`() {
        val provider =
            TlsFingerprintProvider(
                installedTables = { error("disk is gone") },
                bundledBytes = { tableBytes("bundled_profile") },
                json = json,
            )

        assertEquals(listOf("bundled_profile"), provider.tables()?.profiles?.map(TlsFingerprintProfile::name))
        assertFalse(provider.isUsingDownloadedTables())
    }

    @Test
    fun `refuses an empty or wrong-schema table set from either source`() {
        val emptySet = TlsFingerprintTables(schema = 1, generatedAt = "2026-01-01T00:00:00Z", profiles = emptyList())
        val futureSchema = tables("downloaded_profile").copy(schema = 2)

        assertFalse(emptySet.isUsable())
        assertFalse(futureSchema.isUsable())
        assertNull(
            TlsFingerprintProvider(
                installedTables = { futureSchema },
                bundledBytes = { json.encodeToString(emptySet).toByteArray(Charsets.UTF_8) },
                json = json,
            ).tables(),
        )
    }

    @Test
    fun `refuses a table set carrying the same profile twice`() {
        val single = tables("chrome_133")
        val duplicated = single.copy(profiles = single.profiles + single.profiles)

        assertFalse(duplicated.isUsable())
    }

    private fun tableBytes(name: String): ByteArray = json.encodeToString(tables(name)).toByteArray(Charsets.UTF_8)

    private fun tables(name: String): TlsFingerprintTables {
        val table = fingerprint(name)
        return TlsFingerprintTables(
            schema = 1,
            generatedAt = "2026-01-01T00:00:00Z",
            profiles =
            listOf(
                TlsFingerprintProfile(
                    name = name,
                    describes = "test profile $name",
                    fingerprintSha256 = table.canonicalDigest(),
                    fingerprint = table,
                ),
            ),
        )
    }

    private fun fingerprint(name: String): JsonObject =
        buildJsonObject {
            put("legacy_version", "0x0303")
            put("permute_extensions", true)
            put("record_size", 1216)
            put("alpn", buildJsonArray { listOf("h2", "http/1.1").forEach { value -> add(JsonPrimitive(value)) } })
            put("describes_name", name)
        }
}
