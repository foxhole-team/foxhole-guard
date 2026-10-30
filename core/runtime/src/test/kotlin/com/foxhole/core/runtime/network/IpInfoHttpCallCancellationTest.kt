package com.foxhole.core.runtime.network

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.CoroutineContext

internal class IpInfoHttpCallCancellationTest {
    @Test
    fun `response delivered before cancellation is closed when resume is still queued`() {
        val dispatcher = QueuedDispatcher()
        val call = PendingCall()
        var bodyClosed = false
        var responseReceived = false
        val response = response(call) { bodyClosed = true }
        val job =
            CoroutineScope(Job() + dispatcher).launch(start = CoroutineStart.UNDISPATCHED) {
                call.awaitResponse().use { responseReceived = true }
            }

        call.deliver(response)
        job.cancel()
        dispatcher.drain()

        assertTrue(job.isCompleted)
        assertTrue(call.isCanceled())
        assertFalse(responseReceived)
        assertTrue("the caller cannot close a response it never received", bodyClosed)
    }

    @Test
    fun `response arriving after cancellation is closed immediately`() {
        val dispatcher = QueuedDispatcher()
        val call = PendingCall()
        var closes = 0
        val response = response(call) { closes += 1 }
        val job =
            CoroutineScope(Job() + dispatcher).launch(start = CoroutineStart.UNDISPATCHED) {
                call.awaitResponse().close()
            }

        job.cancel()
        call.deliver(response)
        dispatcher.drain()

        assertTrue(job.isCompleted)
        assertEquals(1, closes)
    }

    private fun response(call: Call, onClosed: () -> Unit): Response {
        val source = object : ForwardingSource(Buffer()) {
            override fun close() {
                onClosed()
                super.close()
            }
        }.buffer()
        val body = object : ResponseBody() {
            override fun contentType(): MediaType? = null

            override fun contentLength(): Long = 0L

            override fun source(): BufferedSource = source
        }
        return Response.Builder()
            .request(call.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body)
            .build()
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            tasks.addLast(block)
        }

        fun drain() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }

    private class PendingCall :
        Call by OkHttpClient().newCall(
            Request.Builder().url("https://example.com/ip").build(),
        ) {
        private lateinit var callback: Callback
        private var cancelled = false

        override fun execute(): Response = error("synchronous execution is not used")

        override fun enqueue(responseCallback: Callback) {
            callback = responseCallback
        }

        override fun cancel() {
            cancelled = true
        }

        override fun isExecuted(): Boolean = ::callback.isInitialized

        override fun isCanceled(): Boolean = cancelled

        override fun clone(): Call = PendingCall()

        fun deliver(response: Response) {
            callback.onResponse(this, response)
        }
    }
}
