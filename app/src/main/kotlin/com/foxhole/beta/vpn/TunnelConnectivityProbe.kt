package com.foxhole.beta.vpn

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

internal object TunnelConnectivityProbe {
    suspend fun <T> run(
        attempts: Int,
        initialDelayMs: Long = 0,
        retryDelayMs: Long,
        timeoutMs: Long? = null,
        onFailure: (attemptIndex: Int, Throwable) -> Unit = { _, _ -> },
        block: suspend () -> T,
    ): Result<T> {
        require(attempts > 0) { "attempts must be positive" }
        require(initialDelayMs >= 0) { "initialDelayMs must not be negative" }
        require(retryDelayMs >= 0) { "retryDelayMs must not be negative" }
        require(timeoutMs == null || timeoutMs > 0) { "timeoutMs must be positive" }

        suspend fun runAttempts(): Result<T> {
            if (initialDelayMs > 0) {
                delay(initialDelayMs)
            }
            var lastFailure: Throwable? = null
            repeat(attempts) { attemptIndex ->
                val result = runCatching { block() }
                if (result.isSuccess) {
                    return result
                }
                val error = result.exceptionOrNull() ?: IllegalStateException("probe failed")
                lastFailure = error
                onFailure(attemptIndex, error)
                if (attemptIndex < attempts - 1) {
                    delay(retryDelayMs)
                }
            }
            return Result.failure(lastFailure ?: IllegalStateException("probe failed"))
        }

        return if (timeoutMs == null) {
            runAttempts()
        } else {
            withTimeoutOrNull(timeoutMs) { runAttempts() }
                ?: Result.failure(IllegalStateException("probe timed out"))
        }
    }
}
