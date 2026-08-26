package com.foxhole.guard.runtime

import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress

class AppUpdateCheckOutcomeTest {
    @Test
    fun `a release with only a tag is an offer that carries how far behind the build is`() =
        runBlocking {
            val check = client(tagRelease("v0.0.5")).check(INSTALLED_CODE, INSTALLED_NAME)

            val available = check as? AppUpdateCheck.Available
            assertTrue("expected Available, was $check", available != null)
            requireNotNull(available)
            assertEquals("0.0.5", available.versionName)
            assertEquals(3, available.versionsBehind)
            assertEquals(AppUpdateSeverity.BEHIND_MANY, available.severity)
            assertFalse("a release without a manifest has nothing verifiable to install", available.installable)
        }

    private suspend fun severityOf(tag: String): AppUpdateSeverity =
        (
            client(tagRelease(tag)).check(INSTALLED_CODE, INSTALLED_NAME)
                as AppUpdateCheck.Available
            ).severity

    @Test
    fun `each rung of the ladder comes back from its own tag`() =
        runBlocking {
            assertEquals(
                AppUpdateSeverity.BEHIND_ONE,
                severityOf("v0.0.3"),
            )
            assertEquals(
                AppUpdateSeverity.BEHIND_TWO,
                severityOf("v0.0.4"),
            )
            assertEquals(
                AppUpdateSeverity.BEHIND_MANY,
                severityOf("v0.1.0"),
            )
        }

    @Test
    fun `the published tag equal to or older than the installed build is not an update`() =
        runBlocking {
            assertEquals(AppUpdateCheck.UpToDate, client(tagRelease("v0.0.2")).check(INSTALLED_CODE, INSTALLED_NAME))
            assertEquals(AppUpdateCheck.UpToDate, client(tagRelease("0.0.1")).check(INSTALLED_CODE, INSTALLED_NAME))
        }

    @Test
    fun `an equal tag is up to date without touching an unavailable manifest`() =
        runBlocking {
            var requests = 0
            val check =
                client { chain ->
                    requests++
                    if (chain.request().url.encodedPath.endsWith(AppUpdateClient.MANIFEST_ASSET_NAME)) {
                        respond(chain, 503, "unavailable")
                    } else {
                        respond(chain, 200, tagReleaseWithManifest("v0.0.2"))
                    }
                }.check(INSTALLED_CODE, INSTALLED_NAME)

            assertEquals(AppUpdateCheck.UpToDate, check)
            assertEquals("the manifest must not be fetched for an equal tag", 1, requests)
        }

    @Test
    fun `a newer tag still reads the manifest`() =
        runBlocking {
            var requests = 0
            val check =
                client { chain ->
                    requests++
                    if (chain.request().url.encodedPath.endsWith(AppUpdateClient.MANIFEST_ASSET_NAME)) {
                        respond(chain, 503, "unavailable")
                    } else {
                        respond(chain, 200, tagReleaseWithManifest("v0.0.3"))
                    }
                }.check(INSTALLED_CODE, INSTALLED_NAME)

            assertEquals(AppUpdateFailure.NETWORK, (check as AppUpdateCheck.Failed).failure)
            assertEquals("the manifest remains required for a newer tag", 2, requests)
        }

    @Test
    fun `a non-installable offer is refused by the repository instead of failing mid-transfer`() =
        runBlocking {
            var apkRequests = 0
            val repository =
                AppUpdateRepository(
                    client =
                    client { chain ->
                        apkRequests++
                        respond(chain, 200, tagRelease("v0.0.5"))
                    },
                    currentVersionCode = INSTALLED_CODE,
                    downloadDirectory = createTempDir(),
                    currentVersionName = INSTALLED_NAME,
                )
            val offer = repository.check() as AppUpdateCheck.Available
            val before = apkRequests

            val result = repository.download(offer)

            assertTrue(result.isFailure)
            assertEquals(
                AppUpdateFailure.NO_ARTIFACT,
                (repository.state.value as AppUpdateState.Failed).failure,
            )
            assertEquals("nothing may be fetched for an offer with no artifact", before, apkRequests)
        }

    @Test
    fun `a missing repository or release is not the same failure as a spent quota`() =
        runBlocking {
            assertEquals(AppUpdateFailure.NOT_FOUND, failureOf(status(404, """{"message":"Not Found"}""")))
            assertEquals(AppUpdateFailure.NOT_FOUND, failureOf(status(410, "gone")))
            assertEquals(
                AppUpdateFailure.RATE_LIMITED,
                failureOf(status(403, """{"message":"API rate limit exceeded for 1.2.3.4"}""")),
            )
            assertEquals(
                AppUpdateFailure.RATE_LIMITED,
                failureOf(status(403, "denied", headers = mapOf("x-ratelimit-remaining" to "0"))),
            )
            assertEquals(AppUpdateFailure.RATE_LIMITED, failureOf(status(429, "slow down")))
        }

    @Test
    fun `a rejected token is told apart from a spent quota`() =
        runBlocking {
            assertEquals(AppUpdateFailure.UNAUTHORIZED, failureOf(status(401, """{"message":"Bad credentials"}""")))
            assertEquals(
                AppUpdateFailure.UNAUTHORIZED,
                failureOf(status(403, """{"message":"Resource not accessible by personal access token"}""")),
            )
        }

    @Test
    fun `an error envelope served with 200 is malformed, never silently up to date`() =
        runBlocking {
            val envelope = """{"message":"API rate limit exceeded","documentation_url":"https://docs.github.com"}"""

            assertEquals(AppUpdateFailure.MALFORMED, failureOf(status(200, envelope)))
            assertEquals(AppUpdateFailure.MALFORMED, failureOf(status(200, "<html>not json</html>")))
            assertEquals(
                "a tag that cannot be ordered is malformed, not an update",
                AppUpdateFailure.MALFORMED,
                failureOf(status(200, tagRelease("nightly"))),
            )
        }

    @Test
    fun `a dead network is its own failure`() =
        runBlocking {
            val check = client { throw IOException("connection reset") }.check(INSTALLED_CODE, INSTALLED_NAME)

            assertEquals(AppUpdateFailure.NETWORK, (check as AppUpdateCheck.Failed).failure)
            assertEquals(AppUpdateFailure.NETWORK, failureOf(status(503, "unavailable")))
        }

    @Test
    fun `an address that fails the public https guard is never contacted`() =
        runBlocking {
            var requests = 0
            val client =
                AppUpdateClient(
                    httpClient =
                    OkHttpClient.Builder()
                        .addInterceptor { chain ->
                            requests++
                            respond(chain, 200, tagRelease("v9.9.9"))
                        }.build(),
                    resolver = { listOf(InetAddress.getByName("8.8.8.8")) },
                    releasesApiUrl = { "http://updates.example.com/releases/latest" },
                )

            val check = client.check(INSTALLED_CODE, INSTALLED_NAME)

            assertEquals(AppUpdateFailure.BLOCKED, (check as AppUpdateCheck.Failed).failure)
            assertEquals(0, requests)
        }

    private suspend fun failureOf(interceptor: Interceptor): AppUpdateFailure {
        val check = client(interceptor).check(INSTALLED_CODE, INSTALLED_NAME)
        assertTrue("expected Failed, was $check", check is AppUpdateCheck.Failed)
        return (check as AppUpdateCheck.Failed).failure
    }

    private fun status(
        code: Int,
        body: String,
        headers: Map<String, String> = emptyMap(),
    ) = Interceptor { chain -> respond(chain, code, body, headers) }

    private fun respond(
        chain: Interceptor.Chain,
        code: Int,
        body: String,
        headers: Map<String, String> = emptyMap(),
    ): Response =
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 200) "OK" else "ERR")
            .headers(Headers.headersOf(*headers.flatMap { (name, value) -> listOf(name, value) }.toTypedArray()))
            .body(body.toByteArray().toResponseBody("application/json".toMediaType()))
            .build()

    private fun client(releaseJson: String): AppUpdateClient = client(status(200, releaseJson))

    private fun client(interceptor: Interceptor): AppUpdateClient =
        AppUpdateClient(
            httpClient = OkHttpClient.Builder().addInterceptor(interceptor).build(),
            resolver = { listOf(InetAddress.getByName("8.8.8.8")) },
            releasesApiUrl = { "$BASE_URL/releases/latest" },
        )

    private fun tagRelease(tag: String): String =
        """
        {"tag_name":"$tag","name":"$tag","html_url":"$BASE_URL/releases/tag/$tag","body":"notes",
         "assets":[{"name":"foxhole-guard.apk","browser_download_url":"$BASE_URL/foxhole-guard.apk","size":4096}]}
        """.trimIndent()

    private fun tagReleaseWithManifest(tag: String): String =
        """
        {"tag_name":"$tag","name":"$tag","html_url":"$BASE_URL/releases/tag/$tag","body":"notes",
         "assets":[{"name":"${AppUpdateClient.MANIFEST_ASSET_NAME}",
                    "browser_download_url":"$BASE_URL/${AppUpdateClient.MANIFEST_ASSET_NAME}","size":128}]}
        """.trimIndent()

    private fun createTempDir(): java.io.File =
        java.io.File(System.getProperty("java.io.tmpdir"), "app-update-${System.nanoTime()}")

    private companion object {
        const val BASE_URL = "https://updates.example.com"
        const val INSTALLED_CODE = 90L
        const val INSTALLED_NAME = "0.0.2"
    }
}
