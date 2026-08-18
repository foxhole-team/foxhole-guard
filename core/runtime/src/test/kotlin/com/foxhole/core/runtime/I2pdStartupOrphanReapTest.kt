package com.foxhole.core.runtime

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class I2pdStartupOrphanReapTest {
    @Before
    fun resetProcessLatch() = I2pdStartupOrphanReap.resetForTest()

    private class StubProcess : Process() {
        override fun getOutputStream() = java.io.OutputStream.nullOutputStream()

        override fun getInputStream() =
            object : java.io.InputStream() {
                override fun read(): Int {
                    Thread.sleep(60_000)
                    return -1
                }
            }

        override fun getErrorStream() = ByteArrayInputStream(ByteArray(0))

        override fun waitFor(): Int = 0

        override fun exitValue(): Int = 0

        override fun destroy() = Unit

        override fun isAlive(): Boolean = true
    }

    private class StubGate(override val port: Int) : I2pdSocksGate {
        override fun close() = Unit
    }

    private fun manager(
        directory: File,
        reaps: AtomicInteger,
        seen: MutableList<Pair<String, String>>,
    ): I2pdProcessManager {
        val nextPort = AtomicInteger(31_000)
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
                processLauncher = RuntimeChildProcessLauncher { _, _ -> StubProcess() },
                allocateLoopbackPort = nextPort::incrementAndGet,
                localPortAccepts = { true },
                generateWebConsolePassword = { "test-password" },
                reapStartupOrphans = { executable, dataRoot ->
                    seen += executable to dataRoot
                    reaps.incrementAndGet()
                    1
                },
                startSocksCredentialGate = { _, _, _, _ -> StubGate(nextPort.incrementAndGet()) },
            ),
        )
    }

    @Test
    fun `the first start of an app process reaps an i2pd orphaned by the last one`() =
        runBlocking {
            val directory = File.createTempFile("i2pd-reap", "").let { file ->
                file.delete()
                file.mkdirs()
                file
            }
            val reaps = AtomicInteger(0)
            val seen = mutableListOf<Pair<String, String>>()
            val manager = manager(directory, reaps, seen)

            manager.ensureStarted()

            assertEquals("a fresh app process must look for an orphan exactly once", 1, reaps.get())
            assertEquals(
                "matched on this app's own private executable and data directory, so the scan " + "cannot reach another application's process",
                "/test/i2pd" to directory.absolutePath,
                seen.single(),
            )
            manager.stop()
            directory.deleteRecursively()
            Unit
        }

    @Test
    fun `a later start in the same app process never reaps again`() =
        runBlocking {
            val directory = File.createTempFile("i2pd-reap-twice", "").let { file ->
                file.delete()
                file.mkdirs()
                file
            }
            val reaps = AtomicInteger(0)
            val manager = manager(directory, reaps, mutableListOf())

            manager.ensureStarted()
            assertEquals(1, reaps.get())

            manager.ensureStarted(
                com.foxhole.core.model.I2pSettings(relayTransitTraffic = true),
            )
            assertEquals(
                "the reap is once per app process; a router this process owns is never a candidate",
                1,
                reaps.get(),
            )

            manager.stop()
            directory.deleteRecursively()
            Unit
        }

    @Test
    fun `the published socks port is the credential gate rather than the router`() =
        runBlocking {
            val directory = File.createTempFile("i2pd-gate-port", "").let { file ->
                file.delete()
                file.mkdirs()
                file
            }
            val routerPorts = mutableListOf<Int>()
            val nextPort = AtomicInteger(41_000)
            val manager = I2pdProcessManager(
                prepareRuntime = {
                    I2pdRuntimePaths(
                        executablePath = "/test/i2pd",
                        dataDirectory = directory.absolutePath,
                    )
                },
                diagnosticsLogger = NoOpRuntimeDiagnosticsSink,
                isMeteredNetwork = { false },
                hooks = I2pdProcessManagerHooks(
                    processLauncher = RuntimeChildProcessLauncher { _, _ -> StubProcess() },
                    allocateLoopbackPort = nextPort::incrementAndGet,
                    localPortAccepts = { true },
                    generateWebConsolePassword = { "test-password" },
                    reapStartupOrphans = { _, _ -> 0 },
                    startSocksCredentialGate = { routerPort, _, _, _ ->
                        routerPorts += routerPort
                        StubGate(59_999)
                    },
                ),
            )

            val endpoints = manager.ensureStarted()

            assertEquals(
                "callers are handed the authenticated gate, not i2pd's own listener",
                59_999,
                endpoints.socksPort,
            )
            assertEquals(
                "and the engine's socks outbound is pointed at the same one",
                59_999,
                I2pdSocksProxy.endpoint?.port,
            )
            val routerPort = routerPorts.single()
            val confSocksPort = File(directory, "i2pd.conf").readLines().portUnder("[socksproxy]")
            assertEquals(
                "while i2pd.conf still binds the router's own private port",
                routerPort,
                confSocksPort,
            )
            val confHttpProxyPort = File(directory, "i2pd.conf").readLines().portUnder("[httpproxy]")
            assertNotEquals(
                "the http proxy is i2pd's own listener, not the gate callers are handed",
                59_999,
                confHttpProxyPort,
            )
            assertNotEquals("and it is not the socks listener either", confSocksPort, confHttpProxyPort)

            manager.stop()
            directory.deleteRecursively()
            Unit
        }

    private fun List<String>.portUnder(section: String): Int {
        val start = indexOf(section)
        check(start >= 0) { "$section missing from i2pd.conf" }
        return subList(start, size)
            .first { line -> line.startsWith("port = ") }
            .removePrefix("port = ")
            .trim()
            .toInt()
    }
}
