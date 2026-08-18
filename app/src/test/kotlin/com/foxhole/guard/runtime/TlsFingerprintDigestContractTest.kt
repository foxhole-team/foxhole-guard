package com.foxhole.guard.runtime

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TlsFingerprintDigestContractTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `every bundled profile re-derives the digest the publishing pipeline wrote`() {
        val tables = json.decodeFromString<TlsFingerprintTables>(bundledAsset().readText())

        assertTrue("the bundled table set carries no profiles", tables.profiles.isNotEmpty())
        tables.profiles.forEach { profile ->
            assertEquals(
                "profile ${profile.name} does not re-derive its committed digest",
                profile.fingerprintSha256,
                profile.fingerprint.canonicalDigest(),
            )
        }
    }

    @Test
    fun `the bundled table set is one the app would accept from the network`() {
        val tables = json.decodeFromString<TlsFingerprintTables>(bundledAsset().readText())

        assertEquals(TLS_FINGERPRINT_TABLES_SCHEMA, tables.schema)
        assertTrue("the built-in fallback must carry a usable table set", tables.isUsable())
    }

    private fun bundledAsset(): File {
        val asset = File(repositoryRoot(), "app/src/main/assets/$BUNDLED_FINGERPRINTS_ASSET_PATH")
        assertTrue("missing built-in fallback: ${asset.path}", asset.isFile)
        return asset
    }

    private fun repositoryRoot(): File {
        var directory: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
        while (directory != null && !File(directory, "settings.gradle.kts").isFile) {
            directory = directory.parentFile
        }
        return requireNotNull(directory) { "could not locate the repository root" }
    }
}
