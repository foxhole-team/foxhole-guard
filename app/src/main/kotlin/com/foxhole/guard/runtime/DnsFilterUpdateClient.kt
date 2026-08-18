package com.foxhole.guard.runtime

import com.foxhole.core.model.DnsFilterCategory
import com.foxhole.core.network.RemoteHostResolver
import com.foxhole.core.network.ensurePublicHttpsUrl
import com.foxhole.core.network.requirePublicHttpsUrl
import com.foxhole.core.runtime.RuntimeDnsRuleSetInstallOutcome
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
import okhttp3.ResponseBody
import okio.Buffer
import java.io.IOException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.concurrent.TimeUnit

interface DnsFilterRuleSetStore {
    suspend fun installVerifiedDnsRuleSet(ruleSet: VerifiedDnsRuleSet): String
}

/**
 * Exact bytes that passed the Android-side channel checks and must be checked
 * again by FoxCore before activation.
 */
data class VerifiedDnsRuleSet(
    val manifest: DnsFilterManifest,
    val manifestBytes: ByteArray,
    val signatureBytes: ByteArray,
    val artifactBytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is VerifiedDnsRuleSet &&
                    manifest == other.manifest &&
                    manifestBytes.contentEquals(other.manifestBytes) &&
                    signatureBytes.contentEquals(other.signatureBytes) &&
                    artifactBytes.contentEquals(other.artifactBytes)
                )

    override fun hashCode(): Int {
        var result = manifest.hashCode()
        result = 31 * result + manifestBytes.contentHashCode()
        result = 31 * result + signatureBytes.contentHashCode()
        return 31 * result + artifactBytes.contentHashCode()
    }
}

/** Schema consumed verbatim by `foxcore-route::ruleset::verify_rule_set`. */
@Serializable
data class DnsFilterManifest(
    val schema: Int,
    val name: String,
    val format: String,
    val sequence: Long,
    @SerialName("generated_at_unix") val generatedAtUnix: Long,
    @SerialName("expires_at_unix") val expiresAtUnix: Long,
    @SerialName("key_sha256") val keySha256: String,
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
    @SerialName("block_entries") val blockEntries: Long,
    @SerialName("allow_entries") val allowEntries: Long,
)

@Serializable
data class DnsFilterManifestCompatibility(
    @SerialName("core_schema") val coreSchema: Int,
)

/** Compatibility holder for settings/repository call sites during the schema cutover. */
@Serializable
data class InstalledDnsRuleSets(
    val ruleSets: Map<String, InstalledDnsRuleSet> = emptyMap(),
) {
    fun forCategory(category: DnsFilterCategory): InstalledDnsRuleSet? = ruleSets[category.name]
}

@Serializable
data class InstalledDnsRuleSet(
    val tag: String,
    val sha256: String,
    val size: Long,
    val sequence: Long = 0,
)

enum class DnsFilterUpdateStatus {
    UPDATED,
    UP_TO_DATE,
    SKIPPED,
    FAILED,
}

data class DnsFilterUpdateResult(
    val status: DnsFilterUpdateStatus,
    val retryable: Boolean = false,
    val installedPath: String? = null,
    val sourceCommit: String? = null,
    val reason: String? = null,
    val liveActivation: RuntimeDnsRuleSetInstallOutcome? = null,
)

enum class DnsFilterUpdateAvailability {
    UP_TO_DATE,
    UPDATE_AVAILABLE,
    UNKNOWN,
}

data class DnsFilterUpdateCheck(
    val availability: DnsFilterUpdateAvailability,
    val remoteCommit: String? = null,
    val reason: String? = null,
)

class DnsFilterUpdateClient(
    private val httpClient: OkHttpClient,
    private val json: Json,
    private val publicKeyPem: String = FOXHOLE_DNS_MANIFEST_PUBLIC_KEY_PEM,
    @Suppress("unused")
    private val currentVersionName: String = BuildConfig.VERSION_NAME,
    private val resolver: RemoteHostResolver? = null,
    private val nowUnixSeconds: () -> Long = { System.currentTimeMillis() / 1_000L },
) {
    @Suppress("LongParameterList")
    suspend fun update(
        manifestUrl: String,
        store: DnsFilterRuleSetStore,
        requestedTags: Map<DnsFilterCategory, String> = emptyMap(),
        installedRuleSets: InstalledDnsRuleSets = InstalledDnsRuleSets(),
        installedCommit: String? = null,
        onPhase: (RemoteUpdatePhase) -> Unit = {},
        onProgress: (RemoteDownloadProgress) -> Unit = {},
    ): DnsFilterUpdateResult =
        withContext(Dispatchers.IO) {
            // Kept in the signature so settings written by older builds migrate
            // without a second repository API. FoxCore v1 publishes one merged,
            // categorized FST and ignores old per-category artifact choices.
            @Suppress("UNUSED_VARIABLE")
            val ignoredLegacySelection = requestedTags
            runCatching {
                val client = buildGuardedClient()
                val manifestHttpUrl =
                    manifestUrl
                        .trim()
                        .ensurePublicHttpsUrl(resolveHost = true, resolver = resolver)
                onPhase(RemoteUpdatePhase.CHECKING)
                val envelope = client.fetchVerifiedManifest(manifestHttpUrl)
                val manifest = envelope.manifest
                requireNotRolledBack(manifest, installedRuleSets)
                if (installedCommit != null &&
                    manifest.source.commit.equals(installedCommit, ignoreCase = true)
                ) {
                    return@runCatching DnsFilterUpdateResult(
                        status = DnsFilterUpdateStatus.UP_TO_DATE,
                        sourceCommit = manifest.source.commit,
                    )
                }
                val artifactUrl =
                    requireNotNull(manifestHttpUrl.resolve(manifest.artifact.file)) {
                        "invalid artifact url"
                    }
                onPhase(RemoteUpdatePhase.DOWNLOADING)
                val artifact =
                    client.getBytes(
                        artifactUrl,
                        MAX_RULE_SET_BYTES,
                        "rule set",
                        expectedBytes = manifest.artifact.size,
                        onProgress = onProgress,
                    )
                onPhase(RemoteUpdatePhase.VERIFYING)
                artifact.requireValidArtifact(manifest)
                val installedPath =
                    store.installVerifiedDnsRuleSet(
                        VerifiedDnsRuleSet(
                            manifest = manifest,
                            manifestBytes = envelope.bytes,
                            signatureBytes = envelope.signature,
                            artifactBytes = artifact,
                        ),
                    )
                DnsFilterUpdateResult(
                    status = DnsFilterUpdateStatus.UPDATED,
                    installedPath = installedPath,
                    sourceCommit = manifest.source.commit,
                )
            }.getOrElse(::failureResult)
        }

    suspend fun checkForUpdate(
        manifestUrl: String,
        installedCommit: String?,
        requestedTags: Map<DnsFilterCategory, String> = emptyMap(),
        installedRuleSets: InstalledDnsRuleSets = InstalledDnsRuleSets(),
    ): DnsFilterUpdateCheck =
        withContext(Dispatchers.IO) {
            @Suppress("UNUSED_VARIABLE")
            val ignoredLegacySelection = requestedTags
            runCatching {
                val manifestHttpUrl =
                    manifestUrl
                        .trim()
                        .ensurePublicHttpsUrl(resolveHost = true, resolver = resolver)
                val manifest = buildGuardedClient().fetchVerifiedManifest(manifestHttpUrl).manifest
                requireNotRolledBack(manifest, installedRuleSets)
                DnsFilterUpdateCheck(
                    availability =
                    if (installedCommit != null &&
                        manifest.source.commit.equals(installedCommit, ignoreCase = true)
                    ) {
                        DnsFilterUpdateAvailability.UP_TO_DATE
                    } else {
                        DnsFilterUpdateAvailability.UPDATE_AVAILABLE
                    },
                    remoteCommit = manifest.source.commit,
                )
            }.getOrElse { error ->
                DnsFilterUpdateCheck(
                    availability = DnsFilterUpdateAvailability.UNKNOWN,
                    reason = error.message ?: error.javaClass.simpleName,
                )
            }
        }

    private fun failureResult(error: Throwable): DnsFilterUpdateResult =
        DnsFilterUpdateResult(
            status = DnsFilterUpdateStatus.FAILED,
            retryable =
            when (error) {
                is DnsFilterUpdateException -> error.retryable
                is IOException -> true
                else -> false
            },
            reason = error.message ?: error.javaClass.simpleName,
        )

    private fun buildGuardedClient(): OkHttpClient =
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

    private fun OkHttpClient.fetchVerifiedManifest(url: HttpUrl): ManifestEnvelope {
        val bytes = getBytes(url, MAX_MANIFEST_BYTES, "manifest")
        val signature = getBytes(url.signatureUrl(), MAX_SIGNATURE_BYTES, "manifest signature")
        val publicKey = requireVerifiedManifestSignature(bytes, signature)
        val manifest =
            json.decodeFromString<DnsFilterManifest>(bytes.toString(Charsets.UTF_8))
        manifest.requireValid(publicKey)
        return ManifestEnvelope(bytes, signature, manifest)
    }

    private fun requireVerifiedManifestSignature(
        manifestBytes: ByteArray,
        signatureBytes: ByteArray,
    ): ByteArray {
        val keyBytes =
            publicKeyPem
                .lineSequence()
                .map(String::trim)
                .filter { line -> line.isNotBlank() && !line.startsWith("-----") }
                .joinToString(separator = "")
                .let(Base64.getDecoder()::decode)
        require(keyBytes.isNotEmpty() && keyBytes.size <= MAX_PUBLIC_KEY_BYTES) {
            "DNS update public key is invalid"
        }
        val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(keyBytes))
        val verifier =
            Signature.getInstance("SHA256withECDSA").apply {
                initVerify(publicKey)
                update(manifestBytes)
            }
        if (!verifier.verify(signatureBytes)) {
            throw DnsFilterUpdateException(
                "manifest signature verification failed",
                retryable = false,
            )
        }
        return keyBytes
    }

    private fun DnsFilterManifest.requireValid(publicKey: ByteArray) {
        require(schema == EXPECTED_MANIFEST_SCHEMA) { "unsupported manifest schema" }
        require(name == EXPECTED_MANIFEST_NAME) { "unexpected manifest name" }
        require(format == EXPECTED_RULE_SET_FORMAT) { "unexpected rule set format" }
        require(sequence > 0L) { "invalid manifest sequence" }
        val now = nowUnixSeconds()
        require(generatedAtUnix <= now + MAX_GENERATED_AT_FUTURE_SKEW_SECONDS) {
            "manifest generated_at is in the future"
        }
        val validity = expiresAtUnix - generatedAtUnix
        require(validity in 1..MAX_MANIFEST_VALIDITY_SECONDS && now < expiresAtUnix) {
            "manifest is expired or has an invalid validity window"
        }
        require(keySha256.equals(publicKey.sha256Hex(), ignoreCase = true)) {
            "manifest public key identity mismatch"
        }
        require(source.name == EXPECTED_SOURCE_NAME) { "unexpected source name" }
        require(source.repo in EXPECTED_SOURCE_REPOS) { "unexpected source repository" }
        require(source.license == EXPECTED_SOURCE_LICENSE) { "unexpected source license" }
        require(source.inputPath == EXPECTED_SOURCE_INPUT_PATH) {
            "unexpected source input path"
        }
        require(source.commit.isShaLike()) { "invalid source commit" }
        require(source.inputSha256.isSha256Hex()) { "invalid source sha256" }
        require(artifact.file == EXPECTED_ARTIFACT_FILE) { "unexpected artifact file" }
        require(artifact.size in MIN_RULE_SET_BYTES..MAX_RULE_SET_BYTES) {
            "unexpected artifact size"
        }
        require(artifact.sha256.isSha256Hex()) { "invalid artifact sha256" }
        require(artifact.blockEntries in 0..MAX_RULE_ENTRIES) { "invalid block entry count" }
        require(artifact.allowEntries in 0..MAX_RULE_ENTRIES) { "invalid allow entry count" }
        require(artifact.blockEntries + artifact.allowEntries > 0L) { "empty rule set" }
        require(compatibility.coreSchema == FOXCORE_DNS_CORE_SCHEMA) {
            "unsupported FoxCore DNS schema"
        }
    }

    private fun requireNotRolledBack(
        manifest: DnsFilterManifest,
        installed: InstalledDnsRuleSets,
    ) {
        val previous =
            installed.ruleSets.values
                .filter { ruleSet -> ruleSet.tag == manifest.name }
                .maxByOrNull(InstalledDnsRuleSet::sequence)
                ?: return
        require(manifest.sequence >= previous.sequence) { "manifest sequence rollback" }
        require(
            manifest.sequence != previous.sequence ||
                manifest.artifact.sha256.equals(previous.sha256, ignoreCase = true),
        ) {
            "manifest sequence was reused for different bytes"
        }
    }

    private fun ByteArray.requireValidArtifact(manifest: DnsFilterManifest) {
        val artifact = manifest.artifact
        require(size.toLong() == artifact.size) { "rule set size mismatch" }
        require(isFoxCoreDnsFst()) { "invalid FoxCore DNS rule set header" }
        require(sha256Hex().equals(artifact.sha256, ignoreCase = true)) {
            "rule set sha256 mismatch"
        }
        require(readU64(8) == artifact.blockEntries) { "block entry count mismatch" }
        require(readU64(16) == artifact.allowEntries) { "allow entry count mismatch" }
        val blockBytes = readU64(24)
        val allowBytes = readU64(32)
        require(80L + blockBytes + allowBytes == size.toLong()) {
            "rule set payload length mismatch"
        }
        require(copyOfRange(40, 72).toHex().equals(manifest.source.inputSha256, ignoreCase = true)) {
            "rule set source identity mismatch"
        }
        require(copyOfRange(72, 80).all { byte -> byte == 0.toByte() }) {
            "rule set reserved header bytes are not zero"
        }
    }

    private fun ByteArray.readU64(offset: Int): Long {
        require(size >= offset + Long.SIZE_BYTES) { "truncated rule set header" }
        var value = 0L
        for (index in offset until offset + Long.SIZE_BYTES) {
            val byte = this[index].toLong() and 0xffL
            require(value <= (Long.MAX_VALUE - byte) ushr 8) { "rule set integer overflow" }
            value = (value shl 8) or byte
        }
        return value
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
                throw DnsFilterUpdateException(
                    "$label request failed with HTTP ${response.code}",
                    retryable = response.isRetryableHttpFailure(),
                )
            }
            return requireNotNull(response.body) { "$label response body is empty" }
                .readBytesCapped(
                    maxBytes = maxBytes,
                    expectedBytes = expectedBytes,
                    onProgress = onProgress,
                )
        }
    }

    private fun HttpUrl.signatureUrl(): HttpUrl =
        newBuilder()
            .encodedPath("$encodedPath.sig")
            .build()

    private fun Response.isRetryableHttpFailure(): Boolean =
        code == 408 || code == 429 || code in 500..599

    private data class ManifestEnvelope(
        val bytes: ByteArray,
        val signature: ByteArray,
        val manifest: DnsFilterManifest,
    )

    private class DnsFilterUpdateException(
        message: String,
        val retryable: Boolean,
    ) : IllegalStateException(message)

    private companion object {
        const val EXPECTED_MANIFEST_SCHEMA = 2
        const val EXPECTED_MANIFEST_NAME = "foxhole-adguard-dns-filter"
        const val EXPECTED_RULE_SET_FORMAT = "foxhole-dns-fst-v1"
        const val EXPECTED_SOURCE_NAME = "AdGuardSDNSFilter"
        val EXPECTED_SOURCE_REPOS =
            setOf(
                "https://github.com/AdguardTeam/AdGuardSDNSFilter",
                "https://github.com/AdguardTeam/AdGuardSDNSFilter.git",
            )
        const val EXPECTED_SOURCE_LICENSE = "GPL-3.0"
        const val EXPECTED_SOURCE_INPUT_PATH = "Filters/filter.txt"
        const val EXPECTED_ARTIFACT_FILE = "adguard-dns-filter.fhds"
        const val FOXCORE_DNS_CORE_SCHEMA = 1
        const val MIN_RULE_SET_BYTES = 80L
        const val MAX_RULE_SET_BYTES = 64L * 1024L * 1024L
        const val MAX_RULE_ENTRIES = 5_000_000L
        const val MAX_MANIFEST_BYTES = 64L * 1024L
        const val MAX_SIGNATURE_BYTES = MAX_FOXHOLE_DB_SIGNATURE_BYTES
        const val MAX_PUBLIC_KEY_BYTES = 4 * 1024
        const val MAX_GENERATED_AT_FUTURE_SKEW_SECONDS = 10L * 60L
        const val MAX_MANIFEST_VALIDITY_SECONDS = 31L * 24L * 60L * 60L
        const val DNS_FILTER_CONNECT_TIMEOUT_MS = 10_000L
        const val DNS_FILTER_READ_TIMEOUT_MS = 30_000L
        const val DNS_FILTER_CALL_TIMEOUT_MS = 45_000L
    }
}

internal fun ByteArray.isFoxCoreDnsFst(): Boolean =
    size >= 8 &&
        this[0] == 'F'.code.toByte() &&
        this[1] == 'H'.code.toByte() &&
        this[2] == 'D'.code.toByte() &&
        this[3] == 'N'.code.toByte() &&
        this[4] == 'S'.code.toByte() &&
        this[5] == '1'.code.toByte() &&
        this[6] == 0.toByte() &&
        this[7] == 0.toByte()

internal fun ByteArray.sha256Hex(): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(this)
        .toHex()

private fun ByteArray.toHex(): String =
    joinToString(separator = "") { byte -> "%02x".format(byte) }

internal fun compareAppVersions(
    left: String,
    right: String,
): Int {
    val leftParts = left.versionNumberParts()
    val rightParts = right.versionNumberParts()
    val maxSize = maxOf(leftParts.size, rightParts.size)
    for (index in 0 until maxSize) {
        val comparison =
            leftParts
                .getOrElse(index) { 0 }
                .compareTo(rightParts.getOrElse(index) { 0 })
        if (comparison != 0) {
            return comparison
        }
    }
    return 0
}

private fun String.versionNumberParts(): List<Int> =
    substringBefore('-')
        .split('.')
        .mapNotNull(String::toIntOrNull)

internal fun String.isSha256Hex(): Boolean =
    length == 64 &&
        all { character ->
            character in '0'..'9' || character.lowercaseChar() in 'a'..'f'
        }

private fun String.isShaLike(): Boolean =
    length in 7..64 &&
        all { character ->
            character in '0'..'9' || character.lowercaseChar() in 'a'..'f'
        }

internal fun ResponseBody.readBytesCapped(
    maxBytes: Long,
    expectedBytes: Long? = null,
    onProgress: (RemoteDownloadProgress) -> Unit = {},
): ByteArray {
    require(maxBytes > 0L) { "maxBytes must be positive" }
    val declaredLength = contentLength()
    require(declaredLength <= maxBytes || declaredLength == -1L) {
        "response body too large: $declaredLength > $maxBytes"
    }
    val source = source()
    val buffer = Buffer()
    val progressTotal =
        expectedBytes?.takeIf { bytes -> bytes > 0L }
            ?: declaredLength.takeIf { bytes -> bytes > 0L }
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
        progressTotal?.let { total ->
            onProgress(RemoteDownloadProgress(downloadedBytes = buffer.size, totalBytes = total))
        }
    }
    return buffer.readByteArray()
}

internal const val FOXHOLE_DNS_MANIFEST_PUBLIC_KEY_PEM = FOXHOLE_DB_MANIFEST_PUBLIC_KEY_PEM
