package com.foxhole.core.runtime

import com.foxhole.core.model.I2pNetworkPhase
import com.foxhole.core.model.I2pPhaseSnapshot
import com.foxhole.core.model.I2pSettings
import com.foxhole.core.model.networkUp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class I2pdProcessManagerLifecycleTest {
    @After
    fun clearPublishedI2pdState() {
        I2pdWebConsole.endpoint = null
        I2pdSocksProxy.endpoint = null
        FoxholeVpnRuntimeBridge.updateI2pPhase(I2pPhaseSnapshot())
    }

    @Test
    fun `concurrent identical starts are serialized and reuse one child`() =
        runBlocking {
            val directory = Files.createTempDirectory("i2pd-serialized").toFile()
            val launchEntered = CountDownLatch(1)
            val releaseLaunch = CountDownLatch(1)
            val launches = AtomicInteger(0)
            val child = ControllableChildProcess()
            val manager =
                i2pdManager(directory, launch = {
                    launches.incrementAndGet()
                    launchEntered.countDown()
                    check(releaseLaunch.await(2, TimeUnit.SECONDS))
                    child
                })

            val first = async(Dispatchers.Default) { manager.ensureStarted() }
            assertTrue(launchEntered.await(1, TimeUnit.SECONDS))
            val second = async(Dispatchers.Default) { manager.ensureStarted() }
            Thread.sleep(30L)
            assertEquals(1, launches.get())

            releaseLaunch.countDown()
            assertEquals(first.await(), second.await())
            assertEquals(1, launches.get())
            assertTrue(child.isAlive)
            assertEquals(I2pdState.RUNNING, manager.snapshot().state)
            manager.stop()
            directory.deleteRecursively()
            Unit
        }

    @Test
    fun `kill during launch invalidates start and clears published globals`() =
        runBlocking {
            val directory = Files.createTempDirectory("i2pd-kill-launch").toFile()
            val launchEntered = CountDownLatch(1)
            val releaseLaunch = CountDownLatch(1)
            val child = ControllableChildProcess()
            val manager =
                i2pdManager(directory, launch = {
                    launchEntered.countDown()
                    check(releaseLaunch.await(2, TimeUnit.SECONDS))
                    child
                })

            val start = async(Dispatchers.Default) { manager.ensureStarted() }
            assertTrue(launchEntered.await(1, TimeUnit.SECONDS))
            manager.kill("test")
            releaseLaunch.countDown()

            val failure = runCatching { start.await() }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            assertFalse(child.isAlive)
            assertNull(I2pdWebConsole.endpoint)
            assertEquals(I2pNetworkPhase.OFFLINE, FoxholeVpnRuntimeBridge.i2pPhase.value.phase)
            assertEquals(I2pdState.KILLED, manager.snapshot().state)
            directory.deleteRecursively()
            Unit
        }

    @Test
    fun `awaitReady cannot accept probe from replaced generation`() =
        runBlocking {
            val directory = Files.createTempDirectory("i2pd-ready-generation").toFile()
            val probeEntered = CountDownLatch(1)
            val releaseProbe = CountDownLatch(1)
            val processes = ArrayDeque<ControllableChildProcess>()
            processes += ControllableChildProcess()
            processes += ControllableChildProcess()
            val manager =
                i2pdManager(
                    directory = directory,
                    launch = { processes.removeFirst() },
                    localPortAccepts = {
                        probeEntered.countDown()
                        check(releaseProbe.await(2, TimeUnit.SECONDS))
                        true
                    },
                )
            val firstEndpoints = manager.ensureStarted()

            val ready = async(Dispatchers.IO) { manager.awaitReady(2_000L) }
            assertTrue(probeEntered.await(1, TimeUnit.SECONDS))
            manager.ensureStarted(restartSettings())
            releaseProbe.countDown()

            assertFalse(ready.await())
            assertFalse(manager.confirmCarrierReady(firstEndpoints.generation))
            assertEquals(I2pNetworkPhase.STARTING, FoxholeVpnRuntimeBridge.i2pPhase.value.phase)
            assertEquals(I2pdState.RUNNING, manager.snapshot().state)
            manager.stop()
            directory.deleteRecursively()
            Unit
        }

    @Test
    fun `stale stdout cannot advance replacement phase`() =
        runBlocking {
            val directory = Files.createTempDirectory("i2pd-stale-watcher").toFile()
            val first = ControllableChildProcess(closeOutputOnTermination = false)
            val second = ControllableChildProcess()
            val processes = ArrayDeque<ControllableChildProcess>()
            processes += first
            processes += second
            val manager = i2pdManager(directory, launch = { processes.removeFirst() })

            manager.ensureStarted()
            manager.ensureStarted(restartSettings())
            first.emitStdout("Tunnel created")
            Thread.sleep(50L)
            first.closeStdout()

            assertEquals(I2pNetworkPhase.STARTING, FoxholeVpnRuntimeBridge.i2pPhase.value.phase)
            assertEquals(I2pdState.RUNNING, manager.snapshot().state)
            assertTrue(second.isAlive)
            manager.stop()
            directory.deleteRecursively()
            Unit
        }

    @Test
    fun `tunnel log stays building until pinned proxy readiness succeeds`() =
        runBlocking {
            val directory = Files.createTempDirectory("i2pd-honest-ready").toFile()
            val child = ControllableChildProcess()
            val manager =
                i2pdManager(
                    directory = directory,
                    launch = { child },
                    localPortAccepts = { true },
                )
            val endpoints = manager.ensureStarted()

            child.emitStdout("Exploratory tunnel created")
            awaitCondition {
                FoxholeVpnRuntimeBridge.i2pPhase.value.phase == I2pNetworkPhase.BUILDING_TUNNELS
            }

            assertEquals(I2pNetworkPhase.BUILDING_TUNNELS, FoxholeVpnRuntimeBridge.i2pPhase.value.phase)
            assertFalse(FoxholeVpnRuntimeBridge.i2pPhase.value.phase.networkUp)
            assertTrue(manager.awaitReady(100L))
            // An authenticated loopback proxy is only half of readiness: until the VPN service
            // confirms that this exact endpoint generation is embedded in an applied TUN, the
            // public phase must stay non-connected.
            assertEquals(I2pNetworkPhase.BUILDING_TUNNELS, FoxholeVpnRuntimeBridge.i2pPhase.value.phase)
            assertTrue(manager.confirmCarrierReady(endpoints.generation))
            assertEquals(I2pNetworkPhase.CONNECTED, FoxholeVpnRuntimeBridge.i2pPhase.value.phase)
            assertTrue(FoxholeVpnRuntimeBridge.i2pPhase.value.phase.networkUp)
            manager.markCarrierUnavailable()
            assertEquals(I2pNetworkPhase.BUILDING_TUNNELS, FoxholeVpnRuntimeBridge.i2pPhase.value.phase)
            assertFalse(FoxholeVpnRuntimeBridge.i2pPhase.value.phase.networkUp)
            manager.stop()
            directory.deleteRecursively()
            Unit
        }

    @Test
    fun `immediate exit clears endpoint phase and fingerprint before retry`() =
        runBlocking {
            val directory = Files.createTempDirectory("i2pd-immediate-exit").toFile()
            val processes = ArrayDeque<ControllableChildProcess>()
            processes += ControllableChildProcess(initiallyAlive = false)
            val replacement = ControllableChildProcess()
            processes += replacement
            val launches = AtomicInteger(0)
            val manager =
                i2pdManager(directory, launch = {
                    launches.incrementAndGet()
                    processes.removeFirst()
                })

            val failure = runCatching { manager.ensureStarted() }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertEquals(I2pdState.ERROR, manager.snapshot().state)
            assertNull(I2pdWebConsole.endpoint)
            assertEquals(I2pNetworkPhase.OFFLINE, FoxholeVpnRuntimeBridge.i2pPhase.value.phase)

            manager.ensureStarted()

            assertEquals(2, launches.get())
            assertEquals(I2pdState.RUNNING, manager.snapshot().state)
            assertTrue(replacement.isAlive)
            manager.stop()
            directory.deleteRecursively()
            Unit
        }

    @Test
    fun `stop timeout retains orphan handle and clears endpoint phase fingerprint`() =
        runBlocking {
            val directory = Files.createTempDirectory("i2pd-stop-timeout").toFile()
            val child =
                ControllableChildProcess(
                    gracefulDestroyTerminates = false,
                    forcibleDestroyTerminates = false,
                )
            val manager = i2pdManager(directory, launch = { child })
            manager.ensureStarted()

            manager.stop(
                I2pdStopPolicy(
                    processDestroyTimeoutMs = 1L,
                    processDestroyForciblyTimeoutMs = 1L,
                ),
            )

            assertEquals(I2pdState.ERROR, manager.snapshot().state)
            assertNull(I2pdWebConsole.endpoint)
            assertEquals(I2pNetworkPhase.OFFLINE, FoxholeVpnRuntimeBridge.i2pPhase.value.phase)
            assertEquals(1, child.destroyForciblyCalls.get())

            manager.kill("retry")

            assertEquals(2, child.destroyForciblyCalls.get())
            assertEquals(I2pdState.ERROR, manager.snapshot().state)
            child.terminate()
            manager.stop()
            directory.deleteRecursively()
            Unit
        }

    private fun i2pdManager(
        directory: File,
        launch: () -> Process,
        localPortAccepts: (Int) -> Boolean = { false },
        clientTunnels: Int = I2PD_MIN_CLIENT_TUNNELS,
    ): I2pdProcessManager {
        val nextPort = AtomicInteger(21_000)
        return I2pdProcessManager(
            prepareRuntime = {
                I2pdRuntimePaths(
                    executablePath = "/test/i2pd",
                    dataDirectory = directory.absolutePath,
                )
            },
            diagnosticsLogger = NoOpRuntimeDiagnosticsSink,
            isMeteredNetwork = { false },
            hooks = I2pdProcessManagerHooks(
                processLauncher = RuntimeChildProcessLauncher { _, _ -> launch() },
                allocateLoopbackPort = nextPort::incrementAndGet,
                localPortAccepts = localPortAccepts,
                clientTunnelCount = { clientTunnels },
                generateWebConsolePassword = { "test-password" },
            ),
        )
    }

    private fun restartSettings(): I2pSettings =
        I2pSettings(
            relayTransitTraffic = true,
            allowRelayOnCellular = true,
        )
}
