package com.foxhole.guard.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TlsFingerprintTableInstallerTest {
    private val json = Json { explicitNulls = false }

    @Test
    fun `hands the core the verified downloaded document byte for byte`() {
        val downloaded = document("downloaded_profile")
        val handed = mutableListOf<ByteArray>()
        val installer =
            TlsFingerprintTableInstaller(
                provider =
                TlsFingerprintProvider(
                    installedTables = { tables("downloaded_profile") },
                    bundledBytes = { document("bundled_profile") },
                    installedBytes = { downloaded },
                    json = json,
                ),
                installTables = { bytes ->
                    handed += bytes
                    1
                },
                clearTables = { throw AssertionError("a successful install must not clear") },
            )

        val result = installer.install()

        assertEquals(1, result.profilesReplaced)
        assertTrue(result.downloaded)
        assertNull(result.reason)
        assertArrayEquals(downloaded, handed.single())
    }

    @Test
    fun `falls back to the document built into the apk when nothing is downloaded`() {
        val bundled = document("bundled_profile")
        val handed = mutableListOf<ByteArray>()
        val installer =
            TlsFingerprintTableInstaller(
                provider =
                TlsFingerprintProvider(
                    installedTables = { null },
                    bundledBytes = { bundled },
                    installedBytes = { throw AssertionError("there is no downloaded document") },
                    json = json,
                ),
                installTables = { bytes ->
                    handed += bytes
                    1
                },
                clearTables = { throw AssertionError("a successful install must not clear") },
            )

        val result = installer.install()

        assertFalse(result.downloaded)
        assertArrayEquals(bundled, handed.single())
    }

    @Test
    fun `a rewritten downloaded document is never the one handed over`() {
        val bundled = document("bundled_profile")
        val handed = mutableListOf<ByteArray>()
        val rewritten =
            tables("downloaded_profile").let { set ->
                set.copy(
                    profiles =
                    set.profiles.map { profile ->
                        profile.copy(fingerprint = buildJsonObject { put("alpn", "rewritten") })
                    },
                )
            }
        val installer =
            TlsFingerprintTableInstaller(
                provider =
                TlsFingerprintProvider(
                    installedTables = { rewritten },
                    bundledBytes = { bundled },
                    installedBytes = { "rewritten".toByteArray() },
                    json = json,
                ),
                installTables = { bytes ->
                    handed += bytes
                    1
                },
                clearTables = { throw AssertionError("the bundled document installs cleanly") },
            )

        installer.install()

        assertArrayEquals(bundled, handed.single())
    }

    @Test
    fun `a refusal clears rather than leaving a stale set installed`() {
        for (outcome in listOf<(ByteArray) -> Int>(
            { FAILED_CODE },
            { throw UnsatisfiedLinkError("no foxhole_native in this process") },
        )) {
            var cleared = false
            val installer =
                TlsFingerprintTableInstaller(
                    provider =
                    TlsFingerprintProvider(
                        installedTables = { null },
                        bundledBytes = { document("bundled_profile") },
                        json = json,
                    ),
                    installTables = outcome,
                    clearTables = { cleared = true },
                )

            val result = installer.install()

            assertEquals(0, result.profilesReplaced)
            assertFalse(result.downloaded)
            assertNotNull(result.reason)
            assertTrue("a refused document must not stay installed", cleared)
        }
    }

    @Test
    fun `no readable document at all clears and reports`() {
        var cleared = false
        val installer =
            TlsFingerprintTableInstaller(
                provider =
                TlsFingerprintProvider(
                    installedTables = { null },
                    bundledBytes = { null },
                    json = json,
                ),
                installTables = { throw AssertionError("nothing may be handed over") },
                clearTables = { cleared = true },
            )

        val result = installer.install()

        assertEquals(0, result.profilesReplaced)
        assertNotNull(result.reason)
        assertTrue(cleared)
    }

    private fun tables(name: String): TlsFingerprintTables {
        val fingerprint = buildJsonObject { put("alpn", name) }
        return TlsFingerprintTables(
            schema = TLS_FINGERPRINT_TABLES_SCHEMA,
            generatedAt = "2026-08-17T00:00:00Z",
            profiles =
            listOf(
                TlsFingerprintProfile(
                    name = name,
                    describes = name,
                    fingerprintSha256 = fingerprint.canonicalDigest(),
                    fingerprint = fingerprint,
                ),
            ),
        )
    }

    private fun document(name: String): ByteArray {
        val set = tables(name)
        val profile = set.profiles.single()
        return buildJsonObject {
            put("schema", set.schema)
            put("generated_at", set.generatedAt)
            put(
                "profiles",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("name", profile.name)
                            put("describes", profile.describes)
                            put("fingerprint_sha256", profile.fingerprintSha256)
                            put("fingerprint", profile.fingerprint)
                        },
                    )
                },
            )
        }.toString().toByteArray()
    }

    private companion object {
        const val FAILED_CODE = -2
    }
}
