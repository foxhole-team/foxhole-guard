package com.foxhole.guard.runtime

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

class FoxholeDbSigningKeyContractTest {
    @Test
    fun `every FoxHole DB verifier pins one identical P-256 key`() {
        val canonical = decodePem(FOXHOLE_DB_MANIFEST_PUBLIC_KEY_PEM)

        assertArrayEquals(canonical, decodePem(FOXHOLE_DNS_MANIFEST_PUBLIC_KEY_PEM))
        assertArrayEquals(canonical, decodePem(FOXHOLE_THREAT_INTEL_MANIFEST_PUBLIC_KEY_PEM))
        assertArrayEquals(canonical, decodePem(FOXHOLE_TLS_FINGERPRINT_MANIFEST_PUBLIC_KEY_PEM))
        assertArrayEquals(canonical, FOXHOLE_DB_MANIFEST_PUBLIC_KEY_DER)
        assertArrayEquals(
            canonical,
            Base64.getDecoder().decode(DnsFilterAssetInstaller.FOXCORE_DNS_UPDATE_PUBLIC_KEY_BASE64),
        )
        assertEquals(FOXHOLE_DB_MANIFEST_PUBLIC_KEY_SHA256, canonical.sha256Hex())
        assertEquals("EC", KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(canonical)).algorithm)
    }

    @Test
    fun `installed DNS revision requires a complete signed bundle`() {
        val source =
            File("src/main/kotlin/com/foxhole/guard/runtime/DnsFilterAssetInstaller.kt").readText()
        val installedManifestBody =
            source
                .substringAfter("suspend fun installedManifestOrNull")
                .substringBefore("suspend fun installedRuleSetsOrEmpty")
        val bundleLoaderBody =
            source
                .substringAfter("private fun loadSignedBundleOrNull")
                .substringBefore("private fun File.isValidRuleSet")

        assertTrue(installedManifestBody.contains("loadSignedBundleOrNull(targetDir)?.manifest"))
        assertTrue(bundleLoaderBody.contains("requireFoxholeDbManifestSignature"))
        assertTrue(bundleLoaderBody.contains("FOXHOLE_DB_MANIFEST_PUBLIC_KEY_SHA256"))
        assertTrue(bundleLoaderBody.contains("artifact.isValidRuleSet"))
    }

    private fun decodePem(pem: String): ByteArray =
        pem
            .lineSequence()
            .map(String::trim)
            .filter { line -> line.isNotBlank() && !line.startsWith("-----") }
            .joinToString(separator = "")
            .let(Base64.getDecoder()::decode)

    private fun ByteArray.sha256Hex(): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(this)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
}
