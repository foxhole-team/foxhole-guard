package com.foxhole.core.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

class TunnelConnectivityProbeTimeoutException(
    val attemptsDone: Int,
    val timeoutMs: Long,
    val elapsedMs: Long = timeoutMs,
    cause: Throwable?,
) : IllegalStateException(
    buildString {
        append("probe timed out after ")
        append(attemptsDone)
        append(" attempt")
        if (attemptsDone != 1) {
            append("s")
        }
        append(" and ")
        append(timeoutMs)
        append(" ms")
    },
    cause,
)

data class TunnelConnectivityProbeAttemptReport(
    val attemptIndex: Int,
    val attemptNumber: Int,
    val elapsedMs: Long,
    val success: Boolean,
    val failure: Throwable?,
)

object TunnelConnectivityProbe {
    suspend fun <T> run(
        attempts: Int,
        initialDelayMs: Long = 0,
        retryDelayMs: Long,
        timeoutMs: Long? = null,
        onFailure: (attemptIndex: Int, Throwable) -> Unit = { _, _ -> },
        onAttemptCompleted: (TunnelConnectivityProbeAttemptReport) -> Unit = {},
        block: suspend () -> T,
    ): Result<T> {
        require(attempts > 0) { "attempts must be positive" }
        require(initialDelayMs >= 0) { "initialDelayMs must not be negative" }
        require(retryDelayMs >= 0) { "retryDelayMs must not be negative" }
        require(timeoutMs == null || timeoutMs > 0) { "timeoutMs must be positive" }

        var attemptsDone = 0
        var lastFailure: Throwable? = null
        val probeStartedAtNs = System.nanoTime()

        suspend fun runAttempts(): Result<T> {
            if (initialDelayMs > 0) {
                delay(initialDelayMs)
            }
            repeat(attempts) { attemptIndex ->
                attemptsDone = attemptIndex + 1
                val attemptStartedAtNs = System.nanoTime()
                val result =
                    try {
                        Result.success(block())
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        Result.failure(error)
                    }
                val error = result.exceptionOrNull()
                onAttemptCompleted(
                    TunnelConnectivityProbeAttemptReport(
                        attemptIndex = attemptIndex,
                        attemptNumber = attemptIndex + 1,
                        elapsedMs = (System.nanoTime() - attemptStartedAtNs).toElapsedMs(),
                        success = result.isSuccess,
                        failure = error,
                    ),
                )
                if (result.isSuccess) {
                    return result
                }
                val failure = error ?: IllegalStateException("probe failed")
                lastFailure = failure
                onFailure(attemptIndex, failure)
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
                ?: Result.failure(
                    TunnelConnectivityProbeTimeoutException(
                        attemptsDone = attemptsDone,
                        timeoutMs = timeoutMs,
                        elapsedMs = (System.nanoTime() - probeStartedAtNs).toElapsedMs(),
                        cause = lastFailure,
                    ),
                )
        }
    }
}

private fun Long.toElapsedMs(): Long =
    TimeUnit.NANOSECONDS.toMillis(this).coerceAtLeast(0L)
