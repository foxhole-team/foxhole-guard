package com.foxhole.core.runtime

import com.foxhole.guard.runtime.FOXHOLE_DB_MAX_MANIFEST_AGE_SECONDS
import com.foxhole.guard.runtime.TlsFingerprintManifest
import com.foxhole.guard.runtime.TlsFingerprintManifestArtifact
import com.foxhole.guard.runtime.TlsFingerprintManifestCompatibility
import com.foxhole.guard.runtime.TlsFingerprintManifestSource
import com.foxhole.guard.runtime.TlsFingerprintProfile
import com.foxhole.guard.runtime.TlsFingerprintStore
import com.foxhole.guard.runtime.TlsFingerprintTableInstaller
import com.foxhole.guard.runtime.TlsFingerprintTables
import com.foxhole.guard.runtime.TlsFingerprintUpdateClient
import com.foxhole.guard.runtime.TlsFingerprintUpdateRepository
import com.foxhole.guard.runtime.TlsFingerprintUpdateResult
import com.foxhole.guard.runtime.TlsFingerprintUpdateStatus
import com.foxhole.guard.runtime.canonicalDigest
import com.foxhole.guard.runtime.sha256Hex
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64

class TlsFingerprintUpdateClientTest {
    private val json = Json { explicitNulls = false }

    @Test
    fun `installs fingerprint tables only after signed manifest and artifact verification`() =
        runBlocking {
            val keyPair = testKeyPair()
            val tableBytes = testTableBytes()
            val manifest = testManifest(size = tableBytes.size.toLong(), sha256 = tableBytes.sha256Hex())
            val manifestBytes = json.encodeToString(manifest).toByteArray(Charsets.UTF_8)
            val store = RecordingTlsFingerprintStore()
            val client =
                TlsFingerprintUpdateClient(
                    httpClient = testHttpClient(manifestBytes, keyPair.sign(manifestBytes), tableBytes),
                    json = json,
                    publicKeyPem = keyPair.publicKeyPem(),
                    resolver = { listOf(InetAddress.getByName("8.8.8.8")) },
                )

            val result = client.update(MANIFEST_URL, store)

            assertEquals(TlsFingerprintUpdateStatus.UPDATED, result.status)
            assertEquals("/verified/fingerprints.json", result.installedPath)
            assertEquals(PROFILE_COUNT, result.profileCount)
            assertArrayEquals(tableBytes, store.documentBytes)
            assertEquals(manifest, store.manifest)
        }

    @Test
    fun `rejects manifest with invalid signature and keeps local store untouched`() =
        runBlocking {
            val keyPair = testKeyPair()
            val tableBytes = testTableBytes()
            val manifest = testManifest(size = tableBytes.size.toLong(), sha256 = tableBytes.sha256Hex())
            val manifestBytes = json.encodeToString(manifest).toByteArray(Charsets.UTF_8)
            val store = RecordingTlsFingerprintStore()
            val client =
                TlsFingerprintUpdateClient(
                    httpClient = testHttpClient(manifestBytes, byteArrayOf(0x30, 0x00), tableBytes),
                    json = json,
                    publicKeyPem = keyPair.publicKeyPem(),
                    resolver = { listOf(InetAddress.getByName("8.8.8.8")) },
                )

            val result = client.update(MANIFEST_URL, store)

            assertEquals(TlsFingerprintUpdateStatus.FAILED, result.status)
            assertEquals(false, result.retryable)
            assertNull(store.documentBytes)
        }

    @Test
    fun `rejects manifest host that resolves to a private address before fetching`() =
        runBlocking {
            val keyPair = testKeyPair()
            val tableBytes = testTableBytes()
            val manifest = testManifest(size = tableBytes.size.toLong(), sha256 = tableBytes.sha256Hex())
            val manifestBytes = json.encodeToString(manifest).toByteArray(Charsets.UTF_8)
            val store = RecordingTlsFingerprintStore()
            val client =
                TlsFingerprintUpdateClient(
                    httpClient = testHttpClient(manifestBytes, keyPair.sign(manifestBytes), tableBytes),
                    json = json,
                    publicKeyPem = keyPair.publicKeyPem(),
                    resolver = { listOf(InetAddress.getByName("127.0.0.1")) },
                )

            val result = client.update(MANIFEST_URL, store)

            assertEquals(TlsFingerprintUpdateStatus.FAILED, result.status)
            assertTrue(result.reason.orEmpty().contains("private or loopback"))
            assertNull(store.documentBytes)
        }

    @Test
    fun `rejects a tampered artifact whose bytes no longer match the signed manifest`() =
        runBlocking {
            val store = RecordingTlsFingerprintStore()
            val result =
                updateWithSignedManifest(
                    store = store,
                    tableTransform = { bytes -> bytes + " ".toByteArray(Charsets.UTF_8) },
                )

            assertEquals(TlsFingerprintUpdateStatus.FAILED, result.status)
            assertEquals(false, result.retryable)
            assertTrue(result.reason.orEmpty().contains("size mismatch"))
            assertNull(store.documentBytes)
        }

    @Test
    fun `rejects a table rewritten under a manifest that was updated to match it`() =
        runBlocking {
            val keyPair = testKeyPair()
            val tampered =
                testTables().let { tables ->
                    tables.copy(
                        profiles =
                        tables.profiles.mapIndexed { index, profile ->
                            if (index == 0) {
                                profile.copy(fingerprint = buildJsonObject { put("alpn", "h2-only") })
                            } else {
                                profile
                            }
                        },
                    )
                }
            val tableBytes = json.encodeToString(tampered).toByteArray(Charsets.UTF_8)
            val manifest = testManifest(size = tableBytes.size.toLong(), sha256 = tableBytes.sha256Hex())
            val manifestBytes = json.encodeToString(manifest).toByteArray(Charsets.UTF_8)
            val store = RecordingTlsFingerprintStore()
            val client =
                TlsFingerprintUpdateClient(
                    httpClient = testHttpClient(manifestBytes, keyPair.sign(manifestBytes), tableBytes),
                    json = json,
                    publicKeyPem = keyPair.publicKeyPem(),
                    resolver = { listOf(InetAddress.getByName("8.8.8.8")) },
                )

            val result = client.update(MANIFEST_URL, store)

            assertEquals(TlsFingerprintUpdateStatus.FAILED, result.status)
            assertEquals(false, result.retryable)
            assertTrue(result.reason.orEmpty().contains("hashes to"))
            assertNull(store.documentBytes)
        }

    @Test
    fun `rejects signed manifest identity tampering`() =
        runBlocking {
            val variants =
                listOf<TlsFingerprintManifest.() -> TlsFingerprintManifest>(
                    { copy(schema = 2) },
                    { copy(name = "other-feed") },
                    { copy(format = "csv") },
                    { copy(artifact = artifact.copy(file = "../etc/passwd")) },
                    { copy(artifact = artifact.copy(sha256 = "0".repeat(64))) },
                    { copy(source = source.copy(profileCount = 1)) },
                    { copy(generatedAt = Instant.now().plusSeconds(25 * 60 * 60).toString()) },
                )

            variants.forEach { transform ->
                val store = RecordingTlsFingerprintStore()
                val result = updateWithSignedManifest(store = store, manifestTransform = transform)
                assertEquals(TlsFingerprintUpdateStatus.FAILED, result.status)
                assertEquals(false, result.retryable)
                assertNull(store.documentBytes)
            }
        }

    @Test
    fun `refuses a correctly signed table set that is not newer than the installed one`() =
        runBlocking {
            val installed = Instant.now()
            val store = RecordingTlsFingerprintStore(installedGeneratedAt = installed.toString())
            val result =
                updateWithSignedManifest(
                    store = store,
                    manifestTransform = { copy(generatedAt = installed.minusSeconds(3600).toString()) },
                )

            assertEquals(TlsFingerprintUpdateStatus.FAILED, result.status)
            assertEquals(false, result.retryable)
            assertTrue(result.reason.orEmpty().contains("older than"))
            assertNull(store.documentBytes)
        }

    @Test
    fun `an identical signed table set is already current without downloading it again`() =
        runBlocking {
            val installed = Instant.now().minusSeconds(60)
            val store = RecordingTlsFingerprintStore(installedGeneratedAt = installed.toString())
            val result =
                updateWithSignedManifest(
                    store = store,
                    manifestTransform = { copy(generatedAt = installed.toString()) },
                    responseCodes = mapOf(ARTIFACT_PATH to 503),
                )

            assertEquals(TlsFingerprintUpdateStatus.UP_TO_DATE, result.status)
            assertEquals(false, result.retryable)
            assertNull(store.documentBytes)
        }

    @Test
    fun `refuses a correctly signed table set older than the device staleness bound`() =
        runBlocking {
            val store = RecordingTlsFingerprintStore()
            val tooOld = Instant.now().minusSeconds(FOXHOLE_DB_MAX_MANIFEST_AGE_SECONDS + 60L)
            val stale =
                updateWithSignedManifest(
                    store = store,
                    manifestTransform = { copy(generatedAt = tooOld.toString()) },
                )

            assertEquals(TlsFingerprintUpdateStatus.FAILED, stale.status)
            assertEquals(false, stale.retryable)
            assertTrue(stale.reason.orEmpty().contains("stale"))
            assertNull(store.documentBytes)

            val justInside =
                updateWithSignedManifest(
                    manifestTransform = {
                        copy(
                            generatedAt =
                            Instant.now().minusSeconds(FOXHOLE_DB_MAX_MANIFEST_AGE_SECONDS - 3600L).toString(),
                        )
                    },
                )
            assertEquals(TlsFingerprintUpdateStatus.UPDATED, justInside.status)
        }

    @Test
    fun `marks retryable and non retryable http failures explicitly`() =
        runBlocking {
            val retryable = updateWithSignedManifest(responseCodes = mapOf(ARTIFACT_PATH to 503))
            val nonRetryable = updateWithSignedManifest(responseCodes = mapOf(ARTIFACT_PATH to 404))

            assertEquals(TlsFingerprintUpdateStatus.FAILED, retryable.status)
            assertEquals(true, retryable.retryable)
            assertEquals(TlsFingerprintUpdateStatus.FAILED, nonRetryable.status)
            assertEquals(false, nonRetryable.retryable)
        }

    @Test
    fun `a successful refresh hands the freshly installed document to the running core`() =
        runBlocking {
            val handed = mutableListOf<ByteArray>()
            val store = RecordingTlsFingerprintStore()
            val tableBytes = testTableBytes()

            val result = refreshThroughRepository(store, handed, tableBytes)

            assertEquals(TlsFingerprintUpdateStatus.UPDATED, result.status)
            assertArrayEquals(tableBytes, handed.single())
            assertArrayEquals(store.documentBytes, handed.single())
        }

    @Test
    fun `a refresh that installs nothing leaves the core alone`() =
        runBlocking {
            val handed = mutableListOf<ByteArray>()
            val result =
                refreshThroughRepository(
                    RecordingTlsFingerprintStore(),
                    handed,
                    testTableBytes(),
                    responseCodes = mapOf(ARTIFACT_PATH to 404),
                )

            assertEquals(TlsFingerprintUpdateStatus.FAILED, result.status)
            assertTrue(handed.isEmpty())
        }

    private suspend fun refreshThroughRepository(
        store: RecordingTlsFingerprintStore,
        handed: MutableList<ByteArray>,
        tableBytes: ByteArray,
        responseCodes: Map<String, Int> = emptyMap(),
    ): TlsFingerprintUpdateResult {
        val keyPair = testKeyPair()
        val manifest = testManifest(size = tableBytes.size.toLong(), sha256 = tableBytes.sha256Hex())
        val manifestBytes = json.encodeToString(manifest).toByteArray(Charsets.UTF_8)
        val repository =
            TlsFingerprintUpdateRepository(
                client =
                TlsFingerprintUpdateClient(
                    httpClient =
                    testHttpClient(manifestBytes, keyPair.sign(manifestBytes), tableBytes, responseCodes),
                    json = json,
                    publicKeyPem = keyPair.publicKeyPem(),
                    resolver = { listOf(InetAddress.getByName("8.8.8.8")) },
                ),
                store = store,
                diagnosticsLogger = NoopDiagnosticsSink(),
                manifestUrl = { MANIFEST_URL },
                handOverToCore = {
                    TlsFingerprintTableInstaller(
                        documentInEffect = store::installedDocumentBytes,
                        isDownloaded = { true },
                        installTables = { bytes ->
                            handed += bytes
                            1
                        },
                        clearTables = {},
                    ).install()
                },
            )
        return repository.refreshNow()
    }

    private class RecordingTlsFingerprintStore(
        private val installedGeneratedAt: String? = null,
    ) : TlsFingerprintStore {
        var documentBytes: ByteArray? = null
        var manifest: TlsFingerprintManifest? = null

        override suspend fun installVerifiedTables(
            documentBytes: ByteArray,
            manifest: TlsFingerprintManifest,
        ): String {
            this.documentBytes = documentBytes
            this.manifest = manifest
            return "/verified/fingerprints.json"
        }

        override fun installedGeneratedAt(): String? = installedGeneratedAt

        override fun installedDocumentBytes(): ByteArray? = documentBytes
    }

    private class NoopDiagnosticsSink : RuntimeDiagnosticsSink {
        override fun record(
            tag: String,
            message: String,
        ) = Unit

        override fun recordStructured(
            tag: String,
            headline: String,
            vararg details: String?,
        ) = Unit
    }

    private fun testHttpClient(
        manifestBytes: ByteArray,
        signatureBytes: ByteArray,
        tableBytes: ByteArray,
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
                            ARTIFACT_PATH -> tableBytes
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
    ): TlsFingerprintManifest =
        TlsFingerprintManifest(
            schema = 1,
            name = "foxhole-tls-fingerprints",
            format = "tls-fingerprint-tables-json",
            generatedAt = generatedAt,
            source =
            TlsFingerprintManifestSource(
                repo = "https://github.com/foxhole-team/foxhole-core",
                dataset = "fingerprints",
                license = "GPL-3.0",
                profileCount = PROFILE_COUNT,
            ),
            artifact = TlsFingerprintManifestArtifact(file = "fingerprints.json", size = size, sha256 = sha256),
            compatibility = TlsFingerprintManifestCompatibility(minAppVersion = "0.0.1"),
        )

    private suspend fun updateWithSignedManifest(
        store: TlsFingerprintStore = RecordingTlsFingerprintStore(),
        manifestTransform: TlsFingerprintManifest.() -> TlsFingerprintManifest = { this },
        tableTransform: (ByteArray) -> ByteArray = { bytes -> bytes },
        responseCodes: Map<String, Int> = emptyMap(),
    ): TlsFingerprintUpdateResult {
        val keyPair = testKeyPair()
        val honestBytes = testTableBytes()
        val manifest =
            testManifest(size = honestBytes.size.toLong(), sha256 = honestBytes.sha256Hex()).manifestTransform()
        val manifestBytes = json.encodeToString(manifest).toByteArray(Charsets.UTF_8)
        val client =
            TlsFingerprintUpdateClient(
                httpClient =
                testHttpClient(
                    manifestBytes,
                    keyPair.sign(manifestBytes),
                    tableTransform(honestBytes),
                    responseCodes,
                ),
                json = json,
                publicKeyPem = keyPair.publicKeyPem(),
                resolver = { listOf(InetAddress.getByName("8.8.8.8")) },
            )
        return client.update(MANIFEST_URL, store)
    }

    private fun testTableBytes(): ByteArray = json.encodeToString(testTables()).toByteArray(Charsets.UTF_8)

    private fun testTables(): TlsFingerprintTables =
        TlsFingerprintTables(
            schema = 1,
            generatedAt = Instant.now().toString(),
            profiles =
            listOf("chrome_133", "firefox_148", "safari_26_3", "edge_85").map { name ->
                val table = testFingerprint(name)
                TlsFingerprintProfile(
                    name = name,
                    describes = "test profile $name",
                    fingerprintSha256 = table.canonicalDigest(),
                    fingerprint = table,
                )
            },
        )

    private fun testFingerprint(name: String): JsonObject =
        buildJsonObject {
            put("legacy_version", "0x0303")
            put("permute_extensions", name.startsWith("chrome"))
            put("record_size", 1216)
            put("alpn", buildJsonArray { listOf("h2", "http/1.1").forEach { value -> add(JsonPrimitive(value)) } })
            put(
                "extension_order",
                buildJsonArray {
                    listOf("0x0000", "0x000a", "0x0010").forEach { value -> add(JsonPrimitive(value)) }
                },
            )
        }

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
        const val PROFILE_COUNT = 4
        const val MANIFEST_URL = "https://db.example.org/foxhole/fingerprint-manifest.json"
        const val MANIFEST_PATH = "/foxhole/fingerprint-manifest.json"
        const val SIGNATURE_PATH = "/foxhole/fingerprint-manifest.json.sig"
        const val ARTIFACT_PATH = "/foxhole/fingerprints.json"
    }
}
