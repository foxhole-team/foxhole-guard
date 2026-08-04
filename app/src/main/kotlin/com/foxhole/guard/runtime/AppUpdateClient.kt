package com.foxhole.guard.runtime

import com.foxhole.core.network.RemoteHostResolver
import com.foxhole.core.network.ensurePublicHttpsUrl
import com.foxhole.core.network.requirePublicHttpsUrl
import com.foxhole.guard.core.data.withBoundedRemoteFetchTimeouts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Application updates for builds installed from GitHub. F-Droid and Play manage their own updates,
 * so the caller gates this on [com.foxhole.guard.BuildConfig.UPDATE_CHANNEL].
 *
 * The release carries an `update-manifest.json` asset next to the APK; that manifest — not the tag
 * name — is the source of truth, because only a `versionCode` can be ordered reliably (`beta10`
 * sorts before `beta5` as a string). The APK is accepted only when its SHA-256 matches the digest
 * the manifest declares, so a swapped asset cannot reach the installer.
 */
@Serializable
data class AppUpdateManifest(
    val versionCode: Long,
    val versionName: String,
    val apkName: String,
    val apkSha256: String,
    val notes: String = "",
)

sealed interface AppUpdateCheck {
    data object UpToDate : AppUpdateCheck

    data class Available(
        val manifest: AppUpdateManifest,
        val downloadUrl: String,
        val sizeBytes: Long,
    ) : AppUpdateCheck

    data class Failed(val reason: String) : AppUpdateCheck
}

class AppUpdateClient(
    private val httpClient: OkHttpClient,
    private val resolver: RemoteHostResolver? = null,
    private val releasesApiUrl: String = DEFAULT_RELEASES_API_URL,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun check(currentVersionCode: Long): AppUpdateCheck =
        withContext(Dispatchers.IO) {
            runCatching {
                val client = guardedClient()
                val releaseJson =
                    client.getText(
                        releasesApiUrl.asPublicHttpsUrl(),
                        MAX_METADATA_BYTES,
                        "release metadata",
                    )
                val release = json.decodeFromString<GithubRelease>(releaseJson)
                val manifestAsset = release.assets.firstOrNull { asset -> asset.name == MANIFEST_ASSET_NAME }
                    ?: return@runCatching AppUpdateCheck.Failed("release has no $MANIFEST_ASSET_NAME")
                val manifestJson =
                    client.getText(
                        manifestAsset.browserDownloadUrl.asPublicHttpsUrl(),
                        MAX_METADATA_BYTES,
                        "update manifest",
                    )
                val manifest = json.decodeFromString<AppUpdateManifest>(manifestJson)
                if (manifest.versionCode <= currentVersionCode) {
                    return@runCatching AppUpdateCheck.UpToDate
                }
                val apkAsset = release.assets.firstOrNull { asset -> asset.name == manifest.apkName }
                    ?: return@runCatching AppUpdateCheck.Failed("release has no ${manifest.apkName}")
                AppUpdateCheck.Available(
                    manifest = manifest,
                    downloadUrl = apkAsset.browserDownloadUrl,
                    sizeBytes = apkAsset.size,
                )
            }.getOrElse { error ->
                AppUpdateCheck.Failed(error.message ?: error.javaClass.simpleName)
            }
        }

    /**
     * Downloads the APK and returns it only when the digest matches. A partial or mismatched file is
     * deleted rather than left behind: the installer must never see a half-written package.
     */
    suspend fun download(
        update: AppUpdateCheck.Available,
        into: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = update.downloadUrl.asPublicHttpsUrl()
                url.requirePublicHttpsUrl(resolveHost = true, resolver = resolver)
                into.parentFile?.mkdirs()
                if (into.exists()) {
                    into.delete()
                }
                val digest = MessageDigest.getInstance("SHA-256")
                guardedClient().newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("apk request failed with HTTP ${response.code}")
                    }
                    val body = requireNotNull(response.body) { "apk response body is empty" }
                    val total = body.contentLength().takeIf { length -> length > 0 } ?: update.sizeBytes
                    var downloaded = 0L
                    body.byteStream().use { source ->
                        into.outputStream().use { sink ->
                            val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                            while (true) {
                                val read = source.read(buffer)
                                if (read < 0) break
                                if (downloaded + read > MAX_APK_BYTES) {
                                    throw IOException("apk exceeds the ${MAX_APK_BYTES / (1024 * 1024)} MiB cap")
                                }
                                sink.write(buffer, 0, read)
                                digest.update(buffer, 0, read)
                                downloaded += read
                                onProgress(downloaded, total)
                            }
                        }
                    }
                }
                val actual = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
                if (!actual.equals(update.manifest.apkSha256, ignoreCase = true)) {
                    into.delete()
                    throw IOException("apk digest mismatch")
                }
                into
            }.onFailure { into.delete() }
        }

    private fun guardedClient(): OkHttpClient =
        httpClient.withBoundedRemoteFetchTimeouts(
            connectTimeoutMs = CONNECT_TIMEOUT_MS,
            readTimeoutMs = READ_TIMEOUT_MS,
            callTimeoutMs = CALL_TIMEOUT_MS,
        )

    private fun String.asPublicHttpsUrl(): HttpUrl = ensurePublicHttpsUrl(resolveHost = true, resolver = resolver)

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

    @Serializable
    private data class GithubRelease(
        val assets: List<GithubAsset> = emptyList(),
    )

    @Serializable
    private data class GithubAsset(
        val name: String,
        @kotlinx.serialization.SerialName("browser_download_url") val browserDownloadUrl: String,
        val size: Long = 0,
    )

    companion object {
        const val MANIFEST_ASSET_NAME = "update-manifest.json"
        const val DEFAULT_RELEASES_API_URL = "https://api.github.com/repos/foxhole-team/foxhole-guard/releases/latest"
        private const val MAX_METADATA_BYTES = 512L * 1024L
        private const val MAX_APK_BYTES = 256L * 1024L * 1024L
        private const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val READ_TIMEOUT_MS = 60_000L
        private const val CALL_TIMEOUT_MS = 600_000L
    }
}
