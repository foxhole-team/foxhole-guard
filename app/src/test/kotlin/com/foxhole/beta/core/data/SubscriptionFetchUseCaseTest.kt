package com.foxhole.beta.core.data

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class SubscriptionFetchUseCaseTest {
    @Test
    fun `returns not modified without reading body`() =
        runBlocking {
            val useCase =
                SubscriptionFetchUseCase(
                    scriptedClient("https://example.com/sub" to TestResponse.notModified()),
                )

            val response =
                useCase.fetchSubscriptionResponse(
                    sourceUrl = "https://example.com/sub",
                    safeUrl = "https://example.com/sub".toHttpUrl(),
                    lastEtag = "\"old\"",
                    allowHttp = false,
                )

            assertTrue(response.notModified)
            assertNull(response.body)
        }

    @Test
    fun `sends if none match and decodes base64 profile title`() =
        runBlocking {
            val title = Base64.getEncoder().encodeToString("Edge profile".toByteArray(StandardCharsets.UTF_8))
            val interceptor =
                ScriptedResponseInterceptor(
                    mapOf(
                        "https://example.com/sub" to
                            TestResponse.ok(
                                body = "payload",
                                headers = listOf(
                                    "ETag" to "\"new\"",
                                    "profile-title" to "base64:$title",
                                ),
                            ),
                    ),
                )
            val useCase = SubscriptionFetchUseCase(OkHttpClient.Builder().addInterceptor(interceptor).build())

            val response =
                useCase.fetchSubscriptionResponse(
                    sourceUrl = "https://example.com/sub",
                    safeUrl = "https://example.com/sub".toHttpUrl(),
                    lastEtag = "\"old\"",
                    allowHttp = false,
                )

            assertEquals("\"old\"", interceptor.lastRequest?.header("If-None-Match"))
            assertEquals("payload", response.body)
            assertEquals("\"new\"", response.etag)
            assertEquals("Edge profile", response.metadataTitle)
        }

    @Test
    fun `rejects oversized subscription body`() {
        val useCase =
            SubscriptionFetchUseCase(
                scriptedClient("https://example.com/sub" to TestResponse.oversized()),
            )

        val error =
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    useCase.fetchSubscriptionResponse(
                        sourceUrl = "https://example.com/sub",
                        safeUrl = "https://example.com/sub".toHttpUrl(),
                        lastEtag = null,
                        allowHttp = false,
                    )
                }
            }

        assertTrue(error.message.orEmpty().contains("response body too large"))
    }

    @Test
    fun `rejects redirect to private host`() {
        val useCase =
            SubscriptionFetchUseCase(
                scriptedClient("https://example.com/sub" to TestResponse.redirect("https://10.0.0.1/private")),
            )

        val error =
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    useCase.fetchSubscriptionResponse(
                        sourceUrl = "https://example.com/sub",
                        safeUrl = "https://example.com/sub".toHttpUrl(),
                        lastEtag = null,
                        allowHttp = false,
                    )
                }
            }

        assertTrue(error.message.orEmpty().contains("private or loopback hosts"))
    }

    @Test
    fun `http subscriptions require explicit allowance`() =
        runBlocking {
            val useCase =
                SubscriptionFetchUseCase(
                    scriptedClient("http://example.com/sub" to TestResponse.ok("payload")),
                )

            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    useCase.fetchSubscriptionResponse(
                        sourceUrl = "http://example.com/sub",
                        safeUrl = "http://example.com/sub".toHttpUrl(),
                        lastEtag = null,
                        allowHttp = false,
                    )
                }
            }

            val response =
                useCase.fetchSubscriptionResponse(
                    sourceUrl = "http://example.com/sub",
                    safeUrl = "http://example.com/sub".toHttpUrl(),
                    lastEtag = null,
                    allowHttp = true,
                )
            assertEquals("payload", response.body)
        }

    private fun scriptedClient(vararg routes: Pair<String, TestResponse>): OkHttpClient =
        OkHttpClient
            .Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor(ScriptedResponseInterceptor(routes.toMap()))
            .build()

    private class ScriptedResponseInterceptor(
        private val routes: Map<String, TestResponse>,
    ) : Interceptor {
        var lastRequest: Request? = null
            private set

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            lastRequest = request
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

    private data class TestResponse(
        val code: Int,
        val message: String,
        val body: String = "",
        val headers: List<Pair<String, String>> = emptyList(),
        val oversized: Boolean = false,
    ) {
        fun toResponseBody(): ResponseBody =
            if (oversized) {
                object : ResponseBody() {
                    override fun contentType() = TEXT_PLAIN

                    override fun contentLength(): Long = MAX_SUBSCRIPTION_BYTES + 1L

                    override fun source(): BufferedSource = Buffer().writeUtf8(body)
                }
            } else {
                body.toResponseBody(TEXT_PLAIN)
            }

        companion object {
            fun ok(
                body: String,
                headers: List<Pair<String, String>> = emptyList(),
            ): TestResponse = TestResponse(code = 200, message = "OK", body = body, headers = headers)

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

            fun oversized(): TestResponse =
                TestResponse(
                    code = 200,
                    message = "OK",
                    body = "x",
                    oversized = true,
                )
        }
    }

    private companion object {
        val TEXT_PLAIN = "text/plain; charset=utf-8".toMediaType()
    }
}
