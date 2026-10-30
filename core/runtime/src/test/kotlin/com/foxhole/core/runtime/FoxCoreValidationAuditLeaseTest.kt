package com.foxhole.core.runtime

import android.content.Context
import android.os.ParcelFileDescriptor
import com.foxhole.core.model.FoxCoreSessionConfig
import com.foxhole.core.model.FoxCoreTunPlan
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.lang.reflect.Proxy

class FoxCoreValidationAuditLeaseTest {
    @Test
    fun `obsolete or inactive native generations never drain the current engine`() {
        val calls = mutableListOf<Long>()
        val runtime = seededRuntime(calls)
        assertNull(runtime.drainRuntimeAuditEventsJson(128, 6L))
        setField(runtime, "state", RuntimeState.IDLE)
        assertNull(runtime.drainRuntimeAuditEventsJson(128, 7L))
        assertEquals(emptyList<Long>(), calls)
    }

    @Test
    fun `publication during the read cannot redirect the captured old native handle`() {
        val calls = mutableListOf<Long>()
        lateinit var runtime: FoxCoreRuntime
        runtime = seededRuntime(calls) {
            val lock = field(runtime, "stateLock")!!
            assertFalse(Thread.holdsLock(lock))
            val old = field(runtime, "active") as ActiveFoxCoreSession
            val publish = FoxCoreRuntime::class.java.getDeclaredMethod(
                "publishState", ActiveFoxCoreSession::class.java, java.lang.Long.TYPE,
            ).apply { isAccessible = true }
            publish.invoke(runtime, old.copy(handle = 99L), 11L)
        }
        assertEquals("{\"events\":[],\"dropped\":0}", runtime.drainRuntimeAuditEventsJson(128, 7L))
        assertEquals(listOf(41L), calls)
        assertEquals(8L, runtime.nativeSnapshot().nativeGeneration)
        assertNull(runtime.drainRuntimeAuditEventsJson(128, 7L))
        assertEquals(listOf(41L), calls)
    }

    @Test
    fun `existing unbound drain method remains available for the normal collector`() {
        val calls = mutableListOf<Long>()
        val runtime = seededRuntime(calls)
        assertEquals("{\"events\":[],\"dropped\":0}", runtime.drainRuntimeAuditEventsJson(16))
        assertEquals(listOf(41L), calls)
    }

    @Test
    fun `guarded drain propagates the original native cancellation`() {
        val calls = mutableListOf<Long>()
        val cancelled = CancellationException("native audit cancelled")
        val runtime = seededRuntime(calls) { throw cancelled }

        val failure = runCatching { runtime.drainRuntimeAuditEventsJson(128, 7L) }.exceptionOrNull()

        assertSame(cancelled, failure)
        assertEquals(listOf(41L), calls)
    }

    @Test
    fun `guarded drain propagates the original fatal native error`() {
        val calls = mutableListOf<Long>()
        val fatal = LinkageError("native audit unavailable")
        val runtime = seededRuntime(calls) { throw fatal }

        val failure = runCatching { runtime.drainRuntimeAuditEventsJson(128, 7L) }.exceptionOrNull()

        assertSame(fatal, failure)
        assertEquals(listOf(41L), calls)
    }

    @Test
    fun `ordinary guarded drain exceptions retain the nullable failure contract`() {
        val calls = mutableListOf<Long>()
        val runtime = seededRuntime(calls) { throw IllegalStateException("native audit failed") }

        assertNull(runtime.drainRuntimeAuditEventsJson(128, 7L))
        assertEquals(listOf(41L), calls)
    }

    private fun seededRuntime(calls: MutableList<Long>, onRead: () -> Unit = {}): FoxCoreRuntime {
        val native = Proxy.newProxyInstance(
            FoxCoreNativeApi::class.java.classLoader,
            arrayOf(FoxCoreNativeApi::class.java),
        ) { _, method, args ->
            when (method.name) {
                "drainEvents" -> {
                    calls += args!![0] as Long
                    onRead()
                    "{\"events\":[],\"dropped\":0}"
                }
                "version", "lastPolicyError" -> ""
                else -> when (method.returnType) {
                    java.lang.Long.TYPE -> 0L
                    java.lang.Integer.TYPE -> 0
                    java.lang.Boolean.TYPE -> false
                    else -> null
                }
            }
        } as FoxCoreNativeApi
        val config = FoxCoreSessionConfig(
            engineConfigJson = "{}",
            policyConfigJson = "{}",
            tunPlan = FoxCoreTunPlan(1500, "10.0.0.1", 30, null, null, emptyList(), emptyList()),
        )
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
                    translated = config,
                    immutableFingerprint = "audit-lease",
                    policyRevision = 1L,
                    networkHandle = 1L,
                    dnsServers = emptyList(),
                ),
            )
            setField(runtime, "state", RuntimeState.RUNNING)
            setField(runtime, "generation", 7L)
        }
    }

    private fun field(runtime: FoxCoreRuntime, name: String): Any? =
        FoxCoreRuntime::class.java.getDeclaredField(name).apply { isAccessible = true }.get(runtime)

    private fun setField(runtime: FoxCoreRuntime, name: String, value: Any?) {
        FoxCoreRuntime::class.java.getDeclaredField(name).apply { isAccessible = true }.set(runtime, value)
    }
}
