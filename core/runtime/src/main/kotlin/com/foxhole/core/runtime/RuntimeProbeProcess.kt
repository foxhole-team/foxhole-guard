package com.foxhole.core.runtime

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.TimeUnit

internal suspend fun runRuntimeProbeProcess(
    command: List<String>,
    timeoutMs: Long,
    failureLabel: String,
    onWaitFinished: () -> Unit,
): String {
    val coroutine = currentCoroutineContext()
    val deadline = RuntimeLaunchDeadline(timeoutMs.coerceAtLeast(1L))
    var spawned: Process? = null
    var primary: Throwable? = null
    try {
        val process = startRuntimeProcess(
            ProcessBuilder(command).redirectErrorStream(true),
            RuntimeProcessLaunchControl(
                deadline = deadline,
                checkActive = { coroutine.ensureActive() },
                authorizeExec = { acknowledge -> coroutine.ensureActive(); acknowledge() },
                onSpawn = { spawned = it },
            ),
        )
        val finished = if (process is OwnedLegacyProcess) {
            process.waitForCompat(deadline.remainingNanos(), TimeUnit.NANOSECONDS, 24)
        } else {
            process.waitForCompat(timeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
        }
        onWaitFinished()
        if (!finished) {
            process.destroyForciblyCompat()
            error("$failureLabel timed out")
        }
        if (process is OwnedLegacyProcess) coroutine.ensureActive()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (process.exitValue() != 0) error("$failureLabel failed")
        return output
    } catch (failure: Throwable) {
        primary = failure
        throw failure
    } finally {
        val owned = spawned as? OwnedLegacyProcess
        if (owned != null) {
            try {
                if (owned.isAliveCompat(24)) owned.forceOwned()
                if (owned.isAliveCompat(24)) LegacyProcessRecovery.retain(owned) else owned.closeStreams()
            } catch (cleanup: Throwable) {
                LegacyProcessRecovery.retain(owned)
                if (primary == null) throw cleanup
                if (cleanup !== primary) primary.addSuppressed(cleanup)
            }
        }
    }
}
