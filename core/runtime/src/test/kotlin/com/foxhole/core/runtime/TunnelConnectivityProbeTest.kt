package com.foxhole.core.runtime

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelConnectivityProbeTest {
    @Test
    fun `returns first successful result`() =
        runBlocking {
            val result =
                TunnelConnectivityProbe.run(
                    attempts = 3,
                    retryDelayMs = 0,
                ) { "ok" }

            assertTrue(result.isSuccess)
            assertEquals("ok", result.getOrNull())
        }

    @Test
    fun `retries until success`() =
        runBlocking {
            var attempts = 0

            val result =
                TunnelConnectivityProbe.run(
                    attempts = 3,
                    retryDelayMs = 0,
                ) {
                    attempts += 1
                    check(attempts == 3) { "not yet" }
                    "ready"
                }

            assertTrue(result.isSuccess)
            assertEquals(3, attempts)
            assertEquals("ready", result.getOrNull())
        }

    @Test
    fun `returns last failure after exhausting retries`() =
        runBlocking {
            var failures = 0

            val result =
                TunnelConnectivityProbe.run(
                    attempts = 2,
                    retryDelayMs = 0,
                ) {
                    failures += 1
                    error("boom-$failures")
                }

            assertTrue(result.isFailure)
            assertEquals(2, failures)
            assertEquals("boom-2", result.exceptionOrNull()?.message)
        }

    @Test
    fun `fails when overall timeout is reached`() =
        runBlocking {
            val result =
                TunnelConnectivityProbe.run(
                    attempts = 5,
                    retryDelayMs = 0,
                    timeoutMs = 10,
                ) {
                    kotlinx.coroutines.delay(50)
                    "late"
                }

            assertTrue(result.isFailure)
            val error = result.exceptionOrNull()
            assertTrue(error is TunnelConnectivityProbeTimeoutException)
            assertEquals("probe timed out after 1 attempt and 10 ms", error?.message)
            assertEquals(1, (error as TunnelConnectivityProbeTimeoutException).attemptsDone)
            assertEquals(10L, error.timeoutMs)
        }

    @Test
    fun `timeout keeps last probe failure as cause`() =
        runBlocking {
            var attempts = 0

            val result =
                TunnelConnectivityProbe.run(
                    attempts = 3,
                    retryDelayMs = 1_000,
                    timeoutMs = 250,
                ) {
                    attempts += 1
                    error("boom-$attempts")
                }

            val error = result.exceptionOrNull()
            assertTrue(error is TunnelConnectivityProbeTimeoutException)
            assertEquals(1, (error as TunnelConnectivityProbeTimeoutException).attemptsDone)
            assertEquals("boom-1", error.cause?.message)
        }

    @Test
    fun `reports attempt duration and outcome for profiling`() =
        runBlocking {
            val attempts = mutableListOf<TunnelConnectivityProbeAttemptReport>()
            var invocationCount = 0

            val result =
                TunnelConnectivityProbe.run(
                    attempts = 2,
                    retryDelayMs = 0,
                    onAttemptCompleted = attempts::add,
                ) {
                    invocationCount += 1
                    check(invocationCount == 2) { "warming up" }
                    "ok"
                }

            assertTrue(result.isSuccess)
            assertEquals(2, attempts.size)
            assertEquals(1, attempts[0].attemptNumber)
            assertEquals(2, attempts[1].attemptNumber)
            assertFalse(attempts[0].success)
            assertTrue(attempts[1].success)
            assertTrue(attempts.all { report -> report.elapsedMs >= 0L })
            assertEquals("warming up", attempts[0].failure?.message)
        }
}
