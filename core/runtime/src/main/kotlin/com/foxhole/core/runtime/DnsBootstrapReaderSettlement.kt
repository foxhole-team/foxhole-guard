package com.foxhole.core.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal suspend fun runWithDnsBootstrapReader(
    reader: RuntimeDnsBootstrapReadLease?,
    cleanupPending: () -> Unit,
    operation: suspend () -> Result<Unit>,
): Result<Unit> {
    var thrown: Throwable? = null
    val result = try { operation() } catch (failure: Throwable) {
        thrown = failure
        Result.failure(failure)
    }
    val primary = result.exceptionOrNull()
    var criticalCleanup: Throwable? = null
    withContext(NonCancellable) {
        try {
            reader?.release()
        } catch (cancelled: CancellationException) {
            criticalCleanup = combineCriticalCleanup(primary, cancelled)
        } catch (fatal: Error) {
            criticalCleanup = combineCriticalCleanup(primary, fatal)
        } catch (ordinary: Exception) {
            if (primary != null) addDistinctSuppressed(primary, ordinary) else reportCleanupPending(cleanupPending)
        }
    }
    criticalCleanup?.let { throw it }
    thrown?.let { throw it }
    return result
}

private fun reportCleanupPending(report: () -> Unit) {
    try { report() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { Unit }
}

private fun combineCriticalCleanup(primary: Throwable?, cleanup: Throwable): Throwable =
    if (primary is CancellationException || primary is Error) {
        addDistinctSuppressed(primary, cleanup)
        primary
    } else {
        if (primary != null) addDistinctSuppressed(cleanup, primary)
        cleanup
    }

private fun addDistinctSuppressed(primary: Throwable, secondary: Throwable) {
    if (primary !== secondary) primary.addSuppressed(secondary)
}
