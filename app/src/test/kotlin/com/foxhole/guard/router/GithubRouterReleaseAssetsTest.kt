package com.foxhole.guard.router

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.ResponseBody
import okhttp3.MediaType
import okio.BufferedSource
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GithubRouterReleaseAssetsTest {
    private val release = "https://github.com/foxhole-team/foxhole-openwrt-client/releases/download/v1.0.0"
    private class Fixture(private val request: Request, private val code: Int = 200, private val body: ByteArray = byteArrayOf(1),
        private val location: String? = null, private val pending: Boolean = false) : Call by OkHttpClient().newCall(request) {
        var cancelled = false
        override fun enqueue(callback: Callback) {
            if (!pending) callback.onResponse(this, Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(code).message("fixture").apply { location?.let { header("Location", it) } }.body(body.toResponseBody()).build())
        }
        override fun cancel() { cancelled = true }
    }

    @Test fun assetPathPreservesTheReleaseTagAndCarriesNoAuthentication() = runTest {
        val source = GithubRouterReleaseAssets("$release/", Call.Factory { request ->
            assertEquals("$release/install.sh", request.url.toString())
            assertEquals(null, request.header("Authorization"))
            Fixture(request, body = "fixture".toByteArray())
        })
        assertArrayEquals("fixture".toByteArray(), source.read("install.sh", 64))
    }

    @Test fun measuredAndUnknownLengthBodiesReportOnlyObservedBytes() = runTest {
        for (unknown in listOf(false, true)) {
            val payload = ByteArray(20000) { (it % 127).toByte() }
            val progress = mutableListOf<Pair<Long, Long?>>()
            val source = GithubRouterReleaseAssets(release, Call.Factory { request ->
                object : Call by OkHttpClient().newCall(request) {
                    override fun enqueue(callback: Callback) {
                        val body = object : ResponseBody() {
                            private val raw = Buffer().write(payload)
                            override fun contentType(): MediaType? = null
                            override fun contentLength(): Long = if (unknown) -1 else payload.size.toLong()
                            override fun source(): BufferedSource = raw
                        }
                        callback.onResponse(this, Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                            .code(200).message("fixture").body(body).build())
                    }
                }
            })
            assertArrayEquals(payload, source.read("install.sh", 65536) { read, total -> progress += read to total })
            assertEquals(0L, progress.first().first)
            assertEquals(payload.size.toLong(), progress.last().first)
            assertTrue(progress.zipWithNext().all { (a, b) -> a.first <= b.first })
            assertTrue(progress.all { it.second == if (unknown) null else payload.size.toLong() })
        }
    }

    @Test fun officialHttpsAssetRedirectsAreFollowedWithinTheOwnedRequest() = runTest {
        val requests = mutableListOf<Request>()
        val redirected = "https://release-assets.githubusercontent.com/fixture?download=1"
        val source = GithubRouterReleaseAssets(release, Call.Factory { request ->
            requests += request
            if (requests.size == 1) Fixture(request, 302, location = redirected) else Fixture(request)
        })
        assertArrayEquals(byteArrayOf(1), source.read("SHA256SUMS", 64))
        assertEquals(listOf("$release/SHA256SUMS", redirected), requests.map { it.url.toString() })
    }

    @Test fun redirectsToPrivateHttpOrCredentialedEndpointsAreRefused() = runTest {
        for (destination in listOf("http://release-assets.githubusercontent.com/fixture", "https://192.168.1.1/fixture",
            "https://user:fixture@github.com/fixture", "https://github.com.evil.example/fixture")) {
            var requested = 0
            val source = GithubRouterReleaseAssets(release, Call.Factory { request -> requested++; Fixture(request, 302, location = destination) })
            assertTrue(runCatching { source.read("SHA256SUMS", 64) }.isFailure)
            assertEquals(1, requested)
        }
    }

    @Test fun oversizedBodyCannotBecomeAnAsset() = runTest {
        val source = GithubRouterReleaseAssets(release, Call.Factory { request -> Fixture(request, body = ByteArray(65)) })
        assertTrue(runCatching { source.read("install.sh", 64) }.isFailure)
    }

    @Test fun closingTheWizardCancelsItsPendingReleaseDownload() = runTest {
        lateinit var call: Fixture
        val source = GithubRouterReleaseAssets(release, Call.Factory { request -> Fixture(request, pending = true).also { call = it } })
        val job = launch { source.read("install.sh", 64) }
        runCurrent()
        job.cancel()
        runCurrent()
        assertTrue(call.cancelled)
    }

    @Test fun assetDeadlineSettlesTheRequestWithoutCancellingItsOwner() = runTest {
        lateinit var call: Fixture
        val source = GithubRouterReleaseAssets(release, Call.Factory { request -> Fixture(request, pending = true).also { call = it } })
        val result = async { runCatching { source.read("install.sh", 64) } }
        runCurrent()
        advanceTimeBy(45001)
        runCurrent()
        assertTrue(result.await().exceptionOrNull() is IOException)
        assertTrue(call.cancelled && isActive)
    }

    @Test fun wholePreparationDeadlinePreservesExternalOwnerCancellation() = runTest {
        val result = async { runCatching { routerReleasePhase<Unit> { awaitCancellation() } } }
        runCurrent()
        advanceTimeBy(120001)
        runCurrent()
        assertTrue(result.await().exceptionOrNull() is IOException)
        assertTrue(isActive)
    }

    @Test fun cancellationClosesAnAlreadyReceivedBodyWhileItsReadIsBlocked() = runTest {
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val closed = AtomicBoolean()
        val raw = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                entered.countDown()
                released.await(5, TimeUnit.SECONDS)
                throw IOException("fixture_closed")
            }
            override fun timeout(): Timeout = Timeout.NONE
            override fun close() { closed.set(true); released.countDown() }
        }.buffer()
        val body = object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength(): Long = -1
            override fun source(): BufferedSource = raw
        }
        var worker: Thread? = null
        val source = GithubRouterReleaseAssets(release, Call.Factory { request ->
            object : Call by OkHttpClient().newCall(request) {
                override fun cancel() { released.countDown() }
                override fun enqueue(callback: Callback) {
                    worker = thread(isDaemon = true) { callback.onResponse(this, Response.Builder().request(request)
                        .protocol(Protocol.HTTP_1_1).code(200).message("fixture").body(body).build()) }
                }
            }
        })
        val progress = ConcurrentLinkedQueue<Long>()
        val job = launch { source.read("install.sh", 64) { read, _ -> progress += read } }
        runCurrent()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        job.cancel()
        runCurrent()
        worker?.join(1000)
        assertTrue(worker?.isAlive == false)
        assertTrue(closed.get())
        assertTrue(progress.none { it > 0 })
    }

    @Test fun cancellingARealDelayedAssetBodyLeavesClosingToItsCallbackThread() = runBlocking {
        RouterDelayedHttpBodyFixture().use { fixture ->
            val failures = ConcurrentLinkedQueue<Throwable>()
            val owner = SupervisorJob()
            val scope = CoroutineScope(owner + Dispatchers.Default + CoroutineExceptionHandler { _, failure -> failures += failure })
            val source = GithubRouterReleaseAssets(release, fixture)
            val job = scope.launch { source.read("install.sh", 64); error("delayed asset unexpectedly completed") }
            try {
                assertTrue(fixture.readEntered.await(5, TimeUnit.SECONDS))
                job.cancel()
                withTimeout(5000) { job.join() }
                assertTrue(fixture.transportReadExited.await(5, TimeUnit.SECONDS))
                assertTrue(job.isCancelled)
                assertTrue(fixture.callCancelled.get())
                assertTrue("cancellation must not close a router response while its callback reader owns it", fixture.closeThreads.isEmpty())
                assertTrue(failures.isEmpty())
            } finally {
                job.cancel()
                fixture.releaseBodyRead()
                owner.cancel()
                assertTrue(fixture.callbackFinished.await(5, TimeUnit.SECONDS))
            }
            assertTrue(fixture.closeThreads.isNotEmpty())
            assertTrue(fixture.closeThreads.all { it === fixture.callbackThread.get() })
            assertTrue(fixture.readThread.get() === fixture.callbackThread.get())
            assertEquals(null, fixture.callbackFailure.get())
            assertEquals(null, fixture.serverFailure.get())
            assertTrue(failures.isEmpty())
        }
    }
}
