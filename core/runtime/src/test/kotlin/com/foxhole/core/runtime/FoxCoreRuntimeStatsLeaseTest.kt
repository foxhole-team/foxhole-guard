package com.foxhole.core.runtime

import android.content.Context
import android.os.ParcelFileDescriptor
import com.foxhole.core.model.FoxCoreSessionConfig
import com.foxhole.core.model.FoxCoreTunPlan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class FoxCoreRuntimeStatsLeaseTest {
    @Test
    fun `idle cleanup quiesced and pending owners cannot capture a lease`() {
        val cases = listOf<(FoxCoreRuntime) -> Unit>(
            { setField(it, "state", RuntimeState.IDLE) },
            { setField(it, "cleanupDraining", true) },
            { setField(it, "handoverQuiesced", true) },
            { setField(it, "generation", 0L) },
            { setPendingReload(it) },
        )
        cases.forEach { change ->
            val calls = mutableListOf<Long>()
            val runtime = seeded(calls)
            change(runtime)
            assertNull(runtime.captureStatsReadLease())
            assertTrue(calls.isEmpty())
        }
    }

    @Test
    fun `held transition mutex refuses capture without waiting`() {
        val runtime = seeded(mutableListOf())
        val mutex = field(runtime, "transitionMutex") as Mutex
        assertTrue(mutex.tryLock())
        try {
            assertNull(runtime.captureStatsReadLease())
        } finally {
            mutex.unlock()
        }
    }

    @Test
    fun `steady owner transition generation is not a pending transition`() {
        val calls = mutableListOf<Long>()
        val runtime = seeded(calls)
        setField(runtime, "activeTransitionGeneration", 12L)
        assertEquals("{}", runtime.captureStatsReadLease()!!.readStatsJson())
        assertEquals(listOf(41L), calls)
    }

    @Test
    fun `captured lease never reads the published successor`() {
        val calls = mutableListOf<Long>()
        val runtime = seeded(calls)
        val lease = runtime.captureStatsReadLease()!!
        publish(runtime, 99L)
        assertNull(lease.readStatsJson())
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `publication during offlock read discards old output without redirect`() {
        val calls = mutableListOf<Long>()
        lateinit var runtime: FoxCoreRuntime
        runtime = seeded(calls) {
            assertFalse(Thread.holdsLock(field(runtime, "stateLock")!!))
            assertFalse((field(runtime, "transitionMutex") as Mutex).isLocked)
            publish(runtime, 99L)
        }
        assertNull(runtime.captureStatsReadLease()!!.readStatsJson())
        assertEquals(listOf(41L), calls)
    }

    @Test
    fun `pending native work or cleanup after JNI invalidates otherwise stable owner`() {
        listOf<(FoxCoreRuntime) -> Unit>(
            { setPendingReload(it) },
            { setField(it, "cleanupDraining", true) },
            { setField(it, "handoverQuiesced", true) },
            { setField(it, "state", RuntimeState.IDLE) },
        ).forEach { change ->
            val calls = mutableListOf<Long>()
            lateinit var runtime: FoxCoreRuntime
            runtime = seeded(calls) { change(runtime) }
            assertNull(runtime.captureStatsReadLease()!!.readStatsJson())
            assertEquals(listOf(41L), calls)
        }
    }

    @Test
    fun `busy transition on either read fence cannot yield available stats`() {
        val calls = mutableListOf<Long>()
        val before = seeded(calls)
        val lease = before.captureStatsReadLease()!!
        val mutex = field(before, "transitionMutex") as Mutex
        assertTrue(mutex.tryLock())
        try {
            assertNull(lease.readStatsJson())
            assertTrue(calls.isEmpty())
        } finally {
            mutex.unlock()
        }
        lateinit var after: FoxCoreRuntime
        after = seeded(calls) { assertTrue((field(after, "transitionMutex") as Mutex).tryLock()) }
        val afterLease = after.captureStatsReadLease()!!
        try {
            assertNull(afterLease.readStatsJson())
            assertEquals(listOf(41L), calls)
        } finally {
            (field(after, "transitionMutex") as Mutex).unlock()
        }
    }

    @Test
    fun `same session metadata update remains readable`() {
        val calls = mutableListOf<Long>()
        val runtime = seeded(calls)
        val lease = runtime.captureStatsReadLease()!!
        val old = field(runtime, "active") as ActiveFoxCoreSession
        setField(runtime, "active", old.copy(networkHandle = 2L))
        assertEquals("{}", lease.readStatsJson())
        assertEquals(listOf(41L), calls)
    }

    @Test
    fun `native cancellation and fatal error propagate original identity`() {
        listOf<Throwable>(CancellationException("cancelled"), LinkageError("fatal")).forEach { failure ->
            val calls = mutableListOf<Long>()
            val runtime = seeded(calls) { throw failure }
            assertSame(failure, runCatching { runtime.captureStatsReadLease()!!.readStatsJson() }.exceptionOrNull())
            assertEquals(listOf(41L), calls)
        }
    }

    @Test
    fun `ordinary read error is unavailable and no event APIs are called`() {
        val calls = mutableListOf<Long>()
        val runtime = seeded(calls) { error("read failed") }
        assertNull(runtime.captureStatsReadLease()!!.readStatsJson())
        assertEquals(listOf(41L), calls)
    }

    private fun seeded(calls: MutableList<Long>, onRead: () -> Unit = {}): FoxCoreRuntime {
        val native = Proxy.newProxyInstance(
            FoxCoreNativeApi::class.java.classLoader,
            arrayOf(FoxCoreNativeApi::class.java),
        ) { _, method, args ->
            when (method.name) {
                "stats" -> {
                    calls += args!![0] as Long
                    onRead()
                    "{}"
                }
                "drainEvents", "drainTrafficEvents", "trafficMap" -> error("unexpected destructive read")
                "version", "lastPolicyError" -> ""
                else -> when (method.returnType) {
                    java.lang.Long.TYPE -> 0L
                    java.lang.Integer.TYPE -> 0
                    java.lang.Boolean.TYPE -> false
                    else -> null
                }
            }
        } as FoxCoreNativeApi
        val host = object : RuntimeServiceHost {
            override val runtimeContext: Context get() = error("not used")
            override fun stopRuntimeService() = Unit
            override fun protectSocket(socket: Int): Boolean = true
        }
        return FoxCoreRuntime(NoOpRuntimeDiagnosticsSink, native = native).also { runtime ->
            setField(
                runtime,
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
                        FoxCoreTunPlan(
                            1500,
                            "10.0.0.1",
                            30,
                            null,
                            null,
                            emptyList(),
                            emptyList(),
                        ),
                    ),
                    immutableFingerprint = "stats-lease",
                    policyRevision = 1L,
                    networkHandle = 1L,
                    dnsServers = emptyList(),
                ),
            )
            setField(runtime, "state", RuntimeState.RUNNING)
            setField(runtime, "generation", 7L)
        }
    }

    private fun setPendingReload(runtime: FoxCoreRuntime) {
        val constructor = Class.forName("com.foxhole.core.runtime.FoxCoreRuntime\$PendingNativeTransition\$Reload")
            .getDeclaredConstructor(java.lang.Long.TYPE, ActiveFoxCoreSession::class.java)
            .apply { isAccessible = true }
        val mutex = field(runtime, "transitionMutex") as Mutex
        assertTrue(mutex.tryLock())
        try {
            setField(runtime, "pendingTransition", constructor.newInstance(7L, field(runtime, "active")))
        } finally {
            mutex.unlock()
        }
    }

    private fun publish(runtime: FoxCoreRuntime, handle: Long) {
        val old = field(runtime, "active") as ActiveFoxCoreSession
        FoxCoreRuntime::class.java.getDeclaredMethod(
            "publishState",
            ActiveFoxCoreSession::class.java,
            java.lang.Long.TYPE,
        )
            .apply { isAccessible = true }.invoke(runtime, old.copy(handle = handle), 12L)
    }

    private fun field(runtime: FoxCoreRuntime, name: String): Any? =
        FoxCoreRuntime::class.java.getDeclaredField(name).apply { isAccessible = true }.get(runtime)

    private fun setField(runtime: FoxCoreRuntime, name: String, value: Any?) {
        FoxCoreRuntime::class.java.getDeclaredField(name).apply { isAccessible = true }.set(runtime, value)
    }
}
