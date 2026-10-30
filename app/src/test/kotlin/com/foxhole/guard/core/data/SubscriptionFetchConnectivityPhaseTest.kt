package com.foxhole.guard.core.data

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.SocketFactory
import javax.net.ssl.SSLHandshakeException

class SubscriptionFetchConnectivityPhaseTest {
    @Test
    fun `DNS and timeout causes cannot become eligible through a connection wrapper`() {
        val causes = listOf(
            UnknownHostException("owned nested policy DNS"),
            SocketTimeoutException("owned nested timeout"),
        )
        for (cause in causes) {
            val primary = ConnectException("owned connection wrapper").apply { initCause(cause) }
            assertSame(primary, subscriptionFetchConnectivityFailure(primary))
        }
    }

    @Test
    fun `actual okhttp failure callback marks only connect and no route`() = runBlocking {
        listOf(ConnectException("owned connect"), NoRouteToHostException("owned route"))
            .forEach { original ->
                withClient({ throw original }) { client ->
                    val failure = runCatching {
                        client.executeCancellableResponse(request, ::subscriptionFetchConnectivityFailure) { it.code }
                    }.exceptionOrNull()
                    assertTrue(failure is SubscriptionFetchConnectivityFailure)
                    assertSame(original, failure!!.cause)
                }
            }
    }

    @Test
    fun `default mapper preserves original callback failure for unrelated consumers`() = runBlocking {
        val original = OwnedConnectFailure("owned default consumer")
        withClient({ throw original }) { client ->
            val failure = runCatching { client.executeCancellableResponse(request) { it.code } }.exceptionOrNull()
            assertSame(original, failure)
        }
    }

    @Test
    fun `callback untagged dns ssl timeout and generic io are not marked eligible`() = runBlocking {
        listOf(
            OwnedDnsFailure("owned DNS or policy"),
            OwnedTlsFailure("owned TLS"),
            SocketTimeoutException("owned unphased timeout"),
            OwnedIoFailure("owned IO")
        )
            .forEach { original ->
                withClient({ throw original }) { client ->
                    val failure = runCatching {
                        client.executeCancellableResponse(request, ::subscriptionFetchConnectivityFailure) { it.code }
                    }.exceptionOrNull()
                    assertSame(original, failure)
                }
            }
    }

    @Test
    fun `401 body timeout remains onResponse failure and is never marked eligible`() = runBlocking {
        val original = SocketTimeoutException("owned 401 body timeout")
        withClient({ chain -> response(chain, 401, throwingBody(original)) }) { client ->
            var status: Int? = null
            val failure = runCatching {
                client.executeCancellableResponse(request, ::subscriptionFetchConnectivityFailure) {
                    status = it.code
                    it.body.string()
                }
            }.exceptionOrNull()
            assertEquals(401, status)
            assertSame(original, failure)
            assertFalse(failure is SubscriptionFetchConnectivityFailure)
        }
    }

    @Test
    fun `public host validation dns failure is outside mapper and never opens a request`() = runBlocking {
        val original = UnknownHostException("owned policy resolver failure")
        withClient({ throw AssertionError("validation must refuse before HTTP enqueue") }) { client ->
            val failure = runCatching {
                executeBoundedPublicGetCancellable(
                    client,
                    Request.Builder().url("https://policy.example/").build().url,
                    false,
                    4096L,
                    resolver = { throw original },
                    transportFailureMapper = ::subscriptionFetchConnectivityFailure,
                ) { url -> Request.Builder().url(url).build() }
            }.exceptionOrNull()
            assertTrue(failure != null)
            assertFalse(failure is SubscriptionFetchConnectivityFailure)
        }
    }

    @Test
    fun `HTTP status and payload callbacks do not acquire connectivity marker`() = runBlocking {
        val primary = OwnedDnsFailure("owned parse failure")
        withClient({ chain -> response(chain, 403, "owned denied".toResponseBody()) }) { client ->
            val failure = runCatching {
                client.executeCancellableResponse(request, ::subscriptionFetchConnectivityFailure) { throw primary }
            }.exceptionOrNull()
            assertSame(primary, failure)
        }
    }

    @Test
    fun `failure mapper fatal exception is delivered with identity rather than losing completion`() = runBlocking {
        val fatal = OwnedFatalFailure()
        withClient({ throw ConnectException("owned connect") }) { client ->
            val failure = runCatching {
                client.executeCancellableResponse(request, { throw fatal }) { it.code }
            }.exceptionOrNull()
            assertSame(fatal, failure)
        }
    }

    @Test
    fun `TLS certificate cancellation and fatal causes veto a top level connection marker`() {
        listOf(
            SSLHandshakeException("owned TLS"),
            CertificateException("owned certificate"),
            kotlinx.coroutines.CancellationException("owned cancellation"),
            AssertionError("owned fatal")
        )
            .forEach { cause ->
                val primary = ConnectException("owned connection wrapper").apply { initCause(cause) }
                assertSame(primary, subscriptionFetchConnectivityFailure(primary))
            }
    }

    @Test
    fun `cyclic cause graph terminates and still vetoes a reachable TLS failure`() {
        val primary = ConnectException("owned cyclic connection")
        val bridge = IOException("owned bridge")
        primary.initCause(bridge)
        bridge.initCause(primary)
        bridge.addSuppressed(SSLHandshakeException("owned nested TLS"))
        assertSame(primary, subscriptionFetchConnectivityFailure(primary))
        val unrelated = IOException(
            "owned unclassified wrapper"
        ).apply { initCause(NoRouteToHostException("owned nested route")) }
        assertSame(unrelated, subscriptionFetchConnectivityFailure(unrelated))
    }

    @Test
    fun `failure graph overflow vetoes a top level connection marker`() {
        val primary = ConnectException("owned graph overflow")
        var tail: Throwable = primary
        repeat(17) {
            val next = IOException("owned graph node")
            tail.initCause(next)
            tail = next
        }
        assertSame(primary, subscriptionFetchConnectivityFailure(primary))
    }

    private suspend fun withClient(interceptor: Interceptor, block: suspend (OkHttpClient) -> Unit) {
        val client = OkHttpClient.Builder().socketFactory(RefusingSocketFactory).addInterceptor(interceptor)
            .addNetworkInterceptor { throw AssertionError("owned fixture forbids all sockets") }.build()
        try {
            block(
                client
            )
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    private fun response(chain: Interceptor.Chain, code: Int, body: ResponseBody): Response =
        Response.Builder().request(
            chain.request()
        ).protocol(Protocol.HTTP_1_1).code(code).message("owned").body(body).build()

    private fun throwingBody(primary: IOException): ResponseBody = object : ResponseBody() {
        override fun contentType(): MediaType? = null
        override fun contentLength(): Long = -1L
        override fun source(): BufferedSource = object : ForwardingSource(Buffer()) {
            override fun read(sink: Buffer, byteCount: Long): Long = throw primary
        }.buffer()
    }

    private companion object { val request: Request = Request.Builder().url("https://1.1.1.1/").build() }
}

private object RefusingSocketFactory : SocketFactory() {
    override fun createSocket(): Socket = refused()
    override fun createSocket(host: String, port: Int): Socket = refused()
    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = refused()
    override fun createSocket(host: InetAddress, port: Int): Socket = refused()
    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int
    ): Socket = refused()
    private fun refused(): Nothing = throw AssertionError("owned fixture refuses socket creation")
}

private class OwnedConnectFailure(message: String, val marker: Any = Any()) : ConnectException(message)
private class OwnedDnsFailure(message: String, val marker: Any = Any()) : UnknownHostException(message)
private class OwnedTlsFailure(message: String, val marker: Any = Any()) : SSLHandshakeException(message)
private class OwnedIoFailure(message: String, val marker: Any = Any()) : IOException(message)
private class OwnedFatalFailure(val marker: Any = Any()) : Error("owned mapper fatal")
