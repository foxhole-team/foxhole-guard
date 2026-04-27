package com.foxhole.beta.core.data

import com.foxhole.beta.core.network.ipv4TestAddress
import com.foxhole.beta.core.network.testRemoteHostResolver
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedPublicHttpFetchTest {
    @Test
    fun `bounded client disables automatic redirects and applies finite timeouts`() {
        val client = OkHttpClient().withBoundedRemoteFetchTimeouts()

        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertEquals(TimeUnit.SECONDS.toMillis(10).toInt(), client.connectTimeoutMillis)
        assertEquals(TimeUnit.SECONDS.toMillis(20).toInt(), client.readTimeoutMillis)
        assertEquals(TimeUnit.SECONDS.toMillis(30).toInt(), client.callTimeoutMillis)
    }

    @Test
    fun `follows public redirects with per hop validation`() {
        val routes =
            mapOf(
                "https://one.example/sub" to TestResponse.redirect("/next"),
                "https://one.example/next" to TestResponse.redirect("https://two.example/final"),
                "https://two.example/final" to TestResponse.ok("payload", "ETag" to "abc"),
            )

        val response =
            executeBoundedPublicGet(
                client = scriptedClient(routes),
                initialUrl = "https://one.example/sub".toHttpUrl(),
                allowHttp = false,
                maxBytes = 64,
                resolver = testRemoteHostResolver(),
            ) { url -> Request.Builder().url(url).get().build() }

        assertEquals(200, response.code)
        assertEquals("payload", response.body)
        assertEquals("abc", response.headers["ETag"])
        assertEquals("https://two.example/final", response.finalUrl.toString())
    }

    @Test
    fun `rejects redirect to private address before fetching target`() {
        val routes =
            mapOf(
                "https://one.example/sub" to TestResponse.redirect("https://10.0.0.1/private"),
                "https://10.0.0.1/private" to TestResponse.ok("private"),
            )

        val error =
            assertThrows(IllegalArgumentException::class.java) {
                executeBoundedPublicGet(
                    client = scriptedClient(routes),
                    initialUrl = "https://one.example/sub".toHttpUrl(),
                    allowHttp = false,
                    maxBytes = 64,
                    resolver = testRemoteHostResolver(),
                ) { url -> Request.Builder().url(url).get().build() }
            }

        assertTrue(error.message.orEmpty().contains("private or loopback hosts"))
    }

    @Test
    fun `rejects redirect host that resolves to private address`() {
        val routes =
            mapOf(
                "https://one.example/sub" to TestResponse.redirect("https://internal.example/private"),
                "https://internal.example/private" to TestResponse.ok("private"),
            )

        val error =
            assertThrows(IllegalArgumentException::class.java) {
                executeBoundedPublicGet(
                    client = scriptedClient(routes),
                    initialUrl = "https://one.example/sub".toHttpUrl(),
                    allowHttp = false,
                    maxBytes = 64,
                    resolver =
                        testRemoteHostResolver(
                            overrides = mapOf("internal.example" to ipv4TestAddress("192.168.1.10")),
                        ),
                ) { url -> Request.Builder().url(url).get().build() }
            }

        assertTrue(error.message.orEmpty().contains("private or loopback hosts"))
    }

    @Test
    fun `blocks dns rebinding when socket dns returns private address after public preflight`() {
        val client =
            OkHttpClient
                .Builder()
                .dns(Dns { listOf(InetAddress.getByName("127.0.0.1")) })
                .connectTimeout(100, TimeUnit.MILLISECONDS)
                .build()

        val error =
            assertThrows(UnknownHostException::class.java) {
                executeBoundedPublicGet(
                    client = client,
                    initialUrl = "https://rebind.example/sub".toHttpUrl(),
                    allowHttp = false,
                    maxBytes = 64,
                    resolver =
                        testRemoteHostResolver(
                            overrides = mapOf("rebind.example" to ipv4TestAddress("93.184.216.34")),
                        ),
                ) { url -> Request.Builder().url(url).get().build() }
            }

        assertTrue(error.message.orEmpty().contains("private, reserved, or loopback hosts"))
    }

    @Test
    fun `rejects too many redirects`() {
        val routes =
            mapOf(
                "https://one.example/0" to TestResponse.redirect("https://one.example/1"),
                "https://one.example/1" to TestResponse.redirect("https://one.example/2"),
                "https://one.example/2" to TestResponse.ok("payload"),
            )

        val error =
            assertThrows(IllegalStateException::class.java) {
                executeBoundedPublicGet(
                    client = scriptedClient(routes),
                    initialUrl = "https://one.example/0".toHttpUrl(),
                    allowHttp = false,
                    maxBytes = 64,
                    maxRedirects = 1,
                    resolver = testRemoteHostResolver(),
                ) { url -> Request.Builder().url(url).get().build() }
            }

        assertEquals("too many redirects", error.message)
    }

    @Test
    fun `rejects declared content length over cap`() {
        val body = "0123456789".toResponseBody(TEXT_PLAIN)

        val error =
            assertThrows(IllegalArgumentException::class.java) {
                body.readUtf8Capped(maxBytes = 4)
            }

        assertEquals("response body too large: 10 > 4", error.message)
    }

    @Test
    fun `rejects chunked body over cap`() {
        val routes =
            mapOf(
                "https://one.example/sub" to TestResponse.ok("0123456789", unknownLength = true),
            )

        val error =
            assertThrows(IllegalStateException::class.java) {
                executeBoundedPublicGet(
                    client = scriptedClient(routes),
                    initialUrl = "https://one.example/sub".toHttpUrl(),
                    allowHttp = false,
                    maxBytes = 4,
                    resolver = testRemoteHostResolver(),
                ) { url -> Request.Builder().url(url).get().build() }
            }

        assertEquals("response body exceeded limit: 4 bytes", error.message)
    }

    @Test
    fun `does not read body for not modified response`() {
        val response =
            executeBoundedPublicGet(
                client = scriptedClient(mapOf("https://one.example/sub" to TestResponse.notModified())),
                initialUrl = "https://one.example/sub".toHttpUrl(),
                allowHttp = false,
                maxBytes = 4,
                resolver = testRemoteHostResolver(),
            ) { url -> Request.Builder().url(url).get().build() }

        assertEquals(304, response.code)
        assertEquals(null, response.body)
    }

    private fun scriptedClient(routes: Map<String, TestResponse>): OkHttpClient =
        OkHttpClient
            .Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor(ScriptedResponseInterceptor(routes))
            .build()

    private inner class ScriptedResponseInterceptor(
        private val routes: Map<String, TestResponse>,
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val response = routes[request.url.toString()] ?: error("unexpected request: ${request.url}")
            return Response
                .Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(response.code)
                .message(response.message)
                .apply {
                    response.headers.forEach { (name, value) -> header(name, value) }
                    body(response.toResponseBody())
                }
                .build()
        }
    }

    private fun TestResponse.toResponseBody(): ResponseBody =
        if (unknownLength) {
            object : ResponseBody() {
                override fun contentType() = TEXT_PLAIN

                override fun contentLength(): Long = -1L

                override fun source(): BufferedSource = Buffer().writeUtf8(body)
            }
        } else {
            body.toResponseBody(TEXT_PLAIN)
        }

    private data class TestResponse(
        val code: Int,
        val message: String,
        val body: String = "",
        val headers: List<Pair<String, String>> = emptyList(),
        val unknownLength: Boolean = false,
    ) {
        companion object {
            fun ok(
                body: String,
                vararg headers: Pair<String, String>,
                unknownLength: Boolean = false,
            ): TestResponse =
                TestResponse(
                    code = 200,
                    message = "OK",
                    body = body,
                    headers = headers.toList(),
                    unknownLength = unknownLength,
                )

            fun redirect(location: String): TestResponse =
                TestResponse(
                    code = 302,
                    message = "Found",
                    headers = listOf("Location" to location),
                )

            fun notModified(): TestResponse =
                TestResponse(
                    code = 304,
                    message = "Not Modified",
                    body = "must not be read",
                )
        }
    }

    private companion object {
        val TEXT_PLAIN = "text/plain; charset=utf-8".toMediaType()
    }
}
