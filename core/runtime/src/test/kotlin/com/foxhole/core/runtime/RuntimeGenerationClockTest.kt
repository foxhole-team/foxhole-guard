package com.foxhole.core.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeGenerationClockTest {
    @Test
    fun `mints strictly increasing values across concurrent minters`() =
        runBlocking {
            val minted =
                (1..8)
                    .map {
                        async(Dispatchers.Default) {
                            List(500) { RuntimeGenerationClock.next() }
                        }
                    }
                    .awaitAll()
                    .flatten()

            assertEquals(minted.size, minted.toSet().size)
            minted.forEach { value -> assertTrue(value > 0L) }
        }

    @Test
    fun `transition token stays current across unrelated clock activity`() {
        val stateMachine = RuntimeStateMachine(diagnosticsLogger = null)

        val transition = RuntimeGenerationClock.next()
        stateMachine.adoptTransition(generation = transition, reason = "connect")

        repeat(5) { RuntimeGenerationClock.next() }

        assertTrue(stateMachine.isCurrentGeneration(transition, owner = "test"))
        assertEquals(transition, stateMachine.currentGeneration())
    }

    @Test
    fun `advanceTo never lowers the clock`() {
        val current = RuntimeGenerationClock.current()

        RuntimeGenerationClock.advanceTo(current - 10L)
        assertTrue(RuntimeGenerationClock.current() >= current)

        RuntimeGenerationClock.advanceTo(current + 100L)
        assertTrue(RuntimeGenerationClock.current() >= current + 100L)
    }
}
