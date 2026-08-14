package com.foxhole.core.runtime

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal class ControllableChildProcess(
    initiallyAlive: Boolean = true,
    private val gracefulDestroyTerminates: Boolean = true,
    private val forcibleDestroyTerminates: Boolean = true,
    private val closeOutputOnTermination: Boolean = true,
) : Process() {
    private val alive = AtomicBoolean(initiallyAlive)
    private val terminated = CountDownLatch(if (initiallyAlive) 1 else 0)
    private val stdin = ByteArrayOutputStream()
    private val stdout = PipedInputStream()
    private val stdoutWriter = PipedOutputStream(stdout)
    private val stderr = ByteArrayInputStream(ByteArray(0))

    val destroyCalls = AtomicInteger(0)
    val destroyForciblyCalls = AtomicInteger(0)

    @Volatile
    private var exitCode = 0

    init {
        if (!initiallyAlive) {
            stdoutWriter.close()
        }
    }

    override fun getOutputStream(): OutputStream = stdin

    override fun getInputStream(): InputStream = stdout

    override fun getErrorStream(): InputStream = stderr

    override fun waitFor(): Int {
        terminated.await()
        return exitCode
    }

    override fun waitFor(
        timeout: Long,
        unit: TimeUnit,
    ): Boolean = terminated.await(timeout, unit)

    override fun exitValue(): Int {
        if (alive.get()) {
            throw IllegalThreadStateException("process is still alive")
        }
        return exitCode
    }

    override fun destroy() {
        destroyCalls.incrementAndGet()
        if (gracefulDestroyTerminates) {
            terminate()
        }
    }

    override fun destroyForcibly(): Process {
        destroyForciblyCalls.incrementAndGet()
        if (forcibleDestroyTerminates) {
            terminate()
        }
        return this
    }

    override fun isAlive(): Boolean = alive.get()

    fun emitStdout(line: String) {
        stdoutWriter.write("$line\n".toByteArray())
        stdoutWriter.flush()
    }

    fun terminate(code: Int = 0) {
        exitCode = code
        if (alive.compareAndSet(true, false)) {
            terminated.countDown()
        }
        if (closeOutputOnTermination) {
            closeStdout()
        }
    }

    fun closeStdout() {
        runCatching { stdoutWriter.close() }
    }
}

internal object NoOpRuntimeDiagnosticsSink : RuntimeDiagnosticsSink {
    override fun record(
        tag: String,
        message: String,
    ) = Unit

    override fun recordStructured(
        tag: String,
        headline: String,
        vararg details: String?,
    ) = Unit
}

internal fun awaitCondition(
    timeoutMs: Long = 2_000L,
    condition: () -> Boolean,
) {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    while (!condition()) {
        check(System.nanoTime() < deadline) { "condition was not met within $timeoutMs ms" }
        Thread.sleep(5L)
    }
}
