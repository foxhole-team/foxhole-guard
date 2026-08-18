package com.foxhole.core.runtime

import com.foxhole.core.model.ThreatIntelDocument
import com.foxhole.guard.runtime.FOXHOLE_DB_MAX_MANIFEST_AGE_SECONDS
import com.foxhole.guard.runtime.ThreatIntelManifest
import com.foxhole.guard.runtime.ThreatIntelManifestArtifact
import com.foxhole.guard.runtime.ThreatIntelManifestCompatibility
import com.foxhole.guard.runtime.ThreatIntelManifestSource
import com.foxhole.guard.runtime.ThreatIntelStore
import com.foxhole.guard.runtime.ThreatIntelUpdateClient
import com.foxhole.guard.runtime.ThreatIntelUpdateResult
import com.foxhole.guard.runtime.ThreatIntelUpdateStatus
import com.foxhole.guard.runtime.sha256Hex
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetAddress
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64

class ThreatIntelUpdateClientTest {
    private val json = Json { explicitNulls = false }

    @Test
    fun `installs threat intel only after signed manifest and artifact verification`() =
        runBlocking {
            val keyPair = testKeyPair()
            val documentBytes = testDocumentBytes()
            val manifest = testManifest(size = documentBytes.size.toLong(), sha256 = documentBytes.sha256Hex())
            val manifestBytes = json.encodeToString(manifest).toByteArray(Charsets.UTF_8)
            val store = RecordingThreatIntelStore()
            val client =
                ThreatIntelUpdateClient(
                    httpClient = testHttpClient(manifestBytes, keyPair.sign(manifestBytes), documentBytes),
                    json = json,
                    publicKeyPem = keyPair.publicKeyPem(),
                    resolver = { listOf(InetAddress.getByName("8.8.8.8")) },
                )

            val result = client.update(MANIFEST_URL, store)

            assertEquals(ThreatIntelUpdateStatus.UPDATED, result.status)
            assertEquals("/verified/threat-intel.json", result.installedPath)
            assertEquals(3, result.entryCount)
            assertArrayEquals(documentBytes, store.documentBytes)
            assertEquals(manifest, store.manifest)
        }

    @Test
    fun `rejects manifest with invalid signature and keeps local store untouched`() =
        runBlocking {
            val keyPair = testKeyPair()
            val documentBytes = testDocumentBytes()
            val manifest = testManifest(size = documentBytes.size.toLong(), sha256 = documentBytes.sha256Hex())
            val manifestBytes = json.encodeToString(manifest).toByteArray(Charsets.UTF_8)
            val store = RecordingThreatIntelStore()
            val client =
                ThreatIntelUpdateClient(
                    httpClient = testHttpClient(manifestBytes, byteArrayOf(0x30, 0x00), documentBytes),
                    json = json,
                    publicKeyPem = keyPair.publicKeyPem(),
                    resolver = { listOf(InetAddress.getByName("8.8.8.8")) },
                )

            val result = client.update(MANIFEST_URL, store)

            assertEquals(ThreatIntelUpdateStatus.FAILED, result.status)
            assertEquals(false, result.retryable)
            assertNull(store.documentBytes)
        }

    @Test
    fun `rejects manifest host that resolves to a private address before fetching`() =
        runBlocking {
            val keyPair = testKeyPair()
            val documentBytes = testDocumentBytes()
            val manifest = testManifest(size = documentBytes.size.toLong(), sha256 = documentBytes.sha256Hex())
            val manifestBytes = json.encodeToString(manifest).toByteArray(Charsets.UTF_8)
            val store = RecordingThreatIntelStore()
            val client =
                ThreatIntelUpdateClient(
                    httpClient = testHttpClient(manifestBytes, keyPair.sign(manifestBytes), documentBytes),
                    json = json,
                    publicKeyPem = keyPair.publicKeyPem(),
                    resolver = { listOf(InetAddress.getByName("127.0.0.1")) },
                )

            val result = client.update(MANIFEST_URL, store)

            assertEquals(ThreatIntelUpdateStatus.FAILED, result.status)
            assertEquals(true, result.reason.orEmpty().contains("private or loopback"))
            assertNull(store.documentBytes)
        }

    @Test
    fun `rejects artifact sha mismatch after signed manifest verification`() =
        runBlocking {
            val result =
                updateWithSignedManifest(
                    manifestTransform = { copy(artifact = artifact.copy(sha256 = "0".repeat(64))) },
                )

            assertEquals(ThreatIntelUpdateStatus.FAILED, result.status)
            assertEquals(false, result.retryable)
            assertEquals(true, result.reason.orEmpty().contains("sha256 mismatch"))
        }

    @Test
    fun `rejects signed manifest identity tampering`() =
        runBlocking {
            val variants =
                listOf<ThreatIntelManifest.() -> ThreatIntelManifest>(
                    { copy(schema = 2) },
                    { copy(name = "other-feed") },
                    { copy(format = "csv") },
                    { copy(artifact = artifact.copy(file = "../etc/passwd")) },
                    { copy(generatedAt = Instant.now().plusSeconds(25 * 60 * 60).toString()) },
                )

            variants.forEach { transform ->
                val result = updateWithSignedManifest(transform)
                assertEquals(ThreatIntelUpdateStatus.FAILED, result.status)
                assertEquals(false, result.retryable)
            }
        }

    @Test
    fun `refuses a correctly signed feed older than the device staleness bound`() =
        runBlocking {
            val stale =
                updateWithSignedManifest(
                    manifestTransform = {
                        copy(
                            generatedAt =
                            Instant.now().minusSeconds(FOXHOLE_DB_MAX_MANIFEST_AGE_SECONDS + 60L).toString(),
                        )
                    },
                )

            assertEquals(ThreatIntelUpdateStatus.FAILED, stale.status)
            assertEquals(false, stale.retryable)
            assertEquals(true, stale.reason.orEmpty().contains("stale"))

            val justInside =
                updateWithSignedManifest(
                    manifestTransform = {
                        copy(
                            generatedAt =
                            Instant.now().minusSeconds(FOXHOLE_DB_MAX_MANIFEST_AGE_SECONDS - 3600L).toString(),
                        )
                    },
                )
            assertEquals(ThreatIntelUpdateStatus.UPDATED, justInside.status)
        }

    @Test
    fun `marks retryable and non retryable http failures explicitly`() =
        runBlocking {
            val retryable = updateWithSignedManifest(responseCodes = mapOf(ARTIFACT_PATH to 503))
            val nonRetryable = updateWithSignedManifest(responseCodes = mapOf(ARTIFACT_PATH to 404))

            assertEquals(ThreatIntelUpdateStatus.FAILED, retryable.status)
            assertEquals(true, retryable.retryable)
            assertEquals(ThreatIntelUpdateStatus.FAILED, nonRetryable.status)
            assertEquals(false, nonRetryable.retryable)
        }

    private class RecordingThreatIntelStore : ThreatIntelStore {
        var documentBytes: ByteArray? = null
        var manifest: ThreatIntelManifest? = null

        override suspend fun installVerifiedThreatIntel(
            documentBytes: ByteArray,
            manifest: ThreatIntelManifest,
        ): String {
            this.documentBytes = documentBytes
            this.manifest = manifest
            return "/verified/threat-intel.json"
        }
    }

    private fun testHttpClient(
        manifestBytes: ByteArray,
        signatureBytes: ByteArray,
        documentBytes: ByteArray,
        responseCodes: Map<String, Int> = emptyMap(),
    ): OkHttpClient =
        OkHttpClient
            .Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val path = chain.request().url.encodedPath
                    val body =
                        when (path) {
                            MANIFEST_PATH -> manifestBytes
                            SIGNATURE_PATH -> signatureBytes
                            ARTIFACT_PATH -> documentBytes
                            else -> error("unexpected request: ${chain.request().url}")
                        }.toResponseBody("application/octet-stream".toMediaType())
                    val code = responseCodes[path] ?: 200
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(code)
                        .message(if (code == 200) "OK" else "HTTP $code")
                        .body(body)
                        .build()
                },
            ).build()

    private fun testManifest(
        size: Long,
        sha256: String,
        generatedAt: String = Instant.now().toString(),
    ): ThreatIntelManifest =
        ThreatIntelManifest(
            schema = 1,
            name = "foxhole-sentinel-threat-intel",
            format = "sentinel-threat-intel-json",
            generatedAt = generatedAt,
            source = ThreatIntelManifestSource(name = "foxhole-curated", license = "CC0-1.0", entryCount = 3),
            artifact = ThreatIntelManifestArtifact(file = "threat-intel.json", size = size, sha256 = sha256),
            compatibility = ThreatIntelManifestCompatibility(minAppVersion = "0.0.1"),
        )

    private suspend fun updateWithSignedManifest(
        manifestTransform: ThreatIntelManifest.() -> ThreatIntelManifest = { this },
        responseCodes: Map<String, Int> = emptyMap(),
    ): ThreatIntelUpdateResult {
        val keyPair = testKeyPair()
        val documentBytes = testDocumentBytes()
        val manifest =
            testManifest(size = documentBytes.size.toLong(), sha256 = documentBytes.sha256Hex()).manifestTransform()
        val manifestBytes = json.encodeToString(manifest).toByteArray(Charsets.UTF_8)
        val client =
            ThreatIntelUpdateClient(
                httpClient =
                testHttpClient(manifestBytes, keyPair.sign(manifestBytes), documentBytes, responseCodes),
                json = json,
                publicKeyPem = keyPair.publicKeyPem(),
                resolver = { listOf(InetAddress.getByName("8.8.8.8")) },
            )
        return client.update(MANIFEST_URL, RecordingThreatIntelStore())
    }

    private fun testDocumentBytes(): ByteArray =
        json
            .encodeToString(
                ThreatIntelDocument(
                    packages = listOf("com.evil.one", "com.evil.two"),
                    certs = listOf("a".repeat(64)),
                ),
            ).toByteArray(Charsets.UTF_8)

    private fun testKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        return generator.generateKeyPair()
    }

    private fun KeyPair.sign(bytes: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA")
            .apply {
                initSign(private)
                update(bytes)
            }.sign()

    private fun KeyPair.publicKeyPem(): String =
        "-----BEGIN PUBLIC KEY-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(public.encoded) +
            "\n-----END PUBLIC KEY-----"

    private companion object {
        const val MANIFEST_URL = "https://intel.example.org/sentinel/manifest.json"
        const val MANIFEST_PATH = "/sentinel/manifest.json"
        const val SIGNATURE_PATH = "/sentinel/manifest.json.sig"
        const val ARTIFACT_PATH = "/sentinel/threat-intel.json"
    }
}
