package com.foxhole.core.runtime

import com.foxhole.core.model.I2pSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class I2pdLegacyOwnedStartupTest {
    @Test
    fun actorStopSeesThePendingOwnedChildAndWinsBeforeAck() = runBlocking {
        val directory = Files.createTempDirectory("i2pd-legacy-owned-start-").toFile()
        val platform = OwnedLegacyProcessTest.Platform()
        val nextPort = AtomicInteger(21000)
        lateinit var manager: I2pdProcessManager
        val launcher = object : RuntimeChildProcessLauncher {
            override fun launch(command: List<String>, workingDirectory: File): Process =
                error("legacy startup must carry the actor's control")
            override fun launch(command: List<String>, workingDirectory: File, control: RuntimeProcessLaunchControl): Process =
                startRuntimeProcess(ProcessBuilder(command).directory(workingDirectory).redirectErrorStream(true),
                    control, 24, platform)
        }
        manager = I2pdProcessManager(
            prepareRuntime = { I2pdRuntimePaths("/test/i2pd", directory.absolutePath) },
            diagnosticsLogger = NoOpRuntimeDiagnosticsSink,
            isMeteredNetwork = { false },
            hooks = I2pdProcessManagerHooks(
                processLauncher = launcher,
                allocateLoopbackPort = nextPort::incrementAndGet,
                localPortAccepts = { false },
                generateWebConsolePassword = { "test-password" },
                reapStartupOrphans = { _, _ -> 0 },
                startSocksCredentialGate = { _, _, _, _ -> error("stopped startup must not publish a SOCKS gate") },
            ),
        )
        platform.onIdentity = { manager.kill("test-stop-before-ack") }
        try {
            val failure = runCatching { manager.ensureStarted(I2pSettings()) }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            assertEquals(0, platform.child.execs)
            assertFalse(platform.child.alive)
            assertTrue(platform.child.stdinClosed)
            assertNull(I2pdSocksProxy.endpoint)
            assertNull(I2pdWebConsole.endpoint)
            assertTrue(manager.snapshot().state != I2pdState.RUNNING)
        } finally {
            manager.kill("test-cleanup")
            directory.deleteRecursively()
        }
    }
}
