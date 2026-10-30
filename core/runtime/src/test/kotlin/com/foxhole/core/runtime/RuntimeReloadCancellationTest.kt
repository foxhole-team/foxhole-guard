package com.foxhole.core.runtime

import android.content.Context
import android.net.Network
import android.os.ParcelFileDescriptor
import com.foxhole.core.model.FoxCoreSessionConfig
import com.foxhole.core.model.FoxCoreTunPlan
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.VpnSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class RuntimeReloadCancellationTest {
    @Test
    fun `cancelled policy call that settles gets graceful stop without process poison`() = runBlocking {
        val native = ReloadCancellationNative()
        val host = ReloadCancellationHost()
        val runtime = activeReloadRuntime(native, host)
        val failure = AtomicReference<Throwable>()
        val reload = launch(Dispatchers.Default) {
            try {
                runtime.reloadFailClosed(reloadCancellationSession(), host, "test", NoOpRuntimeDiagnosticsSink)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                failure.set(cancelled)
                throw cancelled
            }
        }
        assertTrue(native.reloadEntered.await(2, TimeUnit.SECONDS))
        try {
            reload.cancel()
            Thread {
                Thread.sleep(40L)
                native.releaseReload.countDown()
            }.start()
            reload.join()
            assertTrue(failure.get() is kotlinx.coroutines.CancellationException)
            assertFalse(failure.get() is NativeForceStopPoisonedCancellationException)
            assertEquals(listOf(1L), native.stoppedHandles)
            assertTrue(native.forcedHandles.isEmpty())
            assertTrue(host.poisoned.isEmpty())
            assertFalse(runtime.nativeSnapshot().hasEngineHandle)
        } finally {
            native.releaseReload.countDown()
            native.releaseStop.countDown()
        }
    }

    @Test
    fun `unsettled cancelled JNI is fenced and quarantined and cannot overwrite successor`() = runBlocking {
        val native = ReloadCancellationNative()
        val host = ReloadCancellationHost()
        val runtime = activeReloadRuntime(native, host)
        val reload = launch(Dispatchers.Default) {
            runtime.reloadFailClosed(reloadCancellationSession(), host, "test", NoOpRuntimeDiagnosticsSink)
        }
        assertTrue(native.reloadEntered.await(2, TimeUnit.SECONDS))
        try {
            reload.cancelAndJoin()
            assertEquals(listOf(1L), native.forcedHandles)
            assertEquals(listOf(NativeForceStopOutcome.QUARANTINED), host.poisoned)
            seedReloadActive(runtime, native, host, 2L)
            native.releaseReload.countDown()
            assertTrue(native.reloadReturned.await(2, TimeUnit.SECONDS))
            Thread.sleep(40L)
            assertEquals(2L, activeReloadHandle(runtime))
            assertEquals(listOf(1L), native.forcedHandles)
        } finally {
            native.releaseReload.countDown()
            native.releaseStop.countDown()
        }
    }

    @Test
    fun `settled cancelled predecessor does not stop successor generation`() = runBlocking {
        val native = ReloadCancellationNative()
        val host = ReloadCancellationHost()
        val runtime = activeReloadRuntime(native, host)
        val reload = launch(Dispatchers.Default) {
            runtime.reloadFailClosed(reloadCancellationSession(), host, "test", NoOpRuntimeDiagnosticsSink)
        }
        assertTrue(native.reloadEntered.await(2, TimeUnit.SECONDS))
        try {
            reload.cancel()
            seedReloadActive(runtime, native, host, 2L)
            native.releaseReload.countDown()
            reload.join()
            assertEquals(2L, activeReloadHandle(runtime))
            assertTrue(native.stoppedHandles.isEmpty())
            assertTrue(native.forcedHandles.isEmpty())
            assertTrue(host.poisoned.isEmpty())
        } finally {
            native.releaseReload.countDown()
            native.releaseStop.countDown()
        }
    }

    @Test
    fun `genuinely stalled graceful native stop still preserves process poison`() = runBlocking {
        val native = ReloadCancellationNative(blockStop = true)
        val host = ReloadCancellationHost()
        val runtime = activeReloadRuntime(native, host)
        val failure = AtomicReference<Throwable>()
        val reload = launch(Dispatchers.Default) {
            try {
                runtime.reloadFailClosed(reloadCancellationSession(), host, "test", NoOpRuntimeDiagnosticsSink)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                failure.set(cancelled)
                throw cancelled
            }
        }
        assertTrue(native.reloadEntered.await(2, TimeUnit.SECONDS))
        try {
            reload.cancel()
            native.releaseReload.countDown()
            reload.join()
            assertEquals(listOf(1L), native.stoppedHandles)
            assertEquals(listOf(1L), native.forcedHandles)
            assertEquals(listOf(NativeForceStopOutcome.QUARANTINED), host.poisoned)
            assertTrue(failure.get() is NativeForceStopPoisonedCancellationException)
        } finally {
            native.releaseReload.countDown()
            native.releaseStop.countDown()
        }
    }

    @Test
    fun `contended cancellation deadline cannot abort successor or strand its transition lock`() = runBlocking {
        val native = ReloadCancellationNative()
        val host = ReloadCancellationHost()
        val runtime = activeReloadRuntime(native, host)
        val lock = FoxCoreRuntime::class.java.getDeclaredField("transitionMutex")
            .apply { isAccessible = true }.get(runtime) as Mutex
        val generation = FoxCoreRuntime::class.java.getDeclaredField("activeTransitionGeneration")
            .apply { isAccessible = true }.get(runtime) as Long
        val entered = CompletableDeferred<Unit>()
        lock.lock()
        try {
            val cleanup = async(Dispatchers.Default) {
                withContext(NonCancellable) {
                    val deadline = System.nanoTime() + 60_000_000L
                    entered.complete(Unit)
                    runtime.abortSettledNativeTransition(generation, "test-cancel", deadline)
                }
            }
            withTimeout(1_000L) { entered.await() }
            delay(100L)
            seedReloadActive(runtime, native, host, 2L)
            lock.unlock()
            assertNull(withTimeout(1_000L) { cleanup.await() })
            assertEquals(2L, activeReloadHandle(runtime))
            assertTrue(native.stoppedHandles.isEmpty())
            assertTrue(native.forcedHandles.isEmpty())
            assertTrue(host.poisoned.isEmpty())
            assertTrue(lock.tryLock())
        } finally {
            if (lock.isLocked) lock.unlock()
        }
    }
}

private class ReloadCancellationNative(private val blockStop: Boolean = false) {
    val reloadEntered = CountDownLatch(1)
    val reloadReturned = CountDownLatch(1)
    val releaseReload = CountDownLatch(1)
    val releaseStop = CountDownLatch(1)
    val stoppedHandles = Collections.synchronizedList(mutableListOf<Long>())
    val forcedHandles = Collections.synchronizedList(mutableListOf<Long>())
    val api: FoxCoreNativeApi = Proxy.newProxyInstance(
        FoxCoreNativeApi::class.java.classLoader,
        arrayOf(FoxCoreNativeApi::class.java),
    ) { _, method, args ->
        when (method.name) {
            "reloadPolicy" -> {
                reloadEntered.countDown()
                releaseReload.await()
                reloadReturned.countDown()
                2L
            }
            "stop" -> {
                stoppedHandles += args!![0] as Long
                if (blockStop) releaseStop.await()
                FoxholeNativeEngine.STOPPED
            }
            "forceKill" -> {
                forcedHandles += args!![0] as Long
                FoxholeNativeEngine.STOP_TIMED_OUT
            }
            "version", "lastPolicyError" -> ""
            "stats", "connections", "drainTrafficEvents", "drainEvents" -> "{}"
            "retainDnsContinuation" -> 11L
            "startOnNetworkWithDnsContinuity", "startWithNetwork" -> 2L
            else -> when (method.returnType) {
                java.lang.Long.TYPE -> 0L
                java.lang.Integer.TYPE -> 0
                java.lang.Boolean.TYPE -> false
                else -> null
            }
        }
    } as FoxCoreNativeApi
}

private class ReloadCancellationHost : RuntimeServiceHost {
    val poisoned = Collections.synchronizedList(mutableListOf<NativeForceStopOutcome>())
    override val runtimeContext: Context get() = error("not used")
    override fun stopRuntimeService() = Unit
    override fun protectSocket(socket: Int): Boolean = true
    override fun currentUnderlyingNetwork(): Network? = null
    override fun onNativeProcessPoisoned(outcome: NativeForceStopOutcome) { poisoned += outcome }
}

private fun reloadCancellationConfig(): FoxCoreSessionConfig = FoxCoreSessionConfig(
    engineConfigJson = "{}",
    policyConfigJson = "{}",
    tunPlan = FoxCoreTunPlan(1500, "10.0.0.1", 30, null, null, emptyList(), listOf("10.0.0.2")),
)

private fun reloadCancellationSession(): VpnSession = VpnSession(
    profileId = 1L,
    profileName = "Cancellation regression",
    protocolHint = ProtocolHint.LOCAL_GUARD,
    configJson = "{}",
    correlationId = "cancellation-regression",
    foxCoreConfig = reloadCancellationConfig(),
)

private fun activeReloadRuntime(native: ReloadCancellationNative, host: ReloadCancellationHost): FoxCoreRuntime =
    FoxCoreRuntime(NoOpRuntimeDiagnosticsSink, native = native.api).also { seedReloadActive(it, native, host, 1L) }

private fun seedReloadActive(runtime: FoxCoreRuntime, native: ReloadCancellationNative, host: ReloadCancellationHost, handle: Long) {
    val config = reloadCancellationConfig()
    val fingerprint = FoxCoreNativeEngineOperations(native.api, NoOpRuntimeDiagnosticsSink, FoxCoreConfigTranslator())
        .immutableFingerprint(config)!!
    val generation = runtime.openNativeStartPermit("test-generation")
    setReloadField(
        runtime,
        "active",
        ActiveFoxCoreSession(
            handle = handle,
            tun = ParcelFileDescriptor(null as ParcelFileDescriptor?),
            masterTunFd = -1,
            nativeTunFd = -1,
            host = host,
            translated = config,
            immutableFingerprint = fingerprint,
            policyRevision = 1L,
            networkHandle = 1L,
            dnsServers = listOf("10.0.0.2"),
        )
    )
    setReloadField(runtime, "state", RuntimeState.RUNNING)
    setReloadField(runtime, "pendingTransition", null)
    setReloadField(runtime, "activeTransitionGeneration", generation)
}

private fun activeReloadHandle(runtime: FoxCoreRuntime): Long? =
    (
        FoxCoreRuntime::class.java.getDeclaredField(
            "active"
        ).apply { isAccessible = true }.get(runtime) as? ActiveFoxCoreSession
        )?.handle

private fun setReloadField(runtime: FoxCoreRuntime, name: String, value: Any?) {
    FoxCoreRuntime::class.java.getDeclaredField(name).apply { isAccessible = true }.set(runtime, value)
}
