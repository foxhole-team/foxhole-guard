package com.foxhole.guard.runtime

import android.content.Context
import com.foxhole.core.network.RemoteHostResolver
import com.foxhole.core.network.ensurePublicHttpsUrl
import com.foxhole.core.network.requirePublicHttpsUrl
import com.foxhole.core.runtime.GeoIpDatabaseMetadata
import com.foxhole.core.runtime.GeoIpDatabaseStore
import com.foxhole.core.runtime.network.PublicRemoteDns
import com.foxhole.guard.core.data.withBoundedRemoteFetchTimeouts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

/**
 * Downloads the IP→country database from the open-source ip-location-db repository on GitHub
 * (dbip-country dataset: DB-IP Lite, CC BY 4.0 — the UI must keep the DB-IP attribution). The
 * repository publishes a package.json whose version string changes on every monthly refresh, so
 * an update is a cheap version probe plus two range CSV downloads validated by
 * [GeoIpDatabaseStore.install] before anything replaces the active database.
 */
enum class GeoIpUpdateStatus {
    UPDATED,
    UP_TO_DATE,
    FAILED,
}

data class GeoIpUpdateResult(
    val status: GeoIpUpdateStatus,
    val metadata: GeoIpDatabaseMetadata? = null,
    val reason: String? = null,
)

@Serializable
private data class GeoIpSourcePackage(
    val version: String,
)

class GeoIpUpdateClient(
    context: Context,
    private val httpClient: OkHttpClient,
    private val json: Json,
    private val resolver: RemoteHostResolver? = null,
) {
    private val appContext = context.applicationContext

    /**
     * Check-only version probe (the same cheap package.json read the full update starts with):
     * true when the published version differs from the installed one, null when the source is
     * unreachable. Nothing downloads.
     */
    suspend fun checkForUpdate(store: GeoIpDatabaseStore): Boolean? =
        withContext(Dispatchers.IO) {
            runCatching {
                val remoteVersion =
                    json
                        .decodeFromString<GeoIpSourcePackage>(
                            guardedClient().getText(
                                PACKAGE_URL.asPublicHttpsUrl(),
                                MAX_PACKAGE_BYTES,
                                "geoip package manifest",
                            ),
                        ).version
                        .trim()
                remoteVersion.isNotBlank() && remoteVersion != store.readMetadata()?.version
            }.getOrNull()
        }

    suspend fun update(
        store: GeoIpDatabaseStore,
        onPhase: (RemoteUpdatePhase) -> Unit = {},
    ): GeoIpUpdateResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val client = guardedClient()
                onPhase(RemoteUpdatePhase.CHECKING)
                val remoteVersion =
                    json
                        .decodeFromString<GeoIpSourcePackage>(
                            client.getText(PACKAGE_URL.asPublicHttpsUrl(), MAX_PACKAGE_BYTES, "geoip package manifest"),
                        ).version
                        .trim()
                require(remoteVersion.isNotBlank()) { "empty geoip source version" }
                val installedVersion = store.readMetadata()?.version
                if (installedVersion == remoteVersion) {
                    return@runCatching GeoIpUpdateResult(status = GeoIpUpdateStatus.UP_TO_DATE)
                }
                onPhase(RemoteUpdatePhase.DOWNLOADING)
                val ipv4File =
                    client.downloadToTempFile(IPV4_URL.asPublicHttpsUrl(), MAX_DATABASE_BYTES, "geoip ipv4 ranges")
                val ipv6File =
                    runCatching {
                        client.downloadToTempFile(IPV6_URL.asPublicHttpsUrl(), MAX_DATABASE_BYTES, "geoip ipv6 ranges")
                    }.onFailure { ipv4File.delete() }
                        .getOrThrow()
                onPhase(RemoteUpdatePhase.VERIFYING)
                val metadata =
                    try {
                        store.install(
                            version = remoteVersion,
                            sourceRepo = SOURCE_REPO,
                            license = SOURCE_LICENSE,
                            ipv4File = ipv4File,
                            ipv6File = ipv6File,
                        )
                    } finally {
                        ipv4File.delete()
                        ipv6File.delete()
                    }
                GeoIpUpdateResult(status = GeoIpUpdateStatus.UPDATED, metadata = metadata)
            }.getOrElse { error ->
                GeoIpUpdateResult(
                    status = GeoIpUpdateStatus.FAILED,
                    reason = error.message ?: error.javaClass.simpleName,
                )
            }
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

    private fun OkHttpClient.getText(
        url: HttpUrl,
        maxBytes: Long,
        label: String,
    ): String {
        url.requirePublicHttpsUrl(resolveHost = true, resolver = resolver)
        newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("$label request failed with HTTP ${response.code}")
            }
            return requireNotNull(response.body) { "$label response body is empty" }
                .readBytesCapped(maxBytes)
                .toString(Charsets.UTF_8)
        }
    }

    // Streams straight to a cache file: the two range CSVs are ~10-20MB each and must not be
    // buffered in the heap of a process we just finished shrinking.
    private fun OkHttpClient.downloadToTempFile(
        url: HttpUrl,
        maxBytes: Long,
        label: String,
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
                        .streamCappedTo(target, maxBytes, label)
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

    // Streams the response body into [target] in fixed-size chunks and returns the byte count,
    // aborting as soon as the running total would exceed [maxBytes].
    private fun ResponseBody.streamCappedTo(
        target: File,
        maxBytes: Long,
        label: String,
    ): Long =
        byteStream().use { input ->
            target.outputStream().buffered().use { output ->
                input.copyCappedTo(output, maxBytes, label)
            }
        }

    private fun InputStream.copyCappedTo(
        output: OutputStream,
        maxBytes: Long,
        label: String,
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
        }
        return total
    }

    companion object {
        const val SOURCE_REPO = "https://github.com/sapics/ip-location-db"
        const val SOURCE_LICENSE = "CC BY 4.0 (DB-IP Lite)"
        const val PACKAGE_URL =
            "https://raw.githubusercontent.com/sapics/ip-location-db/main/dbip-country/package.json"
        const val IPV4_URL =
            "https://raw.githubusercontent.com/sapics/ip-location-db/main/dbip-country/dbip-country-ipv4.csv"
        const val IPV6_URL =
            "https://raw.githubusercontent.com/sapics/ip-location-db/main/dbip-country/dbip-country-ipv6.csv"
        private const val MAX_PACKAGE_BYTES = 64L * 1024L
        private const val MAX_DATABASE_BYTES = 64L * 1024L * 1024L
        private const val MIN_DATABASE_BYTES = 1024L * 1024L
        private const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val READ_TIMEOUT_MS = 60_000L
        private const val CALL_TIMEOUT_MS = 180_000L
    }
}
