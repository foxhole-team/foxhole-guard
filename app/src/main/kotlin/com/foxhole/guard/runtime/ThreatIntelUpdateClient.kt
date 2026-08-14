package com.foxhole.guard.runtime

import com.foxhole.core.model.ThreatIntelDocument
import com.foxhole.core.network.RemoteHostResolver
import com.foxhole.core.network.ensurePublicHttpsUrl
import com.foxhole.core.network.requirePublicHttpsUrl
import com.foxhole.core.runtime.network.PublicRemoteDns
import com.foxhole.guard.BuildConfig
import com.foxhole.guard.core.data.withBoundedRemoteFetchTimeouts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Persists a verified FoxHole Sentinel threat-intel document. The bytes handed in have already
 * passed signature, size, hash, and schema verification, so an implementation only needs to store
 * them atomically and expose them back to [SentinelThreatIntelProvider].
 */
interface ThreatIntelStore {
    suspend fun installVerifiedThreatIntel(
        documentBytes: ByteArray,
        manifest: ThreatIntelManifest,
    ): String

    /**
     * `generated_at` of the feed already installed, or null when there is none. The client refuses
     * anything not newer than this — a valid signature says a manifest is ours, not that it is the
     * latest one, so without a floor an old feed can be replayed to drop recent indicators.
     */
    fun installedGeneratedAt(): String? = null
}

@Serializable
data class ThreatIntelManifest(
    val schema: Int,
    val name: String,
    val format: String,
    @SerialName("generated_at") val generatedAt: String,
    val source: ThreatIntelManifestSource,
    val artifact: ThreatIntelManifestArtifact,
    val compatibility: ThreatIntelManifestCompatibility,
)

@Serializable
data class ThreatIntelManifestSource(
    val name: String,
    val license: String,
    @SerialName("entry_count") val entryCount: Int,
)

@Serializable
data class ThreatIntelManifestArtifact(
    val file: String,
    val size: Long,
    val sha256: String,
)

@Serializable
data class ThreatIntelManifestCompatibility(
    @SerialName("min_app_version") val minAppVersion: String,
)

enum class ThreatIntelUpdateStatus {
    UPDATED,
    SKIPPED,
    FAILED,
}

data class ThreatIntelUpdateResult(
    val status: ThreatIntelUpdateStatus,
    val retryable: Boolean = false,
    val installedPath: String? = null,
    val entryCount: Int? = null,
    val reason: String? = null,
)

/**
 * Fetches and verifies the signed SENTINEL threat-intel feed, mirroring [DnsFilterUpdateClient]:
 * an ECDSA-P256 signed manifest gates a hash-pinned JSON artifact, which is only persisted after the
 * full chain (public-HTTPS host → signature → schema/identity → size → sha256 → parse) verifies.
 *
 * The live FoxHole DB endpoint and the repository-wide manifest key are pinned below. Callers may
 * still pass an empty URL to disable remote updates and retain the bundled seed only.
 */
class ThreatIntelUpdateClient(
    private val httpClient: OkHttpClient,
    private val json: Json,
    private val publicKeyPem: String = FOXHOLE_THREAT_INTEL_MANIFEST_PUBLIC_KEY_PEM,
    private val currentVersionName: String = BuildConfig.VERSION_NAME,
    private val resolver: RemoteHostResolver? = null,
) {
    suspend fun update(
        manifestUrl: String,
        store: ThreatIntelStore,
        onPhase: (RemoteUpdatePhase) -> Unit = {},
        onProgress: (RemoteDownloadProgress) -> Unit = {},
    ): ThreatIntelUpdateResult =
        withContext(Dispatchers.IO) {
            runCatching {
                onPhase(RemoteUpdatePhase.CHECKING)
                val manifestHttpUrl =
                    manifestUrl
                        .trim()
                        .ensurePublicHttpsUrl(resolveHost = true, resolver = resolver)
                val guardedClient =
                    httpClient
                        .withBoundedRemoteFetchTimeouts(
                            connectTimeoutMs = THREAT_INTEL_CONNECT_TIMEOUT_MS,
                            readTimeoutMs = THREAT_INTEL_READ_TIMEOUT_MS,
                            callTimeoutMs = THREAT_INTEL_CALL_TIMEOUT_MS,
                        ).newBuilder()
                        .dns(PublicRemoteDns(httpClient.dns::lookup))
                        .connectTimeout(THREAT_INTEL_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        .readTimeout(THREAT_INTEL_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        .callTimeout(THREAT_INTEL_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        .build()
                val manifestBytes = guardedClient.getBytes(manifestHttpUrl, MAX_MANIFEST_BYTES, "manifest")
                val signatureBytes =
                    guardedClient.getBytes(
                        manifestHttpUrl.signatureUrl(),
                        MAX_SIGNATURE_BYTES,
                        "manifest signature",
                    )
                requireVerifiedManifestSignature(manifestBytes, signatureBytes)
                val manifest = json.decodeFromString<ThreatIntelManifest>(manifestBytes.toString(Charsets.UTF_8))
                manifest.requireValid()
                manifest.requireNotARollbackOf(store.installedGeneratedAt())
                val artifactUrl =
                    requireNotNull(manifestHttpUrl.resolve(manifest.artifact.file)) {
                        "invalid artifact url"
                    }
                onPhase(RemoteUpdatePhase.DOWNLOADING)
                val artifactBytes =
                    guardedClient.getBytes(
                        artifactUrl,
                        MAX_DOCUMENT_BYTES,
                        "threat intel",
                        expectedBytes = manifest.artifact.size,
                        onProgress = onProgress,
                    )
                onPhase(RemoteUpdatePhase.VERIFYING)
                val document = artifactBytes.requireValidArtifact(manifest.artifact)
                val installedPath = store.installVerifiedThreatIntel(artifactBytes, manifest)
                ThreatIntelUpdateResult(
                    status = ThreatIntelUpdateStatus.UPDATED,
                    installedPath = installedPath,
                    entryCount = document.packages.size + document.certs.size,
                )
            }.getOrElse { error ->
                val retryable =
                    when (error) {
                        is ThreatIntelUpdateException -> error.retryable
                        is IOException -> true
                        else -> false
                    }
                ThreatIntelUpdateResult(
                    status = ThreatIntelUpdateStatus.FAILED,
                    retryable = retryable,
                    reason = error.message ?: error.javaClass.simpleName,
                )
            }
        }

    private fun requireVerifiedManifestSignature(
        manifestBytes: ByteArray,
        signatureBytes: ByteArray,
    ) {
        val keyBytes =
            publicKeyPem
                .lineSequence()
                .map(String::trim)
                .filter { line -> line.isNotBlank() && !line.startsWith("-----") }
                .joinToString(separator = "")
                .let(Base64.getDecoder()::decode)
        val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(keyBytes))
        val verifier =
            Signature.getInstance("SHA256withECDSA").apply {
                initVerify(publicKey)
                update(manifestBytes)
            }
        if (!verifier.verify(signatureBytes)) {
            throw ThreatIntelUpdateException("manifest signature verification failed", retryable = false)
        }
    }

    /**
     * Refuses a feed that is not newer than the one already installed.
     *
     * The signature check above proves the manifest was issued by us; it says nothing about *when*.
     * Replaying yesterday's correctly signed manifest is therefore a working downgrade: it drops
     * every indicator added since, and the app reports the feed as freshly updated while doing it.
     * An equal timestamp is the same feed and is refused as a no-op; an older one is refused as
     * what it is. Both are non-retryable — retrying cannot make a manifest newer.
     */
    private fun ThreatIntelManifest.requireNotARollbackOf(installedGeneratedAt: String?) {
        val installed =
            installedGeneratedAt
                ?.let { stamp -> runCatching { Instant.parse(stamp) }.getOrNull() }
                ?: return
        val incoming = Instant.parse(generatedAt)
        if (!incoming.isAfter(installed)) {
            throw ThreatIntelUpdateException(
                "threat intel feed is not newer than the installed one " +
                    "(incoming=$generatedAt installed=$installedGeneratedAt)",
                retryable = false,
            )
        }
    }

    private fun ThreatIntelManifest.requireValid() {
        require(schema == EXPECTED_MANIFEST_SCHEMA) { "unsupported manifest schema" }
        require(name == EXPECTED_MANIFEST_NAME) { "unexpected manifest name" }
        require(format == EXPECTED_ARTIFACT_FORMAT) { "unexpected artifact format" }
        require(source.license.isNotBlank()) { "missing source license" }
        require(source.entryCount >= 0) { "invalid source entry count" }
        require(artifact.file == EXPECTED_ARTIFACT_FILE) { "unexpected artifact file" }
        require(artifact.size in MIN_DOCUMENT_BYTES..MAX_DOCUMENT_BYTES) { "unexpected artifact size" }
        require(artifact.sha256.isSha256Hex()) { "invalid artifact sha256" }
        require(
            compareAppVersions(currentVersionName, compatibility.minAppVersion) >= 0,
        ) {
            "app version is too old for threat intel feed"
        }
        runCatching { Instant.parse(generatedAt) }
            .getOrElse { throw IllegalArgumentException("invalid generated_at") }
            .also { generatedAtInstant ->
                val futureLimit = Instant.now().plusSeconds(MAX_GENERATED_AT_FUTURE_SKEW_SECONDS)
                require(!generatedAtInstant.isAfter(futureLimit)) { "manifest generated_at is in the future" }
            }
    }

    private fun ByteArray.requireValidArtifact(artifact: ThreatIntelManifestArtifact): ThreatIntelDocument {
        require(size.toLong() == artifact.size) { "threat intel size mismatch" }
        require(sha256Hex() == artifact.sha256) { "threat intel sha256 mismatch" }
        val document =
            runCatching { json.decodeFromString<ThreatIntelDocument>(toString(Charsets.UTF_8)) }
                .getOrElse { throw ThreatIntelUpdateException("threat intel is not valid JSON", retryable = false) }
        // A range, not an equality: a signed feed still written for an older schema is accepted and
        // read with the fields it has. A schema from the future is refused — this build cannot know
        // what it would be agreeing to.
        require(ThreatIntelDocument.supportsSchema(document.schema)) { "unsupported threat intel schema" }
        return document
    }

    private fun OkHttpClient.getBytes(
        url: HttpUrl,
        maxBytes: Long,
        label: String,
        expectedBytes: Long? = null,
        onProgress: (RemoteDownloadProgress) -> Unit = {},
    ): ByteArray {
        url.requirePublicHttpsUrl(resolveHost = true, resolver = resolver)
        newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw ThreatIntelUpdateException(
                    "$label request failed with HTTP ${response.code}",
                    retryable = response.isRetryableHttpFailure(),
                )
            }
            return requireNotNull(response.body) { "$label response body is empty" }
                .readBytesCapped(maxBytes, expectedBytes, onProgress)
        }
    }

    private fun HttpUrl.signatureUrl(): HttpUrl =
        newBuilder()
            .encodedPath("$encodedPath.sig")
            .build()

    private fun Response.isRetryableHttpFailure(): Boolean = code == 408 || code == 429 || code in 500..599

    private class ThreatIntelUpdateException(
        message: String,
        val retryable: Boolean,
    ) : IllegalStateException(message)

    private companion object {
        const val EXPECTED_MANIFEST_SCHEMA = 1
        const val EXPECTED_MANIFEST_NAME = "foxhole-sentinel-threat-intel"
        const val EXPECTED_ARTIFACT_FORMAT = "sentinel-threat-intel-json"
        const val EXPECTED_ARTIFACT_FILE = "threat-intel.json"
        const val MIN_DOCUMENT_BYTES = 2L
        const val MAX_DOCUMENT_BYTES = 4L * 1024L * 1024L
        const val MAX_MANIFEST_BYTES = 64L * 1024L
        const val MAX_SIGNATURE_BYTES = 8L * 1024L
        const val MAX_GENERATED_AT_FUTURE_SKEW_SECONDS = 24L * 60L * 60L
        const val THREAT_INTEL_CONNECT_TIMEOUT_MS = 10_000L
        const val THREAT_INTEL_READ_TIMEOUT_MS = 30_000L
        const val THREAT_INTEL_CALL_TIMEOUT_MS = 45_000L
    }
}

/**
 * The live SENTINEL threat-intel feed: the FoxHole DB "security lists" group. The manifest is
 * signed by the same repository key as every other feed ([FOXHOLE_DB_MANIFEST_PUBLIC_KEY_PEM]);
 * blanking the URL puts the feature back to dormant (bundled seed only).
 */
const val FOXHOLE_THREAT_INTEL_MANIFEST_URL =
    "$FOXHOLE_DB_PAGES_BASE_URL/threat-intel-manifest.json"

internal const val FOXHOLE_THREAT_INTEL_MANIFEST_PUBLIC_KEY_PEM = FOXHOLE_DB_MANIFEST_PUBLIC_KEY_PEM
