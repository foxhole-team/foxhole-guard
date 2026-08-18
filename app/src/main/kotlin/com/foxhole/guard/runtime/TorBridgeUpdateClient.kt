package com.foxhole.guard.runtime

import android.content.Context
import com.foxhole.core.network.RemoteHostResolver
import com.foxhole.core.network.ensurePublicHttpsUrl
import com.foxhole.core.network.requirePublicHttpsUrl
import com.foxhole.core.runtime.TorBridgeMetadata
import com.foxhole.core.runtime.TorBridgeStore
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
import java.io.File
import java.io.IOException
import java.time.Instant

/**
 * Downloads a fresh Tor bridge list. The default source is the Tor Project's built-in bridges
 * endpoint; enabling the Foxhole mirror switches to the FoxHole DB bridges group, where a signed
 * manifest pins the artifact by size and sha256 before a byte of it is trusted. Both sources
 * carry the same bridge-groups JSON shape ({"obfs4":[…],"snowflake":[…],…}), and the payload is
 * additionally validated by [TorBridgeStore.install] before it can replace the active list.
 */
enum class TorBridgeUpdateStatus {
    UPDATED,
    UP_TO_DATE,
    FAILED,
}

data class TorBridgeUpdateResult(
    val status: TorBridgeUpdateStatus,
    val metadata: TorBridgeMetadata? = null,
    val reason: String? = null,
)

class TorBridgeUpdateClient(
    private val httpClient: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val resolver: RemoteHostResolver? = null,
    // Read per call, not per instance: the repository can be redirected from the updates screen
    // while this client is already in the graph, and a captured value would keep the old mirror
    // until the process restarted.
    private val manifestUrl: () -> String = { TOR_BRIDGES_FOXHOLE_MANIFEST_URL },
    private val currentVersionName: String = BuildConfig.VERSION_NAME,
    private val now: () -> Instant = Instant::now,
    context: Context? = null,
) {
    private val installedStamp: File? =
        context?.applicationContext?.let { app -> File(app.filesDir, INSTALLED_STAMP_FILE) }

    @Volatile
    private var memoryFloor: Instant? = null

    private val installedGeneratedAt: Instant?
        get() = memoryFloor ?: readInstalledGeneratedAt()?.also { stamp -> memoryFloor = stamp }

    private fun readInstalledGeneratedAt(): Instant? =
        installedStamp
            ?.takeIf(File::isFile)
            ?.let { file -> runCatching { file.readText().trim() }.getOrNull() }
            ?.takeIf(String::isNotEmpty)
            ?.let { stamp -> runCatching { Instant.parse(stamp) }.getOrNull() }

    private fun recordInstalledGeneratedAt(generatedAt: Instant) {
        memoryFloor = generatedAt
        val target = installedStamp ?: return
        runCatching {
            val temporary = File("${target.path}.tmp")
            temporary.writeText(generatedAt.toString())
            if (!temporary.renameTo(target)) {
                temporary.delete()
            }
        }
    }

    suspend fun update(
        store: TorBridgeStore,
        useFoxholeSource: Boolean,
        onPhase: (RemoteUpdatePhase) -> Unit = {},
        onProgress: (RemoteDownloadProgress) -> Unit = {},
    ): TorBridgeUpdateResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val client = guardedClient()
                onPhase(RemoteUpdatePhase.CHECKING)
                val fetched =
                    if (useFoxholeSource) {
                        client.fetchViaFoxholeDbManifest(onPhase, onProgress)
                            ?: return@runCatching TorBridgeUpdateResult(
                                status = TorBridgeUpdateStatus.UP_TO_DATE,
                            )
                    } else {
                        onPhase(RemoteUpdatePhase.DOWNLOADING)
                        FetchedBridges(
                            payload =
                            client.getText(
                                TOR_BRIDGES_TOR_SITE_URL.asPublicHttpsUrl(),
                                MAX_BRIDGE_BYTES,
                                "tor bridge list",
                                onProgress = onProgress,
                            ),
                            source = TOR_BRIDGES_TOR_SITE_URL,
                        )
                    }
                require(fetched.payload.isNotBlank()) { "empty bridge list" }
                if (fetched.payload.trim() == store.readGroupsJsonOrNull()?.trim()) {
                    return@runCatching TorBridgeUpdateResult(status = TorBridgeUpdateStatus.UP_TO_DATE)
                }
                onPhase(RemoteUpdatePhase.VERIFYING)
                val metadata = store.install(rawPayload = fetched.payload, source = fetched.source)
                fetched.generatedAt?.let { stamp -> recordInstalledGeneratedAt(stamp) }
                TorBridgeUpdateResult(status = TorBridgeUpdateStatus.UPDATED, metadata = metadata)
            }.getOrElse { error ->
                TorBridgeUpdateResult(
                    status = TorBridgeUpdateStatus.FAILED,
                    reason = error.message ?: error.javaClass.simpleName,
                )
            }
        }

    private data class FetchedBridges(
        val payload: String,
        val source: String,
        val generatedAt: Instant? = null,
    )

    /**
     * The FoxHole DB path: signed manifest first, artifact second, and the artifact's bytes must
     * match the manifest's size and sha256 exactly. Nothing from the mirror is trusted raw.
     *
     * Null when the mirror is republishing the publication already installed — the artifact is not
     * downloaded at all in that case.
     */
    private fun OkHttpClient.fetchViaFoxholeDbManifest(
        onPhase: (RemoteUpdatePhase) -> Unit,
        onProgress: (RemoteDownloadProgress) -> Unit,
    ): FetchedBridges? {
        val manifestUrl = manifestUrl().asPublicHttpsUrl()
        val manifestBytes = getBytes(manifestUrl, MAX_MANIFEST_BYTES, "bridges manifest")
        val signatureBytes =
            getBytes(manifestUrl.signatureUrl(), MAX_SIGNATURE_BYTES, "bridges manifest signature")
        requireFoxholeDbManifestSignature(manifestBytes, signatureBytes)
        val manifest = json.decodeFromString<TorBridgesManifest>(manifestBytes.toString(Charsets.UTF_8))
        val generatedAt = manifest.requireValid()
        requireNotAFoxholeDbRollback(generatedAt, installedGeneratedAt, "tor bridges")
        if (generatedAt == installedGeneratedAt) {
            return null
        }
        onPhase(RemoteUpdatePhase.DOWNLOADING)
        val artifactUrl =
            requireNotNull(manifestUrl.resolve(manifest.artifact.file)) { "invalid bridges artifact url" }
        val payloadBytes =
            getBytes(
                artifactUrl,
                MAX_BRIDGE_BYTES,
                "tor bridge list",
                expectedBytes = manifest.artifact.size,
                onProgress = onProgress,
            )
        require(payloadBytes.size.toLong() == manifest.artifact.size) { "bridge list size mismatch" }
        require(payloadBytes.sha256Hex() == manifest.artifact.sha256) { "bridge list sha256 mismatch" }
        return FetchedBridges(
            payload = payloadBytes.toString(Charsets.UTF_8),
            source = artifactUrl.toString(),
            generatedAt = generatedAt,
        )
    }

    private fun TorBridgesManifest.requireValid(): Instant {
        require(schema == EXPECTED_MANIFEST_SCHEMA) { "unsupported bridges manifest schema" }
        require(name == EXPECTED_MANIFEST_NAME) { "unexpected bridges manifest name" }
        require(format == EXPECTED_ARTIFACT_FORMAT) { "unexpected bridges artifact format" }
        require(artifact.file == EXPECTED_ARTIFACT_FILE) { "unexpected bridges artifact file" }
        require(artifact.size in 1..MAX_BRIDGE_BYTES) { "unexpected bridges artifact size" }
        require(artifact.sha256.isSha256Hex()) { "invalid bridges artifact sha256" }
        require(compareAppVersions(currentVersionName, compatibility.minAppVersion) >= 0) {
            "app version is too old for the bridges feed"
        }
        return requireFreshFoxholeDbManifest(generatedAt, "tor bridges", now())
    }

    @Serializable
    private data class TorBridgesManifest(
        val schema: Int,
        val name: String,
        val format: String,
        @SerialName("generated_at") val generatedAt: String,
        val artifact: TorBridgesManifestArtifact,
        val compatibility: TorBridgesManifestCompatibility,
    )

    @Serializable
    private data class TorBridgesManifestArtifact(
        val file: String,
        val size: Long,
        val sha256: String,
    )

    @Serializable
    private data class TorBridgesManifestCompatibility(
        @SerialName("min_app_version") val minAppVersion: String,
    )

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

    private fun OkHttpClient.getText(
        url: HttpUrl,
        maxBytes: Long,
        label: String,
        onProgress: (RemoteDownloadProgress) -> Unit = {},
    ): String = getBytes(url, maxBytes, label, onProgress = onProgress).toString(Charsets.UTF_8)

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
                throw IOException("$label request failed with HTTP ${response.code}")
            }
            return requireNotNull(response.body) { "$label response body is empty" }
                .readBytesCapped(maxBytes, expectedBytes, onProgress)
        }
    }

    private fun HttpUrl.signatureUrl(): HttpUrl =
        newBuilder()
            .encodedPath("$encodedPath.sig")
            .build()

    companion object {
        const val TOR_BRIDGES_TOR_SITE_URL = "https://bridges.torproject.org/moat/circumvention/builtin"

        // The FoxHole DB bridges group (opt-in mirror): a signed manifest next to every other feed.
        const val TOR_BRIDGES_FOXHOLE_MANIFEST_URL = "$FOXHOLE_DB_PAGES_BASE_URL/bridges-manifest.json"
        private const val INSTALLED_STAMP_FILE = "bridges-manifest.generated-at"
        private const val EXPECTED_MANIFEST_SCHEMA = 1
        private const val EXPECTED_MANIFEST_NAME = "foxhole-tor-bridges"
        private const val EXPECTED_ARTIFACT_FORMAT = "tor-bridges-json"
        private const val EXPECTED_ARTIFACT_FILE = "bridges.json"
        private const val MAX_MANIFEST_BYTES = 64L * 1024L
        private const val MAX_SIGNATURE_BYTES = MAX_FOXHOLE_DB_SIGNATURE_BYTES
        private const val MAX_BRIDGE_BYTES = 256L * 1024L
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val READ_TIMEOUT_MS = 30_000L
        private const val CALL_TIMEOUT_MS = 60_000L
    }
}
