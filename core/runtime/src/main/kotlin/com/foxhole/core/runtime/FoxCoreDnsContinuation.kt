package com.foxhole.core.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal sealed interface FoxCoreDnsContinuation<out T> {
    data class Completed<T>(val value: T) : FoxCoreDnsContinuation<T>
    data object Unavailable : FoxCoreDnsContinuation<Nothing>
    data object Superseded : FoxCoreDnsContinuation<Nothing>
}

internal suspend fun <T> withStoppedDnsContinuation(
    native: FoxCoreNativeApi,
    handle: Long,
    isCurrent: () -> Boolean,
    stop: suspend () -> NativeStopOutcome,
    onReleaseFailure: (Throwable) -> Unit,
    replace: suspend (Long, NativeStopOutcome) -> T,
): FoxCoreDnsContinuation<T> {
    if (!isCurrent()) return FoxCoreDnsContinuation.Superseded
    val token = try {
        native.retainDnsContinuation(handle)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        0L
    } catch (_: LinkageError) {
        0L
    }
    if (token <= 0L) return FoxCoreDnsContinuation.Unavailable
    try {
        if (!isCurrent()) return FoxCoreDnsContinuation.Superseded
        return FoxCoreDnsContinuation.Completed(replace(token, stop()))
    } finally {
        withContext(NonCancellable) {
            try {
                native.releaseDnsContinuation(token)
            } catch (releaseFailure: Throwable) {
                // Cleanup diagnostics must not replace a primary error or the result owning a native handle.
                try {
                    onReleaseFailure(releaseFailure)
                } catch (_: Throwable) {
                    Unit
                }
            }
        }
    }
}

internal fun rejectPoisonedDnsContinuityStart(result: Long) {
    val outcome = when (result) {
        FoxholeNativeEngine.DNS_CONTINUITY_START_QUARANTINED -> NativeForceStopOutcome.QUARANTINED
        FoxholeNativeEngine.DNS_CONTINUITY_START_FAILED -> NativeForceStopOutcome.FAILED
        else -> return
    }
    throw NativeForceStopPoisonedException(outcome, "dns_continuity_start")
}
