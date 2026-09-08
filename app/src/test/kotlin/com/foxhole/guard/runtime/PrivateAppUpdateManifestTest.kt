package com.foxhole.guard.runtime

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class PrivateAppUpdateManifestTest {
    @Test
    fun `private release manifest requests asset bytes with repository authorization`() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = AppUpdateClient(
            httpClient = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                requests += request
                val body = when (request.url.encodedPath) {
                    "/repos/test/private/releases/latest" -> RELEASE
                    "/repos/test/private/releases/assets/11" ->
                        if (request.header("Accept") == "application/octet-stream" &&
                            request.header("Authorization") == "Bearer test-token"
                        ) {
                            MANIFEST
                        } else {
                            """{"id":11,"name":"update-manifest.json"}"""
                        }
                    else -> error("Unexpected request")
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body(body.toResponseBody("application/json".toMediaType())).build()
            }.build(),
            resolver = { listOf(InetAddress.getByName("8.8.8.8")) },
            releasesApiUrl = { "https://api.github.com/repos/test/private/releases/latest" },
            releasesToken = { "test-token" },
        )

        val result = client.check(currentVersionCode = 118, currentVersionName = "0.1.0")

        assertTrue("Expected installable private release, received $result", result is AppUpdateCheck.Available)
        val available = result as AppUpdateCheck.Available
        assertTrue(available.installable)
        assertEquals("https://api.github.com/repos/test/private/releases/assets/12", available.downloadUrl)
        assertEquals(2, requests.size)
        assertEquals("application/vnd.github+json", requests[0].header("Accept"))
        assertEquals("application/octet-stream", requests[1].header("Accept"))
    }

    private companion object {
        val RELEASE = """
            {"tag_name":"v0.1.1","assets":[
              {"name":"update-manifest.json","url":"https://api.github.com/repos/test/private/releases/assets/11",
               "browser_download_url":"https://github.com/test/private/releases/download/v0.1.1/update-manifest.json","size":200},
              {"name":"foxhole.apk","url":"https://api.github.com/repos/test/private/releases/assets/12",
               "browser_download_url":"https://github.com/test/private/releases/download/v0.1.1/foxhole.apk","size":4096}
            ]}
        """.trimIndent()
        val MANIFEST = """
            {"versionCode":119,"versionName":"0.1.1","apkName":"foxhole.apk",
             "apkSha256":"${"a".repeat(64)}"}
        """.trimIndent()
    }
}
