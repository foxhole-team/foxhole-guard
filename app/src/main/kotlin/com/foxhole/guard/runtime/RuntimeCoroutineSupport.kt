package com.foxhole.guard.runtime

import kotlinx.coroutines.CancellationException

/**
 * Runs [block] and captures its result, but never swallows structured-concurrency
 * cancellation: a [CancellationException] is rethrown so the calling coroutine can
 * cancel cleanly. Shared by the runtime service collaborators (previously duplicated
 * as a private helper in each *Support file).
 */
@Suppress("TooGenericExceptionCaught")
internal suspend inline fun <T> runCatchingUnlessCancelled(crossinline block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        Result.failure(error)
    }
