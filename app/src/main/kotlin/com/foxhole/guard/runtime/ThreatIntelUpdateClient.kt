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

interface ThreatIntelStore {
    suspend fun installVerifiedThreatIntel(
        documentBytes: ByteArray,
        manifest: ThreatIntelManifest,
    ): String

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

class ThreatIntelUpdateClient(
    private val httpClient: OkHttpClient,
    private val json: Json,
    private val publicKeyPem: String = FOXHOLE_THREAT_INTEL_MANIFEST_PUBLIC_KEY_PEM,
    private val currentVersionName: String = BuildConfig.VERSION_NAME,
    private val resolver: RemoteHostResolver? = null,
    private val now: () -> Instant = Instant::now,
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
        requireFreshFoxholeDbManifest(generatedAt, "threat intel", now())
    }

    private fun ByteArray.requireValidArtifact(artifact: ThreatIntelManifestArtifact): ThreatIntelDocument {
        require(size.toLong() == artifact.size) { "threat intel size mismatch" }
        require(sha256Hex() == artifact.sha256) { "threat intel sha256 mismatch" }
        val document =
            runCatching { json.decodeFromString<ThreatIntelDocument>(toString(Charsets.UTF_8)) }
                .getOrElse { throw ThreatIntelUpdateException("threat intel is not valid JSON", retryable = false) }

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
        const val MAX_SIGNATURE_BYTES = MAX_FOXHOLE_DB_SIGNATURE_BYTES
        const val THREAT_INTEL_CONNECT_TIMEOUT_MS = 10_000L
        const val THREAT_INTEL_READ_TIMEOUT_MS = 30_000L
        const val THREAT_INTEL_CALL_TIMEOUT_MS = 45_000L
    }
}

const val FOXHOLE_THREAT_INTEL_MANIFEST_URL =
    "$FOXHOLE_DB_PAGES_BASE_URL/threat-intel-manifest.json"

internal const val FOXHOLE_THREAT_INTEL_MANIFEST_PUBLIC_KEY_PEM = FOXHOLE_DB_MANIFEST_PUBLIC_KEY_PEM
