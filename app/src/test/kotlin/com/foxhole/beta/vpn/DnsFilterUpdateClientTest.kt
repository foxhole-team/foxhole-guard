package com.foxhole.beta.vpn

import com.foxhole.beta.BuildConfig
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

class DnsFilterUpdateClientTest {
    private val json = Json { explicitNulls = false }

    @Test
    fun `installs rule set only after signed manifest and artifact verification`() =
        runBlocking {
            val keyPair = testKeyPair()
            val ruleSetBytes = testRuleSetBytes()
            val manifest =
                testManifest(
                    size = ruleSetBytes.size.toLong(),
                    sha256 = ruleSetBytes.sha256Hex(),
                )
            val manifestBytes = json.encodeToString(manifest).toByteArray(Charsets.UTF_8)
            val signatureBytes = keyPair.sign(manifestBytes)
            val store = RecordingRuleSetStore()
            val client =
                DnsFilterUpdateClient(
                    httpClient = testHttpClient(manifestBytes, signatureBytes, ruleSetBytes),
                    json = json,
                    publicKeyPem = keyPair.publicKeyPem(),
                    resolver = { listOf(InetAddress.getByName("8.8.8.8")) },
                )

            val result = client.update(MANIFEST_URL, store)

            assertEquals(DnsFilterUpdateStatus.UPDATED, result.status)
            assertEquals("/verified/adguard-dns-filter.srs", result.installedPath)
            assertEquals(manifest.source.commit, result.sourceCommit)
            assertArrayEquals(ruleSetBytes, store.ruleSetBytes)
            assertEquals(manifest, store.manifest)
        }

    @Test
    fun `rejects manifest with invalid signature and keeps local store untouched`() =
        runBlocking {
            val keyPair = testKeyPair()
            val ruleSetBytes = testRuleSetBytes()
            val manifest =
                testManifest(
                    size = ruleSetBytes.size.toLong(),
                    sha256 = ruleSetBytes.sha256Hex(),
                )
            val manifestBytes = json.encodeToString(manifest).toByteArray(Charsets.UTF_8)
            val store = RecordingRuleSetStore()
            val client =
                DnsFilterUpdateClient(
                    httpClient = testHttpClient(manifestBytes, byteArrayOf(0x30, 0x00), ruleSetBytes),
                    json = json,
                    publicKeyPem = keyPair.publicKeyPem(),
                    resolver = { listOf(InetAddress.getByName("8.8.8.8")) },
                )

            val result = client.update(MANIFEST_URL, store)

            assertEquals(DnsFilterUpdateStatus.FAILED, result.status)
            assertEquals(false, result.retryable)
            assertNull(store.ruleSetBytes)
        }

    @Test
    fun `rejects manifest host that resolves to private address before fetching`() =
        runBlocking {
            val keyPair = testKeyPair()
            val ruleSetBytes = testRuleSetBytes()
            val manifest = testManifest(size = ruleSetBytes.size.toLong(), sha256 = ruleSetBytes.sha256Hex())
            val manifestBytes = json.encodeToString(manifest).toByteArray(Charsets.UTF_8)
            val store = RecordingRuleSetStore()
            val client =
                DnsFilterUpdateClient(
                    httpClient = testHttpClient(manifestBytes, keyPair.sign(manifestBytes), ruleSetBytes),
                    json = json,
                    publicKeyPem = keyPair.publicKeyPem(),
                    resolver = { listOf(InetAddress.getByName("127.0.0.1")) },
                )

            val result = client.update(MANIFEST_URL, store)

            assertEquals(DnsFilterUpdateStatus.FAILED, result.status)
            assertEquals(false, result.retryable)
            assertEquals(true, result.reason.orEmpty().contains("private or loopback"))
            assertNull(store.ruleSetBytes)
        }

    @Test
    fun `rejects oversized manifest before signature or artifact install`() =
        runBlocking {
            val keyPair = testKeyPair()
            val ruleSetBytes = testRuleSetBytes()
            val oversizedManifestBytes = ByteArray(64 * 1024 + 1) { index -> index.toByte() }
            val store = RecordingRuleSetStore()
            val client =
                DnsFilterUpdateClient(
                    httpClient = testHttpClient(oversizedManifestBytes, keyPair.sign(byteArrayOf()), ruleSetBytes),
                    json = json,
                    publicKeyPem = keyPair.publicKeyPem(),
                    resolver = { listOf(InetAddress.getByName("8.8.8.8")) },
                )

            val result = client.update(MANIFEST_URL, store)

            assertEquals(DnsFilterUpdateStatus.FAILED, result.status)
            assertEquals(false, result.retryable)
            assertEquals(true, result.reason.orEmpty().contains("too large"))
            assertNull(store.ruleSetBytes)
        }

    @Test
    fun `rejects signed manifest generated too far in the future`() =
        runBlocking {
            val result =
                updateWithSignedManifest(
                    manifestTransform = {
                        copy(generatedAt = Instant.now().plusSeconds(25 * 60 * 60).toString())
                    },
                )

            assertEquals(DnsFilterUpdateStatus.FAILED, result.status)
            assertEquals(false, result.retryable)
            assertEquals(true, result.reason.orEmpty().contains("generated_at"))
        }

    @Test
    fun `rejects signed manifest identity and source tampering`() =
        runBlocking {
            val variants =
                listOf(
                    "schema" to { manifest: DnsFilterManifest -> manifest.copy(schema = 2) },
                    "name" to { manifest: DnsFilterManifest -> manifest.copy(name = "adguard") },
                    "source repository" to { manifest: DnsFilterManifest ->
                        manifest.copy(source = manifest.source.copy(repo = "https://updates.example.org/other.git"))
                    },
                    "source license" to { manifest: DnsFilterManifest ->
                        manifest.copy(source = manifest.source.copy(license = "UNKNOWN"))
                    },
                    "source input path" to { manifest: DnsFilterManifest ->
                        manifest.copy(source = manifest.source.copy(inputPath = "../filter.txt"))
                    },
                    "artifact file" to { manifest: DnsFilterManifest ->
                        manifest.copy(artifact = manifest.artifact.copy(file = "other.srs"))
                    },
                )

            variants.forEach { (_, transform) ->
                val result = updateWithSignedManifest(manifestTransform = transform)

                assertEquals(DnsFilterUpdateStatus.FAILED, result.status)
                assertEquals(false, result.retryable)
            }
        }

    @Test
    fun `rejects artifact sha mismatch after signed manifest verification`() =
        runBlocking {
            val result =
                updateWithSignedManifest(
                    manifestTransform = {
                        copy(artifact = artifact.copy(sha256 = "0".repeat(64)))
                    },
                )

            assertEquals(DnsFilterUpdateStatus.FAILED, result.status)
            assertEquals(false, result.retryable)
            assertEquals(true, result.reason.orEmpty().contains("sha256 mismatch"))
        }

    @Test
    fun `marks retryable and non retryable http failures explicitly`() =
        runBlocking {
            val retryableResult = updateWithSignedManifest(responseCodes = mapOf(RULE_SET_PATH to 503))
            val nonRetryableResult = updateWithSignedManifest(responseCodes = mapOf(RULE_SET_PATH to 404))

            assertEquals(DnsFilterUpdateStatus.FAILED, retryableResult.status)
            assertEquals(true, retryableResult.retryable)
            assertEquals(DnsFilterUpdateStatus.FAILED, nonRetryableResult.status)
            assertEquals(false, nonRetryableResult.retryable)
        }

    @Test
    fun `accepts rule set generated by older compatible sing box patch release`() {
        assertEquals(true, supportsSingBoxRuleSetVersion("1.13.12", "1.13.11"))
        assertEquals(true, supportsSingBoxRuleSetVersion("1.13.12", "1.13.12"))
    }

    @Test
    fun `rejects rule set generated by newer patch or different sing box line`() {
        assertEquals(false, supportsSingBoxRuleSetVersion("1.13.11", "1.13.12"))
        assertEquals(false, supportsSingBoxRuleSetVersion("1.13.12", "1.14.0"))
        assertEquals(false, supportsSingBoxRuleSetVersion("1.13.12", "1.12.9"))
    }

    private class RecordingRuleSetStore : DnsFilterRuleSetStore {
        var ruleSetBytes: ByteArray? = null
        var manifest: DnsFilterManifest? = null

        override suspend fun installVerifiedDnsRuleSet(
            ruleSetBytes: ByteArray,
            manifest: DnsFilterManifest,
        ): String {
            this.ruleSetBytes = ruleSetBytes
            this.manifest = manifest
            return "/verified/adguard-dns-filter.srs"
        }
    }

    private fun testHttpClient(
        manifestBytes: ByteArray,
        signatureBytes: ByteArray,
        ruleSetBytes: ByteArray,
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
                            RULE_SET_PATH -> ruleSetBytes
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
    ): DnsFilterManifest =
        DnsFilterManifest(
            schema = 1,
            name = "foxhole-adguard-dns-filter",
            format = "sing-box-srs",
            generatedAt = generatedAt,
            source = DnsFilterManifestSource(
                name = "AdGuardSDNSFilter",
                repo = "https://github.com/AdguardTeam/AdGuardSDNSFilter.git",
                commit = "0123456789abcdef0123456789abcdef01234567",
                license = "GPL-3.0",
                inputPath = "Filters/filter.txt",
                inputSha256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            ),
            artifact = DnsFilterManifestArtifact(
                file = "adguard-dns-filter.srs",
                size = size,
                sha256 = sha256,
            ),
            compatibility = DnsFilterManifestCompatibility(
                singBoxVersion = BuildConfig.LIBBOX_SOURCE_VERSION,
                minAppVersion = "0.0.1",
            ),
        )

    private suspend fun updateWithSignedManifest(
        manifestTransform: DnsFilterManifest.() -> DnsFilterManifest = { this },
        responseCodes: Map<String, Int> = emptyMap(),
    ): DnsFilterUpdateResult {
        val keyPair = testKeyPair()
        val ruleSetBytes = testRuleSetBytes()
        val manifest =
            testManifest(
                size = ruleSetBytes.size.toLong(),
                sha256 = ruleSetBytes.sha256Hex(),
            ).manifestTransform()
        val manifestBytes = json.encodeToString(manifest).toByteArray(Charsets.UTF_8)
        val store = RecordingRuleSetStore()
        val client =
            DnsFilterUpdateClient(
                httpClient = testHttpClient(
                    manifestBytes = manifestBytes,
                    signatureBytes = keyPair.sign(manifestBytes),
                    ruleSetBytes = ruleSetBytes,
                    responseCodes = responseCodes,
                ),
                json = json,
                publicKeyPem = keyPair.publicKeyPem(),
                resolver = { listOf(InetAddress.getByName("8.8.8.8")) },
            )
        return client.update(MANIFEST_URL, store)
    }

    private fun testRuleSetBytes(): ByteArray =
        byteArrayOf('S'.code.toByte(), 'R'.code.toByte(), 'S'.code.toByte(), 2) +
            ByteArray(32) { index -> index.toByte() }

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
        const val MANIFEST_URL = "https://updates.example.org/foxhole/manifest.json"
        const val MANIFEST_PATH = "/foxhole/manifest.json"
        const val SIGNATURE_PATH = "/foxhole/manifest.json.sig"
        const val RULE_SET_PATH = "/foxhole/adguard-dns-filter.srs"
    }
}
