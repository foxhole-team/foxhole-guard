package com.foxhole.core.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

private class RecordingDiagnosticsSink : RuntimeDiagnosticsSink {
    override fun record(
        tag: String,
        message: String,
    ) = Unit

    override fun recordStructured(
        tag: String,
        headline: String,
        vararg details: String?,
    ) = Unit
}

class RuntimeGenerationGuardTest {
    @Test
    fun `mints native generations from the shared clock and tracks the latest`() {
        val guard = RuntimeGenerationGuard(RecordingDiagnosticsSink())

        val first = guard.next("start")
        val second = guard.next("reload")

        assertTrue(second > first)
        assertTrue(guard.isCurrent(second))
        assertFalse(guard.isCurrent(first))
    }

    @Test
    fun `commitIfCurrent runs the commit only for the latest generation`() {
        val guard = RuntimeGenerationGuard(RecordingDiagnosticsSink())
        val stale = guard.next("start")
        val committed = AtomicInteger(0)

        assertTrue(guard.commitIfCurrent(stale) { committed.incrementAndGet() })

        val fresh = guard.next("kill:teardown")
        assertFalse(guard.commitIfCurrent(stale) { committed.incrementAndGet() })
        assertTrue(guard.commitIfCurrent(fresh) { committed.incrementAndGet() })

        // The stale generation's commit ran once (before the bump) and never again after.
        assertEquals(2, committed.get())
    }

    @Test
    fun `kill advancing generation between check and publish invalidates a stale start commit`() =
        runBlocking {
            repeat(200) {
                val guard = RuntimeGenerationGuard(RecordingDiagnosticsSink())
                val startGeneration = guard.next("start")
                val committedByStart = AtomicInteger(0)

                val results =
                    listOf(
                        async(Dispatchers.Default) {
                            guard.commitIfCurrent(startGeneration) { committedByStart.incrementAndGet() }
                        },
                        async(Dispatchers.Default) {
                            guard.next("kill:race")
                        },
                    ).awaitAll()

                val startCommitted = results[0] as Boolean

                if (!guard.isCurrent(startGeneration)) {
                    assertTrue(committedByStart.get() <= 1)
                }
                assertEquals(if (startCommitted) 1 else 0, committedByStart.get())
            }
        }
}
