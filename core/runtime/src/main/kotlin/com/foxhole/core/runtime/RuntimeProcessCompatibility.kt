package com.foxhole.core.runtime

import android.os.Build
import java.io.IOException
import java.util.concurrent.TimeUnit

// Android SDK stubs report zero on the host JVM, whose Process methods remain available.
private val processPlatformSdk: Int =
    Build.VERSION.SDK_INT.let { if (it > 0) it else Int.MAX_VALUE }

internal fun runtimeProcessPlatformSdk(): Int = processPlatformSdk

internal fun Process.destroyForciblyCompat(sdkInt: Int = processPlatformSdk): Process {
    if (this is OwnedLegacyProcess) {
        forceOwned()
        return this
    }
    if (sdkInt >= 26) return destroyForcibly()
    throw IOException("runtime force-stop requires an owned spawn receipt")
}

internal fun Process.isAliveCompat(sdkInt: Int = processPlatformSdk): Boolean =
    if (sdkInt >= 26) {
        isAlive
    } else {
        try {
            exitValue()
            false
        } catch (_: IllegalThreadStateException) {
            true
        }
    }

internal fun Process.waitForCompat(
    timeout: Long,
    unit: TimeUnit,
    sdkInt: Int = processPlatformSdk,
    nanoTime: () -> Long = System::nanoTime,
    sleepNanos: (Long) -> Unit = { TimeUnit.NANOSECONDS.sleep(it) },
): Boolean {
    if (sdkInt >= 26) return waitFor(timeout, unit)
    val budget = unit.toNanos(timeout).coerceAtLeast(0L)
    val started = nanoTime()
    while (true) {
        if (!isAliveCompat(sdkInt)) return true
        if (Thread.interrupted()) throw InterruptedException()
        val elapsed = nanoTime() - started
        if (elapsed >= budget) return false
        sleepNanos(minOf(budget - elapsed, 10_000_000L))
    }
}
