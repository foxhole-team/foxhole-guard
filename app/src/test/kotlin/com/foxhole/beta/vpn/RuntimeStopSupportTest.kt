package com.foxhole.beta.vpn

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeStopSupportTest {
    @Test
    fun `runtime stop result is graceful only when native closes without escalation`() {
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
    }

    @Test
    fun `blocking runtime close times out without waiting for native call to return`() =
        runBlocking {
            val completed =
                runBlockingRuntimeClose(timeoutMs = 10L) {
                    Thread.sleep(250L)
                }

            assertFalse(completed)
        }

    @Test
    fun `blocking runtime close reports success when native call returns before timeout`() =
        runBlocking {
            val completed =
                runBlockingRuntimeClose(timeoutMs = 250L) {
                    Thread.sleep(10L)
                }

            assertTrue(completed)
        }
}
