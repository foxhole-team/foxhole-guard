package com.foxhole.core.runtime

import android.content.Context
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.VpnSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class RuntimeStopSupportTest {
    @Test
    fun `reload timeout propagates every poisoned force stop outcome`() =
        runBlocking {
            listOf(
                NativeForceStopOutcome.QUARANTINED,
                NativeForceStopOutcome.FAILED,
                NativeForceStopOutcome.CALL_TIMED_OUT,
            ).forEach { outcome ->
                val runtime = BlockingReloadFenceRuntime(outcome)
                try {
                    val result =
                        runtime.reloadFailClosed(
                            session = STOP_SUPPORT_SESSION,
                            host = StopSupportHost,
                            owner = "test",
                            diagnosticsLogger = NoOpRuntimeDiagnosticsSink,
                            timeoutMs = 20L,
                        )

                    assertEquals(outcome, result.nativeForceStopOutcomeOrNull())
                    assertTrue(result.isFailure)
                    assertEquals(1, runtime.abortCalls.get())
                } finally {
                    runtime.releaseReload.countDown()
                }
            }
        }

    @Test
    fun `reload cancellation abort preserves every poisoned outcome and original cause`() =
        runBlocking {
            listOf(
                NativeForceStopOutcome.QUARANTINED,
                NativeForceStopOutcome.FAILED,
                NativeForceStopOutcome.CALL_TIMED_OUT,
            ).forEach { outcome ->
                val original = CancellationException("reload cancelled by test")
                val runtime = BlockingReloadFenceRuntime(outcome, cancellation = original)

                val failure =
                    runCatching {
                        runtime.reloadFailClosed(
                            session = STOP_SUPPORT_SESSION,
                            host = StopSupportHost,
                            owner = "test",
                            diagnosticsLogger = NoOpRuntimeDiagnosticsSink,
                            timeoutMs = 1_000L,
                        )
                    }.exceptionOrNull()

                assertTrue(failure is NativeForceStopPoisonedCancellationException)
                val poisoned = failure as NativeForceStopPoisonedCancellationException
                assertEquals(outcome, poisoned.forceStopOutcome)
                assertEquals(outcome, poisoned.nativeForceStopOutcomeOrNull())
                assertSame(original, poisoned.cause)
                assertEquals(1, runtime.abortCalls.get())
            }
        }

    @Test
    fun `runtime stop result is graceful only without escalation`() {
        val graceful =
            RuntimeStopResult(
                closeServiceOk = true,
                closeServerOk = true,
                tunClosed = true,
                escalatedToKill = false,
                elapsedMs = 42L,
            )
        assertTrue(graceful.graceful)
        assertEquals(NativeForceStopOutcome.NOT_ATTEMPTED, graceful.forceStopOutcome)
        assertFalse(graceful.processPoisoned)
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
        assertFalse(
            RuntimeStopResult(
                closeServiceOk = true,
                closeServerOk = true,
                tunClosed = true,
                escalatedToKill = false,
                elapsedMs = 42L,
                forceStopOutcome = NativeForceStopOutcome.QUARANTINED,
            ).graceful,
        )
    }

    @Test
    fun `runtime test fakes default to a non poisoned force stop outcome`() {
        val result =
            RuntimeKillResult(
                reason = "test",
                tunClosed = true,
                serverDetached = true,
            )

        assertEquals(NativeForceStopOutcome.NOT_ATTEMPTED, result.forceStopOutcome)
        assertFalse(result.processPoisoned)
    }

    @Test
    fun `production stop timeout covers native settlement and force kill`() {
        val policy = RuntimeStopPolicy(totalGracefulTimeoutMs = RUNTIME_STOP_TIMEOUT_MS)

        assertEquals(3_750L, nativeStopCallTimeoutMs(policy))
        assertEquals(5_750L, runtimeStopSupervisionTimeoutMs(policy))
        assertTrue(runtimeStopSupervisionTimeoutMs(policy) > nativeStopCallTimeoutMs(policy))
        assertTrue(
            runtimeStopSupervisionTimeoutMs(policy) >=
                nativeStopCallTimeoutMs(policy) + RUNTIME_FORCE_KILL_TIMEOUT_MS,
        )
    }

    @Test
    fun `policy defaults preserve the same timeout hierarchy`() {
        val policy = RuntimeStopPolicy()

        assertEquals(2_250L, nativeStopCallTimeoutMs(policy))
        assertEquals(4_250L, runtimeStopSupervisionTimeoutMs(policy))
    }

    @Test
    fun `stop supervision addition cannot overflow into an immediate timeout`() {
        listOf(Long.MAX_VALUE - 749L, Long.MAX_VALUE).forEach { gracefulTimeoutMs ->
            val policy = RuntimeStopPolicy(totalGracefulTimeoutMs = gracefulTimeoutMs)

            assertEquals(Long.MAX_VALUE, nativeStopCallTimeoutMs(policy))
            assertEquals(Long.MAX_VALUE, runtimeStopSupervisionTimeoutMs(policy))
        }
    }

    @Test
    fun `stop timeout hierarchy preserves every inner deadline`() {
        listOf(0L, 1L, 30L, 3_000L, Long.MAX_VALUE - 2_000L).forEach { gracefulTimeoutMs ->
            val policy = RuntimeStopPolicy(totalGracefulTimeoutMs = gracefulTimeoutMs)
            val nativeStopTimeoutMs = nativeStopCallTimeoutMs(policy)
            val supervisionTimeoutMs = runtimeStopSupervisionTimeoutMs(policy)

            assertTrue(nativeStopTimeoutMs >= gracefulTimeoutMs.coerceAtLeast(1L))
            assertTrue(supervisionTimeoutMs >= nativeStopTimeoutMs)
            if (supervisionTimeoutMs != Long.MAX_VALUE) {
                assertTrue(supervisionTimeoutMs >= nativeStopTimeoutMs + RUNTIME_FORCE_KILL_TIMEOUT_MS)
            }
        }
    }

    @Test
    fun `native stopped result just after graceful deadline is not force killed`() =
        runBlocking {
            val policy = RuntimeStopPolicy(totalGracefulTimeoutMs = 30L)
            val native = DelayedStoppedNativeApi(policy.totalGracefulTimeoutMs + 25L)
            val operations =
                FoxCoreNativeEngineOperations(
                    native = native,
                    diagnostics = NoOpRuntimeDiagnosticsSink,
                    translator = FoxCoreConfigTranslator(),
                )

            val result = operations.stop(handle = 7L, policy = policy)

            assertTrue(result.stopped)
            assertFalse(result.escalated)
            assertEquals(1, native.stopCalls.get())
            assertEquals(0, native.forceKillCalls.get())
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

@Suppress("TooManyFunctions")
private class DelayedStoppedNativeApi(
    private val stopDelayMs: Long,
) : FoxCoreNativeApi {
    val stopCalls = AtomicInteger(0)
    val forceKillCalls = AtomicInteger(0)

    override fun version(): String = "test"

    override fun abiVersion(): Int = FoxholeNativeEngine.ABI_VERSION

    override fun capabilities(): String = "{}"

    override fun startWithNetwork(
        tunFd: Int,
        configJson: String,
        networkHandle: Long,
        host: RuntimeServiceHost,
    ): Long = 0L

    override fun startWithNetworkAndTrustedDnsRuleSet(
        tunFd: Int,
        configJson: String,
        networkHandle: Long,
        name: String,
        artifact: ByteArray,
        host: RuntimeServiceHost,
    ): Long = 0L

    override fun installDnsRuleSet(
        handle: Long,
        name: String,
        manifest: ByteArray,
        signature: ByteArray,
        artifact: ByteArray,
    ): Long = 0L

    override fun stop(handle: Long): Int {
        stopCalls.incrementAndGet()
        Thread.sleep(stopDelayMs)
        return FoxholeNativeEngine.STOPPED
    }

    override fun forceKill(handle: Long): Int {
        forceKillCalls.incrementAndGet()
        return FoxholeNativeEngine.STOPPED
    }

    override fun reloadPolicy(
        handle: Long,
        policyJson: String,
    ): Long = 0L

    override fun lastPolicyError(handle: Long): String = ""

    override fun stats(handle: Long): String = "{}"

    override fun connections(handle: Long): String = "{}"

    override fun drainTrafficEvents(
        handle: Long,
        max: Int,
    ): String = "{}"

    override fun drainEvents(
        handle: Long,
        max: Int,
    ): String = "{}"

    override fun networkChanged(handle: Long) = Unit

    override fun networkChangedWithHandle(
        handle: Long,
        networkHandle: Long,
    ) = Unit
}

private class BlockingReloadFenceRuntime(
    private val outcome: NativeForceStopOutcome,
    private val cancellation: CancellationException? = null,
) : FoxholeRuntime,
    RuntimeNativeStartFenceOwner {
    val releaseReload = CountDownLatch(1)
    val abortCalls = AtomicInteger(0)

    override fun openNativeStartPermit(reason: String): Long = 1L

    override fun invalidateNativeStartPermit(generation: Long, reason: String): Boolean = true

    override suspend fun startWithNativePermit(
        session: VpnSession,
        host: RuntimeServiceHost,
        generation: Long,
    ): Result<Unit> = Result.success(Unit)

    override suspend fun reloadWithNativePermit(
        session: VpnSession,
        host: RuntimeServiceHost,
        generation: Long,
    ): Result<Unit> {
        cancellation?.let { throw it }
        releaseReload.await()
        return Result.success(Unit)
    }

    override suspend fun abortNativeTransition(generation: Long, reason: String): RuntimeKillResult {
        abortCalls.incrementAndGet()
        return RuntimeKillResult(
            reason = reason,
            tunClosed = outcome.handleReleased,
            serverDetached = outcome.handleReleased,
            forceStopOutcome = outcome,
        )
    }

    override suspend fun start(session: VpnSession, host: RuntimeServiceHost): Result<Unit> =
        Result.success(Unit)

    override suspend fun reload(session: VpnSession, host: RuntimeServiceHost): Result<Unit> =
        Result.success(Unit)

    override suspend fun quiesceForInterfaceHandover(): Boolean = true

    override suspend fun stop(policy: RuntimeStopPolicy): RuntimeStopResult =
        RuntimeStopResult(true, true, true, false, 0L)
}

private object StopSupportHost : RuntimeServiceHost {
    override val runtimeContext: Context
        get() = throw UnsupportedOperationException("not used by the fake runtime")

    override fun stopRuntimeService() = Unit

    override fun protectSocket(socket: Int): Boolean = true
}

private val STOP_SUPPORT_SESSION =
    VpnSession(
        profileId = 1L,
        profileName = "reload-timeout",
        protocolHint = ProtocolHint.LOCAL_GUARD,
        configJson = "{}",
        correlationId = "reload-timeout-test",
    )
