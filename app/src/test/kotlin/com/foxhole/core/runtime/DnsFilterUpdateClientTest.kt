package com.foxhole.core.runtime

import com.foxhole.core.network.RemoteHostResolver
import com.foxhole.guard.runtime.DnsFilterManifest
import com.foxhole.guard.runtime.DnsFilterManifestArtifact
import com.foxhole.guard.runtime.DnsFilterManifestCompatibility
import com.foxhole.guard.runtime.DnsFilterManifestSource
import com.foxhole.guard.runtime.DnsFilterRuleSetStore
import com.foxhole.guard.runtime.DnsFilterUpdateAvailability
import com.foxhole.guard.runtime.DnsFilterUpdateClient
import com.foxhole.guard.runtime.DnsFilterUpdateStatus
import com.foxhole.guard.runtime.VerifiedDnsRuleSet
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
import java.util.Base64

class DnsFilterUpdateClientTest {
    private val json = Json { explicitNulls = false }

    @Test
    fun `persists exact signed envelope only after all FoxCore checks pass`() =
        runBlocking {
            val fixture = fixture()
            val store = RecordingStore()

            val result = fixture.client.update(MANIFEST_URL, store)

            assertEquals(DnsFilterUpdateStatus.UPDATED, result.status)
            assertEquals("/verified/adguard-dns-filter.signed.fhds", result.installedPath)
            assertEquals(fixture.manifest.source.commit, result.sourceCommit)
            assertEquals(fixture.manifest, store.ruleSet?.manifest)
            assertArrayEquals(fixture.manifestBytes, store.ruleSet?.manifestBytes)
            assertArrayEquals(fixture.signatureBytes, store.ruleSet?.signatureBytes)
            assertArrayEquals(fixture.artifactBytes, store.ruleSet?.artifactBytes)
        }

    @Test
    fun `invalid signature never reaches persistent store`() =
        runBlocking {
            val fixture = fixture(signatureOverride = byteArrayOf(0x30, 0x00))
            val store = RecordingStore()

            val result = fixture.client.update(MANIFEST_URL, store)

            assertEquals(DnsFilterUpdateStatus.FAILED, result.status)
            assertEquals(false, result.retryable)
            assertNull(store.ruleSet)
        }

    @Test
    fun `artifact source binding and hash are enforced before install`() =
        runBlocking {
            val fixture = fixture(artifactOverride = validArtifact(sourceHash = ByteArray(32) { 7 }))
            val store = RecordingStore()

            val result = fixture.client.update(MANIFEST_URL, store)

            assertEquals(DnsFilterUpdateStatus.FAILED, result.status)
            assertEquals(false, result.retryable)
            assertNull(store.ruleSet)
        }

    @Test
    fun `expired signed manifest fails closed`() =
        runBlocking {
            val fixture = fixture(expiresAt = 999)

            val result = fixture.client.update(MANIFEST_URL, RecordingStore())

            assertEquals(DnsFilterUpdateStatus.FAILED, result.status)
            assertEquals(true, result.reason.orEmpty().contains("expired"))
        }

    @Test
    fun `private manifest destination is rejected before a request`() =
        runBlocking {
            val fixture =
                fixture(
                    resolver = { listOf(InetAddress.getByName("127.0.0.1")) },
                )
            val store = RecordingStore()

            val result = fixture.client.update(MANIFEST_URL, store)

            assertEquals(DnsFilterUpdateStatus.FAILED, result.status)
            assertEquals(true, result.reason.orEmpty().contains("private or loopback"))
            assertNull(store.ruleSet)
        }

    @Test
    fun `manifest-only check compares the installed source revision`() =
        runBlocking {
            val fixture = fixture()

            assertEquals(
                DnsFilterUpdateAvailability.UP_TO_DATE,
                fixture.client
                    .checkForUpdate(
                        MANIFEST_URL,
                        installedCommit = fixture.manifest.source.commit,
                    ).availability,
            )
            assertEquals(
                DnsFilterUpdateAvailability.UPDATE_AVAILABLE,
                fixture.client
                    .checkForUpdate(
                        MANIFEST_URL,
                        installedCommit = "ffffffffffffffffffffffffffffffffffffffff",
                    ).availability,
            )
        }

    private fun fixture(
        expiresAt: Long = 1_800,
        signatureOverride: ByteArray? = null,
        artifactOverride: ByteArray? = null,
        resolver: RemoteHostResolver = {
            listOf(InetAddress.getByName("8.8.8.8"))
        },
    ): Fixture {
        val keyPair = testKeyPair()
        val sourceHash = ByteArray(32) { 0x11 }
        val artifact = artifactOverride ?: validArtifact(sourceHash)
        val manifest =
            DnsFilterManifest(
                schema = 2,
                name = "foxhole-adguard-dns-filter",
                format = "foxhole-dns-fst-v1",
                sequence = 7,
                generatedAtUnix = 900,
                expiresAtUnix = expiresAt,
                keySha256 = keyPair.public.encoded.sha256Hex(),
                source =
                DnsFilterManifestSource(
                    name = "AdGuardSDNSFilter",
                    repo = "https://github.com/AdguardTeam/AdGuardSDNSFilter",
                    commit = "0123456789abcdef0123456789abcdef01234567",
                    license = "GPL-3.0",
                    inputPath = "Filters/filter.txt",
                    inputSha256 = sourceHash.toHex(),
                ),
                artifact =
                DnsFilterManifestArtifact(
                    file = "adguard-dns-filter.fhds",
                    size = artifact.size.toLong(),
                    sha256 = artifact.sha256Hex(),
                    blockEntries = 1,
                    allowEntries = 0,
                ),
                compatibility = DnsFilterManifestCompatibility(coreSchema = 1),
            )
        val manifestBytes = json.encodeToString(manifest).toByteArray()
        val signature = signatureOverride ?: keyPair.sign(manifestBytes)
        return Fixture(
            client =
            DnsFilterUpdateClient(
                httpClient = testHttpClient(manifestBytes, signature, artifact),
                json = json,
                publicKeyPem = keyPair.publicKeyPem(),
                resolver = resolver,
                nowUnixSeconds = { 1_000 },
            ),
            manifest = manifest,
            manifestBytes = manifestBytes,
            signatureBytes = signature,
            artifactBytes = artifact,
        )
    }

    private fun validArtifact(sourceHash: ByteArray): ByteArray {
        val blockPayload = byteArrayOf(0, 1)
        return buildList<Byte> {
            addAll("FHDNS1\u0000\u0000".toByteArray().toList())
            addAll(1L.bigEndianBytes().toList())
            addAll(0L.bigEndianBytes().toList())
            addAll(blockPayload.size.toLong().bigEndianBytes().toList())
            addAll(0L.bigEndianBytes().toList())
            addAll(sourceHash.toList())
            addAll(ByteArray(8).toList())
            addAll(blockPayload.toList())
        }.toByteArray()
    }

    private fun Long.bigEndianBytes(): ByteArray =
        ByteArray(8) { index ->
            (this ushr ((7 - index) * 8)).toByte()
        }

    private fun testHttpClient(
        manifest: ByteArray,
        signature: ByteArray,
        artifact: ByteArray,
    ): OkHttpClient =
        OkHttpClient
            .Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val bytes =
                        when (chain.request().url.encodedPath) {
                            MANIFEST_PATH -> manifest
                            SIGNATURE_PATH -> signature
                            ARTIFACT_PATH -> artifact
                            else -> error("unexpected request")
                        }
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(bytes.toResponseBody("application/octet-stream".toMediaType()))
                        .build()
                },
            ).build()

    private class RecordingStore : DnsFilterRuleSetStore {
        var ruleSet: VerifiedDnsRuleSet? = null

        override suspend fun installVerifiedDnsRuleSet(ruleSet: VerifiedDnsRuleSet): String {
            this.ruleSet = ruleSet
            return "/verified/adguard-dns-filter.signed.fhds"
        }
    }

    private fun testKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        return generator.generateKeyPair()
    }

    private fun KeyPair.sign(bytes: ByteArray): ByteArray =
        Signature
            .getInstance("SHA256withECDSA")
            .apply {
                initSign(private)
                update(bytes)
            }.sign()

    private fun KeyPair.publicKeyPem(): String =
        "-----BEGIN PUBLIC KEY-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(public.encoded) +
            "\n-----END PUBLIC KEY-----"

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { byte -> "%02x".format(byte) }

    private data class Fixture(
        val client: DnsFilterUpdateClient,
        val manifest: DnsFilterManifest,
        val manifestBytes: ByteArray,
        val signatureBytes: ByteArray,
        val artifactBytes: ByteArray,
    )

    private companion object {
        const val MANIFEST_URL = "https://updates.example.org/foxhole/manifest.json"
        const val MANIFEST_PATH = "/foxhole/manifest.json"
        const val SIGNATURE_PATH = "/foxhole/manifest.json.sig"
        const val ARTIFACT_PATH = "/foxhole/adguard-dns-filter.fhds"
    }
}
