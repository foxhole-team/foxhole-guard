package com.foxhole.guard.router

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Test

class RouterTransportCleanupTest {
    @Test fun successCloseInvalidatesImmediatelyAndClosesOnceOffTheOwnerThread() = runBlocking {
        val owner = Thread.currentThread()
        val invalidated = AtomicInteger()
        val closed = AtomicInteger()
        val cleanup = RouterTransportCleanup(invalidate = { invalidated.incrementAndGet() }, cleanup = {
            check(Thread.currentThread() !== owner)
            closed.incrementAndGet()
        })
        cleanup.close(); cleanup.close()
        check(invalidated.get() == 1)
        cleanup.closeAndAwait()
        check(closed.get() == 1)
    }

    @Test fun cleanupFailureIsReportedToTheAwaitingOwner() = runBlocking {
        val expected = IOException("synthetic close failure")
        val cleanup = RouterTransportCleanup(cleanup = { throw expected })
        val failure = try { cleanup.closeAndAwait(); null } catch (error: IOException) { error }
        val actual = requireNotNull(failure)
        check(actual.javaClass == expected.javaClass && actual.message == expected.message)
        check(actual === expected || actual.cause === expected)
    }

    @Test fun cancellationFinallyStillCompletesNonCancellableCleanup() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val closed = AtomicInteger()
        val cleanup = RouterTransportCleanup(cleanup = { closed.incrementAndGet() })
        val job = launch {
            try { entered.complete(Unit); awaitCancellation() }
            finally { cleanup.closeAndAwait() }
        }
        entered.await(); job.cancelAndJoin()
        check(closed.get() == 1)
    }

    @Test fun cleanupWaitHasABoundWithoutCancellingPhysicalCleanup() = runBlocking {
        val release = CountDownLatch(1)
        val cleanup = RouterTransportCleanup(cleanup = { check(release.await(5, TimeUnit.SECONDS)) })
        try {
            val failure = try { cleanup.closeAndAwait(25); null } catch (error: IOException) { error }
            check(failure?.message == "router_cleanup_timeout")
        } finally { release.countDown() }
        cleanup.closeAndAwait()
    }

    @Test fun ownedRawSocketsCloseBeforeTlsPoolEvictionAndRejectLateCreation() = runBlocking {
        val owner = Thread.currentThread()
        val events = Collections.synchronizedList(mutableListOf<String>())
        val opened = AtomicInteger()
        val factory = RouterOwnedSocketFactory(object : SocketFactory() {
            private fun socket(): Socket {
                opened.incrementAndGet()
                return object : Socket() {
                    override fun close() { check(Thread.currentThread() !== owner); events.add("raw"); super.close() }
                }
            }
            override fun createSocket() = socket()
            override fun createSocket(host: String, port: Int) = socket()
            override fun createSocket(host: InetAddress, port: Int) = socket()
            override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int) = socket()
            override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int) = socket()
        })
        val socket = factory.createSocket()
        val cleanup = RouterTransportCleanup(invalidate = factory::invalidate, cleanup = {
            factory.closeOwnedOnIo()
            check(socket.isClosed && Thread.currentThread() !== owner)
            events.add("tls-pool")
        })
        cleanup.close()
        val rejected = try { factory.createSocket(); false } catch (_: IOException) { true }
        check(rejected && opened.get() == 1)
        cleanup.closeAndAwait()
        check(events == listOf("raw", "tls-pool"))
    }

    @Test fun cancellingBlockedRouterIoClosesOnAnIndependentIoWorker() = runBlocking {
        val owner = Thread.currentThread()
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        val closes = AtomicInteger()
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { worker ->
            val job = async {
                routerIo(worker) { operation ->
                    operation.register(Closeable { check(Thread.currentThread() !== owner); closes.incrementAndGet(); release.countDown() })
                    started.countDown(); check(release.await(5, TimeUnit.SECONDS)); "discarded"
                }
            }
            withContext(Dispatchers.Default) { check(started.await(5, TimeUnit.SECONDS)) }
            job.cancel()
            withTimeout(2000) { job.join() }
            check(job.isCancelled && closes.get() == 1)
        }
    }

    @Test fun cancelledRetainedHandoffClosesTheSameObjectOnceOffMain() = runBlocking {
        val owner = Thread.currentThread()
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        val closes = AtomicInteger()
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { worker ->
            val transport = Closeable { check(Thread.currentThread() !== owner); closes.incrementAndGet(); release.countDown() }
            val job = async {
                retainedRouterIo(worker) { register -> register(transport); started.countDown(); check(release.await(5, TimeUnit.SECONDS)); transport }
            }
            withContext(Dispatchers.Default) { check(started.await(5, TimeUnit.SECONDS)) }
            job.cancel()
            withTimeout(2000) { job.join() }
            check(job.isCancelled && closes.get() == 1)
        }
    }

    @Test fun registrationAfterCancellationStillReleasesTheOwnedTransport() = runBlocking {
        val owner = Thread.currentThread()
        val started = CountDownLatch(1); val registerNow = CountDownLatch(1)
        val closed = CompletableDeferred<Unit>(); val closes = AtomicInteger()
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { worker ->
            val transport = Closeable { check(Thread.currentThread() !== owner); closes.incrementAndGet(); closed.complete(Unit) }
            val job = async {
                retainedRouterIo(worker) { register ->
                    started.countDown(); check(registerNow.await(5, TimeUnit.SECONDS)); register(transport); transport
                }
            }
            withContext(Dispatchers.Default) { check(started.await(5, TimeUnit.SECONDS)) }
            job.cancel(); registerNow.countDown()
            withTimeout(2000) { job.join(); closed.await() }
            check(closes.get() == 1)
        }
    }
}
