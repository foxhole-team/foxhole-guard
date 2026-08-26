package com.foxhole.guard.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeSettingUpdateBarrierTest {
    @Test
    fun `authoritative TOR removal bypasses an earlier reconnect requirement`() {
        assertTrue(pendingReconnectBlocksRuntimeReload(reconnectRequired = true, forceRuntimeApply = false))
        assertFalse(pendingReconnectBlocksRuntimeReload(reconnectRequired = true, forceRuntimeApply = true))
        assertTrue(
            shouldStopTorRuntimeBeforeReload(
                targetProfileId = null,
                forceStopStandaloneTor = true,
                torDesired = false,
            ),
        )
    }

    @Test
    fun `cancellation cannot split an authoritative setting write from its runtime dispatch`() =
        runBlocking {
            val applied = CompletableDeferred<Unit>()
            val job =
                launch {
                    runAuthoritativeRuntimeSettingUpdate(
                        updateAction = {
                            currentCoroutineContext().cancel()
                        },
                        applyAction = {
                            applied.complete(Unit)
                            true
                        },
                    )
                }

            withTimeout(TIMEOUT_MS) { applied.await() }
            job.join()
        }

    @Test
    fun `failed UDP selection happens only after authoritative TOR removal dispatch`() =
        runBlocking {
            val events = mutableListOf<String>()

            val selected =
                runCatching {
                    runAuthoritativeRuntimeSettingUpdateThen(
                        updateAction = { events += "persist_off" },
                        applyAction = {
                            events += "stop_tor"
                            true
                        },
                        followUpAction = {
                            events += "select_udp"
                            error("protocol selection failed")
                        },
                    )
                }

            assertTrue(selected.isFailure)
            assertEquals(listOf("persist_off", "stop_tor", "select_udp"), events)
        }

    @Test
    fun `cancelled UDP selection cannot cancel the earlier TOR removal dispatch`() =
        runBlocking {
            val applied = CompletableDeferred<Unit>()
            val job =
                launch {
                    runAuthoritativeRuntimeSettingUpdateThen(
                        updateAction = {},
                        applyAction = {
                            applied.complete(Unit)
                            true
                        },
                        followUpAction = {
                            currentCoroutineContext().cancel()
                            yield()
                        },
                    )
                }

            withTimeout(TIMEOUT_MS) { applied.await() }
            job.join()
            assertTrue(applied.isCompleted)
        }

    @Test
    fun `cancelled VPN plus TOR handoff persists TOR-only intent and stops VPN first`() =
        runBlocking {
            val events = mutableListOf<String>()
            val job =
                launch {
                    runAuthoritativeRuntimeSettingUpdateThen(
                        updateAction = { events += "persist_tor_only" },
                        applyAction = {
                            events += "stop_vpn"
                            true
                        },
                        followUpAction = {
                            currentCoroutineContext().cancel()
                            yield()
                        },
                    )
                }

            job.join()
            assertEquals(listOf("persist_tor_only", "stop_vpn"), events)
        }

    @Test
    fun `cancelled standalone TOR start rolls back intent and dispatches stop`() =
        runBlocking {
            val rolledBack = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            val job =
                launch {
                    runFailClosedRuntimeStart(
                        updateAction = {},
                        startAction = {
                            currentCoroutineContext().cancel()
                            yield()
                        },
                        rollbackAction = {
                            rolledBack.complete(Unit)
                            stopped.complete(Unit)
                        },
                    )
                }

            withTimeout(TIMEOUT_MS) {
                rolledBack.await()
                stopped.await()
            }
            job.join()
        }

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
