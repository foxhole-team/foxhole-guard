package com.foxhole.beta.vpn

import android.content.Context
import android.net.Network
import com.foxhole.beta.core.model.ProtocolHint
import com.foxhole.beta.core.model.VpnSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class RuntimeStopSupportTest {
    @Test
    fun `runtime stop result is graceful only when native closes without escalation`() {
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
    }

    @Test
    fun `blocking runtime close times out without waiting for native call to return`() =
        runBlocking {
            val completed =
                runBlockingRuntimeClose(timeoutMs = 10L) {
                    Thread.sleep(250L)
                }

            assertFalse(completed)
        }

    @Test
    fun `blocking runtime close reports success when native call returns before timeout`() =
        runBlocking {
            val completed =
                runBlockingRuntimeClose(timeoutMs = 250L) {
                    Thread.sleep(10L)
                }

            assertTrue(completed)
        }

    @Test
    fun `blocking runtime close reports false when native close throws`() =
        runBlocking {
            val completed =
                runBlockingRuntimeClose(timeoutMs = 250L) {
                    error("native close failed")
                }

            assertFalse(completed)
        }

    @Test
    fun `priority disconnect closes fail-closed while startServer is blocked`() =
        runBlocking {
            assertPriorityDisconnectWhileStartBlocked(NativeBlockPoint.START_SERVER)
        }

    @Test
    fun `priority disconnect closes fail-closed while checkConfig is blocked`() =
        runBlocking {
            assertPriorityDisconnectWhileStartBlocked(NativeBlockPoint.CHECK_CONFIG)
        }

    @Test
    fun `priority disconnect closes fail-closed while startOrReloadService is blocked`() =
        runBlocking {
            assertPriorityDisconnectWhileStartBlocked(NativeBlockPoint.START_OR_RELOAD_SERVICE)
        }

    @Test
    fun `native command reload skips when stop holds runtime lock`() =
        runBlocking {
            val fixture = runtimeFixture()
            fixture.runtime.start(testSession(), FakeRuntimeHost()).getOrThrow()
            fixture.operationMutex.lock()
            try {
                fixture.native.triggerReload()
            } finally {
                fixture.operationMutex.unlock()
            }

            assertEquals(1, fixture.native.startOrReloadServiceCalls.get())
            assertTrue(fixture.diagnostics.contains("native_callback_skipped_operation_busy"))
        }

    @Test
    fun `default network reset skips when stop holds runtime lock`() =
        runBlocking {
            val fixture = runtimeFixture()
            fixture.runtime.start(testSession(), FakeRuntimeHost()).getOrThrow()
            fixture.operationMutex.lock()
            try {
                fixture.runtime.onDefaultNetworkAvailable()
            } finally {
                fixture.operationMutex.unlock()
            }

            assertEquals(0, fixture.native.resetNetworkCalls.get())
            assertEquals(1, fixture.defaultNetworkMonitor.dispatchListenerUpdateCalls.get())
            assertTrue(fixture.diagnostics.contains("native_callback_skipped_operation_busy"))
        }

    @Test
    fun `force kill while runtime lock is busy returns detached close and closed tun`() =
        runBlocking {
            val fixture = runtimeFixture()
            fixture.runtime.start(testSession(), FakeRuntimeHost()).getOrThrow()
            fixture.operationMutex.lock()
            val result =
                try {
                    fixture.runtime.forceKill("test_busy")
                } finally {
                    fixture.operationMutex.unlock()
                }

            assertTrue(result.tunClosed)
            assertTrue(result.serverDetached)
            assertTrue(result.closeDetached)
            assertTrue(fixture.diagnostics.contains("force_kill_lock_busy"))
        }

    @Test
    fun `native callback skips stale server after rechecking inside lock`() {
        val diagnostics = FakeRuntimeDiagnosticsSink()
        val operationMutex = Mutex()
        val oldServer = Any()
        val currentServer = Any()
        val commandServerRef = AtomicReference<Any?>(oldServer)
        val callbackCalled = AtomicBoolean(false)

        withRunningServerIfIdle(
            reason = "stale_test",
            operationMutex = operationMutex,
            commandServerRef = commandServerRef,
            diagnosticsLogger = diagnostics,
            beforeTryLock = { commandServerRef.set(currentServer) },
        ) {
            callbackCalled.set(true)
        }

        assertFalse(callbackCalled.get())
        assertTrue(diagnostics.contains("native_callback_skipped_stale_server"))
    }

    private suspend fun assertPriorityDisconnectWhileStartBlocked(blockPoint: NativeBlockPoint) = coroutineScope {
        val fixture = runtimeFixture(blockPoint)
        val startJob =
            async(Dispatchers.Default) {
                fixture.runtime.start(testSession(), FakeRuntimeHost())
            }

        fixture.native.awaitBlocked()
        val stopResult =
            fixture.runtime.stop(
                RuntimeStopPolicy(
                    closeTunFdImmediately = true,
                    totalGracefulTimeoutMs = 10L,
                    forceKillAfterTimeout = true,
                ),
            )
        fixture.native.releaseBlocked()
        runCatching { startJob.await() }

        assertTrue(stopResult.escalatedToKill)
        assertTrue(stopResult.tunClosed)
        assertTrue(fixture.diagnostics.contains("stop_lock_busy"))
    }

    private fun runtimeFixture(blockPoint: NativeBlockPoint? = null): RuntimeFixture {
        val diagnostics = FakeRuntimeDiagnosticsSink()
        val native = FakeLibboxRuntimeNative(blockPoint)
        val defaultNetworkMonitor = FakeDefaultNetworkMonitor()
        val operationMutex = Mutex()
        val runtime =
            ReflectiveLibboxRuntime(
                diagnosticsLogger = diagnostics,
                reflection = native,
                defaultNetworkMonitor = defaultNetworkMonitor,
                operationMutex = operationMutex,
                elapsedRealtime = { 0L },
            )
        return RuntimeFixture(
            runtime = runtime,
            native = native,
            defaultNetworkMonitor = defaultNetworkMonitor,
            diagnostics = diagnostics,
            operationMutex = operationMutex,
        )
    }

    private fun testSession(): VpnSession =
        VpnSession(
            profileId = 42L,
            profileName = "Test",
            protocolHint = ProtocolHint.VLESS,
            configJson = "{}",
            correlationId = "test-session",
        )
}

private data class RuntimeFixture(
    val runtime: ReflectiveLibboxRuntime,
    val native: FakeLibboxRuntimeNative,
    val defaultNetworkMonitor: FakeDefaultNetworkMonitor,
    val diagnostics: FakeRuntimeDiagnosticsSink,
    val operationMutex: Mutex,
)

private enum class NativeBlockPoint {
    START_SERVER,
    CHECK_CONFIG,
    START_OR_RELOAD_SERVICE,
}

private class FakeLibboxRuntimeNative(
    private val blockPoint: NativeBlockPoint? = null,
) : LibboxRuntimeNative {
    val startOrReloadServiceCalls = AtomicInteger(0)
    val resetNetworkCalls = AtomicInteger(0)

    private val server = Any()
    private val blockedEntered = CountDownLatch(1)
    private val unblock = CountDownLatch(1)
    private var onReload: (() -> Unit)? = null

    override fun isAvailable(): Boolean = true

    override fun setupIfNeeded() = Unit

    override fun commandServerHandlerProxy(
        onReload: () -> Unit,
        onStop: () -> Unit,
        onDebug: (String) -> Unit,
    ): Any {
        this.onReload = onReload
        return Any()
    }

    override fun platformProxy(
        host: RuntimeServiceHost,
        defaultNetworkMonitor: RuntimeDefaultNetworkMonitor,
        openTun: (RuntimeServiceHost, Any) -> Int,
    ): Any = Any()

    override fun newCommandServer(handler: Any, platform: Any): Any = server

    override fun startServer(commandServer: Any) {
        blockIf(NativeBlockPoint.START_SERVER)
    }

    override fun closeServer(commandServer: Any) = Unit

    override fun closeService(commandServer: Any) = Unit

    override fun checkConfig(commandServer: Any, config: String) {
        blockIf(NativeBlockPoint.CHECK_CONFIG)
    }

    override fun startOrReloadService(commandServer: Any, config: String) {
        startOrReloadServiceCalls.incrementAndGet()
        blockIf(NativeBlockPoint.START_OR_RELOAD_SERVICE)
    }

    override fun resetNetwork(commandServer: Any) {
        resetNetworkCalls.incrementAndGet()
    }

    override fun call(target: Any?, name: String, vararg args: Any?): Any? =
        error("reflection call is not used by these tests: $name")

    override fun callBoolean(target: Any?, name: String): Boolean = false

    override fun callInt(target: Any?, name: String): Int = 0

    override fun collectStrings(iterator: Any?): List<String> = emptyList()

    override fun collectStringBoxOrIterator(value: Any?): List<String> = emptyList()

    override fun forEachRoutePrefix(
        iterator: Any?,
        block: (ReflectedRoutePrefix) -> Unit,
    ) = Unit

    fun triggerReload() {
        requireNotNull(onReload).invoke()
    }

    fun awaitBlocked() {
        assertTrue("native call did not block", blockedEntered.await(2, TimeUnit.SECONDS))
    }

    fun releaseBlocked() {
        unblock.countDown()
    }

    private fun blockIf(point: NativeBlockPoint) {
        if (blockPoint != point) {
            return
        }
        blockedEntered.countDown()
        assertTrue("native call was not released", unblock.await(2, TimeUnit.SECONDS))
    }
}

private class FakeDefaultNetworkMonitor : RuntimeDefaultNetworkMonitor {
    val dispatchListenerUpdateCalls = AtomicInteger(0)

    override fun start() = Unit

    override fun stop() = Unit

    override fun setListener(listener: Any?) = Unit

    override fun requireNetwork(): Network =
        error("network is not used by these tests")

    override fun isCurrentNetworkMetered(): Boolean = false

    override fun bindSocketToDefaultNetwork(fd: Int) = Unit

    override fun dispatchListenerUpdate() {
        dispatchListenerUpdateCalls.incrementAndGet()
    }
}

private class FakeRuntimeDiagnosticsSink : RuntimeDiagnosticsSink {
    private val entries = CopyOnWriteArrayList<String>()

    override fun record(tag: String, message: String) {
        entries += "$tag:$message"
    }

    override fun recordStructured(
        tag: String,
        headline: String,
        vararg details: String?,
    ) {
        entries += "$tag:$headline:${details.filterNotNull().joinToString("|")}"
    }

    fun contains(fragment: String): Boolean =
        entries.any { entry -> fragment in entry }
}

private class FakeRuntimeHost : RuntimeServiceHost {
    override val runtimeContext: Context
        get() = error("context is not used by these tests")

    override fun stopRuntimeService() = Unit

    override fun protectSocket(socket: Int): Boolean = true
}
