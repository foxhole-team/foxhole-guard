package com.foxhole.guard.runtime

import com.foxhole.core.network.RemoteHostResolver
import com.foxhole.core.network.ensurePublicHttpsUrl
import com.foxhole.core.network.requirePublicHttpsUrl
import com.foxhole.core.runtime.network.PublicRemoteDns
import com.foxhole.guard.core.data.withBoundedRemoteFetchTimeouts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

@Serializable
data class AppUpdateManifest(
    val versionCode: Long,
    val versionName: String,
    val apkName: String,
    val apkSha256: String,
    val notes: String = "",
)

enum class AppUpdateFailure {
    NETWORK,

    RATE_LIMITED,

    UNAUTHORIZED,

    NOT_FOUND,

    MALFORMED,

    BLOCKED,

    NO_ARTIFACT,

    VERIFICATION,

    UNKNOWN,
}

sealed interface AppUpdateCheck {
    data object UpToDate : AppUpdateCheck

    data class Available(
        val manifest: AppUpdateManifest,
        val downloadUrl: String,
        val sizeBytes: Long,
        val versionsBehind: Int = 1,
        val releaseUrl: String = "",
    ) : AppUpdateCheck {
        val versionName: String get() = manifest.versionName

        val severity: AppUpdateSeverity get() = AppUpdateSeverity.forVersionsBehind(versionsBehind)

        val installable: Boolean
            get() = downloadUrl.isNotBlank() && manifest.apkName.isSafeApkBasename() && manifest.apkSha256.isSha256Hex()
    }

    data class Failed(
        val failure: AppUpdateFailure,
        val reason: String,
    ) : AppUpdateCheck
}

internal class AppUpdateHttpException(
    val code: Int,
    val rateLimited: Boolean,
    message: String,
) : IOException(message)

internal class AppUpdateBlockedUrlException(
    message: String,
) : Exception(message)

internal fun Throwable.asAppUpdateFailure(): AppUpdateFailure =
    when {
        this is AppUpdateBlockedUrlException -> AppUpdateFailure.BLOCKED
        this is AppUpdateHttpException ->
            when {
                rateLimited -> AppUpdateFailure.RATE_LIMITED
                code == HTTP_UNAUTHORIZED || code == HTTP_FORBIDDEN -> AppUpdateFailure.UNAUTHORIZED
                code == HTTP_NOT_FOUND || code == HTTP_GONE -> AppUpdateFailure.NOT_FOUND
                code >= HTTP_SERVER_ERROR -> AppUpdateFailure.NETWORK
                else -> AppUpdateFailure.UNKNOWN
            }
        this is SerializationException -> AppUpdateFailure.MALFORMED
        this is AppUpdateVerificationException -> AppUpdateFailure.VERIFICATION
        this is IOException -> AppUpdateFailure.NETWORK
        this is IllegalArgumentException -> AppUpdateFailure.MALFORMED
        else -> AppUpdateFailure.UNKNOWN
    }

internal fun Throwable.appUpdateReason(): String = message?.takeIf(String::isNotBlank) ?: javaClass.simpleName

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_NOT_FOUND = 404
private const val HTTP_GONE = 410
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_SERVER_ERROR = 500
private const val RATE_LIMIT_PEEK_BYTES = 2048L

class AppUpdateClient(
    private val httpClient: OkHttpClient,
    private val resolver: RemoteHostResolver? = null,

    private val releasesApiUrl: () -> String = { DEFAULT_RELEASES_API_URL },

    private val releasesToken: () -> String = { "" },
    private val operationTimeoutMs: Long = CALL_TIMEOUT_MS,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val callFactory: Call.Factory? = null,
    private val manifestAssetName: String = MANIFEST_ASSET_NAME,
) {
    private val json = Json { ignoreUnknownKeys = true }

    init {
        require(manifestAssetName in setOf(MANIFEST_ASSET_NAME, ARMV7_MANIFEST_ASSET_NAME))
    }

    suspend fun check(
        currentVersionCode: Long,
        currentVersionName: String = "",
    ): AppUpdateCheck =
        withTimeoutOrNull(operationTimeoutMs) {
            withContext(ioDispatcher) {
                try {
                    latestRelease(currentVersionCode, currentVersionName)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    AppUpdateCheck.Failed(error.asAppUpdateFailure(), error.appUpdateReason())
                }
            }
        } ?: AppUpdateCheck.Failed(AppUpdateFailure.NETWORK, "update check timed out")

    private fun isNewerRelease(
        manifest: AppUpdateManifest?,
        currentVersionCode: Long,
        installed: AppUpdateVersion?,
        published: AppUpdateVersion,
    ): Boolean =
        when {
            manifest != null && manifest.versionCode > 0L -> manifest.versionCode > currentVersionCode
            installed != null -> published > installed
            else -> false
        }

    @Suppress("ReturnCount")
    private suspend fun latestRelease(
        currentVersionCode: Long,
        currentVersionName: String,
    ): AppUpdateCheck {
        val client = guardedClient()
        val endpoint = releasesApiUrl().ifBlank { DEFAULT_RELEASES_API_URL }.asPublicHttpsUrl()
        val releaseJson = client.getText(endpoint, MAX_METADATA_BYTES, "release metadata")
        val release = json.decodeFromString<GithubRelease>(releaseJson)
        val tag = release.tagName.ifBlank { release.name }
        if (tag.isBlank() && release.assets.isEmpty()) {
            return AppUpdateCheck.Failed(
                AppUpdateFailure.MALFORMED,
                "release metadata carries neither a tag nor any asset",
            )
        }
        val installed = AppUpdateVersion.parseOrNull(currentVersionName)
        val tagged = AppUpdateVersion.parseOrNull(tag)
        if (installed != null && tagged != null && tagged <= installed) {
            return AppUpdateCheck.UpToDate
        }
        val manifest = release.manifestOrNull(client)
        val publishedName = manifest?.versionName?.takeIf(String::isNotBlank) ?: appUpdateDisplayVersionName(tag)
        val published =
            AppUpdateVersion.parseOrNull(publishedName)
                ?: return AppUpdateCheck.Failed(
                    AppUpdateFailure.MALFORMED,
                    "release version '$publishedName' cannot be ordered",
                )
        if (!isNewerRelease(manifest, currentVersionCode, installed, published)) {
            return AppUpdateCheck.UpToDate
        }
        val versionsBehind =
            installed?.let { from -> appUpdateVersionsBehind(from, published) }?.takeIf { behind -> behind > 0 } ?: 1
        if (manifest == null) {
            return AppUpdateCheck.Available(
                manifest =
                AppUpdateManifest(
                    versionCode = 0L,
                    versionName = publishedName,
                    apkName = "",
                    apkSha256 = "",
                    notes = release.body,
                ),
                downloadUrl = "",
                sizeBytes = 0L,
                versionsBehind = versionsBehind,
                releaseUrl = release.htmlUrl,
            )
        }
        if (!manifest.apkSha256.isSha256Hex()) {
            return AppUpdateCheck.Failed(AppUpdateFailure.MALFORMED, "update manifest carries no usable sha256")
        }
        val apkAsset =
            release.assets.firstOrNull { asset -> asset.name == manifest.apkName }
                ?: return AppUpdateCheck.Failed(AppUpdateFailure.NO_ARTIFACT, "release has no ${manifest.apkName}")
        return AppUpdateCheck.Available(
            manifest = manifest,
            downloadUrl = apkAsset.readableUrl(),
            sizeBytes = apkAsset.size,
            versionsBehind = versionsBehind,
            releaseUrl = release.htmlUrl,
        )
    }

    private suspend fun GithubRelease.manifestOrNull(client: OkHttpClient): AppUpdateManifest? {
        val asset = assets.firstOrNull { candidate -> candidate.name == manifestAssetName } ?: return null
        val manifestJson =
            client.getText(
                asset.readableUrl().asPublicHttpsUrl(),
                MAX_METADATA_BYTES,
                "update manifest",
                accept = ASSET_ACCEPT,
            )
        return json.decodeFromString<AppUpdateManifest>(manifestJson)
    }

    suspend fun download(
        update: AppUpdateCheck.Available,
        into: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Result<File> {
        var staging: File? = null
        return try {
            val result = withTimeoutOrNull(operationTimeoutMs) {
                withContext(ioDispatcher) {
                    val parent = requireNotNull(into.absoluteFile.parentFile)
                    if (!parent.isDirectory && !parent.mkdirs()) throw IOException("update directory unavailable")
                    val candidate = File.createTempFile(".foxhole-update-", ".part", parent)
                    staging = candidate
                    downloadFile(update, candidate, onProgress)
                    currentCoroutineContext().ensureActive()
                    Files.move(
                        candidate.toPath(),
                        into.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                    )
                    into
                }
            }
            if (result == null) Result.failure(IOException("update download timed out")) else Result.success(result)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        } finally {
            staging?.delete()
        }
    }

    private suspend fun downloadFile(
        update: AppUpdateCheck.Available,
        into: File,
        onProgress: (Long, Long) -> Unit,
    ): File {
        val url = update.downloadUrl.asPublicHttpsUrl()
        url.requirePublicHttpsUrl(resolveHost = true, resolver = resolver)
        val digest = MessageDigest.getInstance("SHA-256")
        guardedClient().executeGet(url, ASSET_ACCEPT) { response, active ->
            try {
                if (!response.isSuccessful) throw IOException("apk request failed with HTTP ${response.code}")
                requireDownloadActive(active)
                val body = requireNotNull(response.body) { "apk response body is empty" }
                val total = body.contentLength().takeIf { length -> length > 0 } ?: update.sizeBytes
                var downloaded = 0L
                body.byteStream().use { source ->
                    into.outputStream().use { sink ->
                        val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                        while (true) {
                            requireDownloadActive(active)
                            val read = source.read(buffer)
                            requireDownloadActive(active)
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
                requireDownloadActive(active)
                verifyDownloadedFile(update, into, digest)
            } finally {
                // Cancellation may finish the caller before this callback opens its output file.
                if (!active()) into.delete()
            }
        }
        return into
    }

    private fun requireDownloadActive(active: () -> Boolean) {
        if (!active()) throw CancellationException()
    }

    private fun verifyDownloadedFile(update: AppUpdateCheck.Available, into: File, digest: MessageDigest) {
        if (update.sizeBytes > 0L && into.length() != update.sizeBytes) {
            throw IOException("apk size ${into.length()} does not match the announced ${update.sizeBytes}")
        }
        val actual = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        if (!actual.equals(update.manifest.apkSha256, ignoreCase = true)) throw IOException("apk digest mismatch")
    }

    private fun guardedClient(): OkHttpClient =
        httpClient.withBoundedRemoteFetchTimeouts(
            connectTimeoutMs = CONNECT_TIMEOUT_MS,
            readTimeoutMs = READ_TIMEOUT_MS,
            callTimeoutMs = CALL_TIMEOUT_MS,
        ).newBuilder()
            .dns(PublicRemoteDns(httpClient.dns::lookup))
            .build()

    private fun String.asPublicHttpsUrl(): HttpUrl =
        runCatching { ensurePublicHttpsUrl(resolveHost = true, resolver = resolver) }
            .getOrElse { error -> throw AppUpdateBlockedUrlException(error.appUpdateReason()) }

    private suspend fun OkHttpClient.getText(
        url: HttpUrl,
        maxBytes: Long,
        label: String,
        accept: String = "application/vnd.github+json",
    ): String {
        runCatching { url.requirePublicHttpsUrl(resolveHost = true, resolver = resolver) }
            .getOrElse { error -> throw AppUpdateBlockedUrlException(error.appUpdateReason()) }
        return executeGet(url, accept) { response, _ ->
            if (!response.isSuccessful) {
                throw AppUpdateHttpException(
                    code = response.code,
                    rateLimited = response.looksRateLimited(),
                    message = "$label request failed with HTTP ${response.code}",
                )
            }
            requireNotNull(response.body) { "$label response body is empty" }
                .readBytesCapped(maxBytes)
                .toString(Charsets.UTF_8)
        }
    }

    private suspend fun <T> OkHttpClient.executeGet(
        initialUrl: HttpUrl,
        accept: String,
        consume: (Response, () -> Boolean) -> T,
    ): T {
        var url = initialUrl
        repeat(MAX_REDIRECTS + 1) {
            url = url.toString().asPublicHttpsUrl()
            val request = Request.Builder().url(url).get().header("Accept", accept).authorized(url).build()
            val call = callFactory?.newCall(request) ?: newCall(request)
            when (
                val fetched = consumeAppUpdateResponse(call) { response, active ->
                    if (response.isRedirect) {
                        AppUpdateResponse.Redirect(
                            response.header("Location")?.let(url::resolve)
                                ?: throw IOException("missing or invalid redirect Location")
                        )
                    } else {
                        AppUpdateResponse.Value(consume(response, active))
                    }
                }
            ) {
                is AppUpdateResponse.Value -> return fetched.value
                is AppUpdateResponse.Redirect -> url = fetched.url
            }
        }
        throw IOException("too many update redirects")
    }

    private sealed interface AppUpdateResponse<out T> {
        data class Value<T>(val value: T) : AppUpdateResponse<T>
        data class Redirect(val url: HttpUrl) : AppUpdateResponse<Nothing>
    }

    private fun Response.looksRateLimited(): Boolean {
        if (code == HTTP_TOO_MANY_REQUESTS) {
            return true
        }
        if (code != HTTP_FORBIDDEN) {
            return false
        }
        if (header("x-ratelimit-remaining")?.trim() == "0") {
            return true
        }
        val body = runCatching { peekBody(RATE_LIMIT_PEEK_BYTES).string() }.getOrNull().orEmpty()
        return body.contains("rate limit", ignoreCase = true)
    }

    @Serializable
    private data class GithubRelease(
        @kotlinx.serialization.SerialName("tag_name") val tagName: String = "",
        val name: String = "",
        val body: String = "",
        @kotlinx.serialization.SerialName("html_url") val htmlUrl: String = "",
        val assets: List<GithubAsset> = emptyList(),
    )

    @Serializable
    private data class GithubAsset(
        val name: String,

        val url: String = "",
        @kotlinx.serialization.SerialName("browser_download_url") val browserDownloadUrl: String,
        val size: Long = 0,
    )

    private fun GithubAsset.readableUrl(): String =
        if (releasesToken().isNotBlank() && url.isNotBlank()) url else browserDownloadUrl

    // Rebuild authorization at each hop: a release token never belongs to a redirect CDN.
    private fun Request.Builder.authorized(url: HttpUrl): Request.Builder {
        val token = releasesToken()
        if (token.isBlank()) {
            return this
        }
        val configured = releasesApiUrl().trim().takeIf(String::isNotEmpty) ?: return this
        val origin = runCatching { configured.asPublicHttpsUrl() }.getOrNull() ?: return this
        return if (url.host == origin.host && url.port == origin.port) {
            header("Authorization", "Bearer $token")
        } else {
            this
        }
    }

    companion object {
        const val MANIFEST_ASSET_NAME = "update-manifest.json"
        const val ARMV7_MANIFEST_ASSET_NAME = "update-manifest-armeabi-v7a.json"
        private const val MAX_REDIRECTS = 5
        private const val ASSET_ACCEPT = "application/octet-stream"
        const val DEFAULT_RELEASES_API_URL = "https://api.github.com/repos/foxhole-team/foxhole-guard/releases/latest"
        private const val MAX_METADATA_BYTES = 512L * 1024L
        private const val MAX_APK_BYTES = 256L * 1024L * 1024L
        private const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val READ_TIMEOUT_MS = 60_000L
        private const val CALL_TIMEOUT_MS = 600_000L
    }
}
