package com.foxhole.beta.vpn

import com.foxhole.beta.core.model.ConnectivityHealthState

internal object RuntimeUpdatePolicy {
    private const val TRAFFIC_UPDATE_UI_ACTIVE_MS = 1_000L
    private const val TRAFFIC_UPDATE_BACKGROUND_MS = 30_000L
    private const val HEALTH_PROBE_ACTIVE_MS = 10_000L
    private const val HEALTH_PROBE_RETRY_BASE_MS = 15_000L
    private const val HEALTH_PROBE_RETRY_MAX_MS = 120_000L
    private const val HEALTH_PROBE_STABLE_ONLINE_MS = 60_000L

    fun trafficUpdateIntervalMs(highFrequencyUiActive: Boolean): Long =
        if (highFrequencyUiActive) {
            TRAFFIC_UPDATE_UI_ACTIVE_MS
        } else {
            TRAFFIC_UPDATE_BACKGROUND_MS
        }

    fun notificationHealthProbeIntervalMs(
        state: ConnectivityHealthState,
        consecutiveFailures: Int = 0,
    ): Long =
        when (state) {
            ConnectivityHealthState.ONLINE -> HEALTH_PROBE_STABLE_ONLINE_MS
            ConnectivityHealthState.OFFLINE ->
                if (consecutiveFailures <= 0) {
                    HEALTH_PROBE_ACTIVE_MS
                } else {
                    val multiplier = 1L shl (consecutiveFailures - 1).coerceAtMost(3)
                    (HEALTH_PROBE_RETRY_BASE_MS * multiplier).coerceAtMost(HEALTH_PROBE_RETRY_MAX_MS)
                }
            ConnectivityHealthState.CHECKING -> HEALTH_PROBE_ACTIVE_MS
        }
}
