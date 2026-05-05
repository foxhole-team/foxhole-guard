package com.foxhole.beta.vpn

internal object RuntimeAutoReconnectPolicy {
    const val MAX_ATTEMPTS = 4

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
                    seconds.toLong() * 1000L
                }
            } ?: when (attempt.coerceAtLeast(1)) {
                1 -> 0L
                2 -> 2_000L
                3 -> 5_000L
                else -> 15_000L
            }
}
