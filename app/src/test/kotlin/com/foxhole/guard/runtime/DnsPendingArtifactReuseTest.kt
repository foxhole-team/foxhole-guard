package com.foxhole.guard.runtime

import com.foxhole.core.model.DnsSettings
import com.foxhole.core.model.Settings
import com.foxhole.core.runtime.RuntimeDiagnosticsSink
import com.foxhole.core.runtime.RuntimeSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class DnsPendingArtifactReuseTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun repeatedSameVerifiedPendingRefreshDoesNotRefetchArtifactOrReportApplied() = runBlocking {
        val wire = OwnedDnsWire()
        val store = CategorizedDnsStagedStore(temporary.newFolder())
        val repository = wire.repository()
        val first = refresh(repository, wire.settings.dns, store)
        val stage = requireNotNull(store.pendingOrNull("normal-medium"))
        val second = refresh(repository, wire.settings.dns, store)
        assertEquals(1, wire.artifactRequests)
        assertEquals(2, wire.manifestRequests)
        assertEquals(2, wire.signatureRequests)
        assertEquals(stage, store.pendingOrNull("normal-medium"))
        assertNull(store.activeOrNull("normal-medium"))
        listOf(first, second).forEach { result ->
            assertEquals(DnsFilterUpdateStatus.SKIPPED, result.status)
            assertEquals(DnsFilterBundleState.STAGED, result.bundleState)
            assertNull(result.appliedVariant)
        }
    }

    @Test
    fun activeIdenticalArtifactIsReusedWithoutReplacingActive() = runBlocking {
        val wire = OwnedDnsWire()
        val store = CategorizedDnsStagedStore(temporary.newFolder())
        val repository = wire.repository()
        refresh(repository, wire.settings.dns, store)
        val old = requireNotNull(store.pendingOrNull("normal-medium"))
        store.promote(old) { true }
        val result = refresh(repository, wire.settings.dns, store)
        assertEquals(1, wire.artifactRequests)
        assertEquals(old, store.activeOrNull("normal-medium"))
        assertTrue(result.status != DnsFilterUpdateStatus.UPDATED)
    }

    @Test
    fun freshHigherSequenceSameArtifactReusesBytesAndStagesNewSignedIdentity() = runBlocking {
        val wire = OwnedDnsWire()
        val store = CategorizedDnsStagedStore(temporary.newFolder())
        val repository = wire.repository()
        refresh(repository, wire.settings.dns, store)
        wire.sequence = 11uL
        refresh(repository, wire.settings.dns, store)
        assertEquals(1, wire.artifactRequests)
        assertEquals(11uL, store.pendingOrNull("normal-medium")?.sequence)
        assertNull(store.activeOrNull("normal-medium"))
    }

    @Test
    fun changedArtifactFetchesOnceAndStagesNewHash() = runBlocking {
        val wire = OwnedDnsWire()
        val store = CategorizedDnsStagedStore(temporary.newFolder())
        val repository = wire.repository()
        refresh(repository, wire.settings.dns, store)
        wire.sequence = 11uL
        wire.artifact[99] = 1
        val result = refresh(repository, wire.settings.dns, store)
        assertEquals(2, wire.artifactRequests)
        assertEquals(DnsFilterBundleState.STAGED, result.bundleState)
        assertEquals(CategorizedDnsContract.sha256(wire.artifact), store.pendingOrNull("normal-medium")?.artifactSha256)
    }

    @Test
    fun higherPendingFloorRejectsRemoteEvenAboveActiveFloor() = runBlocking {
        val wire = OwnedDnsWire()
        val store = CategorizedDnsStagedStore(temporary.newFolder())
        val repository = wire.repository()
        wire.sequence = 12uL
        refresh(repository, wire.settings.dns, store)
        store.promote(requireNotNull(store.pendingOrNull("normal-medium"))) { true }
        wire.sequence = 14uL
        refresh(repository, wire.settings.dns, store)
        wire.sequence = 13uL
        assertEquals(DnsFilterUpdateStatus.FAILED, refresh(repository, wire.settings.dns, store).status)
        assertEquals(14uL, store.pendingOrNull("normal-medium")?.sequence)
        assertEquals(1, wire.artifactRequests)
    }

    @Test
    fun sameSequenceDifferentFreshManifestIsRefusedBeforeArtifact() = runBlocking {
        val wire = OwnedDnsWire()
        val store = CategorizedDnsStagedStore(temporary.newFolder())
        val repository = wire.repository()
        refresh(repository, wire.settings.dns, store)
        val old = store.pendingOrNull("normal-medium")
        wire.generated = 1100uL
        assertEquals(DnsFilterUpdateStatus.FAILED, refresh(repository, wire.settings.dns, store).status)
        assertEquals(old, store.pendingOrNull("normal-medium"))
        assertEquals(1, wire.artifactRequests)
    }

    @Test
    fun wrongFreshSignatureNeverReusesOrFetchesArtifact() = runBlocking {
        val wire = OwnedDnsWire()
        val store = CategorizedDnsStagedStore(temporary.newFolder())
        val repository = wire.repository()
        refresh(repository, wire.settings.dns, store)
        wire.wrongSignature = true
        assertEquals(DnsFilterUpdateStatus.FAILED, refresh(repository, wire.settings.dns, store).status)
        assertEquals(1, wire.artifactRequests)
    }

    @Test
    fun expiredFreshManifestNeverReusesArtifact() = runBlocking {
        val wire = OwnedDnsWire()
        val store = CategorizedDnsStagedStore(temporary.newFolder())
        val repository = wire.repository()
        refresh(repository, wire.settings.dns, store)
        wire.sequence = 11uL
        wire.expires = 1400uL
        assertEquals(DnsFilterUpdateStatus.FAILED, refresh(repository, wire.settings.dns, store).status)
        assertEquals(1, wire.artifactRequests)
    }

    @Test
    fun expiredCachedManifestRequiresFreshGetAndHigherSequenceRenewal() = runBlocking {
        val wire = OwnedDnsWire()
        val store = CategorizedDnsStagedStore(temporary.newFolder())
        val repository = wire.repository()
        refresh(repository, wire.settings.dns, store)
        wire.now = 2100L
        wire.sequence = 11uL
        wire.generated = 2000uL
        wire.expires = 3000uL
        val renewed = refresh(repository, wire.settings.dns, store)
        assertEquals(DnsFilterBundleState.STAGED, renewed.bundleState)
        assertEquals(2, wire.artifactRequests)
        assertEquals(11uL, store.pendingOrNull("normal-medium")?.sequence)
        assertNull(store.activeOrNull("normal-medium"))
    }

    @Test
    fun corruptCachedThreeFileSnapshotGetsFreshImmutableRepair() = runBlocking {
        for (name in listOf("manifest.json", "manifest.json.sig", "dns-categories.fhds")) {
            val wire = OwnedDnsWire()
            val store = CategorizedDnsStagedStore(temporary.newFolder())
            val repository = wire.repository()
            refresh(repository, wire.settings.dns, store)
            val old = requireNotNull(store.pendingOrNull("normal-medium"))
            File(old.directory, name).writeBytes(byteArrayOf(0))
            val result = refresh(repository, wire.settings.dns, store)
            assertEquals(DnsFilterBundleState.STAGED, result.bundleState)
            assertEquals(2, wire.artifactRequests)
            val repaired = requireNotNull(store.pendingOrNull("normal-medium"))
            assertTrue(repaired.token != old.token)
            assertArrayEquals(wire.artifact, store.readBundle(repaired).artifact)
            assertNull(store.activeOrNull("normal-medium"))
        }
    }

    @Test
    fun missingCachedArtifactGetsFreshImmutableRepairWithoutSeedFallback() = runBlocking {
        val wire = OwnedDnsWire()
        val store = CategorizedDnsStagedStore(temporary.newFolder())
        val repository = wire.repository()
        refresh(repository, wire.settings.dns, store)
        val old = requireNotNull(store.pendingOrNull("normal-medium"))
        assertTrue(File(old.directory, "dns-categories.fhds").delete())
        assertEquals(DnsFilterBundleState.STAGED, refresh(repository, wire.settings.dns, store).bundleState)
        assertEquals(2, wire.artifactRequests)
        assertTrue(requireNotNull(store.pendingOrNull("normal-medium")).token != old.token)
    }

    @Test
    fun changedStoreDuringFreshManifestCheckRejectsCapturedSuccessorRedirect() = runBlocking {
        val wire = OwnedDnsWire()
        val store = CategorizedDnsStagedStore(temporary.newFolder())
        val repository = wire.repository()
        refresh(repository, wire.settings.dns, store)
        wire.beforeManifest = {
            wire.beforeManifest = null
            wire.sequence = 11uL
            val bytes = wire.manifestBytes
            val signature = wire.signature
            val manifest = wire.client.verifyCategorizedManifest(bytes, signature)
            runBlocking { store.stage(VerifiedCategorizedDnsRuleSet(manifest, bytes, signature, wire.artifact)) }
            wire.sequence = 10uL
        }
        assertEquals(DnsFilterUpdateStatus.FAILED, refresh(repository, wire.settings.dns, store).status)
        assertEquals(11uL, store.pendingOrNull("normal-medium")?.sequence)
        assertEquals(1, wire.artifactRequests)
    }

    @Test
    fun cancellationAndFatalClientFailuresRemainTransparent() = runBlocking {
        val wire = OwnedDnsWire()
        val store = CategorizedDnsStagedStore(temporary.newFolder())
        val repository = wire.repository()
        wire.failure = CancellationException("owned cancellation")
        assertThrows(
            CancellationException::class.java
        ) { runBlocking { refresh(repository, wire.settings.dns, store) } }
        val fatal = OwnedDnsFatalFailure()
        wire.failure = fatal
        assertSame(
            fatal,
            assertThrows(LinkageError::class.java) { runBlocking { refresh(repository, wire.settings.dns, store) } }
        )
        Unit
    }

    @Test
    fun reusedReturnedBytesHaveIndependentOwnership() = runBlocking {
        val wire = OwnedDnsWire()
        val store = CategorizedDnsStagedStore(temporary.newFolder())
        val repository = wire.repository()
        refresh(repository, wire.settings.dns, store)
        val state = store.state("normal-medium")
        val manifest = wire.client.verifyCategorizedManifest(wire.manifestBytes, wire.signature)
        val reusable = requireNotNull(
            store.reusableArtifact(
                state,
                manifest,
                wire.manifestBytes,
                wire.client::verifyCategorizedManifest
            )
        )
        reusable[99] = 2
        assertArrayEquals(wire.artifact, store.readBundle(requireNotNull(state.pending)).artifact)
    }

    @Test
    fun freshlySignedNoncanonicalManifestIsRejectedBeforeReuse() = runBlocking {
        val wire = OwnedDnsWire()
        val store = CategorizedDnsStagedStore(temporary.newFolder())
        val repository = wire.repository()
        refresh(repository, wire.settings.dns, store)
        wire.rawOverride = wire.manifestBytes + byteArrayOf(10)
        assertEquals(DnsFilterUpdateStatus.FAILED, refresh(repository, wire.settings.dns, store).status)
        assertEquals(1, wire.artifactRequests)
    }

    @Test
    fun missingRequiredCategoryDoesNotFetchOrReuseDifferentPolicy() = runBlocking {
        val wire = OwnedDnsWire()
        val store = CategorizedDnsStagedStore(temporary.newFolder())
        val requested = wire.settings.dns.copy(blockAds = false, blockAppTelemetry = true)
        assertEquals(DnsFilterUpdateStatus.FAILED, refresh(wire.repository(), requested, store).status)
        assertEquals(0, wire.artifactRequests)
    }

    @Test
    fun masterAndAllCategoriesOffIssueNoRequests() = runBlocking {
        val wire = OwnedDnsWire()
        listOf(
            wire.settings.dns.copy(filteringEnabled = false),
            wire.settings.dns.copy(blockAds = false)
        ).forEach { settings ->
            val result = wire.repository().refreshNow(requireAutoEnabled = false, dnsSettingsOverride = settings)
            assertEquals(DnsFilterUpdateStatus.SKIPPED, result.status)
        }
        assertEquals(0, wire.manifestRequests)
        assertEquals(0, wire.artifactRequests)
    }

    @Test
    fun corruptFloorMetadataRefusesFetchWithoutGuessingOrResetting() = runBlocking {
        val wire = OwnedDnsWire()
        val store = CategorizedDnsStagedStore(temporary.newFolder())
        val repository = wire.repository()
        refresh(repository, wire.settings.dns, store)
        val old = requireNotNull(store.pendingOrNull("normal-medium"))
        val pointer = File(old.directory.parentFile, "pending")
        val fields = pointer.readText().split('\n').toMutableList()
        fields[4] = "9"
        pointer.writeText(fields.joinToString("\n"))
        wire.sequence = 11uL
        assertEquals(DnsFilterUpdateStatus.FAILED, refresh(repository, wire.settings.dns, store).status)
        assertEquals(1, wire.artifactRequests)
        assertEquals("9", pointer.readText().split('\n')[4])
    }

    @Test
    fun sameIdentityRepairNeverOverwritesDirectoryHeldByColdReader() = runBlocking {
        val wire = OwnedDnsWire()
        val store = CategorizedDnsStagedStore(temporary.newFolder())
        val repository = wire.repository()
        refresh(repository, wire.settings.dns, store)
        val old = requireNotNull(store.pendingOrNull("normal-medium"))
        val lease = requireNotNull(store.acquireReadLease(old))
        val broken = byteArrayOf(0)
        File(old.directory, "manifest.json.sig").writeBytes(broken)
        assertEquals(DnsFilterBundleState.STAGED, refresh(repository, wire.settings.dns, store).bundleState)
        val next = requireNotNull(store.pendingOrNull("normal-medium"))
        assertTrue(next.token != old.token)
        assertTrue(old.directory.isDirectory)
        assertArrayEquals(broken, File(old.directory, "manifest.json.sig").readBytes())
        assertArrayEquals(wire.artifact, store.readBundle(next).artifact)
        lease.release()
        assertTrue(!old.directory.exists())
    }

    private suspend fun refresh(
        repository: DnsFilterUpdateRepository,
        settings: DnsSettings,
        store: CategorizedDnsStagedStore
    ): DnsFilterUpdateResult = suspendCoroutine { continuation ->
        val method = DnsFilterUpdateRepository::class.java.declaredMethods.single { it.name == "refreshCategorized" }
        method.isAccessible = true
        try {
            val value = method.invoke(
                repository,
                settings,
                store,
                { _: RemoteUpdatePhase -> },
                { _: RemoteDownloadProgress -> },
                continuation
            )
            if (value !== COROUTINE_SUSPENDED) continuation.resume(value as DnsFilterUpdateResult)
        } catch (error: java.lang.reflect.InvocationTargetException) {
            continuation.resumeWithException(requireNotNull(error.cause))
        }
    }
}

internal class OwnedDnsWire : RuntimeSettings, RuntimeDiagnosticsSink {
    val key = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    val settings = Settings(
        dns = DnsSettings(
            filteringEnabled = true,
            autoUpdateFilters = true,
            blockTrackers = false,
            blockAppTelemetry = false,
            blockMaliciousDomains = false,
            dnsFilterUpdateUrl = "https://example.com/manifest.json"
        )
    )
    var artifactRequests = 0
    var manifestRequests = 0
    var signatureRequests = 0
    var artifact = ownedArtifact()
    var sequence = 10uL
    var now = 1500L
    var generated = 1000uL
    var expires = 2000uL
    var rawOverride: ByteArray? = null
    var wrongSignature = false
    var beforeManifest: (() -> Unit)? = null
    var beforeArtifact: (() -> Unit)? = null
    var failure: Throwable? = null
    val manifestBytes: ByteArray get() {
        rawOverride?.let { return it }
        val manifest = CategorizedDnsManifest(
            3, "foxhole-dns-categories-normal-medium", "foxhole-dns-fst-v1",
            sequence, generated, expires, CategorizedDnsContract.sha256(key.public.encoded), listOf("ads"),
            CategorizedDnsTiers("normal", "medium"), "b".repeat(64),
            listOf(
                CategorizedDnsSource(
                    "owned",
                    "https://example.invalid/owned",
                    "c".repeat(40),
                    "CC0-1.0",
                    listOf(CategorizedDnsInput("owned.txt", "d".repeat(64))),
                    listOf("ads")
                )
            ),
            CategorizedDnsArtifact(
                "dns-categories.fhds",
                artifact.size.toULong(),
                CategorizedDnsContract.sha256(artifact),
                1uL,
                0uL,
                CategorizedDnsCounts(0uL, 0uL, 0uL, 1uL)
            ),
            CategorizedDnsCompatibility(1, 1)
        )
        return (canonicalAsciiJson(Json.parseToJsonElement(Json.encodeToString(manifest))) + "\n").encodeToByteArray()
    }
    val signature: ByteArray get() = Signature.getInstance("SHA256withECDSA").run {
        initSign(key.private)
        update(if (wrongSignature) byteArrayOf(0) else manifestBytes)
        sign()
    }
    val client = DnsFilterUpdateClient(
        OkHttpClient.Builder().addInterceptor { chain ->
            failure?.let { throw it }
            val path = chain.request().url.encodedPath
            val body = when {
                path.endsWith("manifest.json.sig") -> {
                    signatureRequests++
                    signature
                }
                path.endsWith("manifest.json") -> {
                    manifestRequests++
                    beforeManifest?.invoke()
                    manifestBytes
                }
                path.endsWith("dns-categories.fhds") -> {
                    artifactRequests++
                    beforeArtifact?.invoke()
                    artifact.copyOf()
                }
                else -> error("unexpected owned fixture request")
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("owned")
                .body(body.toResponseBody()).build()
        }.build(),
        Json,
        publicKeyPem = "-----BEGIN PUBLIC KEY-----\n" + Base64.getEncoder().encodeToString(key.public.encoded) +
            "\n-----END PUBLIC KEY-----",
        resolver = { listOf(InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1))) },
        nowUnixSeconds = { now }
    )

    fun repository(): DnsFilterUpdateRepository = DnsFilterUpdateRepository(
        this,
        client,
        object : DnsFilterRuleSetStore { override suspend fun installVerifiedDnsRuleSet(
            ruleSet: VerifiedDnsRuleSet
        ) = error(
            "legacy must not run"
        ) },
        this
    )

    override suspend fun current() = settings
    override suspend fun markDnsFiltersUpdated(timestamp: Long) = Unit
    override suspend fun markDnsFiltersChecked(timestamp: Long) = Unit
    override fun record(tag: String, message: String) = Unit
    override fun recordStructured(tag: String, headline: String, vararg details: String?) = Unit
}

internal fun ownedArtifact(): ByteArray = ByteArray(100).also { bytes ->
    "FHDNS1".encodeToByteArray().copyInto(bytes)
    ByteBuffer.wrap(bytes).apply {
        putLong(8, 1)
        putLong(24, 20)
    }
    ByteArray(32) { 0xbb.toByte() }.copyInto(bytes, 40)
}

private class OwnedDnsFatalFailure(val marker: Any = Any()) : LinkageError("owned fatal")
