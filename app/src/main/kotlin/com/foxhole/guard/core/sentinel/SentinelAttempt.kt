package com.foxhole.guard.core.sentinel

import kotlinx.coroutines.CancellationException

internal inline fun <T> sentinelAttempt(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(error)
    }
