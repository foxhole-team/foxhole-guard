package com.foxhole.guard.runtime

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetAddress
import java.security.MessageDigest

class AppUpdateRedirectTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `private manifest and APK follow CDN redirects without leaking credentials`() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = client(routes(), requests)

        val update = client.check(127, "0.1.1") as AppUpdateCheck.Available
        val target = temporaryFolder.newFile()
        val result = client.download(update, target)

        assertTrue(result.isSuccess)
        assertArrayEquals(APK.toByteArray(), target.readBytes())
        assertEquals(6, requests.size)
        requests.forEach { request ->
            if (request.url.host == "api.github.com") {
                assertEquals("Bearer test-token", request.header("Authorization"))
            } else {
                assertNull(request.header("Authorization"))
            }
            if (request.url.toString() != RELEASE_URL) {
                assertEquals("application/octet-stream", request.header("Accept"))
            }
        }
    }

    @Test
    fun `public manifest and APK follow download redirects`() = runBlocking {
        val requests = mutableListOf<Request>()
        val routes = routes().toMutableMap()
        routes[BROWSER_MANIFEST] = 302 to CDN_MANIFEST
        routes[BROWSER_APK] = 307 to CDN_APK
        val client = client(routes, requests, token = "")

        val update = client.check(122, "0.1.0") as AppUpdateCheck.Available
        val target = temporaryFolder.newFile()

        assertEquals(BROWSER_APK, update.downloadUrl)
        assertTrue(client.download(update, target).isSuccess)
        assertArrayEquals(APK.toByteArray(), target.readBytes())
        assertTrue(requests.all { it.header("Authorization") == null })
    }

    @Test
    fun `unsafe manifest redirects are refused before contacting the target`() = runBlocking {
        listOf(
            "http://cdn.example/manifest",
            "https://127.0.0.1/manifest",
            "https://private.example/manifest"
        ).forEach { target ->
            val requests = mutableListOf<Request>()
            val routes = routes().toMutableMap().apply { put(API_MANIFEST, 302 to target) }

            val result = client(routes, requests).check(127, "0.1.1") as AppUpdateCheck.Failed

            assertEquals(AppUpdateFailure.BLOCKED, result.failure)
            assertEquals(2, requests.size)
        }
    }

    @Test
    fun `APK redirects retain digest verification and remove tampered downloads`() = runBlocking {
        val routes = routes().toMutableMap().apply { put(CDN_APK, 200 to "x".repeat(APK.length)) }
        val client = client(routes, mutableListOf())
        val update = client.check(127, "0.1.1") as AppUpdateCheck.Available
        val target = temporaryFolder.newFile()

        val result = client.download(update, target)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("digest mismatch"))
        assertTrue(target.exists())
        assertEquals(0L, target.length())
        assertFalse(target.parentFile?.listFiles().orEmpty().any { it.name.endsWith(".part") })
    }

    @Test
    fun `redirect loops stop after five hops`() = runBlocking {
        val requests = mutableListOf<Request>()
        val result = client(mapOf(RELEASE_URL to (302 to RELEASE_URL)), requests).check(127, "0.1.1")

        assertEquals(AppUpdateFailure.NETWORK, (result as AppUpdateCheck.Failed).failure)
        assertEquals(6, requests.size)
    }

    @Test
    fun `authorization is not sent to another port on the API host`() = runBlocking {
        val target = "https://api.github.com:8443/manifest"
        val requests = mutableListOf<Request>()
        val routes = routes().toMutableMap().apply {
            put(API_MANIFEST, 302 to target)
            put(target, 200 to MANIFEST)
        }

        assertTrue(client(routes, requests).check(127, "0.1.1") is AppUpdateCheck.Available)
        assertNull(requests.last().header("Authorization"))
    }

    private fun client(routes: Map<String, Pair<Int, String>>, requests: MutableList<Request>, token: String = "test-token") =
        AppUpdateClient(
            httpClient = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                requests += request
                val (status, content) = requireNotNull(routes[request.url.toString()]) { "Unexpected target" }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("test")
                    .apply { if (status in 300..399) header("Location", content) }
                    .body((if (status == 200) content else "").toResponseBody())
                    .build()
            }.build(),
            resolver = { host ->
                listOf(
                    InetAddress.getByName(if (host == "private.example") "127.0.0.1" else "8.8.8.8")
                )
            },
            releasesApiUrl = { RELEASE_URL },
            releasesToken = { token },
        )

    private fun routes() = mapOf(
        RELEASE_URL to (200 to RELEASE),
        API_MANIFEST to (302 to CDN_MANIFEST),
        CDN_MANIFEST to (303 to "/manifest-final"),
        "https://cdn.example/manifest-final" to (200 to MANIFEST),
        API_APK to (302 to CDN_APK),
        CDN_APK to (200 to APK),
    )

    private companion object {
        const val RELEASE_URL = "https://api.github.com/repos/test/private/releases/latest"
        const val API_MANIFEST = "https://api.github.com/repos/test/private/releases/assets/11"
        const val API_APK = "https://api.github.com/repos/test/private/releases/assets/12"
        const val BROWSER_MANIFEST = "https://github.com/test/private/releases/download/private-128/update-manifest.json"
        const val BROWSER_APK = "https://github.com/test/private/releases/download/private-128/foxhole.apk"
        const val CDN_MANIFEST = "https://cdn.example/manifest"
        const val CDN_APK = "https://cdn.example/apk"
        const val APK = "apk test payload"
        val DIGEST = MessageDigest.getInstance(
            "SHA-256"
        ).digest(APK.toByteArray()).joinToString("") { "%02x".format(it) }
        val MANIFEST = """{"versionCode":128,"versionName":"0.1.1","apkName":"foxhole.apk","apkSha256":"$DIGEST"}"""
        val RELEASE = """{"tag_name":"private-128","assets":[
          {"name":"update-manifest.json","url":"$API_MANIFEST","browser_download_url":"$BROWSER_MANIFEST","size":200},
          {"name":"foxhole.apk","url":"$API_APK","browser_download_url":"$BROWSER_APK","size":${APK.length}}]}"""
    }
}
