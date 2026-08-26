package com.foxhole.guard.runtime

import android.content.Context
import com.foxhole.core.network.RemoteHostResolver
import com.foxhole.core.network.ensurePublicHttpsUrl
import com.foxhole.core.network.requirePublicHttpsUrl
import com.foxhole.core.runtime.GeoIpDatabaseMetadata
import com.foxhole.core.runtime.GeoIpDatabaseStore
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
import okhttp3.ResponseBody
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.time.Instant

enum class GeoIpUpdateStatus {
    UPDATED,
    UP_TO_DATE,
    FAILED,
}

internal enum class GeoIpInstallDecision {
    INSTALL,
    UP_TO_DATE,
    ROLLBACK,
}

internal fun geoIpInstallDecision(
    incomingGeneratedAt: Instant,
    incomingVersion: String,
    installedGeneratedAt: Instant?,
    installedVersion: String?,
): GeoIpInstallDecision =
    when {
        installedGeneratedAt != null && incomingGeneratedAt.isBefore(installedGeneratedAt) ->
            GeoIpInstallDecision.ROLLBACK

        incomingGeneratedAt == installedGeneratedAt || incomingVersion == installedVersion ->
            GeoIpInstallDecision.UP_TO_DATE

        else -> GeoIpInstallDecision.INSTALL
    }

data class GeoIpUpdateResult(
    val status: GeoIpUpdateStatus,
    val metadata: GeoIpDatabaseMetadata? = null,
    val reason: String? = null,
)

@Serializable
private data class FoxholeGeoIpManifest(
    val schema: Int,
    val name: String,
    val format: String,
    @SerialName("generated_at") val generatedAt: String,
    val version: String,
    val artifacts: List<FoxholeGeoIpArtifact>,
    val compatibility: FoxholeGeoIpCompatibility,
)

@Serializable
private data class FoxholeGeoIpArtifact(
    val file: String,
    val size: Long,
    val sha256: String,
)

@Serializable
private data class FoxholeGeoIpCompatibility(
    @SerialName("min_app_version") val minAppVersion: String,
)

class GeoIpUpdateClient(
    context: Context,
    private val httpClient: OkHttpClient,
    private val json: Json,
    private val resolver: RemoteHostResolver? = null,

    private val manifestUrl: () -> String = { FOXHOLE_GEOIP_MANIFEST_URL },
    private val currentVersionName: String = BuildConfig.VERSION_NAME,
    private val now: () -> Instant = Instant::now,
) {
    private val appContext = context.applicationContext

    private val installedStamp = File(appContext.filesDir, INSTALLED_STAMP_FILE)

    suspend fun checkForUpdate(store: GeoIpDatabaseStore): Boolean? =
        withContext(Dispatchers.IO) {
            runCatching {
                val manifest = guardedClient().fetchVerifiedManifest()
                manifest.version.isNotBlank() && manifest.decide(store) == GeoIpInstallDecision.INSTALL
            }.getOrNull()
        }

    private fun FoxholeGeoIpManifest.decide(store: GeoIpDatabaseStore): GeoIpInstallDecision =
        geoIpInstallDecision(
            incomingGeneratedAt = Instant.parse(generatedAt),
            incomingVersion = version,
            installedGeneratedAt = installedGeneratedAt(),
            installedVersion = store.readMetadata()?.version,
        )

    suspend fun update(
        store: GeoIpDatabaseStore,
        onPhase: (RemoteUpdatePhase) -> Unit = {},
        onProgress: (RemoteDownloadProgress) -> Unit = {},
    ): GeoIpUpdateResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val client = guardedClient()
                onPhase(RemoteUpdatePhase.CHECKING)
                val manifest = client.fetchVerifiedManifest()
                when (manifest.decide(store)) {
                    GeoIpInstallDecision.UP_TO_DATE ->
                        return@runCatching GeoIpUpdateResult(status = GeoIpUpdateStatus.UP_TO_DATE)

                    GeoIpInstallDecision.ROLLBACK ->
                        error(
                            "geoip manifest is older than the installed one " +
                                "(incoming=${manifest.generatedAt} installed=${installedGeneratedAt()})",
                        )

                    GeoIpInstallDecision.INSTALL -> Unit
                }
                val manifestUrl = manifestUrl().asPublicHttpsUrl()
                val ipv4 = manifest.artifactNamed(EXPECTED_IPV4_FILE)
                val ipv6 = manifest.artifactNamed(EXPECTED_IPV6_FILE)
                onPhase(RemoteUpdatePhase.DOWNLOADING)
                val totalBytes = ipv4.size + ipv6.size
                val ipv4File =
                    client.downloadVerified(manifestUrl, ipv4, "geoip ipv4 ranges") { downloaded ->
                        onProgress(RemoteDownloadProgress(downloaded, totalBytes))
                    }
                val ipv6File =
                    runCatching {
                        client.downloadVerified(manifestUrl, ipv6, "geoip ipv6 ranges") { downloaded ->
                            onProgress(RemoteDownloadProgress(ipv4.size + downloaded, totalBytes))
                        }
                    }
                        .onFailure { ipv4File.delete() }
                        .getOrThrow()
                onPhase(RemoteUpdatePhase.VERIFYING)
                val metadata =
                    try {
                        store.install(
                            version = manifest.version,
                            sourceRepo = SOURCE_REPO,
                            license = SOURCE_LICENSE,
                            ipv4File = ipv4File,
                            ipv6File = ipv6File,
                        )
                    } finally {
                        ipv4File.delete()
                        ipv6File.delete()
                    }
                recordInstalledGeneratedAt(manifest.generatedAt)
                GeoIpUpdateResult(status = GeoIpUpdateStatus.UPDATED, metadata = metadata)
            }.getOrElse { error ->
                GeoIpUpdateResult(
                    status = GeoIpUpdateStatus.FAILED,
                    reason = error.message ?: error.javaClass.simpleName,
                )
            }
        }

    /** Manifest + detached signature, verified against the pinned FoxHole DB key, then validated. */
    private fun OkHttpClient.fetchVerifiedManifest(): FoxholeGeoIpManifest {
        val manifestUrl = manifestUrl().asPublicHttpsUrl()
        val manifestBytes = getBytes(manifestUrl, MAX_PACKAGE_BYTES, "geoip manifest")
        val signatureBytes =
            getBytes(manifestUrl.signatureUrl(), MAX_SIGNATURE_BYTES, "geoip manifest signature")
        requireFoxholeDbManifestSignature(manifestBytes, signatureBytes)
        val manifest = json.decodeFromString<FoxholeGeoIpManifest>(manifestBytes.toString(Charsets.UTF_8))
        manifest.requireValid()
        return manifest
    }

    private fun FoxholeGeoIpManifest.requireValid() {
        require(schema == EXPECTED_MANIFEST_SCHEMA) { "unsupported geoip manifest schema" }
        require(name == EXPECTED_MANIFEST_NAME) { "unexpected geoip manifest name" }
        require(format == EXPECTED_ARTIFACT_FORMAT) { "unexpected geoip artifact format" }
        require(version.isNotBlank()) { "empty geoip source version" }
        require(compareAppVersions(currentVersionName, compatibility.minAppVersion) >= 0) {
            "app version is too old for the geoip feed"
        }
        requireFreshFoxholeDbManifest(generatedAt, "geoip", now())
        artifactNamed(EXPECTED_IPV4_FILE)
        artifactNamed(EXPECTED_IPV6_FILE)
    }

    private fun installedGeneratedAt(): Instant? =
        installedStamp
            .takeIf(File::isFile)
            ?.let { file -> runCatching(file::readText).getOrNull() }
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { stamp -> runCatching { Instant.parse(stamp) }.getOrNull() }

    private fun recordInstalledGeneratedAt(generatedAt: String) {
        runCatching {
            val temporary = File(appContext.filesDir, "$INSTALLED_STAMP_FILE.tmp")
            temporary.writeText(generatedAt)
            if (!temporary.renameTo(installedStamp)) {
                temporary.delete()
            }
        }
    }

    private fun FoxholeGeoIpManifest.artifactNamed(file: String): FoxholeGeoIpArtifact {
        val artifact =
            requireNotNull(artifacts.firstOrNull { candidate -> candidate.file == file }) {
                "geoip manifest is missing $file"
            }
        require(artifact.size in MIN_DATABASE_BYTES..MAX_DATABASE_BYTES) { "unexpected size of $file" }
        require(artifact.sha256.isSha256Hex()) { "invalid sha256 of $file" }
        return artifact
    }

    private fun OkHttpClient.downloadVerified(
        manifestUrl: HttpUrl,
        artifact: FoxholeGeoIpArtifact,
        label: String,
        onProgress: (Long) -> Unit,
    ): File {
        val url = requireNotNull(manifestUrl.resolve(artifact.file)) { "invalid url for ${artifact.file}" }
        val target = downloadToTempFile(url, artifact.size, label, onProgress)
        var keep = false
        try {
            require(target.length() == artifact.size) { "$label size mismatch" }
            require(target.sha256Hex() == artifact.sha256) { "$label sha256 mismatch" }
            keep = true
        } finally {
            if (!keep) {
                target.delete()
            }
        }
        return target
    }

    private fun guardedClient(): OkHttpClient =
        httpClient
            .withBoundedRemoteFetchTimeouts(
                connectTimeoutMs = CONNECT_TIMEOUT_MS,
                readTimeoutMs = READ_TIMEOUT_MS,
                callTimeoutMs = CALL_TIMEOUT_MS,
            ).newBuilder()
            .dns(PublicRemoteDns(httpClient.dns::lookup))
            .build()

    private fun String.asPublicHttpsUrl(): HttpUrl =
        ensurePublicHttpsUrl(resolveHost = true, resolver = resolver)

    private fun OkHttpClient.getBytes(
        url: HttpUrl,
        maxBytes: Long,
        label: String,
    ): ByteArray {
        url.requirePublicHttpsUrl(resolveHost = true, resolver = resolver)
        newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("$label request failed with HTTP ${response.code}")
            }
            return requireNotNull(response.body) { "$label response body is empty" }
                .readBytesCapped(maxBytes)
        }
    }

    private fun HttpUrl.signatureUrl(): HttpUrl =
        newBuilder()
            .encodedPath("$encodedPath.sig")
            .build()

    // Streamed digest: the range CSVs are tens of megabytes and must not visit the heap whole.
    private fun File.sha256Hex(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(inputStream().buffered(), digest).use { input ->
            val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
            while (input.read(buffer) != -1) {
                // DigestInputStream updates the digest.
            }
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun OkHttpClient.downloadToTempFile(
        url: HttpUrl,
        maxBytes: Long,
        label: String,
        onProgress: (Long) -> Unit,
    ): File {
        url.requirePublicHttpsUrl(resolveHost = true, resolver = resolver)
        val target = File.createTempFile("geoip-download", ".csv", appContext.cacheDir)
        var completed = false
        try {
            newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("$label request failed with HTTP ${response.code}")
                }
                val total =
                    requireNotNull(response.body) { "$label response body is empty" }
                        .streamCappedTo(target, maxBytes, label, onProgress)
                if (total < MIN_DATABASE_BYTES) {
                    throw IOException("$label too small: $total bytes")
                }
            }
            completed = true
        } finally {
            if (!completed) {
                target.delete()
            }
        }
        return target
    }

    private fun ResponseBody.streamCappedTo(
        target: File,
        maxBytes: Long,
        label: String,
        onProgress: (Long) -> Unit,
    ): Long =
        byteStream().use { input ->
            target.outputStream().buffered().use { output ->
                input.copyCappedTo(output, maxBytes, label, onProgress)
            }
        }

    private fun InputStream.copyCappedTo(
        output: OutputStream,
        maxBytes: Long,
        label: String,
        onProgress: (Long) -> Unit,
    ): Long {
        var total = 0L
        val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
        while (true) {
            val read = read(buffer)
            if (read == -1) {
                break
            }
            total += read
            if (total > maxBytes) {
                throw IOException("$label exceeded $maxBytes bytes")
            }
            output.write(buffer, 0, read)
            onProgress(total)
        }
        return total
    }

    companion object {
        const val SOURCE_REPO = "https://github.com/sapics/ip-location-db"
        const val SOURCE_LICENSE = "CC BY 4.0 (DB-IP Lite)"

        const val FOXHOLE_GEOIP_MANIFEST_URL = "$FOXHOLE_DB_PAGES_BASE_URL/geoip-manifest.json"
        private const val EXPECTED_MANIFEST_SCHEMA = 1
        private const val EXPECTED_MANIFEST_NAME = "foxhole-geoip"
        private const val EXPECTED_ARTIFACT_FORMAT = "dbip-country-csv"
        private const val EXPECTED_IPV4_FILE = "dbip-country-ipv4.csv"
        private const val EXPECTED_IPV6_FILE = "dbip-country-ipv6.csv"
        private const val INSTALLED_STAMP_FILE = "geoip-manifest.generated-at"
        private const val MAX_SIGNATURE_BYTES = MAX_FOXHOLE_DB_SIGNATURE_BYTES
        private const val MAX_PACKAGE_BYTES = 64L * 1024L
        private const val MAX_DATABASE_BYTES = 64L * 1024L * 1024L
        private const val MIN_DATABASE_BYTES = 1024L * 1024L
        private const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val READ_TIMEOUT_MS = 60_000L
        private const val CALL_TIMEOUT_MS = 180_000L
    }
}
