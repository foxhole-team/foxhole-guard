package com.foxhole.guard.runtime

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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit

interface TlsFingerprintStore {
    suspend fun installVerifiedTables(
        documentBytes: ByteArray,
        manifest: TlsFingerprintManifest,
    ): String

    fun installedGeneratedAt(): String? = null

    fun installedDocumentBytes(): ByteArray? = null
}

@Serializable
data class TlsFingerprintManifest(
    val schema: Int,
    val name: String,
    val format: String,
    @SerialName("generated_at") val generatedAt: String,
    val source: TlsFingerprintManifestSource,
    val artifact: TlsFingerprintManifestArtifact,
    val compatibility: TlsFingerprintManifestCompatibility,
)

@Serializable
data class TlsFingerprintManifestSource(
    val repo: String,
    val dataset: String,
    val license: String,
    @SerialName("profile_count") val profileCount: Int,
)

@Serializable
data class TlsFingerprintManifestArtifact(
    val file: String,
    val size: Long,
    val sha256: String,
)

@Serializable
data class TlsFingerprintManifestCompatibility(
    @SerialName("min_app_version") val minAppVersion: String,
)

@Serializable
data class TlsFingerprintProfile(
    val name: String,
    val describes: String,
    @SerialName("fingerprint_sha256") val fingerprintSha256: String,
    val fingerprint: JsonObject,
)

@Serializable
data class TlsFingerprintTables(
    val schema: Int,
    @SerialName("generated_at") val generatedAt: String,
    val profiles: List<TlsFingerprintProfile>,
)

enum class TlsFingerprintUpdateStatus {
    UPDATED,
    UP_TO_DATE,
    SKIPPED,
    FAILED,
}

data class TlsFingerprintUpdateResult(
    val status: TlsFingerprintUpdateStatus,
    val retryable: Boolean = false,
    val installedPath: String? = null,
    val profileCount: Int? = null,
    val reason: String? = null,
)

class TlsFingerprintUpdateClient(
    private val httpClient: OkHttpClient,
    private val json: Json,
    private val publicKeyPem: String = FOXHOLE_TLS_FINGERPRINT_MANIFEST_PUBLIC_KEY_PEM,
    private val currentVersionName: String = BuildConfig.VERSION_NAME,
    private val resolver: RemoteHostResolver? = null,
    private val now: () -> Instant = Instant::now,
) {
    suspend fun update(
        manifestUrl: String,
        store: TlsFingerprintStore,
        onPhase: (RemoteUpdatePhase) -> Unit = {},
        onProgress: (RemoteDownloadProgress) -> Unit = {},
    ): TlsFingerprintUpdateResult =
        withContext(Dispatchers.IO) {
            runCatching { performUpdate(manifestUrl, store, onPhase, onProgress) }
                .getOrElse { error ->
                    val retryable =
                        when (error) {
                            is TlsFingerprintUpdateException -> error.retryable
                            is IOException -> true
                            else -> false
                        }
                    TlsFingerprintUpdateResult(
                        status = TlsFingerprintUpdateStatus.FAILED,
                        retryable = retryable,
                        reason = error.message ?: error.javaClass.simpleName,
                    )
                }
        }

    private suspend fun performUpdate(
        manifestUrl: String,
        store: TlsFingerprintStore,
        onPhase: (RemoteUpdatePhase) -> Unit,
        onProgress: (RemoteDownloadProgress) -> Unit,
    ): TlsFingerprintUpdateResult {
        onPhase(RemoteUpdatePhase.CHECKING)
        val manifestHttpUrl =
            manifestUrl
                .trim()
                .ensurePublicHttpsUrl(resolveHost = true, resolver = resolver)
        val guardedClient = guardedClient()
        val manifestBytes = guardedClient.getBytes(manifestHttpUrl, MAX_MANIFEST_BYTES, "manifest")
        val signatureBytes =
            guardedClient.getBytes(
                manifestHttpUrl.signatureUrl(),
                MAX_SIGNATURE_BYTES,
                "manifest signature",
            )
        requireFoxholeDbManifestSignature(manifestBytes, signatureBytes, publicKeyPem)
        val manifest = json.decodeFromString<TlsFingerprintManifest>(manifestBytes.toString(Charsets.UTF_8))
        manifest.requireValid()
        if (manifest.isAlreadyInstalled(store.installedGeneratedAt())) {
            return TlsFingerprintUpdateResult(
                status = TlsFingerprintUpdateStatus.UP_TO_DATE,
                reason = "tls fingerprint tables are already current",
            )
        }
        val artifactUrl =
            requireNotNull(manifestHttpUrl.resolve(manifest.artifact.file)) {
                "invalid artifact url"
            }
        onPhase(RemoteUpdatePhase.DOWNLOADING)
        val artifactBytes =
            guardedClient.getBytes(
                artifactUrl,
                MAX_TABLES_BYTES,
                "tls fingerprint tables",
                expectedBytes = manifest.artifact.size,
                onProgress = onProgress,
            )
        onPhase(RemoteUpdatePhase.VERIFYING)
        val tables = artifactBytes.requireValidArtifact(manifest)
        val installedPath = store.installVerifiedTables(artifactBytes, manifest)
        return TlsFingerprintUpdateResult(
            status = TlsFingerprintUpdateStatus.UPDATED,
            installedPath = installedPath,
            profileCount = tables.profiles.size,
        )
    }

    private fun guardedClient(): OkHttpClient =
        httpClient
            .withBoundedRemoteFetchTimeouts(
                connectTimeoutMs = TLS_FINGERPRINT_CONNECT_TIMEOUT_MS,
                readTimeoutMs = TLS_FINGERPRINT_READ_TIMEOUT_MS,
                callTimeoutMs = TLS_FINGERPRINT_CALL_TIMEOUT_MS,
            ).newBuilder()
            .dns(PublicRemoteDns(httpClient.dns::lookup))
            .connectTimeout(TLS_FINGERPRINT_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(TLS_FINGERPRINT_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(TLS_FINGERPRINT_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()

    private fun TlsFingerprintManifest.isAlreadyInstalled(installedGeneratedAt: String?): Boolean {
        val installed =
            installedGeneratedAt
                ?.let { stamp -> runCatching { Instant.parse(stamp) }.getOrNull() }
                ?: return false
        val incoming = Instant.parse(generatedAt)
        requireNotAFoxholeDbRollback(incoming, installed, "tls fingerprint tables")
        return incoming == installed
    }

    private fun TlsFingerprintManifest.requireValid() {
        require(schema == EXPECTED_MANIFEST_SCHEMA) { "unsupported manifest schema" }
        require(name == EXPECTED_MANIFEST_NAME) { "unexpected manifest name" }
        require(format == EXPECTED_ARTIFACT_FORMAT) { "unexpected artifact format" }
        require(source.license.isNotBlank()) { "missing source license" }
        require(source.profileCount >= MIN_PROFILE_COUNT) { "too few fingerprint profiles" }
        require(artifact.file == EXPECTED_ARTIFACT_FILE) { "unexpected artifact file" }
        require(artifact.size in MIN_TABLES_BYTES..MAX_TABLES_BYTES) { "unexpected artifact size" }
        require(artifact.sha256.isSha256Hex()) { "invalid artifact sha256" }
        require(
            compareAppVersions(currentVersionName, compatibility.minAppVersion) >= 0,
        ) {
            "app version is too old for the tls fingerprint feed"
        }
        requireFreshFoxholeDbManifest(generatedAt, "tls fingerprint", now())
    }

    private fun ByteArray.requireValidArtifact(manifest: TlsFingerprintManifest): TlsFingerprintTables {
        require(size.toLong() == manifest.artifact.size) { "tls fingerprint tables size mismatch" }
        require(sha256Hex() == manifest.artifact.sha256) { "tls fingerprint tables sha256 mismatch" }
        val tables =
            runCatching { json.decodeFromString<TlsFingerprintTables>(toString(Charsets.UTF_8)) }
                .getOrElse {
                    throw TlsFingerprintUpdateException(
                        "tls fingerprint tables are not valid JSON",
                        retryable = false,
                    )
                }
        require(tables.schema == EXPECTED_TABLES_SCHEMA) { "unsupported tls fingerprint tables schema" }
        require(tables.profiles.size == manifest.source.profileCount) {
            "tls fingerprint tables carry ${tables.profiles.size} profiles, the manifest declares " +
                "${manifest.source.profileCount}"
        }
        tables.requireDistinctProfiles()
        tables.profiles.forEach { profile -> profile.requireOwnDigest() }
        return tables
    }

    private fun TlsFingerprintTables.requireDistinctProfiles() {
        val names = profiles.map(TlsFingerprintProfile::name)
        require(names.none(String::isBlank)) { "tls fingerprint profile has a blank name" }
        require(names.toSet().size == names.size) { "duplicate tls fingerprint profile name" }
    }

    private fun TlsFingerprintProfile.requireOwnDigest() {
        require(fingerprintSha256.isSha256Hex()) { "profile $name has an invalid fingerprint_sha256" }
        val derived = fingerprint.canonicalDigest()
        require(derived == fingerprintSha256) {
            "profile $name hashes to $derived but declares $fingerprintSha256"
        }
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
                throw TlsFingerprintUpdateException(
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

    private class TlsFingerprintUpdateException(
        message: String,
        val retryable: Boolean,
    ) : IllegalStateException(message)

    private companion object {
        const val EXPECTED_MANIFEST_SCHEMA = 1
        const val EXPECTED_MANIFEST_NAME = "foxhole-tls-fingerprints"
        const val EXPECTED_ARTIFACT_FORMAT = "tls-fingerprint-tables-json"
        const val EXPECTED_ARTIFACT_FILE = "fingerprints.json"
        const val EXPECTED_TABLES_SCHEMA = 1
        const val MIN_PROFILE_COUNT = 4
        const val MIN_TABLES_BYTES = 2L
        const val MAX_TABLES_BYTES = 4L * 1024L * 1024L
        const val MAX_MANIFEST_BYTES = 64L * 1024L
        const val MAX_SIGNATURE_BYTES = MAX_FOXHOLE_DB_SIGNATURE_BYTES
        const val TLS_FINGERPRINT_CONNECT_TIMEOUT_MS = 10_000L
        const val TLS_FINGERPRINT_READ_TIMEOUT_MS = 30_000L
        const val TLS_FINGERPRINT_CALL_TIMEOUT_MS = 45_000L
    }
}

internal fun JsonObject.canonicalDigest(): String = canonicalJson().toByteArray(Charsets.US_ASCII).sha256Hex()

internal fun JsonElement.canonicalJson(): String = StringBuilder().also { out -> appendCanonical(out) }.toString()

private fun JsonElement.appendCanonical(out: StringBuilder) {
    when (this) {
        is JsonObject -> {
            out.append('{')
            entries
                .sortedBy { entry -> entry.key }
                .forEachIndexed { index, entry ->
                    if (index > 0) {
                        out.append(',')
                    }
                    entry.key.appendCanonicalString(out)
                    out.append(':')
                    entry.value.appendCanonical(out)
                }
            out.append('}')
        }

        is JsonArray -> {
            out.append('[')
            forEachIndexed { index, element ->
                if (index > 0) {
                    out.append(',')
                }
                element.appendCanonical(out)
            }
            out.append(']')
        }

        is JsonPrimitive ->
            if (isString) {
                content.appendCanonicalString(out)
            } else {
                out.append(content)
            }
    }
}

private const val FORM_FEED = '\u000C'

private fun String.appendCanonicalString(out: StringBuilder) {
    out.append('"')
    forEach { character ->
        when {
            character == '"' -> out.append("\\\"")
            character == '\\' -> out.append("\\\\")
            character == '\n' -> out.append("\\n")
            character == '\r' -> out.append("\\r")
            character == '\t' -> out.append("\\t")
            character == '\b' -> out.append("\\b")
            character == FORM_FEED -> out.append("\\f")
            character < ' ' || character > '~' -> out.append("\\u%04x".format(character.code))
            else -> out.append(character)
        }
    }
    out.append('"')
}

const val FOXHOLE_TLS_FINGERPRINT_MANIFEST_URL =
    "$FOXHOLE_DB_PAGES_BASE_URL/fingerprint-manifest.json"

internal const val FOXHOLE_TLS_FINGERPRINT_MANIFEST_PUBLIC_KEY_PEM = FOXHOLE_DB_MANIFEST_PUBLIC_KEY_PEM
