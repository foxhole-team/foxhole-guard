package com.foxhole.guard.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeSettingUpdateBarrierTest {
    @Test
    fun `a registered update blocks an immediate runtime sync`() =
        runBlocking {
            val barrier = RuntimeSettingUpdateBarrier()
            val update = barrier.reserve()
            val waiting = CompletableDeferred<Unit>()
            val released = CompletableDeferred<Unit>()
            val waiter =
                launch {
                    waiting.complete(Unit)
                    barrier.awaitPending()
                    released.complete(Unit)
                }

            waiting.await()
            yield()
            assertFalse(released.isCompleted)

            update.complete()
            withTimeout(TIMEOUT_MS) { released.await() }
            waiter.join()
        }

    @Test
    fun `updates run in reservation order`() =
        runBlocking {
            val barrier = RuntimeSettingUpdateBarrier()
            val first = barrier.reserve()
            val second = barrier.reserve()
            val secondTurn = CompletableDeferred<Unit>()
            val waiter =
                launch {
                    second.awaitTurn()
                    secondTurn.complete(Unit)
                }

            yield()
            assertFalse(secondTurn.isCompleted)
            first.complete()
            withTimeout(TIMEOUT_MS) { secondTurn.await() }
            second.complete()
            barrier.awaitPending()
            assertTrue(secondTurn.isCompleted)
            waiter.join()
        }

    private companion object {
        const val TIMEOUT_MS = 1_000L
    }
}
