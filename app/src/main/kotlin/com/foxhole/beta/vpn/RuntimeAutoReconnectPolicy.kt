package com.foxhole.beta.vpn

import kotlin.math.roundToLong
import kotlin.random.Random

internal object RuntimeAutoReconnectPolicy {
    const val MAX_ATTEMPTS = 5
    private const val MAX_DELAY_MS = 30_000L
    private const val JITTER_RATIO = 0.2
    private val DEFAULT_BACKOFF_MS = listOf(0L, 2_000L, 5_000L, 15_000L, MAX_DELAY_MS)

    fun shouldSchedule(
        autoReconnectEnabled: Boolean,
        attempt: Int,
        maxAttempts: Int = MAX_ATTEMPTS,
    ): Boolean = autoReconnectEnabled && attempt in 1..maxAttempts.coerceAtLeast(1)

    fun backoffDelayMs(
        attempt: Int,
        retryDelaySeconds: Int? = null,
    ): Long =
        retryDelaySeconds
            ?.takeIf { seconds -> seconds > 0 }
            ?.let { seconds ->
                if (attempt <= 1) {
                    0L
                } else {
                    (seconds.toLong() * 1000L).coerceAtMost(MAX_DELAY_MS)
                }
            } ?: DEFAULT_BACKOFF_MS.getOrElse(attempt.coerceAtLeast(1) - 1) { MAX_DELAY_MS }

    fun jitteredBackoffDelayMs(
        attempt: Int,
        retryDelaySeconds: Int? = null,
        randomFactor: Double = Random.nextDouble(),
    ): Long {
        val baseDelayMs = backoffDelayMs(attempt, retryDelaySeconds)
        if (baseDelayMs <= 0L) {
            return 0L
        }
        val spreadMs = (baseDelayMs * JITTER_RATIO).roundToLong()
        val offsetMs =
            ((randomFactor.coerceIn(0.0, 1.0) * spreadMs * 2) - spreadMs)
                .roundToLong()
        return (baseDelayMs + offsetMs).coerceIn(1L, MAX_DELAY_MS)
    }
}
