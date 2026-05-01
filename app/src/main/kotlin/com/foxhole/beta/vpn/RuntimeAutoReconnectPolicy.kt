package com.foxhole.beta.vpn

internal object RuntimeAutoReconnectPolicy {
    const val MAX_ATTEMPTS = 4

    fun shouldSchedule(
        autoReconnectEnabled: Boolean,
        attempt: Int,
    ): Boolean = autoReconnectEnabled && attempt in 1..MAX_ATTEMPTS

    fun backoffDelayMs(attempt: Int): Long =
        when (attempt.coerceAtLeast(1)) {
            1 -> 0L
            2 -> 2_000L
            3 -> 5_000L
            else -> 15_000L
        }
}
