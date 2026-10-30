package com.foxhole.core.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FoxCoreDnsContinuationTest {
    @Test
    fun `replacement cannot start until the retained predecessor stop completes`() = runBlocking {
        val native = ContinuationNative()
        val stopEntered = CompletableDeferred<Unit>()
        val allowStop = CompletableDeferred<Unit>()
        val replacement = async {
            withStoppedDnsContinuation(
                native,
                8L,
                { true },
                {
                    native.events += "stop_enter"
                    stopEntered.complete(Unit)
                    allowStop.await()
                    native.events += "stop_joined"
                    NativeStopOutcome(stopped = true, escalated = false)
                },
                onReleaseFailure = { native.releaseDiagnostics += it },
            ) { token, stopped ->
                assertTrue(stopped.stopped)
                assertEquals(41L, token)
                native.events += "start"
                "successor"
            }
        }
        stopEntered.await()
        assertEquals(listOf("retain:8", "stop_enter"), native.events)
        allowStop.complete(Unit)
        assertEquals(FoxCoreDnsContinuation.Completed("successor"), replacement.await())
        assertEquals(listOf("retain:8", "stop_enter", "stop_joined", "start", "release:41"), native.events)
    }

    @Test
    fun `retain refusal keeps the predecessor running and never enters stop`() = runBlocking {
        val native = ContinuationNative(retainedToken = 0L)
        val result = withStoppedDnsContinuation(
            native,
            8L,
            { true },
            {
                error("retention failure must not stop the engine")
            },
            onReleaseFailure = { native.releaseDiagnostics += it },
        ) { _, _ ->
            error("retention failure must not start a successor")
        }
        assertEquals(FoxCoreDnsContinuation.Unavailable, result)
        assertEquals(listOf("retain:8"), native.events)
    }

    @Test
    fun `stale ownership after capture releases its lease without stopping`() = runBlocking {
        val native = ContinuationNative()
        var checks = 0
        val result = withStoppedDnsContinuation(
            native,
            8L,
            { ++checks == 1 },
            {
                error("superseded predecessor must not be stopped")
            },
            onReleaseFailure = { native.releaseDiagnostics += it },
        ) { _, _ ->
            error("superseded predecessor must not start")
        }
        assertEquals(FoxCoreDnsContinuation.Superseded, result)
        assertEquals(listOf("retain:8", "release:41"), native.events)
    }

    @Test
    fun `already stale ownership never captures a continuation`() = runBlocking {
        val native = ContinuationNative()
        val result = withStoppedDnsContinuation(
            native,
            8L,
            { false },
            {
                error("stale owner must not stop")
            },
            onReleaseFailure = { native.releaseDiagnostics += it },
        ) { _, _ ->
            error("stale owner must not start")
        }
        assertEquals(FoxCoreDnsContinuation.Superseded, result)
        assertTrue(native.events.isEmpty())
    }

    @Test
    fun `cancellation while joining the predecessor still releases the lease`() = runBlocking {
        val native = ContinuationNative()
        val entered = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        val replacement = async {
            withStoppedDnsContinuation(
                native,
                8L,
                { true },
                {
                    entered.complete(Unit)
                    never.await()
                    NativeStopOutcome(stopped = true, escalated = false)
                },
                onReleaseFailure = { native.releaseDiagnostics += it },
            ) { _, _ ->
                error("cancelled replacement must not start")
            }
        }
        entered.await()
        replacement.cancelAndJoin()
        assertEquals(listOf("retain:8", "release:41"), native.events)
    }

    @Test
    fun `rollback can reuse the same lease before the scope releases it`() = runBlocking {
        val native = ContinuationNative()
        val result = withStoppedDnsContinuation(
            native,
            8L,
            { true },
            {
                NativeStopOutcome(stopped = true, escalated = false)
            },
            onReleaseFailure = { native.releaseDiagnostics += it },
        ) { token, stopped ->
            assertTrue(stopped.stopped)
            native.events += "successor_failed:$token"
            native.events += "restore:$token"
            "restored"
        }
        assertEquals(FoxCoreDnsContinuation.Completed("restored"), result)
        assertEquals(listOf("retain:8", "successor_failed:41", "restore:41", "release:41"), native.events)
    }

    @Test
    fun `replacement exception still releases the lease`() = runBlocking {
        val native = ContinuationNative()
        var rejected = false
        try {
            withStoppedDnsContinuation(
                native,
                8L,
                { true },
                {
                    NativeStopOutcome(stopped = true, escalated = false)
                },
                onReleaseFailure = { native.releaseDiagnostics += it },
            ) { _, _ ->
                error("start failed")
            }
        } catch (_: IllegalStateException) {
            rejected = true
        }
        assertTrue(rejected)
        assertEquals(listOf("retain:8", "release:41"), native.events)
    }

    @Test
    fun `quarantined continuity start releases the lease and remains a typed poison signal`() = runBlocking {
        val native = ContinuationNative()
        var restoreEntered = false
        var outcome: NativeForceStopOutcome? = null
        try {
            withStoppedDnsContinuation(
                native,
                8L,
                { true },
                {
                    NativeStopOutcome(stopped = true, escalated = false)
                },
                onReleaseFailure = { native.releaseDiagnostics += it },
            ) { _, _ ->
                rejectPoisonedDnsContinuityStart(FoxholeNativeEngine.DNS_CONTINUITY_START_QUARANTINED)
                restoreEntered = true
            }
        } catch (poisoned: NativeForceStopPoisonedException) {
            outcome = poisoned.nativeForceStopOutcomeOrNull()
        }
        assertEquals(NativeForceStopOutcome.QUARANTINED, outcome)
        assertTrue(!restoreEntered)
        assertEquals(listOf("retain:8", "release:41"), native.events)
    }

    @Test
    fun `failed continuity cleanup also poisons while clean refusal is not a poison code`() {
        rejectPoisonedDnsContinuityStart(0L)
        rejectPoisonedDnsContinuityStart(33L)
        var outcome: NativeForceStopOutcome? = null
        try {
            rejectPoisonedDnsContinuityStart(FoxholeNativeEngine.DNS_CONTINUITY_START_FAILED)
        } catch (poisoned: NativeForceStopPoisonedException) {
            outcome = poisoned.forceStopOutcome
        }
        assertEquals(NativeForceStopOutcome.FAILED, outcome)
    }

    @Test
    fun `release failure preserves the primary native poison exception`() = runBlocking {
        val releaseFailure = IllegalStateException("release failed")
        val native = ContinuationNative(releaseFailure = releaseFailure)
        val primary = NativeForceStopPoisonedException(NativeForceStopOutcome.QUARANTINED, "test_start")
        var observed: Throwable? = null
        try {
            withStoppedDnsContinuation(
                native,
                8L,
                { true },
                {
                    NativeStopOutcome(stopped = true, escalated = false)
                },
                onReleaseFailure = { native.releaseDiagnostics += it },
            ) { _, _ ->
                throw primary
            }
        } catch (failure: Throwable) {
            observed = failure
        }
        assertSame(primary, observed)
        assertEquals(NativeForceStopOutcome.QUARANTINED, observed.nativeForceStopOutcomeOrNull())
        assertSame(releaseFailure, native.releaseDiagnostics.single())
        assertEquals(listOf("retain:8", "release:41"), native.events)
    }

    @Test
    fun `release failure returns the completed result that owns the registered successor`() = runBlocking {
        val releaseFailure = IllegalStateException("release failed")
        val native = ContinuationNative(releaseFailure = releaseFailure)
        val registered = mutableSetOf<Long>()
        val successor = OwnedSuccessor(99L)
        val result = withStoppedDnsContinuation(
            native,
            8L,
            { true },
            {
                NativeStopOutcome(stopped = true, escalated = false)
            },
            onReleaseFailure = { native.releaseDiagnostics += it },
        ) { _, _ ->
            registered += successor.handle
            successor
        }
        assertTrue(result is FoxCoreDnsContinuation.Completed)
        assertSame(successor, (result as FoxCoreDnsContinuation.Completed<*>).value)
        assertEquals(setOf(99L), registered)
        assertSame(releaseFailure, native.releaseDiagnostics.single())
        assertEquals(listOf("retain:8", "release:41"), native.events)
    }

    @Test
    fun `release failure during cancellation does not replace cancellation`() = runBlocking {
        val releaseFailure = IllegalStateException("release failed")
        val native = ContinuationNative(releaseFailure = releaseFailure)
        val entered = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        val replacement = async {
            withStoppedDnsContinuation(
                native,
                8L,
                { true },
                {
                    entered.complete(Unit)
                    never.await()
                    NativeStopOutcome(stopped = true, escalated = false)
                },
                onReleaseFailure = { native.releaseDiagnostics += it },
            ) { _, _ ->
                error("cancelled start")
            }
        }
        entered.await()
        replacement.cancelAndJoin()
        assertTrue(replacement.isCancelled)
        assertSame(releaseFailure, native.releaseDiagnostics.single())
        assertEquals(listOf("retain:8", "release:41"), native.events)
    }

    @Test
    fun `a failing release diagnostic cannot lose the completed successor`() = runBlocking {
        val native = ContinuationNative(releaseFailure = IllegalStateException("release failed"))
        val successor = OwnedSuccessor(100L)
        var diagnosticInvoked = false
        val result = withStoppedDnsContinuation(
            native,
            8L,
            { true },
            {
                NativeStopOutcome(stopped = true, escalated = false)
            },
            onReleaseFailure = {
                diagnosticInvoked = true
                error("diagnostic sink failed")
            },
        ) { _, _ ->
            successor
        }
        assertTrue(diagnosticInvoked)
        assertSame(successor, (result as FoxCoreDnsContinuation.Completed<*>).value)
        assertEquals(listOf("retain:8", "release:41"), native.events)
    }
}

private class ContinuationNative(
    private val retainedToken: Long = 41L,
    private val releaseFailure: Throwable? = null,
) : FoxCoreNativeApi {
    val events = mutableListOf<String>()
    val releaseDiagnostics = mutableListOf<Throwable>()
    override fun retainDnsContinuation(handle: Long): Long {
        events += "retain:$handle"
        return retainedToken
    }
    override fun releaseDnsContinuation(token: Long) {
        events += "release:$token"
        releaseFailure?.let { throw it }
    }
    override fun version(): String = "test"
    override fun abiVersion(): Int = 1
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
    override fun stop(handle: Long): Int = 0
    override fun forceKill(handle: Long): Int = 0
    override fun reloadPolicy(handle: Long, policyJson: String): Long = 0L
    override fun lastPolicyError(handle: Long): String = ""
    override fun stats(handle: Long): String = "{}"
    override fun connections(handle: Long): String = "{}"
    override fun drainTrafficEvents(handle: Long, max: Int): String = "{}"
    override fun drainEvents(handle: Long, max: Int): String = "{}"
    override fun networkChanged(handle: Long) = Unit
    override fun networkChangedWithHandle(handle: Long, networkHandle: Long) = Unit
}

private data class OwnedSuccessor(val handle: Long)
