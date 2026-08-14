package com.foxhole.core.runtime

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
    fun `stop supervision covers graceful stop and its force kill`() {
        val policy = RuntimeStopPolicy(totalGracefulTimeoutMs = 3_000L)

        assertEquals(5_000L, runtimeStopSupervisionTimeoutMs(policy))
        assertTrue(runtimeStopSupervisionTimeoutMs(policy) > policy.totalGracefulTimeoutMs)
        assertTrue(
            runtimeStopSupervisionTimeoutMs(policy) >=
                policy.totalGracefulTimeoutMs + RUNTIME_FORCE_KILL_TIMEOUT_MS,
        )
    }

    @Test
    fun `stop supervision addition cannot overflow into an immediate timeout`() {
        assertEquals(
            Long.MAX_VALUE,
            runtimeStopSupervisionTimeoutMs(
                RuntimeStopPolicy(totalGracefulTimeoutMs = Long.MAX_VALUE),
            ),
        )
    }

    @Test
    fun `a native stop returning just after its own deadline is not force killed by its supervisor`() =
        runBlocking {
            val forceKilled = AtomicBoolean(false)
            val runtime =
                object : FoxholeRuntime {
                    override suspend fun start(
                        session: com.foxhole.core.model.VpnSession,
                        host: RuntimeServiceHost,
                    ): Result<Unit> = Result.success(Unit)

                    override suspend fun reload(
                        session: com.foxhole.core.model.VpnSession,
                        host: RuntimeServiceHost,
                    ): Result<Unit> = Result.success(Unit)

                    override suspend fun quiesceForInterfaceHandover(): Boolean = true

                    override suspend fun stop(policy: RuntimeStopPolicy): RuntimeStopResult {
                        delay(policy.totalGracefulTimeoutMs + 25L)
                        return RuntimeStopResult(
                            closeServiceOk = true,
                            closeServerOk = true,
                            tunClosed = true,
                            escalatedToKill = false,
                            elapsedMs = policy.totalGracefulTimeoutMs + 25L,
                        )
                    }

                    override suspend fun forceKill(reason: String): RuntimeKillResult {
                        forceKilled.set(true)
                        return super.forceKill(reason)
                    }
                }
            val result =
                runtime.stopFailClosed(
                    owner = "test",
                    reason = "deadline_edge",
                    diagnosticsLogger = NoOpRuntimeDiagnosticsSink,
                    policy = RuntimeStopPolicy(totalGracefulTimeoutMs = 30L),
                )

            assertTrue(result.graceful)
            assertFalse(forceKilled.get())
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

    @Test
    fun `hung native call flood is capped by live thread ownership`() =
        runBlocking {
            val registry = RuntimeNativeCallRegistry(maxOwnedThreads = 2)
            val release = CountDownLatch(1)
            val entered = CountDownLatch(2)
            val overflowRan = AtomicBoolean(false)
            try {
                repeat(2) { index ->
                    assertNull(
                        runAbandonableNativeRuntimeCall(
                            operation = "owned-$index",
                            timeoutMs = 20L,
                            registry = registry,
                        ) {
                            entered.countDown()
                            release.await()
                            "late"
                        },
                    )
                }
                assertTrue(entered.await(1, TimeUnit.SECONDS))
                assertEquals(2, registry.outstandingCount())

                assertNull(
                    runAbandonableNativeRuntimeCall(
                        operation = "overflow",
                        timeoutMs = 500L,
                        registry = registry,
                    ) {
                        overflowRan.set(true)
                        "must-not-run"
                    },
                )
                assertFalse(overflowRan.get())
                assertEquals(1L, registry.rejectedCount())
                assertEquals(2, registry.outstandingCount())
            } finally {
                release.countDown()
                withTimeout(1_000L) {
                    while (registry.outstandingCount() != 0) delay(5L)
                }
            }
        }
}
