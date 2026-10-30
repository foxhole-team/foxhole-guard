package com.foxhole.core.runtime

import android.content.Context
import android.os.ParcelFileDescriptor
import com.foxhole.core.model.FoxCoreSessionConfig
import com.foxhole.core.model.FoxCoreTunPlan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.lang.reflect.Proxy

class FoxCoreNetworkChangeLeaseTest {
    @Test
    fun capturedTargetReachesNativeWithoutAmbientHostSelection() = runBlocking {
        val fixture = Fixture()
        val caller = Thread.currentThread()
        fixture.onNative = { assertFalse(Thread.currentThread() === caller) }
        assertEquals(
            RuntimeNetworkChangeOutcome.SUBMITTED,
            requireNotNull(fixture.runtime.captureNetworkChangeLease(2L)).submit(),
        )
        assertEquals(listOf(41L to 2L), fixture.calls)
        assertEquals(2L, fixture.active().networkHandle)
    }

    @Test
    fun sameTargetCoalescesPendingAndCompletedRequests() = runBlocking {
        val fixture = Fixture()
        val lease = requireNotNull(fixture.runtime.captureNetworkChangeLease(2L))
        assertNull(fixture.runtime.captureNetworkChangeLease(2L))
        assertEquals(RuntimeNetworkChangeOutcome.SUBMITTED, lease.submit())
        assertNull(fixture.runtime.captureNetworkChangeLease(2L))
        assertEquals(1, fixture.calls.size)
    }

    @Test
    fun newestCapturedTargetSupersedesQueuedPredecessor() = runBlocking {
        val fixture = Fixture()
        val old = requireNotNull(fixture.runtime.captureNetworkChangeLease(2L))
        val latest = requireNotNull(fixture.runtime.captureNetworkChangeLease(3L))
        assertEquals(RuntimeNetworkChangeOutcome.SUPERSEDED, old.submit())
        assertEquals(RuntimeNetworkChangeOutcome.SUBMITTED, latest.submit())
        assertEquals(listOf(41L to 3L), fixture.calls)
    }

    @Test
    fun returningToCurrentHandleInvalidatesPendingDifferentTarget() = runBlocking {
        val fixture = Fixture()
        val old = requireNotNull(fixture.runtime.captureNetworkChangeLease(2L))
        val latest = requireNotNull(fixture.runtime.captureNetworkChangeLease(1L))
        assertEquals(RuntimeNetworkChangeOutcome.SUPERSEDED, old.submit())
        assertEquals(RuntimeNetworkChangeOutcome.UNCHANGED, latest.submit())
        assertTrue(fixture.calls.isEmpty())
        assertEquals(1L, fixture.active().networkHandle)
    }

    @Test
    fun lossThenSameHandleStillRebindsEvenWhenLossWasSuperseded() = runBlocking {
        val fixture = Fixture()
        val lost = requireNotNull(fixture.runtime.captureNetworkChangeLease(null))
        val restored = requireNotNull(fixture.runtime.captureNetworkChangeLease(1L))
        assertEquals(RuntimeNetworkChangeOutcome.SUPERSEDED, lost.submit())
        assertEquals(RuntimeNetworkChangeOutcome.SUBMITTED, restored.submit())
        assertEquals(listOf(41L to 1L), fixture.calls)
    }

    @Test
    fun retiredServiceCannotApplyAndCannotBlockSuccessorSameTarget() = runBlocking {
        val fixture = Fixture()
        var current = true
        val retired = requireNotNull(fixture.runtime.captureNetworkChangeLease(2L) { current })
        current = false
        val successor = requireNotNull(fixture.runtime.captureNetworkChangeLease(2L) { true })
        assertEquals(RuntimeNetworkChangeOutcome.SUPERSEDED, retired.submit())
        assertEquals(RuntimeNetworkChangeOutcome.SUBMITTED, successor.submit())
        assertEquals(listOf(41L to 2L), fixture.calls)
    }

    @Test
    fun changedNativeGenerationOrSessionNeverRedirectsOldLease() = runBlocking {
        listOf<(Fixture) -> Unit>(
            { it.set("generation", 8L) },
            { it.set("active", it.active().copy(sessionIdentity = Any(), handle = 99L)) },
            { it.set("state", RuntimeState.IDLE) },
            { it.set("cleanupDraining", true) },
            { it.set("handoverQuiesced", true) },
        ).forEach { invalidate ->
            val fixture = Fixture()
            val lease = requireNotNull(fixture.runtime.captureNetworkChangeLease(2L))
            invalidate(fixture)
            assertTrue(
                lease.submit() in setOf(
                    RuntimeNetworkChangeOutcome.SUPERSEDED,
                    RuntimeNetworkChangeOutcome.UNAVAILABLE,
                )
            )
            assertTrue(fixture.calls.isEmpty())
        }
    }

    @Test
    fun cancelledQueuedLeaseAllowsFreshSameTargetWithoutNativeCall() = runBlocking {
        val fixture = Fixture()
        val mutex = fixture.field("transitionMutex") as Mutex
        mutex.lock()
        val lease = requireNotNull(fixture.runtime.captureNetworkChangeLease(2L))
        val job = async(start = CoroutineStart.UNDISPATCHED) { lease.submit() }
        job.cancelAndJoin()
        mutex.unlock()
        assertTrue(fixture.calls.isEmpty())
        val retry = requireNotNull(fixture.runtime.captureNetworkChangeLease(2L))
        assertEquals(RuntimeNetworkChangeOutcome.SUBMITTED, retry.submit())
        assertEquals(1, fixture.calls.size)
    }

    @Test
    fun ordinaryNativeFailureIsFalseAndRetryDoesNotUseStaleHost() = runBlocking {
        val fixture = Fixture()
        fixture.onNative = { throw IOException("owned native boundary failure") }
        assertEquals(
            RuntimeNetworkChangeOutcome.FAILED,
            requireNotNull(fixture.runtime.captureNetworkChangeLease(2L)).submit(),
        )
        assertEquals(1L, fixture.active().networkHandle)
        fixture.onNative = {}
        assertEquals(
            RuntimeNetworkChangeOutcome.SUBMITTED,
            requireNotNull(fixture.runtime.captureNetworkChangeLease(2L)).submit(),
        )
        assertEquals(2L, fixture.active().networkHandle)
    }

    @Test
    fun cancellationAndFatalNativeFailuresPropagateAndReleasePendingSlot() = runBlocking {
        listOf<Throwable>(
            CancellationException("owned cancellation"),
            AssertionError("owned fatal"),
        ).forEach { failure ->
            val fixture = Fixture()
            fixture.onNative = { throw failure }
            var caught: Throwable? = null
            try {
                requireNotNull(fixture.runtime.captureNetworkChangeLease(2L)).submit()
            } catch (error: Throwable) {
                caught = error
            }
            assertEquals(failure::class.java, caught?.javaClass)
            assertNotNull(fixture.runtime.captureNetworkChangeLease(2L))
        }
    }

    @Test
    fun invalidHandlesAndInactiveOwnerCannotCapture() {
        val fixture = Fixture()
        assertNull(fixture.runtime.captureNetworkChangeLease(0L))
        assertNull(fixture.runtime.captureNetworkChangeLease(-1L))
        fixture.set("state", RuntimeState.IDLE)
        assertNull(fixture.runtime.captureNetworkChangeLease(2L))
    }

    @Test
    fun abandonedPendingLeaseCannotApplyOrSuppressFreshSameTarget() = runBlocking {
        val fixture = Fixture()
        val discarded = requireNotNull(fixture.runtime.captureNetworkChangeLease(2L))
        discarded.abandon()
        assertEquals(RuntimeNetworkChangeOutcome.SUPERSEDED, discarded.submit())
        assertTrue(fixture.calls.isEmpty())
        val fresh = requireNotNull(fixture.runtime.captureNetworkChangeLease(2L))
        assertEquals(RuntimeNetworkChangeOutcome.SUBMITTED, fresh.submit())
        assertEquals(1, fixture.calls.size)
    }

    private class Fixture {
        val calls = mutableListOf<Pair<Long, Long?>>()
        var onNative: () -> Unit = {}
        private val native = Proxy.newProxyInstance(
            FoxCoreNativeApi::class.java.classLoader,
            arrayOf(FoxCoreNativeApi::class.java),
        ) { _, method, arguments ->
            when (method.name) {
                "networkChangedWithHandle" -> {
                    onNative()
                    calls += arguments!![0] as Long to arguments[1] as Long
                    null
                }
                "networkChanged" -> {
                    onNative()
                    calls += arguments!![0] as Long to null
                    null
                }
                else -> when (method.returnType) {
                    java.lang.Long.TYPE -> 0L
                    java.lang.Integer.TYPE -> 0
                    java.lang.Boolean.TYPE -> false
                    else -> null
                }
            }
        } as FoxCoreNativeApi
        private val host = object : RuntimeServiceHost {
            override val runtimeContext: Context get() = error("not used")
            override fun stopRuntimeService() = Unit
            override fun protectSocket(socket: Int): Boolean = true
            override fun currentUnderlyingNetwork() = error("typed target must not reselect ambient host")
        }
        private val diagnostics = object : RuntimeDiagnosticsSink {
            override fun record(tag: String, message: String) = Unit
            override fun recordStructured(tag: String, headline: String, vararg details: String?) = Unit
        }
        val runtime = FoxCoreRuntime(diagnostics, native = native).also {
            set(
                it,
                "active",
                ActiveFoxCoreSession(
                    handle = 41L,
                    tun = ParcelFileDescriptor(null as ParcelFileDescriptor?),
                    masterTunFd = -1,
                    nativeTunFd = -1,
                    host = host,
                    translated = FoxCoreSessionConfig(
                        "{}",
                        "{}",
                        FoxCoreTunPlan(1500, "10.0.0.1", 30, null, null, emptyList(), emptyList()),
                    ),
                    immutableFingerprint = "network-lease",
                    policyRevision = 1L,
                    networkHandle = 1L,
                    dnsServers = emptyList(),
                ),
            )
            set(it, "state", RuntimeState.RUNNING)
            set(it, "generation", 7L)
        }

        fun active() = field("active") as ActiveFoxCoreSession

        fun field(name: String): Any? = FoxCoreRuntime::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.get(runtime)

        fun set(name: String, value: Any?) = set(runtime, name, value)

        private fun set(runtime: FoxCoreRuntime, name: String, value: Any?) {
            FoxCoreRuntime::class.java.getDeclaredField(name).apply { isAccessible = true }.set(runtime, value)
        }
    }
}
