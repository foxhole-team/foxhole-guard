package com.foxhole.guard.runtime

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.security.MessageDigest

class AppUpdateDownloadVerificationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val apkBytes = ByteArray(4096) { index -> (index % 251).toByte() }
    private val apkDigest = apkBytes.hex()

    @Test
    fun `a matching digest and length produce an installable artifact`() =
        runBlocking {
            val repository = repository()

            val result = repository.download(available())

            assertTrue(result.isSuccess)
            val state = repository.state.value
            assertTrue("expected Downloaded, was $state", state is AppUpdateState.Downloaded)
            assertTrue(result.getOrThrow().exists())
            assertEquals(apkBytes.size.toLong(), result.getOrThrow().length())
        }

    @Test
    fun `a tampered payload is refused and leaves nothing behind`() =
        runBlocking {
            val repository = repository(served = apkBytes.copyOf().also { it[0] = (it[0] + 1).toByte() })

            val result = repository.download(available())

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("digest mismatch"))
            assertNoApkLeftBehind(repository)
        }

    @Test
    fun `a truncated transfer is refused as a length mismatch`() =
        runBlocking {
            val repository = repository(served = apkBytes.copyOf(apkBytes.size - 16))

            val result = repository.download(available())

            assertTrue(result.isFailure)
            assertTrue(
                "expected a size failure, was ${result.exceptionOrNull()?.message}",
                result.exceptionOrNull()?.message.orEmpty().contains("size"),
            )
            assertNoApkLeftBehind(repository)
        }

    @Test
    fun `a foreign-signed package never becomes installable even when the digest matches`() =
        runBlocking {
            val repository =
                repository(
                    verifier = { _, _ ->
                        Result.failure(
                            AppUpdateVerificationException("signing certificate differs from the installed app"),
                        )
                    },
                )

            val result = repository.download(available())

            assertTrue("a digest-valid but foreign package must not install", result.isFailure)
            assertFalse(repository.state.value is AppUpdateState.Downloaded)
            assertNoApkLeftBehind(repository)
        }

    @Test
    fun `a manifest without a usable digest is rejected before anything is downloaded`() =
        runBlocking {
            var apkRequests = 0
            val client =
                client { chain ->
                    val path = chain.request().url.encodedPath
                    if (path.endsWith(APK_NAME)) {
                        apkRequests++
                    }
                    stubbedResponse(chain, path, apkBytes, manifestDigest = "")
                }

            val check = client.check(currentVersionCode = 89)

            assertTrue("expected Failed, was $check", check is AppUpdateCheck.Failed)
            assertTrue((check as AppUpdateCheck.Failed).reason.contains("sha256"))
            assertEquals("a manifest that cannot verify must not cost a download", 0, apkRequests)
        }

    @Test
    fun `an up-to-date release is not offered as an update`() =
        runBlocking {
            val check = client().check(currentVersionCode = NEW_VERSION_CODE)

            assertEquals(AppUpdateCheck.UpToDate, check)
        }

    private fun assertNoApkLeftBehind(repository: AppUpdateRepository) {
        assertTrue(repository.state.value is AppUpdateState.Failed)
        assertFalse(File(downloadDirectory, APK_NAME).exists())
    }

    private val downloadDirectory: File by lazy { temporaryFolder.newFolder("app-update") }

    private fun available() =
        AppUpdateCheck.Available(
            manifest = manifest(apkDigest),
            downloadUrl = "$BASE_URL/$APK_NAME",
            sizeBytes = apkBytes.size.toLong(),
        )

    private fun manifest(digest: String) =
        AppUpdateManifest(
            versionCode = NEW_VERSION_CODE,
            versionName = "0.0.2",
            apkName = APK_NAME,
            apkSha256 = digest,
        )

    private fun repository(
        served: ByteArray = apkBytes,
        verifier: (File, AppUpdateManifest) -> Result<Unit> = { _, _ -> Result.success(Unit) },
    ) = AppUpdateRepository(
        client = client(served),
        currentVersionCode = 89,
        downloadDirectory = downloadDirectory,
        apkVerifier = verifier,
    )

    private fun client(served: ByteArray = apkBytes): AppUpdateClient =
        client { chain -> stubbedResponse(chain, chain.request().url.encodedPath, served, apkDigest) }

    private fun client(interceptor: Interceptor): AppUpdateClient =
        AppUpdateClient(
            httpClient = OkHttpClient.Builder().addInterceptor(interceptor).build(),
            resolver = { listOf(InetAddress.getByName("8.8.8.8")) },
            releasesApiUrl = { "$BASE_URL/releases/latest" },
        )

    private fun stubbedResponse(
        chain: Interceptor.Chain,
        path: String,
        apk: ByteArray,
        manifestDigest: String,
    ): Response {
        val body =
            when {
                path.endsWith("releases/latest") -> releaseJson().toByteArray()
                path.endsWith(AppUpdateClient.MANIFEST_ASSET_NAME) -> manifestJson(manifestDigest).toByteArray()
                else -> apk
            }
        return Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body.toResponseBody("application/octet-stream".toMediaType()))
            .build()
    }

    private fun releaseJson(): String =
        """
        {"assets":[
          {"name":"${AppUpdateClient.MANIFEST_ASSET_NAME}",
           "browser_download_url":"$BASE_URL/${AppUpdateClient.MANIFEST_ASSET_NAME}","size":128},
          {"name":"$APK_NAME","browser_download_url":"$BASE_URL/$APK_NAME","size":${apkBytes.size}}
        ]}
        """.trimIndent()

    private fun manifestJson(digest: String): String =
        """
        {"versionCode":$NEW_VERSION_CODE,"versionName":"0.0.2",
         "apkName":"$APK_NAME","apkSha256":"$digest"}
        """.trimIndent()

    private fun ByteArray.hex(): String =
        MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val BASE_URL = "https://updates.example.com"
        const val APK_NAME = "foxhole-guard.apk"
        const val NEW_VERSION_CODE = 90L
    }
}
