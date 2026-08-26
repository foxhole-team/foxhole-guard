package com.foxhole.guard.runtime

import kotlinx.coroutines.CancellationException

@Suppress("TooGenericExceptionCaught")
internal suspend inline fun <T> runCatchingUnlessCancelled(crossinline block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        Result.failure(error)
    }
