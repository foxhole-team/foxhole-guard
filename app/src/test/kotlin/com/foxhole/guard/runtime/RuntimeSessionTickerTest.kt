package com.foxhole.guard.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class RuntimeSessionTickerTest {
    private val interval = 40L
    private val awaitMs = 4_000L

    private fun ticker(
        scope: CoroutineScope,
        onError: (String, Throwable) -> Unit = { _, _ -> },
    ) = RuntimeSessionTicker(scope, nowMs = { System.nanoTime() / 1_000_000 }, onError = onError)

    @Test
    fun `task fires repeatedly on its own cadence`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val ticker = ticker(scope)
            val fires = AtomicInteger(0)
            val reachedThree = CompletableDeferred<Unit>()

            ticker.register("traffic", fireImmediately = false, intervalMs = { interval }) {
                if (fires.incrementAndGet() >= 3) reachedThree.complete(Unit)
            }

            withTimeout(awaitMs) { reachedThree.await() }
            assertTrue(fires.get() >= 3)
            ticker.stop()
        }

    @Test
    fun `fireImmediately runs the task without waiting an interval`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val ticker = ticker(scope)
            val fired = CompletableDeferred<Unit>()

            ticker.register("app_traffic", fireImmediately = true, intervalMs = { 60_000L }) {
                fired.complete(Unit)
            }

            withTimeout(awaitMs) { fired.await() }
            ticker.stop()
        }

    @Test
    fun `a slow-cadence task never delays a fast task folded onto the same ticker`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val ticker = ticker(scope)
            val slowFired = AtomicInteger(0)
            val fastReachedThree = CompletableDeferred<Unit>()
            val fastFires = AtomicInteger(0)

            ticker.register("slow", fireImmediately = false, intervalMs = { 60_000L }) { slowFired.incrementAndGet() }
            ticker.register("fast", fireImmediately = false, intervalMs = { interval }) {
                if (fastFires.incrementAndGet() >= 3) fastReachedThree.complete(Unit)
            }

            withTimeout(awaitMs) { fastReachedThree.await() }
            assertTrue("fast task kept firing", fastFires.get() >= 3)
            assertEquals("slow task's long interval was not reached", 0, slowFired.get())
            ticker.stop()
        }

    @Test
    fun `a throwing task is isolated and never stops the others`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val errors = Collections.synchronizedList(mutableListOf<String>())
            val ticker = ticker(scope) { id, _ -> errors += id }
            val healthyReachedThree = CompletableDeferred<Unit>()
            val healthy = AtomicInteger(0)

            ticker.register("boom", fireImmediately = false, intervalMs = { interval }) { error("boom") }
            ticker.register("healthy", fireImmediately = false, intervalMs = { interval }) {
                if (healthy.incrementAndGet() >= 3) healthyReachedThree.complete(Unit)
            }

            withTimeout(awaitMs) { healthyReachedThree.await() }
            assertTrue(healthy.get() >= 3)
            assertTrue("throwing task surfaced per-task errors", errors.count { it == "boom" } >= 1)
            ticker.stop()
        }

    @Test
    fun `unregister stops only that task`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val ticker = ticker(scope)
            val aFires = AtomicInteger(0)
            val bFires = AtomicInteger(0)
            val bFiredOnce = CompletableDeferred<Unit>()

            ticker.register("a", fireImmediately = false, intervalMs = { interval }) { aFires.incrementAndGet() }
            ticker.register("b", fireImmediately = false, intervalMs = { interval }) {
                bFires.incrementAndGet()
                bFiredOnce.complete(Unit)
            }

            withTimeout(awaitMs) { bFiredOnce.await() }
            ticker.unregister("b")
            val bAtUnregister = bFires.get()
            val aAtUnregister = aFires.get()

            val aAdvanced = CompletableDeferred<Unit>()
            ticker.register("a_probe", fireImmediately = false, intervalMs = { interval * 4 }) {
                aAdvanced.complete(Unit)
            }
            withTimeout(awaitMs) { aAdvanced.await() }

            assertTrue("a kept firing", aFires.get() > aAtUnregister)
            assertEquals("b stopped after unregister", bAtUnregister, bFires.get())
            ticker.stop()
        }

    @Test
    fun `registering a faster task wakes the loop before the slow deadline`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val ticker = ticker(scope)
            val slowFired = AtomicInteger(0)

            ticker.register("slow", fireImmediately = false, intervalMs = { 60_000L }) { slowFired.incrementAndGet() }
            val fastFired = CompletableDeferred<Unit>()
            ticker.register("fast", fireImmediately = false, intervalMs = { interval }) { fastFired.complete(Unit) }

            withTimeout(awaitMs) { fastFired.await() }
            assertFalse("slow task did not fire early", slowFired.get() > 0)
            ticker.stop()
        }

    @Test
    fun `interval is re-read after the action - adaptive backoff sees the state the action produced`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val ticker = ticker(scope)
            val currentInterval = AtomicLong(60_000L)
            val fires = AtomicInteger(0)
            val secondFire = CompletableDeferred<Unit>()

            ticker.register("adaptive", fireImmediately = true, intervalMs = { currentInterval.get() }) {
                currentInterval.set(interval)
                if (fires.incrementAndGet() >= 2) secondFire.complete(Unit)
            }

            withTimeout(awaitMs) { secondFire.await() }
            ticker.stop()
        }

    @Test
    fun `a slow dispatched action never blocks inline tasks`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val ticker = ticker(scope)
            val probeStarted = CompletableDeferred<Unit>()
            val fastReachedThree = CompletableDeferred<Unit>()
            val fastFires = AtomicInteger(0)

            ticker.register(
                id = "probe",
                fireImmediately = true,
                intervalMs = { interval },
                runOn = Dispatchers.IO,
            ) {
                probeStarted.complete(Unit)
                delay(60_000L)
            }
            withTimeout(awaitMs) { probeStarted.await() }
            ticker.register("fast", fireImmediately = false, intervalMs = { interval }) {
                if (fastFires.incrementAndGet() >= 3) fastReachedThree.complete(Unit)
            }

            withTimeout(awaitMs) { fastReachedThree.await() }
            ticker.stop()
        }

    @Test
    fun `unregister cancels an in-flight dispatched action`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val ticker = ticker(scope)
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val completedAfterSleep = AtomicInteger(0)

            ticker.register(
                id = "probe",
                fireImmediately = true,
                intervalMs = { interval },
                runOn = Dispatchers.IO,
            ) {
                started.complete(Unit)
                try {
                    delay(60_000L)
                    completedAfterSleep.incrementAndGet()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    cancelled.complete(Unit)
                    throw e
                }
            }

            withTimeout(awaitMs) { started.await() }
            ticker.unregister("probe")
            withTimeout(awaitMs) { cancelled.await() }
            assertEquals("cancelled action never ran to completion", 0, completedAfterSleep.get())
            assertFalse(ticker.isRegistered("probe"))
            ticker.stop()
        }

    @Test
    fun `a dispatched task never overlaps itself`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val ticker = ticker(scope)
            val inFlight = AtomicInteger(0)
            val maxInFlight = AtomicInteger(0)
            val fires = AtomicInteger(0)
            val reachedThree = CompletableDeferred<Unit>()

            ticker.register(
                id = "overlappy",
                fireImmediately = true,
                intervalMs = { interval },
                runOn = Dispatchers.IO,
            ) {
                val current = inFlight.incrementAndGet()
                maxInFlight.accumulateAndGet(current, ::maxOf)
                delay(interval * 3)
                inFlight.decrementAndGet()
                if (fires.incrementAndGet() >= 3) reachedThree.complete(Unit)
            }

            withTimeout(awaitMs * 2) { reachedThree.await() }
            assertEquals("dispatched task never ran concurrently with itself", 1, maxInFlight.get())
            ticker.stop()
        }
}
