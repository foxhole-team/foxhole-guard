package com.foxhole.guard.core.data

import com.foxhole.core.network.ipv4TestAddress
import com.foxhole.core.network.testRemoteHostResolver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.SocketFactory
import kotlin.concurrent.thread

class BoundedPublicHttpCancellationTest {
    @Test
    fun `cancelling a text fetch releases a stalled http socket`() =
        assertCancellationClosesSocket(binary = false)

    @Test
    fun `cancelling an icon fetch releases a stalled response body socket`() =
        assertCancellationClosesSocket(binary = true)

    private fun assertCancellationClosesSocket(binary: Boolean) = runBlocking {
        val received = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Int>()
        val scope = CoroutineScope(
            SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, failure ->
                received.completeExceptionally(failure)
                closed.completeExceptionally(failure)
            }
        )
        val accepted = AtomicReference<Socket?>()
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val reader = thread(isDaemon = true, name = "web-preview-stalled-http") {
            try {
                server.accept().use { socket ->
                    accepted.set(socket)
                    socket.soTimeout = 5_000
                    val input = socket.getInputStream()
                    var ending = 0
                    while (ending != 0x0d0a0d0a) {
                        val next = input.read()
                        check(next >= 0) { "request closed before headers" }
                        ending = (ending shl 8) or next
                    }
                    if (binary) {
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Length: 32\r\n\r\na".toByteArray(Charsets.US_ASCII))
                            flush()
                        }
                    }
                    received.complete(Unit)
                    closed.complete(input.read())
                }
            } catch (failure: Exception) {
                received.completeExceptionally(failure)
                closed.completeExceptionally(failure)
            }
        }
        val client = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .dns(Dns { listOf(ipv4TestAddress("93.184.216.34")) })
            .socketFactory(LocalTestSocketFactory(server.localPort))
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
        try {
            val request = scope.launch {
                val url = "http://web-preview.example:${server.localPort}/icon.png".toHttpUrl()
                if (binary) {
                    executeBoundedPublicGetBytesCancellable(
                        client,
                        url,
                        allowHttp = true,
                        maxBytes = 64,
                        resolver = testRemoteHostResolver(),
                    ) { target -> Request.Builder().url(target).build() }
                } else {
                    executeBoundedPublicGetCancellable(
                        client,
                        url,
                        allowHttp = true,
                        maxBytes = 64,
                        resolver = testRemoteHostResolver(),
                    ) { target -> Request.Builder().url(target).build() }
                }
            }
            withTimeout(3_000) { received.await() }
            request.cancel()

            assertEquals(
                "cancel must close the socket before the network timeout",
                -1,
                withTimeout(1_000) { closed.await() }
            )
            withTimeout(1_000) { request.join() }
        } finally {
            scope.cancel()
            accepted.get()?.close()
            server.close()
            reader.join(1_000)
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    private class LocalTestSocketFactory(private val port: Int) : SocketFactory() {
        override fun createSocket(): Socket = object : Socket() {
            override fun connect(endpoint: SocketAddress, timeout: Int) {
                super.connect(
                    InetSocketAddress(InetAddress.getLoopbackAddress(), this@LocalTestSocketFactory.port),
                    timeout
                )
            }
        }

        override fun createSocket(host: String, port: Int): Socket = error("unexpected connected factory overload")
        override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket =
            error("unexpected connected factory overload")
        override fun createSocket(host: InetAddress, port: Int): Socket = error("unexpected connected factory overload")
        override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket =
            error("unexpected connected factory overload")
    }
}
