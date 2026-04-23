package com.foxhole.beta.vpn

import com.foxhole.beta.core.model.ConnectivityHealthState

internal object RuntimeUpdatePolicy {
    private const val TRAFFIC_UPDATE_UI_ACTIVE_MS = 500L
    private const val TRAFFIC_UPDATE_BACKGROUND_MS = 10_000L
    private const val HEALTH_PROBE_ACTIVE_MS = 10_000L
    private const val HEALTH_PROBE_STABLE_ONLINE_MS = 60_000L

    fun trafficUpdateIntervalMs(highFrequencyUiActive: Boolean): Long =
        if (highFrequencyUiActive) {
            TRAFFIC_UPDATE_UI_ACTIVE_MS
        } else {
            TRAFFIC_UPDATE_BACKGROUND_MS
        }

    fun notificationHealthProbeIntervalMs(state: ConnectivityHealthState): Long =
        if (state == ConnectivityHealthState.ONLINE) {
            HEALTH_PROBE_STABLE_ONLINE_MS
        } else {
            HEALTH_PROBE_ACTIVE_MS
        }
}
