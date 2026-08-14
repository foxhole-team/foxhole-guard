package com.foxhole.core.runtime.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis

/**
 * Regression guard: системный резолв на пути connect вставал навсегда (`Linux.android_getaddrinfo`
 * без бюджета), и команда connect висела в супервизоре, а UI — в «connecting». Ожидание обязано
 * быть конечным, а таймаут — приезжать как [UnknownHostException], чтобы [PublicRemoteDns]
 * переключился на DoH-фолбэк.
 */
class BoundedSystemHostResolverTest {
    @Test
    fun `resolved addresses pass through unchanged`() {
        val expected = listOf(InetAddress.getByName("93.184.216.34"))
        val resolver = BoundedSystemHostResolver(delegate = { expected })

        assertEquals(expected, resolver("example.com"))
    }

    @Test
    fun `a wedged lookup gives up on budget instead of blocking forever`() {
        val release = CountDownLatch(1)
        val resolver =
            BoundedSystemHostResolver(
                delegate = {
                    release.await(30, TimeUnit.SECONDS)
                    emptyList()
                },
                timeoutMs = 150L,
            )

        val elapsed =
            measureTimeMillis {
                val failure = assertThrows(UnknownHostException::class.java) { resolver("example.com") }
                assertTrue(failure.message.orEmpty().contains("timed out"))
            }
        release.countDown()

        assertTrue("ожидание должно уложиться в бюджет, а не в задержку делегата: $elapsed ms", elapsed < 5_000L)
    }

    @Test
    fun `delegate host failure is rethrown as is, not masked by the timeout`() {
        val resolver =
            BoundedSystemHostResolver(
                delegate = { throw UnknownHostException("no such host") },
                timeoutMs = 5_000L,
            )

        val failure = assertThrows(UnknownHostException::class.java) { resolver("example.com") }
        assertEquals("no such host", failure.message)
    }

    @Test
    fun `delegate runtime failure keeps its own type for the caller's fallback branch`() {
        val resolver =
            BoundedSystemHostResolver(
                delegate = { throw IllegalStateException("resolver unavailable") },
                timeoutMs = 5_000L,
            )

        val failure = assertThrows(IllegalStateException::class.java) { resolver("example.com") }
        assertEquals("resolver unavailable", failure.message)
    }

    @Test
    fun `hung lookup flood never creates more than the owned worker cap`() {
        val release = CountDownLatch(1)
        val entered = CountDownLatch(2)
        val executor = newBoundedSystemResolverExecutor(maxThreads = 2)
        val resolver =
            BoundedSystemHostResolver(
                delegate = {
                    entered.countDown()
                    release.await(30, TimeUnit.SECONDS)
                    emptyList()
                },
                timeoutMs = 5_000L,
                executor = executor,
            )
        val callers = Executors.newFixedThreadPool(2)
        try {
            repeat(2) { index ->
                callers.submit { runCatching { resolver("wedged-$index.example") } }
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS))

            val elapsed = measureTimeMillis {
                val failure = assertThrows(UnknownHostException::class.java) { resolver("overflow.example") }
                assertTrue(failure.message.orEmpty().contains("saturated"))
            }

            assertTrue("saturated resolver must fail fast: $elapsed ms", elapsed < 1_000L)
            assertEquals(2, executor.largestPoolSize)
            assertEquals(0, executor.queue.size)
        } finally {
            release.countDown()
            callers.shutdownNow()
            executor.shutdownNow()
        }
    }
}
