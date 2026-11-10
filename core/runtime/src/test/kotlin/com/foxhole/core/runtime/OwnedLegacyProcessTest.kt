package com.foxhole.core.runtime

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

class OwnedLegacyProcessTest {
    @Test
    fun exactArgvEnvironmentDirectoryAndStreamBytesSurviveStartup() {
        val platform = Platform()
        val argv = listOf("/private/runtime", "space arg", "quote'arg", "${'$'}(never-executed)", "line\narg", "")
        val builder = ProcessBuilder(argv).directory(File("private-working")).redirectErrorStream(true)
        builder.environment()["FOXHOLE_TEST_MARKER"] = "exact value"
        val process = startRuntimeProcess(builder, platform.control(), 24, platform)
        assertEquals(argv, builder.command())
        assertEquals(listOf("/system/bin/sh", "-c", LEGACY_OWNED_EXEC, "foxhole-owned-exec", NONCE) + argv,
            platform.spawnedCommand)
        assertEquals(File("private-working"), platform.spawnedDirectory)
        assertEquals("exact value", platform.spawnedEnvironmentMarker)
        assertTrue(platform.redirectedError)
        assertArrayEquals(PAYLOAD, process.inputStream.readBytes())
        process.outputStream.write("target stdin".toByteArray())
        assertEquals("$NONCE:ACK\ntarget stdin", platform.child.stdin.toString("US-ASCII"))
        assertEquals(1, platform.child.execs)
        platform.child.alive = false
        process.exitValue()
    }

    @Test
    fun aShortLivedTargetKeepsItsBufferedOutputAfterTheReadyFrame() {
        val platform = Platform().apply { exitOnExec = true }
        val owned = platform.launch()
        assertFalse(owned.isAliveCompat(24))
        assertArrayEquals(PAYLOAD, owned.inputStream.readBytes())
        owned.destroyForciblyCompat(24)
        assertTrue(platform.signalled.isEmpty())
    }

    @Test
    fun cancellationAndOwnerStopBeforeAckNeverExecTheTarget() {
        for (cancelAtSpawn in listOf(false, true)) {
            val platform = Platform()
            var stopped = false
            val control = platform.control(
                checkActive = { if (stopped) throw CancellationException("owner stopped") },
                onSpawn = { if (cancelAtSpawn) stopped = true },
                authorize = { commit -> stopped = true; commit() },
            )
            assertTrue(runCatching { platform.launch(control) }.exceptionOrNull() is CancellationException)
            assertEquals(0, platform.child.execs)
            assertTrue(platform.child.stdinClosed)
            assertFalse(platform.child.alive)
            assertTrue(platform.signalled.isEmpty())
        }
    }

    @Test
    fun deadlineBeforeAckAndMissingStartupDataNeverExecTheTarget() {
        for (noPidFrame in listOf(false, true)) {
            val platform = Platform()
            if (noPidFrame) platform.initialFrame = ""
            val control = platform.control(authorize = { commit -> platform.now = 10_000_000L; commit() })
            assertTrue(runCatching { platform.launch(control) }.exceptionOrNull() is IOException)
            assertEquals(0, platform.child.execs)
            assertTrue(platform.child.stdinClosed)
            assertFalse(platform.child.alive)
            assertTrue(platform.now <= 10_000_000L)
        }
    }

    @Test
    fun malformedAndOversizedFramesNeverGrantExecOrSignalAnUnboundPid() {
        for (frame in listOf("wrong:101\n", "$NONCE:+101\n", "$NONCE:000101\n", "x".repeat(128) + "\n")) {
            val platform = Platform().apply { initialFrame = frame }
            assertTrue(runCatching { platform.launch() }.exceptionOrNull() is IOException)
            assertEquals(0, platform.child.execs)
            assertTrue(platform.signalled.isEmpty())
            assertFalse(platform.child.alive)
        }
    }

    @Test
    fun wrongUidOrParentIdentityNeverReceivesAck() {
        for (identity in listOf(IDENTITY.copy(uid = 999), IDENTITY.copy(parentPid = 999))) {
            val platform = Platform().apply { currentIdentity = identity }
            assertTrue(runCatching { platform.launch() }.exceptionOrNull() is IOException)
            assertEquals(0, platform.child.execs)
            assertTrue(platform.signalled.isEmpty())
            assertTrue(platform.child.stdinClosed)
        }
    }

    @Test
    fun stopAtAckBoundaryOwnsAndKillsTheWaitingChildWithoutExec() {
        val platform = Platform()
        lateinit var pending: Process
        val control = platform.control(onSpawn = { pending = it }, authorize = { commit ->
            pending.destroyForciblyCompat(24)
            commit()
        })
        assertTrue(runCatching { platform.launch(control) }.isFailure)
        assertEquals(listOf(101), platform.signalled)
        assertEquals(0, platform.child.execs)
        assertFalse(platform.child.alive)
    }

    @Test
    fun threadInterruptionAtAckNeverExecutesTheTarget() {
        val wasInterrupted = Thread.interrupted()
        try {
            val platform = Platform()
            val control = platform.control(authorize = { commit -> Thread.currentThread().interrupt(); commit() })
            assertTrue(runCatching { platform.launch(control) }.exceptionOrNull() is InterruptedException)
            assertEquals(0, platform.child.execs)
            assertFalse(platform.child.alive)
        } finally {
            Thread.interrupted()
            if (wasInterrupted) Thread.currentThread().interrupt()
        }
    }

    @Test
    fun changedOrMissingLiveIdentityFailsClosedWithoutAnySignal() {
        for (replacement in listOf(null, IDENTITY.copy(uid = 999), IDENTITY.copy(startTicks = 987),
            IDENTITY.copy(parentPid = 999))) {
            val platform = Platform()
            val owned = platform.launch()
            platform.currentIdentity = replacement
            assertTrue(runCatching { owned.destroyForciblyCompat(24) }.exceptionOrNull() is IOException)
            assertTrue(platform.signalled.isEmpty())
            assertTrue(platform.child.alive)
            // Fixture cleanup changes only its fake child; no native process exists in this test.
            platform.child.alive = false
            owned.exitValue()
        }
    }

    @Test
    fun forceUsesTheVerifiedReceiptEvenWhenTheTargetIgnoresSigterm() {
        val platform = Platform()
        val owned = platform.launch()
        platform.child.ignoreTerm = true
        assertSame(owned, owned.destroyForciblyCompat(25))
        assertEquals(listOf(101), platform.signalled)
        assertEquals(0, platform.child.destroyCalls)
        assertFalse(platform.child.alive)
        owned.exitValue()
    }

    @Test
    fun anExitedChildIsNeverSignalledAndItsReceiptCannotBeReused() {
        val platform = Platform()
        val owned = platform.launch()
        platform.child.alive = false
        owned.exitValue()
        owned.destroyForciblyCompat(24)
        assertTrue(platform.signalled.isEmpty())
        platform.child.alive = true
        assertTrue(runCatching { owned.destroyForciblyCompat(24) }.exceptionOrNull() is IOException)
        assertTrue(platform.signalled.isEmpty())
        platform.child.alive = false
    }

    @Test
    fun noReadyFrameAfterAckForcesTheOwnedChildWithinTheSameDeadline() {
        val platform = Platform().apply { readyFrame = "" }
        assertTrue(runCatching { platform.launch() }.exceptionOrNull() is IOException)
        assertEquals(1, platform.child.execs)
        assertEquals(listOf(101), platform.signalled)
        assertFalse(platform.child.alive)
        assertTrue(platform.now <= 10_000_000L)
    }

    @Test
    fun legacyForceRejectsAnUnownedProcessInsteadOfCallingGracefulDestroy() {
        val child = Child("", "", byteArrayOf())
        assertTrue(runCatching { child.destroyForciblyCompat(24) }.exceptionOrNull() is IOException)
        assertEquals(0, child.destroyCalls)
        assertTrue(child.alive)
    }

    @Test
    fun kernelMetadataParsingBindsPidParentAllUidsAndStartTicks() {
        val stat = "101 (name with ) parens) S 50 " + List(17) { "0" }.joinToString(" ") + " 123 0\n"
        val status = "Pid:\t101\nPPid:\t50\nUid:\t10055\t10055\t10055\t10055\n"
        assertEquals(IDENTITY, parseOwnedProcessIdentity(101, stat, status))
        assertEquals(null, parseOwnedProcessIdentity(102, stat, status))
        assertEquals(null, parseOwnedProcessIdentity(101, stat, status.replace("10055\t10055\n", "10055\t999\n")))
        assertEquals(null, parseOwnedProcessIdentity(101, stat, status + "Uid:\t10055 10055 10055 10055\n"))
    }

    internal class Platform : LegacyProcessPlatform {
        override val selfPid = 50
        override val selfUid = 10055
        var now = 0L
        var currentIdentity: OwnedProcessIdentity? = IDENTITY
        var initialFrame = "$NONCE:101\n"
        var readyFrame = "$NONCE:READY\n"
        lateinit var child: Child
        var spawnedCommand: List<String>? = null
        var spawnedDirectory: File? = null
        var spawnedEnvironmentMarker: String? = null
        var redirectedError = false
        var exitOnExec = false
        var onIdentity: (() -> Unit)? = null
        val signalled = mutableListOf<Int>()
        override fun nonce() = NONCE
        override fun pause(nanos: Long) { now += nanos }
        override fun identity(pid: Int): OwnedProcessIdentity? {
            val hook = onIdentity
            onIdentity = null
            hook?.invoke()
            return currentIdentity?.takeIf { it.pid == pid }
        }
        override fun start(builder: ProcessBuilder): Process {
            spawnedCommand = builder.command().toList()
            spawnedDirectory = builder.directory()
            spawnedEnvironmentMarker = builder.environment()["FOXHOLE_TEST_MARKER"]
            redirectedError = builder.redirectErrorStream()
            child = Child(initialFrame, readyFrame, PAYLOAD, exitOnExec)
            return child
        }
        override fun kill(pid: Int) { signalled += pid; child.alive = false }
        fun control(
            checkActive: () -> Unit = {},
            authorize: ((() -> Unit) -> Unit) = { it() },
            onSpawn: (Process) -> Unit = {},
        ) = RuntimeProcessLaunchControl(RuntimeLaunchDeadline(10) { now }, checkActive, authorize, onSpawn, retainFailed = {})
        fun launch(control: RuntimeProcessLaunchControl = control()): Process =
            startRuntimeProcess(ProcessBuilder("/private/runtime"), control, 24, this)
    }

    internal class QueueInput : InputStream() {
        private val data = ArrayList<Byte>()
        private var offset = 0
        fun append(bytes: ByteArray) { data += bytes.toList() }
        override fun available(): Int = data.size - offset
        override fun read(): Int = if (offset == data.size) -1 else data[offset++].toInt() and 255
    }

    internal class Child(initial: String, private val ready: String, private val payload: ByteArray,
        private val exitOnExec: Boolean = false) : Process() {
        val stdout = QueueInput().apply { append(initial.toByteArray(Charsets.US_ASCII)) }
        var alive = true
        var execs = 0
        var destroyCalls = 0
        var ignoreTerm = false
        var stdinClosed = false
        val stdin = object : ByteArrayOutputStream() {
            override fun write(bytes: ByteArray, off: Int, len: Int) {
                super.write(bytes, off, len)
                if (execs == 0 && toString("US-ASCII") == "$NONCE:ACK\n") {
                    execs++
                    stdout.append(ready.toByteArray(Charsets.US_ASCII))
                    if (ready.isNotEmpty()) stdout.append(payload)
                    if (exitOnExec) alive = false
                }
            }
            override fun close() { stdinClosed = true; if (execs == 0) alive = false }
        }
        override fun getInputStream(): InputStream = stdout
        override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream() = stdin
        override fun waitFor(): Int = error("unbounded fixture wait")
        override fun exitValue(): Int = if (alive) throw IllegalThreadStateException() else 0
        override fun destroy() { destroyCalls++; if (!ignoreTerm) alive = false }
        override fun destroyForcibly(): Process = error("legacy must not resolve Process.destroyForcibly")
    }

    companion object {
        private const val NONCE = "0123456789abcdef0123456789abcdef"
        private val PAYLOAD = byteArrayOf(0, 1, 10, 127, -1)
        private val IDENTITY = OwnedProcessIdentity(101, 50, 10055, 123, 'S')
    }
}
