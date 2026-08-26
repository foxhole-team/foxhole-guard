package com.foxhole.core.runtime

import android.content.Context
import com.foxhole.core.model.FoxCoreSessionConfig
import com.foxhole.core.model.FoxCoreTunPlan
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.VpnSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class RuntimeNativeStartOwnershipTest {
    @Test
    fun `real runtime timeout can take its own transition mutex while native preflight is wedged`() =
        runBlocking {
            val native = BlockingPreflightNativeApi()
            val runtime = FoxCoreRuntime(diagnosticsLogger = NoOpOwnershipDiagnostics, native = native)
            try {
                val start =
                    async(Dispatchers.Default) {
                        runtime.startFailClosed(
                            session = PREPARED_TEST_SESSION,
                            host = TestRuntimeHost,
                            owner = "real-runtime",
                            diagnosticsLogger = NoOpOwnershipDiagnostics,
                            timeoutMessage = "start timed out",
                            timeoutMs = 50L,
                        )
                    }

                assertTrue(native.preflightEntered.await(1, TimeUnit.SECONDS))
                val result = withTimeout(1_000L) { start.await() }
                assertTrue(result.isFailure)
                assertEquals(RuntimeState.IDLE, runtime.nativeSnapshot().nativeState)

                val stopped = withTimeout(1_000L) { runtime.stop() }
                assertTrue(stopped.graceful)
            } finally {
                native.releasePreflight.countDown()
            }
        }

    @Test
    fun `real runtime cancellation leaves force kill and stop able to acquire transition mutex`() =
        runBlocking {
            val native = BlockingPreflightNativeApi()
            val runtime = FoxCoreRuntime(diagnosticsLogger = NoOpOwnershipDiagnostics, native = native)
            try {
                val start =
                    async(Dispatchers.Default) {
                        runtime.startFailClosed(
                            session = PREPARED_TEST_SESSION,
                            host = TestRuntimeHost,
                            owner = "real-runtime-cancel",
                            diagnosticsLogger = NoOpOwnershipDiagnostics,
                            timeoutMessage = "start timed out",
                            timeoutMs = 5_000L,
                        )
                    }
                assertTrue(native.preflightEntered.await(1, TimeUnit.SECONDS))
                start.cancelAndJoin()

                val killed = withTimeout(1_000L) { runtime.forceKill("test_cancel") }
                assertTrue(killed.tunClosed)
                assertTrue(withTimeout(1_000L) { runtime.stop() }.graceful)
            } finally {
                native.releasePreflight.countDown()
            }
        }

    @Test
    fun `cancelled start closes its late native handle and successor can start`() =
        runBlocking {
            val runtime = BlockingFakeNativeRuntime()
            val cancelled =
                async(Dispatchers.Default) {
                    runtime.startFailClosed(
                        session = TEST_SESSION,
                        host = TestRuntimeHost,
                        owner = "switch",
                        diagnosticsLogger = NoOpOwnershipDiagnostics,
                        timeoutMessage = "start timed out",
                        timeoutMs = 5_000L,
                    )
                }

            withTimeout(1_000L) { runtime.firstNativeStartEntered.await() }
            cancelled.cancelAndJoin()
            runtime.releaseFirstNativeStart.complete(Unit)
            withTimeout(1_000L) { runtime.firstLateHandleClosed.await() }

            assertTrue(runtime.publishedHandles.isEmpty())
            assertEquals(listOf(1L), runtime.closedHandles.toList())

            val successor =
                runtime.startFailClosed(
                    session = TEST_SESSION.copy(correlationId = "ownership-successor"),
                    host = TestRuntimeHost,
                    owner = "switch",
                    diagnosticsLogger = NoOpOwnershipDiagnostics,
                    timeoutMessage = "start timed out",
                    timeoutMs = 1_000L,
                )

            assertTrue(successor.isSuccess)
            assertEquals(listOf(2L), runtime.publishedHandles.toList())
            assertEquals(listOf(1L), runtime.closedHandles.toList())
        }

    @Test
    fun `cancelled predecessor cannot invalidate a newer start permit`() {
        val guard = RuntimeGenerationGuard(NoOpOwnershipDiagnostics)
        val predecessor = guard.next("predecessor")
        val successor = guard.next("successor")
        var successorPublished = false

        assertFalse(guard.invalidateIfCurrent(predecessor, "late_predecessor_cancel"))
        assertTrue(guard.commitIfCurrent(successor) { successorPublished = true })
        assertTrue(successorPublished)
    }

    @Test
    fun `late poisoned predecessor prevents successor publication and notifies once`() =
        runBlocking {
            val runtime = PoisonRaceFakeNativeRuntime()
            val host = PoisonRecordingHost()
            val predecessor =
                async(Dispatchers.Default) {
                    runtime.startFailClosed(
                        session = TEST_SESSION,
                        host = host,
                        owner = "predecessor",
                        diagnosticsLogger = NoOpOwnershipDiagnostics,
                        timeoutMessage = "start timed out",
                        timeoutMs = 5_000L,
                    )
                }

            withTimeout(1_000L) { runtime.predecessorNativeStartEntered.await() }
            predecessor.cancelAndJoin()
            val successor =
                async(Dispatchers.Default) {
                    runtime.startFailClosed(
                        session = TEST_SESSION.copy(correlationId = "poison-race-successor"),
                        host = host,
                        owner = "successor",
                        diagnosticsLogger = NoOpOwnershipDiagnostics,
                        timeoutMessage = "start timed out",
                        timeoutMs = 5_000L,
                    )
                }
            withTimeout(1_000L) { runtime.successorNativeStartEntered.await() }

            runtime.releasePredecessorNativeStart.complete(Unit)
            withTimeout(1_000L) { host.processPoisoned.await() }
            runtime.releaseSuccessorCommit.complete(Unit)
            val successorResult = withTimeout(1_000L) { successor.await() }

            assertEquals(
                NativeForceStopOutcome.QUARANTINED,
                successorResult.nativeForceStopOutcomeOrNull(),
            )
            assertTrue(runtime.publishedHandles.isEmpty())
            assertEquals(listOf(1L), runtime.forceKilledHandles.toList())
            assertEquals(1, host.poisonNotifications.get())
        }

    private companion object {
        val TEST_SESSION =
            VpnSession(
                profileId = 1L,
                profileName = "ownership-test",
                protocolHint = ProtocolHint.LOCAL_GUARD,
                configJson = "{}",
                correlationId = "ownership-first",
            )

        val PREPARED_TEST_SESSION =
            TEST_SESSION.copy(
                foxCoreConfig =
                FoxCoreSessionConfig(
                    engineConfigJson = """{"schema_version":1}""",
                    policyConfigJson = "{}",
                    tunPlan =
                    FoxCoreTunPlan(
                        mtu = 1_500,
                        ipv4Address = "172.19.0.1",
                        ipv4PrefixLength = 30,
                        ipv6Address = null,
                        ipv6PrefixLength = null,
                        routes = emptyList(),
                        advertisedDnsServers = listOf("172.19.0.2"),
                    ),
                ),
            )
    }
}

private class PoisonRaceFakeNativeRuntime :
    FoxholeRuntime,
    RuntimeNativeStartFenceOwner {
    private val guard = RuntimeGenerationGuard(NoOpOwnershipDiagnostics)
    private val poisonLatch = NativeForceStopPoisonLatch()
    private val starts = AtomicInteger(0)
    private val handles = AtomicLong(0L)
    private val transitionLock = Any()

    val predecessorNativeStartEntered = CompletableDeferred<Unit>()
    val releasePredecessorNativeStart = CompletableDeferred<Unit>()
    val successorNativeStartEntered = CompletableDeferred<Unit>()
    val releaseSuccessorCommit = CompletableDeferred<Unit>()
    val publishedHandles = Collections.synchronizedList(mutableListOf<Long>())
    val forceKilledHandles = Collections.synchronizedList(mutableListOf<Long>())

    override fun openNativeStartPermit(reason: String): Long = guard.next(reason)

    override fun invalidateNativeStartPermit(generation: Long, reason: String): Boolean =
        guard.invalidateIfCurrent(generation, reason)

    override suspend fun startWithNativePermit(
        session: VpnSession,
        host: RuntimeServiceHost,
        generation: Long,
    ): Result<Unit> {
        val call = starts.incrementAndGet()
        val handle = handles.incrementAndGet()
        if (call == 1) {
            predecessorNativeStartEntered.complete(Unit)
            releasePredecessorNativeStart.await()
        } else {
            successorNativeStartEntered.complete(Unit)
            releaseSuccessorCommit.await()
        }
        return synchronized(transitionLock) {
            if (call == 1 && !guard.isCurrent(generation)) {
                forceKilledHandles += handle
                val outcome = NativeForceStopOutcome.QUARANTINED
                poisonLatch.remember(outcome, host::onNativeProcessPoisoned)
                nativeForceStopPoisonedFailure(outcome, "late_start_discard")
            } else {
                val poisoned = poisonLatch.current.takeIf(NativeForceStopOutcome::processPoisoned)
                if (poisoned != null) {
                    nativeForceStopPoisonedFailure(poisoned, "start_commit")
                } else {
                    val published = guard.commitIfCurrent(generation) { publishedHandles += handle }
                    if (published) {
                        Result.success(Unit)
                    } else {
                        Result.failure(IllegalStateException("native start superseded"))
                    }
                }
            }
        }
    }

    override suspend fun start(session: VpnSession, host: RuntimeServiceHost): Result<Unit> =
        startWithNativePermit(session, host, openNativeStartPermit("direct"))

    override suspend fun reload(session: VpnSession, host: RuntimeServiceHost): Result<Unit> =
        Result.success(Unit)

    override suspend fun reloadWithNativePermit(
        session: VpnSession,
        host: RuntimeServiceHost,
        generation: Long,
    ): Result<Unit> = reload(session, host)

    override suspend fun abortNativeTransition(generation: Long, reason: String): RuntimeKillResult? {
        if (!guard.invalidateIfCurrent(generation, reason)) return null
        return RuntimeKillResult(reason = reason, tunClosed = true, serverDetached = true)
    }

    override suspend fun quiesceForInterfaceHandover(): Boolean = true

    override suspend fun stop(policy: RuntimeStopPolicy): RuntimeStopResult =
        RuntimeStopResult(true, true, true, false, 0L)
}

private class PoisonRecordingHost : RuntimeServiceHost {
    val processPoisoned = CompletableDeferred<Unit>()
    val poisonNotifications = AtomicInteger(0)

    override val runtimeContext: Context
        get() = throw UnsupportedOperationException("not used by the fake runtime")

    override fun stopRuntimeService() = Unit

    override fun protectSocket(socket: Int): Boolean = true

    override fun onNativeProcessPoisoned(outcome: NativeForceStopOutcome) {
        poisonNotifications.incrementAndGet()
        processPoisoned.complete(Unit)
    }
}

private class BlockingPreflightNativeApi : FoxCoreNativeApi {
    val preflightEntered = CountDownLatch(1)
    val releasePreflight = CountDownLatch(1)

    override fun abiVersion(): Int {
        preflightEntered.countDown()
        releasePreflight.await()
        return FoxholeNativeEngine.ABI_VERSION
    }

    override fun version(): String = "test"

    override fun capabilities(): String = """{"abi_version":${FoxholeNativeEngine.ABI_VERSION}}"""

    override fun startWithNetwork(
        tunFd: Int,
        configJson: String,
        networkHandle: Long,
        host: RuntimeServiceHost,
    ): Long = error("native start must not be reached")

    override fun startWithNetworkAndTrustedDnsRuleSet(
        tunFd: Int,
        configJson: String,
        networkHandle: Long,
        name: String,
        artifact: ByteArray,
        host: RuntimeServiceHost,
    ): Long = error("native start must not be reached")

    override fun installDnsRuleSet(
        handle: Long,
        name: String,
        manifest: ByteArray,
        signature: ByteArray,
        artifact: ByteArray,
    ): Long = 0L

    override fun stop(handle: Long): Int = FoxholeNativeEngine.STOP_UNKNOWN_HANDLE

    override fun forceKill(handle: Long): Int = FoxholeNativeEngine.STOP_UNKNOWN_HANDLE

    override fun reloadPolicy(handle: Long, policyJson: String): Long = 1L

    override fun lastPolicyError(handle: Long): String = ""

    override fun stats(handle: Long): String = "{}"

    override fun connections(handle: Long): String = "{}"

    override fun drainTrafficEvents(handle: Long, max: Int): String = "{}"

    override fun drainEvents(handle: Long, max: Int): String = "{}"

    override fun networkChanged(handle: Long) = Unit

    override fun networkChangedWithHandle(handle: Long, networkHandle: Long) = Unit
}

private class BlockingFakeNativeRuntime :
    FoxholeRuntime,
    RuntimeNativeStartFenceOwner {
    private val guard = RuntimeGenerationGuard(NoOpOwnershipDiagnostics)
    private val starts = AtomicInteger(0)
    private val handles = AtomicLong(0L)

    val firstNativeStartEntered = CompletableDeferred<Unit>()
    val releaseFirstNativeStart = CompletableDeferred<Unit>()
    val firstLateHandleClosed = CompletableDeferred<Unit>()
    val publishedHandles = Collections.synchronizedList(mutableListOf<Long>())
    val closedHandles = Collections.synchronizedList(mutableListOf<Long>())

    override fun openNativeStartPermit(reason: String): Long = guard.next(reason)

    override fun invalidateNativeStartPermit(
        generation: Long,
        reason: String,
    ): Boolean = guard.invalidateIfCurrent(generation, reason)

    override suspend fun startWithNativePermit(
        session: VpnSession,
        host: RuntimeServiceHost,
        generation: Long,
    ): Result<Unit> {
        val call = starts.incrementAndGet()
        val handle = handles.incrementAndGet()
        if (call == 1) {
            firstNativeStartEntered.complete(Unit)
            releaseFirstNativeStart.await()
        }
        val published =
            guard.commitIfCurrent(generation) {
                publishedHandles += handle
            }
        if (!published) {
            closedHandles += handle
            if (call == 1) {
                firstLateHandleClosed.complete(Unit)
            }
            return Result.failure(IllegalStateException("native start superseded"))
        }
        return Result.success(Unit)
    }

    override suspend fun start(
        session: VpnSession,
        host: RuntimeServiceHost,
    ): Result<Unit> = startWithNativePermit(session, host, openNativeStartPermit("direct"))

    override suspend fun reload(
        session: VpnSession,
        host: RuntimeServiceHost,
    ): Result<Unit> = Result.success(Unit)

    override suspend fun reloadWithNativePermit(
        session: VpnSession,
        host: RuntimeServiceHost,
        generation: Long,
    ): Result<Unit> = reload(session, host)

    override suspend fun abortNativeTransition(
        generation: Long,
        reason: String,
    ): RuntimeKillResult? {
        if (!guard.invalidateIfCurrent(generation, reason)) return null
        return RuntimeKillResult(reason = reason, tunClosed = true, serverDetached = true)
    }

    override suspend fun quiesceForInterfaceHandover(): Boolean = true

    override suspend fun stop(policy: RuntimeStopPolicy): RuntimeStopResult =
        RuntimeStopResult(
            closeServiceOk = true,
            closeServerOk = true,
            tunClosed = true,
            escalatedToKill = false,
            elapsedMs = 0L,
        )
}

private object TestRuntimeHost : RuntimeServiceHost {
    override val runtimeContext: Context
        get() = throw UnsupportedOperationException("not used by the fake runtime")

    override fun stopRuntimeService() = Unit

    override fun protectSocket(socket: Int): Boolean = true
}

private object NoOpOwnershipDiagnostics : RuntimeDiagnosticsSink {
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
