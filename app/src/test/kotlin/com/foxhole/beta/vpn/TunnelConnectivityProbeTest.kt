package com.foxhole.beta.vpn

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
            assertEquals("probe timed out", result.exceptionOrNull()?.message)
        }
}
