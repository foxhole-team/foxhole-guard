package com.foxhole.guard.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdateCancellationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun cancellingMetadataRequestCancelsItsCallAndPreservesCancellation() = runTest {
        val factory = HeldFactory()
        val client = client(factory, dispatcher = StandardTestDispatcher(testScheduler))
        val operation = async { client.check(1) }
        runCurrent()
        val call = factory.calls.single()

        operation.cancel()
        runCurrent()

        assertTrue(call.isCanceled())
        try {
            operation.await()
            fail("owner cancellation must propagate")
        } catch (_: CancellationException) {
            assertTrue(isActive)
        }
    }

    @Test fun metadataRedirectsShareOneDeadlineWithoutCancellingTheirOwner() = runTest {
        val factory = HeldFactory()
        val client = client(factory, timeout = 1000, dispatcher = StandardTestDispatcher(testScheduler))
        val operation = async { client.check(1) }
        runCurrent()
        advanceTimeBy(600)
        factory.calls.single().respond("".toResponseBody(), 302, "https://cdn.example/release")
        runCurrent()
        val second = factory.calls.last()
        assertEquals(2, factory.calls.size)

        advanceTimeBy(400)
        runCurrent()

        assertEquals(AppUpdateFailure.NETWORK, (operation.await() as AppUpdateCheck.Failed).failure)
        assertTrue(second.isCanceled())
        assertTrue(isActive)
    }

    @Test fun cancellingAnApkBodyClosesItsReaderAndRemovesThePartialFile() = runBlocking {
        val factory = HeldFactory()
        val target = File(temporary.root, "partial.apk")
        val operation = async(Dispatchers.Default) { client(factory).download(available(), target) }
        val call = requireNotNull(factory.started.poll(2, TimeUnit.SECONDS))
        val body = BlockingBody()
        val reader = thread(isDaemon = true) { call.respond(body) }
        try {
            assertTrue(body.blocked.await(2, TimeUnit.SECONDS))
            assertTrue(temporary.root.listFiles().orEmpty().any { it.name.endsWith(".part") && it.length() > 0 })
            operation.cancel()
            try {
                operation.await()
                fail("download cancellation must propagate")
            } catch (_: CancellationException) {
                assertTrue(call.isCanceled())
            }
            reader.join(2000)
            assertFalse("body reader still running", reader.isAlive)
            assertTrue(body.closed.get())
            assertFalse(target.exists())
        } finally {
            body.close()
            operation.cancel()
            reader.join(2000)
        }
    }

    @Test fun anApkBodyDeadlineClosesItsReaderAndLeavesItsOwnerActive() = runTest {
        val factory = HeldFactory()
        val target = File(temporary.root, "deadline.apk")
        val client = client(factory, timeout = 1000, dispatcher = StandardTestDispatcher(testScheduler))
        val operation = async { client.download(available(), target) }
        runCurrent()
        val body = BlockingBody()
        val call = factory.calls.single()
        val reader = thread(isDaemon = true) { call.respond(body) }
        try {
            assertTrue(body.blocked.await(2, TimeUnit.SECONDS))
            advanceTimeBy(1000)
            runCurrent()
            assertTrue(operation.await().isFailure)
            assertTrue(isActive)
            assertTrue(call.isCanceled())
            reader.join(2000)
            assertFalse(reader.isAlive)
            assertFalse(target.exists())
        } finally {
            body.close()
            operation.cancel()
            reader.join(2000)
        }
    }

    @Test fun aLateApkResponseAfterCancellationCannotRecreateItsFile() = runTest {
        val factory = HeldFactory()
        val target = temporary.newFile("late.apk")
        target.writeText("previous verified artifact")
        val client = client(factory, dispatcher = StandardTestDispatcher(testScheduler))
        val operation = async { client.download(available(), target) }
        runCurrent()
        operation.cancel()
        runCurrent()
        factory.calls.single().respond(PAYLOAD.toResponseBody())

        assertEquals("previous verified artifact", target.readText())
        assertTrue(operation.isCancelled)
    }

    @Test fun delayedCancelledReaderCannotDeleteANewerVerifiedDownloadToTheSameTarget() = runBlocking {
        val factory = HeldFactory()
        val target = temporary.newFile("shared.apk")
        target.writeText("previous verified artifact")
        val client = client(factory)
        val oldOperation = async(Dispatchers.Default) { client.download(available(), target) }
        val oldCall = requireNotNull(factory.started.poll(2, TimeUnit.SECONDS))
        val finishOldReader = CountDownLatch(1)
        val oldBody = BlockingBody(finishOldReader)
        val oldReader = thread(isDaemon = true) { oldCall.respond(oldBody) }
        try {
            assertTrue(oldBody.blocked.await(2, TimeUnit.SECONDS))
            assertEquals("previous verified artifact", target.readText())
            oldOperation.cancel()
            try { oldOperation.await() } catch (_: CancellationException) { }

            val replacement = async(Dispatchers.Default) { client.download(available(), target) }
            val newCall = requireNotNull(factory.started.poll(2, TimeUnit.SECONDS))
            newCall.respond(PAYLOAD.toResponseBody())
            assertTrue(replacement.await().isSuccess)
            assertArrayEquals(PAYLOAD, target.readBytes())

            finishOldReader.countDown()
            oldReader.join(2000)
            assertFalse(oldReader.isAlive)
            assertArrayEquals(PAYLOAD, target.readBytes())
            assertFalse(temporary.root.listFiles().orEmpty().any { it.name.endsWith(".part") })
        } finally {
            finishOldReader.countDown()
            oldBody.close()
            oldOperation.cancel()
            oldReader.join(2000)
        }
    }

    @Test fun aFailedDownloadKeepsThePreviousVerifiedTarget() = runTest {
        val factory = HeldFactory()
        val target = temporary.newFile("preserved.apk")
        target.writeText("previous verified artifact")
        val client = client(factory, dispatcher = StandardTestDispatcher(testScheduler))
        val operation = async { client.download(available(), target) }
        runCurrent()
        factory.calls.single().respond(PAYLOAD.copyOf().also { it[0] = 99 }.toResponseBody())
        runCurrent()

        assertTrue(operation.await().isFailure)
        assertEquals("previous verified artifact", target.readText())
        assertFalse(temporary.root.listFiles().orEmpty().any { it.name.endsWith(".part") })
    }

    private fun client(
        factory: HeldFactory,
        timeout: Long = 10000,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
    ) = AppUpdateClient(
        httpClient = OkHttpClient(),
        resolver = { listOf(InetAddress.getByName("8.8.8.8")) },
        releasesApiUrl = { "https://api.example/release" },
        operationTimeoutMs = timeout,
        ioDispatcher = dispatcher,
        callFactory = factory,
    )

    private fun available() = AppUpdateCheck.Available(
        AppUpdateManifest(
            2,
            "0.1.2",
            "foxhole.apk",
            MessageDigest.getInstance(
                "SHA-256"
            ).digest(PAYLOAD).joinToString("") {
                "%02x".format(it)
            }
        ),
        "https://cdn.example/foxhole.apk",
        PAYLOAD.size.toLong(),
    )

    internal class HeldFactory : Call.Factory {
        val calls = CopyOnWriteArrayList<HeldCall>()
        val started = LinkedBlockingQueue<HeldCall>()
        override fun newCall(request: Request): Call = HeldCall(request) { started.add(it) }.also(calls::add)
    }

    internal class HeldCall(private val request: Request, private val started: (HeldCall) -> Unit) : Call by OkHttpClient().newCall(
        request
    ) {
        private val cancelled = AtomicBoolean(false)
        private val executed = AtomicBoolean(false)
        private lateinit var callback: Callback
        override fun request() = request
        override fun execute(): Response = error("only cancellable enqueue is allowed")
        override fun enqueue(responseCallback: Callback) {
            callback = responseCallback
            executed.set(true)
            started(this)
        }
        override fun cancel() { cancelled.set(true) }
        override fun isCanceled() = cancelled.get()
        override fun isExecuted() = executed.get()
        override fun timeout() = Timeout.NONE
        override fun clone() = HeldCall(request, started)
        fun respond(body: ResponseBody, status: Int = 200, location: String? = null) {
            callback.onResponse(
                this,
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(
                        status
                    ).message("fixture").apply { location?.let { header("Location", it) } }.body(body).build()
            )
        }
    }

    internal class BlockingBody(private val afterClose: CountDownLatch? = null) : ResponseBody() {
        val blocked = CountDownLatch(1)
        val closed = AtomicBoolean(false)
        private val released = CountDownLatch(1)
        private val source = object : Source {
            private var sent = false
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (!sent) {
                    sent = true
                    sink.write(PAYLOAD)
                    return PAYLOAD.size.toLong()
                }
                blocked.countDown()
                if (!released.await(5, TimeUnit.SECONDS)) throw IOException("fixture reader was not closed")
                if (afterClose?.await(5, TimeUnit.SECONDS) == false) throw IOException("fixture callback not released")
                throw IOException("fixture reader closed")
            }
            override fun timeout() = Timeout.NONE
            override fun close() {
                closed.set(true)
                released.countDown()
            }
        }.buffer()
        override fun contentType(): MediaType? = null
        override fun contentLength() = -1L
        override fun source(): BufferedSource = source
    }

    private companion object { val PAYLOAD = ByteArray(32) { it.toByte() } }
}
