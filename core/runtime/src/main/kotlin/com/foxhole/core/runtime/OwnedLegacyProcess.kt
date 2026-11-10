package com.foxhole.core.runtime

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal class RuntimeLaunchDeadline(
    timeoutMs: Long,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val started = nanoTime()
    private val budget = TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(0L))

    fun remainingNanos(): Long = (budget - (nanoTime() - started)).coerceAtLeast(0L)

    fun requireRemaining() {
        if (remainingNanos() == 0L) throw IOException("runtime process startup timed out")
    }
}

internal class RuntimeProcessLaunchControl(
    val deadline: RuntimeLaunchDeadline,
    val checkActive: () -> Unit,
    val authorizeExec: ((() -> Unit) -> Unit),
    val onSpawn: (Process) -> Unit,
    val retainFailed: (OwnedLegacyProcess) -> Unit = LegacyProcessRecovery::retain,
) {
    fun checkStartup() {
        if (Thread.interrupted()) throw InterruptedException()
        checkActive()
        deadline.requireRemaining()
    }
}

internal data class OwnedProcessIdentity(
    val pid: Int,
    val parentPid: Int,
    val uid: Int,
    val startTicks: Long,
    val state: Char,
) {
    fun sameOwner(other: OwnedProcessIdentity): Boolean =
        pid == other.pid && parentPid == other.parentPid && uid == other.uid && startTicks == other.startTicks

    val exited: Boolean get() = state == 'Z' || state == 'X'
}

internal interface LegacyProcessPlatform {
    val selfPid: Int
    val selfUid: Int
    fun start(builder: ProcessBuilder): Process
    fun identity(pid: Int): OwnedProcessIdentity?
    fun kill(pid: Int)
    fun nonce(): String
    fun pause(nanos: Long)
}

internal object AndroidLegacyProcessPlatform : LegacyProcessPlatform {
    override val selfPid: Int get() = android.os.Process.myPid()
    override val selfUid: Int get() = android.os.Process.myUid()
    override fun start(builder: ProcessBuilder): Process = builder.start()
    override fun kill(pid: Int) = android.os.Process.killProcess(pid)
    override fun nonce(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    override fun pause(nanos: Long) = TimeUnit.NANOSECONDS.sleep(nanos)

    override fun identity(pid: Int): OwnedProcessIdentity? = try {
        parseOwnedProcessIdentity(pid,
            readKernelText(File("/proc/$pid/stat"), 4096),
            readKernelText(File("/proc/$pid/status"), 16384))
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }
}

private fun readKernelText(file: File, limit: Int): String = file.inputStream().use { input ->
    val buffer = ByteArray(limit + 1)
    var used = 0
    while (used < buffer.size) {
        val read = input.read(buffer, used, buffer.size - used)
        if (read < 0) break
        if (read == 0) throw IOException("runtime process metadata did not progress")
        used += read
    }
    if (used == 0 || used > limit) throw IOException("runtime process metadata is outside its bound")
    String(buffer, 0, used, Charsets.UTF_8)
}

internal fun parseOwnedProcessIdentity(pid: Int, stat: String, status: String): OwnedProcessIdentity? {
    if (pid <= 0 || !stat.startsWith("$pid (")) return null
    val closing = stat.lastIndexOf(')')
    if (closing < 0) return null
    val fields = stat.substring(closing + 1).trim().split(Regex("\\s+"))
    if (fields.size < 20) return null
    val state = fields[0].singleOrNull() ?: return null
    val parent = fields[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
    val ticks = fields[19].toLongOrNull()?.takeIf { it > 0 } ?: return null
    val metadata = mutableMapOf<String, List<String>>()
    status.lineSequence().forEach { line ->
        val key = line.substringBefore(':')
        if (key in setOf("Pid", "PPid", "Uid")) {
            if (key in metadata) return null
            metadata[key] = line.substringAfter(':').trim().split(Regex("\\s+"))
        }
    }
    if (metadata["Pid"] != listOf(pid.toString()) || metadata["PPid"] != listOf(parent.toString())) return null
    val uids = metadata["Uid"]?.map { it.toIntOrNull() ?: return null } ?: return null
    if (uids.size != 4 || uids.first() < 0 || uids.any { it != uids.first() }) return null
    return OwnedProcessIdentity(pid, parent, uids.first(), ticks, state)
}

internal object LegacyProcessRecovery {
    private val retained = ConcurrentHashMap.newKeySet<OwnedLegacyProcess>()

    fun retain(process: OwnedLegacyProcess) {
        retained += process
    }

    fun retire(process: OwnedLegacyProcess) { retained -= process }

    fun requireClear() {
        var failure: Throwable? = null
        retained.toList().forEach { process ->
            try { process.forceOwned() } catch (error: Throwable) { failure = error }
            if (!process.isAliveCompat(24)) {
                try {
                    process.closeStreams()
                    retained -= process
                } catch (error: Throwable) {
                    retained += process
                    failure = error
                }
            }
        }
        if (retained.isNotEmpty()) throw IOException("owned runtime process cleanup is pending", failure)
    }
}

// Only startup framing belongs to the shell. Successful exec keeps the same owned kernel identity.
internal const val LEGACY_OWNED_EXEC = """nonce=${'$'}1
shift
printf '%s:%s\n' "${'$'}nonce" "${'$'}${'$'}" || exit 125
IFS= read -r ack || exit 125
[ "${'$'}ack" = "${'$'}nonce:ACK" ] || exit 125
printf '%s:READY\n' "${'$'}nonce" || exit 125
exec "${'$'}@"
"""

internal fun startRuntimeProcess(
    builder: ProcessBuilder,
    control: RuntimeProcessLaunchControl,
    sdkInt: Int = runtimeProcessPlatformSdk(),
    platform: LegacyProcessPlatform = AndroidLegacyProcessPlatform,
): Process {
    if (sdkInt >= 26) return builder.start().also(control.onSpawn)
    LegacyProcessRecovery.requireClear()
    control.checkStartup()
    val nonce = platform.nonce()
    check(Regex("[0-9a-f]{32}").matches(nonce)) { "invalid runtime startup nonce" }
    val command = builder.command().toList()
    require(command.isNotEmpty() && command.first().isNotEmpty())
    control.checkStartup()
    val delegate = try {
        builder.command(listOf("/system/bin/sh", "-c", LEGACY_OWNED_EXEC, "foxhole-owned-exec", nonce) + command)
        platform.start(builder)
    } finally {
        builder.command(command)
    }
    val owned = OwnedLegacyProcess(delegate, nonce, platform)
    try {
        control.onSpawn(owned)
        owned.completeStartup(control)
        return owned
    } catch (failure: Throwable) {
        try { owned.abortStartup() } catch (cleanup: Throwable) {
            if (cleanup !== failure) failure.addSuppressed(cleanup)
        }
        try { control.retainFailed(owned) } catch (cleanup: Throwable) {
            if (cleanup !== failure) failure.addSuppressed(cleanup)
        }
        throw failure
    }
}

internal class OwnedLegacyProcess internal constructor(
    private val delegate: Process,
    private val nonce: String,
    private val platform: LegacyProcessPlatform,
) : Process() {
    private val stdin = delegate.outputStream
    private val stdout = delegate.inputStream
    private val stderr = delegate.errorStream
    private var receipt: OwnedProcessIdentity? = null
    private var acknowledged = false
    private var ready = false
    private var aborted = false

    internal fun completeStartup(control: RuntimeProcessLaunchControl) {
        synchronized(delegate) {
            check(!acknowledged && !ready && !aborted && receipt == null) { "owned runtime startup is not fresh" }
        }
        val line = readStartupLine(control)
        val pid = line.removePrefix("$nonce:").toIntOrNull()
        if (!Regex("$nonce:[1-9][0-9]{0,9}").matches(line) || pid == null || pid <= 0) throw IOException("invalid owned runtime startup response")
        val identity = platform.identity(pid) ?: throw IOException("owned runtime process identity is unavailable")
        control.checkStartup()
        if (identity.pid != pid || pid == platform.selfPid || identity.uid != platform.selfUid || identity.parentPid != platform.selfPid || identity.exited) {
            throw IOException("owned runtime process identity differs")
        }
        synchronized(delegate) {
            if (aborted || !delegate.isAliveCompat(24)) throw IOException("owned runtime startup was stopped")
            receipt = identity
        }
        control.authorizeExec {
            synchronized(delegate) {
                control.checkStartup()
                verifyLiveOwner()
                if (aborted) throw IOException("owned runtime startup was stopped")
                if (acknowledged) throw IOException("owned runtime startup was already authorized")
                stdin.write("$nonce:ACK\n".toByteArray(Charsets.US_ASCII))
                stdin.flush()
                acknowledged = true
            }
        }
        if (!synchronized(delegate) { acknowledged }) throw IOException("owned runtime startup was not authorized")
        if (readStartupLine(control) != "$nonce:READY") throw IOException("invalid owned runtime ready response")
        control.checkStartup()
        synchronized(delegate) {
            if (aborted) throw IOException("owned runtime startup was stopped")
            // A short-lived target may already have exited after its exact READY frame.
            if (!delegate.isAliveCompat(24)) retire()
            ready = true
        }
    }

    private fun readStartupLine(control: RuntimeProcessLaunchControl): String {
        val bytes = ArrayList<Byte>(80)
        val input = stdout
        while (true) {
            control.checkStartup()
            synchronized(delegate) { if (aborted) throw IOException("owned runtime startup was stopped") }
            if (input.available() == 0) {
                if (!delegate.isAliveCompat(24)) throw IOException("owned runtime exited before ready")
                platform.pause(minOf(control.deadline.remainingNanos(), 2_000_000L))
                continue
            }
            val value = input.read()
            if (value == 10) return bytes.toByteArray().toString(Charsets.US_ASCII)
            if (value !in 32..126 || bytes.size >= 127) throw IOException("invalid owned runtime startup frame")
            bytes += value.toByte()
        }
    }

    private fun verifyLiveOwner(): OwnedProcessIdentity {
        val expected = receipt ?: throw IOException("runtime process has no owned spawn receipt")
        if (!delegate.isAliveCompat(24)) { retire(); throw IOException("owned runtime process has exited") }
        val actual = platform.identity(expected.pid) ?: throw IOException("owned runtime process identity is unavailable")
        if (!expected.sameOwner(actual) || actual.exited || actual.uid != platform.selfUid || actual.parentPid != platform.selfPid) {
            throw IOException("owned runtime process identity changed")
        }
        if (!delegate.isAliveCompat(24)) { retire(); throw IOException("owned runtime process has exited") }
        return actual
    }

    internal fun forceOwned() = synchronized(delegate) {
        if (!delegate.isAliveCompat(24)) { retire(); return@synchronized }
        if (!acknowledged && receipt == null) {
            abortWaitingShell()
            return@synchronized
        }
        val actual = verifyLiveOwner()
        aborted = true
        platform.kill(actual.pid)
    }

    internal fun abortStartup() = synchronized(delegate) {
        aborted = true
        if (!delegate.isAliveCompat(24)) { retire(); return@synchronized }
        if (acknowledged) forceOwned() else abortWaitingShell()
    }

    private fun abortWaitingShell() {
        aborted = true
        try { stdin.close() } finally { delegate.destroy() }
    }

    private fun retire() {
        receipt = null
        LegacyProcessRecovery.retire(this)
    }

    internal fun closeStreams() {
        check(!delegate.isAliveCompat(24)) { "owned runtime child is still running" }
        var failure: Throwable? = null
        listOf<java.io.Closeable>(stdout, stdin, stderr).forEach { stream ->
            try { stream.close() } catch (error: Throwable) {
                if (failure == null) failure = error else if (failure !== error) failure?.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }

    override fun getInputStream(): InputStream = synchronized(delegate) {
        check(ready) { "runtime process is not ready" }
        stdout
    }
    override fun getOutputStream() = synchronized(delegate) {
        check(ready) { "runtime process is not ready" }
        stdin
    }
    override fun getErrorStream(): InputStream = stderr
    override fun waitFor(): Int = delegate.waitFor().also { synchronized(delegate) { retire() } }
    override fun exitValue(): Int = synchronized(delegate) { delegate.exitValue().also { retire() } }
    override fun destroy() = synchronized(delegate) {
        if (!acknowledged) abortWaitingShell() else delegate.destroy()
    }
}
