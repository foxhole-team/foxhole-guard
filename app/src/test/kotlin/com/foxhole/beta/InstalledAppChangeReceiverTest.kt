package com.foxhole.beta

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class InstalledAppChangeReceiverTest {
    @Test
    fun `pending broadcast finishes after successful work`() =
        runBlocking {
            var finished = 0
            finishPendingBroadcast(
                timeoutMs = 1_000L,
                finish = { finished += 1 },
            ) {
                // Success path.
            }

            assertEquals(1, finished)
        }

    @Test
    fun `pending broadcast finishes after failed work`() =
        runBlocking {
            var finished = 0
            runCatching {
                finishPendingBroadcast(
                    timeoutMs = 1_000L,
                    finish = { finished += 1 },
                ) {
                    error("boom")
                }
            }

            assertEquals(1, finished)
        }

    @Test
    fun `pending broadcast finishes after timeout`() =
        runBlocking {
            var finished = 0
            var timedOut = 0
            finishPendingBroadcast(
                timeoutMs = 1L,
                finish = { finished += 1 },
                onTimeout = { timedOut += 1 },
            ) {
                delay(50L)
            }

            assertEquals(1, timedOut)
            assertEquals(1, finished)
        }
}
