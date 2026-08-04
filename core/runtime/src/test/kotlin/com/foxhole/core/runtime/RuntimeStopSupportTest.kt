package com.foxhole.core.runtime

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RuntimeStopSupportTest {
    @Test
    fun `runtime stop result is graceful only without escalation`() {
        assertTrue(
            RuntimeStopResult(
                closeServiceOk = true,
                closeServerOk = true,
                tunClosed = true,
                escalatedToKill = false,
                elapsedMs = 42L,
            ).graceful,
        )
        assertFalse(
            RuntimeStopResult(
                closeServiceOk = false,
                closeServerOk = true,
                tunClosed = true,
                escalatedToKill = true,
                elapsedMs = 1_500L,
            ).graceful,
        )
        assertFalse(
            RuntimeStopResult(
                closeServiceOk = true,
                closeServerOk = true,
                tunClosed = false,
                escalatedToKill = false,
                elapsedMs = 42L,
            ).graceful,
        )
    }

    @Test
    fun `blocking close timeout does not wait for native return`() =
        runBlocking {
            val startedAt = System.nanoTime()
            val completed =
                runBlockingRuntimeClose(timeoutMs = 10L) {
                    Thread.sleep(250L)
                }
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

            assertFalse(completed)
            assertTrue("close timeout took ${elapsedMs}ms", elapsedMs < 200L)
        }

    @Test
    fun `blocking close reports success and failure`() =
        runBlocking {
            assertTrue(
                runBlockingRuntimeClose(timeoutMs = 250L) {
                    Thread.sleep(10L)
                },
            )
            assertFalse(
                runBlockingRuntimeClose(timeoutMs = 250L) {
                    error("native close failed")
                },
            )
        }

    @Test
    fun `wedged close cannot starve a later close`() =
        runBlocking {
            val releaseFirst = CountDownLatch(1)
            try {
                val first =
                    runBlockingRuntimeClose(timeoutMs = 20L) {
                        releaseFirst.await()
                    }
                assertFalse(first)
                assertTrue(runBlockingRuntimeClose(timeoutMs = 500L) { Thread.sleep(5L) })
            } finally {
                releaseFirst.countDown()
            }
        }

    @Test
    fun `abandoned native operation cannot starve its successor`() =
        runBlocking {
            val releaseFirst = CountDownLatch(1)
            try {
                val first =
                    runAbandonableNativeRuntimeCall(
                        operation = "test-wedge",
                        timeoutMs = 20L,
                    ) {
                        releaseFirst.await()
                        "late"
                    }
                assertNull(first)

                val second =
                    runAbandonableNativeRuntimeCall(
                        operation = "test-next",
                        timeoutMs = 500L,
                    ) {
                        "ok"
                    }
                assertTrue(second == "ok")
            } finally {
                releaseFirst.countDown()
            }
        }
}
