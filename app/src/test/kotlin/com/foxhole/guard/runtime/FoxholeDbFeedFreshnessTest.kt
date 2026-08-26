package com.foxhole.guard.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.Instant

class FoxholeDbFeedFreshnessTest {
    private val now: Instant = Instant.parse("2026-08-17T12:00:00Z")

    @Test
    fun `configured manifest urls are treated as the shared feed base`() {
        assertEquals(
            "https://mirror.example.org/foxhole-db/manifest.json",
            foxholeDbManifestUrl("https://mirror.example.org/foxhole-db/manifest.json"),
        )
        assertEquals(
            "https://mirror.example.org/foxhole-db/bridges-manifest.json",
            foxholeDbBridgesManifestUrl("https://mirror.example.org/foxhole-db/manifest.json"),
        )
        assertEquals(
            "https://mirror.example.org/foxhole-db/manifest.json",
            foxholeDbManifestUrl("https://mirror.example.org/foxhole-db/manifest.json/"),
        )
    }

    @Test
    fun `a manifest inside the age bound is accepted and one past it is not`() {
        val fresh = now.minusSeconds(FOXHOLE_DB_MAX_MANIFEST_AGE_SECONDS - 1)
        assertEquals(fresh, requireFreshFoxholeDbManifest(fresh.toString(), "geoip", now))

        val stale = now.minusSeconds(FOXHOLE_DB_MAX_MANIFEST_AGE_SECONDS + 1)
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                requireFreshFoxholeDbManifest(stale.toString(), "geoip", now)
            }
        assertTrue(error.message.orEmpty().contains("stale"))
    }

    @Test
    fun `the device bound matches the publisher gate it stands in for`() {
        assertEquals(45L, FOXHOLE_DB_MAX_MANIFEST_AGE_SECONDS / (24L * 60L * 60L))
    }

    @Test
    fun `a manifest stamped past the clock skew allowance is refused`() {
        val ahead = now.plusSeconds(FOXHOLE_DB_MAX_GENERATED_AT_FUTURE_SKEW_SECONDS + 1)
        assertThrows(IllegalArgumentException::class.java) {
            requireFreshFoxholeDbManifest(ahead.toString(), "tor bridges", now)
        }
        val slightlyAhead = now.plusSeconds(FOXHOLE_DB_MAX_GENERATED_AT_FUTURE_SKEW_SECONDS - 1)
        assertEquals(slightlyAhead, requireFreshFoxholeDbManifest(slightlyAhead.toString(), "tor bridges", now))
    }

    @Test
    fun `an unparseable generated_at is refused rather than ignored`() {
        assertThrows(IllegalArgumentException::class.java) {
            requireFreshFoxholeDbManifest("last tuesday", "geoip", now)
        }
    }

    @Test
    fun `an older manifest is a rollback and an equal or newer one is not`() {
        requireNotAFoxholeDbRollback(now, null, "tor bridges")
        requireNotAFoxholeDbRollback(now, now, "tor bridges")
        requireNotAFoxholeDbRollback(now.plusSeconds(1), now, "tor bridges")

        val error =
            assertThrows(IllegalArgumentException::class.java) {
                requireNotAFoxholeDbRollback(now.minusSeconds(1), now, "tor bridges")
            }
        assertTrue(error.message.orEmpty().contains("older than the installed one"))
    }

    @Test
    fun `a geoip manifest older than the installed one is refused whatever its version says`() {
        assertEquals(
            GeoIpInstallDecision.ROLLBACK,
            geoIpInstallDecision(
                incomingGeneratedAt = now.minusSeconds(86_400),
                incomingVersion = "3.0.202607010000",
                installedGeneratedAt = now,
                installedVersion = "3.0.202608012121",
            ),
        )
    }

    @Test
    fun `a newer geoip manifest installs and a republished one is up to date`() {
        assertEquals(
            GeoIpInstallDecision.INSTALL,
            geoIpInstallDecision(
                incomingGeneratedAt = now.plusSeconds(86_400),
                incomingVersion = "3.0.202608170000",
                installedGeneratedAt = now,
                installedVersion = "3.0.202608012121",
            ),
        )
        assertEquals(
            GeoIpInstallDecision.UP_TO_DATE,
            geoIpInstallDecision(
                incomingGeneratedAt = now,
                incomingVersion = "3.0.202608012121",
                installedGeneratedAt = now,
                installedVersion = "3.0.202608012121",
            ),
        )
        assertEquals(
            GeoIpInstallDecision.INSTALL,
            geoIpInstallDecision(
                incomingGeneratedAt = now,
                incomingVersion = "3.0.202608012121",
                installedGeneratedAt = null,
                installedVersion = null,
            ),
        )
    }

    @Test
    fun `the signature cap is the largest a real P-256 signature can be`() {
        assertEquals(72L, MAX_FOXHOLE_DB_SIGNATURE_BYTES)

        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val keyPair = generator.generateKeyPair()
        var largest = 0
        repeat(SIGNATURE_SAMPLES) { index ->
            val signature =
                Signature.getInstance("SHA256withECDSA").apply {
                    initSign(keyPair.private)
                    update("manifest sample $index".toByteArray())
                }.sign()
            largest = maxOf(largest, signature.size)
            assertTrue(
                "a real signature must fit the cap, saw ${signature.size} bytes",
                signature.size <= MAX_FOXHOLE_DB_SIGNATURE_BYTES,
            )
        }
        assertTrue("the cap must not be loose by more than the DER slack", largest >= 70)
    }

    @Test
    fun `every FoxHole DB feed caps signatures with the one shared bound`() {
        val clients =
            listOf(
                "DnsFilterUpdateClient.kt",
                "GeoIpUpdateClient.kt",
                "ThreatIntelUpdateClient.kt",
                "TlsFingerprintUpdateClient.kt",
                "TorBridgeUpdateClient.kt",
            )
        clients.forEach { name ->
            assertTrue(
                "$name must cap manifest signatures with MAX_FOXHOLE_DB_SIGNATURE_BYTES",
                Regex("""MAX_SIGNATURE_BYTES\s*=\s*MAX_FOXHOLE_DB_SIGNATURE_BYTES""")
                    .containsMatchIn(runtimeSource(name)),
            )
        }
        assertTrue(
            "the installed DNS bundle must be held to the same bound",
            Regex("""MAX_SIGNATURE_BYTES\s*=\s*MAX_FOXHOLE_DB_SIGNATURE_BYTES\.toInt\(\)""")
                .containsMatchIn(runtimeSource("DnsFilterAssetInstaller.kt")),
        )
    }

    @Test
    fun `the two feeds that had no floor now read one before they download`() {
        val geoIp = runtimeSource("GeoIpUpdateClient.kt")
        assertTrue(
            "the geo client must bound manifest age on the device",
            geoIp.contains("requireFreshFoxholeDbManifest(generatedAt, \"geoip\""),
        )
        assertTrue(
            "the geo client must decide against the installed stamp before downloading",
            Regex("""manifest\.decide\(store\)[\s\S]{0,900}?RemoteUpdatePhase\.DOWNLOADING""")
                .containsMatchIn(geoIp),
        )
        assertTrue(
            "the geo client must enforce the manifest's min_app_version",
            geoIp.contains("compatibility.minAppVersion"),
        )

        val bridges = runtimeSource("TorBridgeUpdateClient.kt")
        assertTrue(
            "the bridge client must bound manifest age on the device",
            bridges.contains("requireFreshFoxholeDbManifest(generatedAt, \"tor bridges\""),
        )
        assertTrue(
            "the bridge client must refuse a rollback before downloading the artifact",
            Regex("""requireNotAFoxholeDbRollback\([\s\S]{0,300}?RemoteUpdatePhase\.DOWNLOADING""")
                .containsMatchIn(bridges),
        )
        assertTrue(
            "the bridge client must enforce the manifest's min_app_version",
            bridges.contains("compatibility.minAppVersion"),
        )
    }

    @Test
    fun `the FoxCore DNS bootstrap key is derived rather than transcribed`() {
        val installer = runtimeSource("DnsFilterAssetInstaller.kt")
        assertNull(
            "a second base64 copy of the signing key would rot on the next rotation",
            Regex("""MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQg""").find(installer),
        )
        assertNotNull(
            Regex("""FOXCORE_DNS_UPDATE_PUBLIC_KEY_BASE64\s*=\s*FOXHOLE_DB_MANIFEST_PUBLIC_KEY_DER_BASE64""")
                .find(installer),
        )
    }

    private fun runtimeSource(name: String): String =
        File("src/main/kotlin/com/foxhole/guard/runtime/$name").readText()

    private companion object {
        const val SIGNATURE_SAMPLES = 200
    }
}
