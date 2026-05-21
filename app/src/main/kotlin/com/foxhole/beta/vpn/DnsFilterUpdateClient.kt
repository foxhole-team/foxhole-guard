package com.foxhole.beta.vpn

import com.foxhole.beta.BuildConfig
import com.foxhole.beta.core.data.withBoundedRemoteFetchTimeouts
import com.foxhole.beta.core.network.PublicRemoteDns
import com.foxhole.beta.core.network.RemoteHostResolver
import com.foxhole.beta.core.network.ensurePublicHttpsUrl
import com.foxhole.beta.core.network.requirePublicHttpsUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import java.io.IOException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64
import java.util.concurrent.TimeUnit

interface DnsFilterRuleSetStore {
    suspend fun installVerifiedDnsRuleSet(
        ruleSetBytes: ByteArray,
        manifest: DnsFilterManifest,
    ): String
}

@Serializable
data class DnsFilterManifest(
    val schema: Int,
    val name: String,
    val format: String,
    @SerialName("generated_at") val generatedAt: String,
    val source: DnsFilterManifestSource,
    val artifact: DnsFilterManifestArtifact,
    val compatibility: DnsFilterManifestCompatibility,
)

@Serializable
data class DnsFilterManifestSource(
    val name: String,
    val repo: String,
    val commit: String,
    val license: String,
    @SerialName("input_path") val inputPath: String,
    @SerialName("input_sha256") val inputSha256: String,
)

@Serializable
data class DnsFilterManifestArtifact(
    val file: String,
    val size: Long,
    val sha256: String,
)

@Serializable
data class DnsFilterManifestCompatibility(
    @SerialName("sing_box_version") val singBoxVersion: String,
    @SerialName("min_app_version") val minAppVersion: String,
)

enum class DnsFilterUpdateStatus {
    UPDATED,
    SKIPPED,
    FAILED,
}

data class DnsFilterUpdateResult(
    val status: DnsFilterUpdateStatus,
    val retryable: Boolean = false,
    val installedPath: String? = null,
    val sourceCommit: String? = null,
    val reason: String? = null,
)

class DnsFilterUpdateClient(
    private val httpClient: OkHttpClient,
    private val json: Json,
    private val publicKeyPem: String = FOXHOLE_DNS_MANIFEST_PUBLIC_KEY_PEM,
    private val currentVersionName: String = BuildConfig.VERSION_NAME,
    private val resolver: RemoteHostResolver? = null,
) {
    suspend fun update(
        manifestUrl: String,
        store: DnsFilterRuleSetStore,
    ): DnsFilterUpdateResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val manifestHttpUrl =
                    manifestUrl
                        .trim()
                        .ensurePublicHttpsUrl(resolveHost = true, resolver = resolver)
                val guardedClient =
                    httpClient
                        .withBoundedRemoteFetchTimeouts(
                            connectTimeoutMs = DNS_FILTER_CONNECT_TIMEOUT_MS,
                            readTimeoutMs = DNS_FILTER_READ_TIMEOUT_MS,
                            callTimeoutMs = DNS_FILTER_CALL_TIMEOUT_MS,
                        ).newBuilder()
                        .dns(PublicRemoteDns(httpClient.dns::lookup))
                        .connectTimeout(DNS_FILTER_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        .readTimeout(DNS_FILTER_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        .callTimeout(DNS_FILTER_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        .build()
                val manifestBytes = guardedClient.getBytes(manifestHttpUrl, MAX_MANIFEST_BYTES, "manifest")
                val signatureBytes = guardedClient.getBytes(manifestHttpUrl.signatureUrl(), MAX_SIGNATURE_BYTES, "manifest signature")
                requireVerifiedManifestSignature(manifestBytes, signatureBytes)
                val manifest = json.decodeFromString<DnsFilterManifest>(manifestBytes.toString(Charsets.UTF_8))
                manifest.requireValid()
                val artifactUrl = requireNotNull(manifestHttpUrl.resolve(manifest.artifact.file)) { "invalid artifact url" }
                val artifactBytes = guardedClient.getBytes(artifactUrl, MAX_RULE_SET_BYTES, "rule set")
                artifactBytes.requireValidArtifact(manifest.artifact)
                val installedPath = store.installVerifiedDnsRuleSet(artifactBytes, manifest)
                DnsFilterUpdateResult(
                    status = DnsFilterUpdateStatus.UPDATED,
                    installedPath = installedPath,
                    sourceCommit = manifest.source.commit,
                )
            }.getOrElse { error ->
                val retryable =
                    when (error) {
                        is DnsFilterUpdateException -> error.retryable
                        is IOException -> true
                        else -> false
                    }
                DnsFilterUpdateResult(
                    status = DnsFilterUpdateStatus.FAILED,
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
            throw DnsFilterUpdateException("manifest signature verification failed", retryable = false)
        }
    }

    private fun DnsFilterManifest.requireValid() {
        require(schema == EXPECTED_MANIFEST_SCHEMA) { "unsupported manifest schema" }
        require(name == EXPECTED_MANIFEST_NAME) { "unexpected manifest name" }
        require(format == EXPECTED_RULE_SET_FORMAT) { "unexpected rule set format" }
        require(source.name == EXPECTED_SOURCE_NAME) { "unexpected source name" }
        require(source.repo in EXPECTED_SOURCE_REPOS) { "unexpected source repository" }
        require(source.license == EXPECTED_SOURCE_LICENSE) { "unexpected source license" }
        require(source.inputPath == EXPECTED_SOURCE_INPUT_PATH) { "unexpected source input path" }
        require(source.commit.isShaLike()) { "invalid source commit" }
        require(source.inputSha256.isSha256Hex()) { "invalid input sha256" }
        require(artifact.file == EXPECTED_ARTIFACT_FILE) { "unexpected artifact file" }
        require(artifact.size in MIN_RULE_SET_BYTES..MAX_RULE_SET_BYTES) { "unexpected artifact size" }
        require(artifact.sha256.isSha256Hex()) { "invalid artifact sha256" }
        require(compatibility.singBoxVersion == BuildConfig.LIBBOX_SOURCE_VERSION) { "unsupported sing-box version" }
        require(compareAppVersions(currentVersionName, compatibility.minAppVersion) >= 0) { "app version is too old for rule set" }
        runCatching { Instant.parse(generatedAt) }
            .getOrElse { throw IllegalArgumentException("invalid generated_at") }
            .also { generatedAtInstant ->
                val futureLimit = Instant.now().plusSeconds(MAX_GENERATED_AT_FUTURE_SKEW_SECONDS)
                require(!generatedAtInstant.isAfter(futureLimit)) { "manifest generated_at is in the future" }
            }
    }

    private fun ByteArray.requireValidArtifact(artifact: DnsFilterManifestArtifact) {
        require(size.toLong() == artifact.size) { "rule set size mismatch" }
        require(isSingBoxSrs()) { "invalid sing-box rule set header" }
        val actualSha256 = sha256Hex()
        require(actualSha256 == artifact.sha256) { "rule set sha256 mismatch" }
    }

    private fun OkHttpClient.getBytes(
        url: HttpUrl,
        maxBytes: Long,
        label: String,
    ): ByteArray {
        url.requirePublicHttpsUrl(resolveHost = true, resolver = resolver)
        newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw DnsFilterUpdateException(
                    "$label request failed with HTTP ${response.code}",
                    retryable = response.isRetryableHttpFailure(),
                )
            }
            return requireNotNull(response.body) { "$label response body is empty" }.readBytesCapped(maxBytes)
        }
    }

    private fun HttpUrl.signatureUrl(): HttpUrl =
        newBuilder()
            .encodedPath("$encodedPath.sig")
            .build()

    private fun Response.isRetryableHttpFailure(): Boolean = code == 408 || code == 429 || code in 500..599

    private class DnsFilterUpdateException(
        message: String,
        val retryable: Boolean,
    ) : IllegalStateException(message)

    private companion object {
        const val EXPECTED_MANIFEST_SCHEMA = 1
        const val EXPECTED_MANIFEST_NAME = "foxhole-adguard-dns-filter"
        const val EXPECTED_RULE_SET_FORMAT = "sing-box-srs"
        const val EXPECTED_SOURCE_NAME = "AdGuardSDNSFilter"
        val EXPECTED_SOURCE_REPOS =
            setOf(
                "https://github.com/AdguardTeam/AdGuardSDNSFilter",
                "https://github.com/AdguardTeam/AdGuardSDNSFilter.git",
            )
        const val EXPECTED_SOURCE_LICENSE = "GPL-3.0"
        const val EXPECTED_SOURCE_INPUT_PATH = "Filters/filter.txt"
        const val EXPECTED_ARTIFACT_FILE = "adguard-dns-filter.srs"
        const val MIN_RULE_SET_BYTES = 4L
        const val MAX_RULE_SET_BYTES = 8L * 1024L * 1024L
        const val MAX_MANIFEST_BYTES = 64L * 1024L
        const val MAX_SIGNATURE_BYTES = 8L * 1024L
        const val MAX_GENERATED_AT_FUTURE_SKEW_SECONDS = 24L * 60L * 60L
        const val DNS_FILTER_CONNECT_TIMEOUT_MS = 10_000L
        const val DNS_FILTER_READ_TIMEOUT_MS = 30_000L
        const val DNS_FILTER_CALL_TIMEOUT_MS = 45_000L
    }
}

internal fun ByteArray.isSingBoxSrs(): Boolean =
    size >= 4 &&
        this[0] == 'S'.code.toByte() &&
        this[1] == 'R'.code.toByte() &&
        this[2] == 'S'.code.toByte() &&
        this[3] == 2.toByte()

internal fun ByteArray.sha256Hex(): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(this)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

internal fun compareAppVersions(
    left: String,
    right: String,
): Int {
    val leftParts = left.versionNumberParts()
    val rightParts = right.versionNumberParts()
    val maxSize = maxOf(leftParts.size, rightParts.size)
    for (index in 0 until maxSize) {
        val comparison = (leftParts.getOrElse(index) { 0 }).compareTo(rightParts.getOrElse(index) { 0 })
        if (comparison != 0) {
            return comparison
        }
    }
    return 0
}

private fun String.versionNumberParts(): List<Int> =
    substringBefore('-')
        .split('.')
        .mapNotNull { part -> part.toIntOrNull() }

private fun String.isSha256Hex(): Boolean =
    length == 64 && all { character -> character in '0'..'9' || character.lowercaseChar() in 'a'..'f' }

private fun String.isShaLike(): Boolean =
    length in 7..64 && all { character -> character in '0'..'9' || character.lowercaseChar() in 'a'..'f' }

private fun ResponseBody.readBytesCapped(maxBytes: Long): ByteArray {
    require(maxBytes > 0L) { "maxBytes must be positive" }
    val declaredLength = contentLength()
    require(declaredLength <= maxBytes || declaredLength == -1L) {
        "response body too large: $declaredLength > $maxBytes"
    }
    val source = source()
    val buffer = Buffer()
    while (true) {
        val remaining = maxBytes + 1L - buffer.size
        if (remaining <= 0L) {
            error("response body exceeded limit: $maxBytes bytes")
        }
        val read = source.read(buffer, minOf(8_192L, remaining))
        if (read == -1L) {
            break
        }
        if (buffer.size > maxBytes) {
            error("response body exceeded limit: $maxBytes bytes")
        }
    }
    return buffer.readByteArray()
}

private const val FOXHOLE_DNS_MANIFEST_PUBLIC_KEY_PEM = """
-----BEGIN PUBLIC KEY-----
MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEUucWYOJ+RmNoGzlv6lyQ7TdvK1Op
6TRMy+ADsghRHXfKh8gIytQMTq0hKq7TB1GRmVeyysYX3kpmuGGz1buayA==
-----END PUBLIC KEY-----
"""
