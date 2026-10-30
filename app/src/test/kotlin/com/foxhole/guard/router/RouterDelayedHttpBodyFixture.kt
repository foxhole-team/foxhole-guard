package com.foxhole.guard.router

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

internal class RouterDelayedHttpBodyFixture : Call.Factory, Closeable {
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    private val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
        .retryOnConnectionFailure(false).readTimeout(10, TimeUnit.SECONDS).build()
    private val stopped = AtomicBoolean()
    private val peer = AtomicReference<Socket?>()
    private val releaseServer = CountDownLatch(1)
    private val releaseRead = CountDownLatch(1)
    val readEntered = CountDownLatch(1)
    val transportReadExited = CountDownLatch(1)
    val callbackFinished = CountDownLatch(1)
    val callCancelled = AtomicBoolean()
    val callbackThread = AtomicReference<Thread?>()
    val readThread = AtomicReference<Thread?>()
    val closeThreads = ConcurrentLinkedQueue<Thread>()
    val callbackFailure = AtomicReference<Throwable?>()
    val serverFailure = AtomicReference<Throwable?>()
    private val serverWorker = thread(name = "router-delayed-http-fixture", isDaemon = true) {
        try {
            server.accept().use { socket ->
                peer.set(socket)
                socket.soTimeout = 5_000
                val input = socket.getInputStream()
                val headers = StringBuilder()
                while (!headers.endsWith("\r\n\r\n")) {
                    val next = input.read()
                    check(next >= 0 && headers.length < 8192)
                    headers.append(next.toChar())
                }
                val length = headers.lines().firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                    ?.substringAfter(':')?.trim()?.toInt() ?: 0
                check(length in 0..300_000)
                repeat(length) { check(input.read() >= 0) }
                socket.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 64\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    flush()
                }
                check(releaseServer.await(10, TimeUnit.SECONDS))
            }
        } catch (failure: Throwable) {
            if (!stopped.get()) serverFailure.set(failure)
        } finally { peer.set(null) }
    }

    override fun newCall(request: Request): Call {
        val call = client.newCall(request.newBuilder().url("http://127.0.0.1:${server.localPort}/fixture").build())
        return object : Call by call {
            override fun cancel() { callCancelled.set(true); call.cancel() }

            override fun enqueue(responseCallback: Callback) {
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        responseCallback.onFailure(call, e)
                        callbackFinished.countDown()
                    }

                    override fun onResponse(call: Call, response: Response) {
                        callbackThread.set(Thread.currentThread())
                        val stream = object : ForwardingSource(response.body.source()) {
                            override fun read(sink: Buffer, byteCount: Long): Long {
                                readThread.set(Thread.currentThread())
                                readEntered.countDown()
                                return try { super.read(sink, byteCount) } finally {
                                    transportReadExited.countDown()
                                    // Hold the callback after transport cancellation so an external close is observable.
                                    check(releaseRead.await(10, TimeUnit.SECONDS))
                                }
                            }

                            override fun close() {
                                closeThreads += Thread.currentThread()
                                super.close()
                            }
                        }.buffer()
                        val observedBody = object : ResponseBody() {
                            override fun contentType(): MediaType? = response.body.contentType()
                            override fun contentLength(): Long = response.body.contentLength()
                            override fun source(): BufferedSource = stream
                        }
                        try {
                            responseCallback.onResponse(call, response.newBuilder().body(observedBody).build())
                        } catch (failure: Throwable) {
                            callbackFailure.set(failure)
                        } finally { callbackFinished.countDown() }
                    }
                })
            }
        }
    }

    fun releaseBodyRead() { releaseRead.countDown() }

    override fun close() {
        stopped.set(true)
        releaseRead.countDown()
        releaseServer.countDown()
        client.dispatcher.cancelAll()
        peer.getAndSet(null)?.close()
        server.close()
        serverWorker.join(1000)
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdownNow()
        check(!serverWorker.isAlive)
    }
}
