package com.foxhole.core.runtime

import android.os.Build
import com.foxhole.core.model.ConnectivityHealthState
import com.foxhole.core.model.DEFAULT_FOXHOLE_RUNTIME_LOG_LEVEL

// Consolidated runtime policy constants and pure policy objects.
// Previously split across RuntimeLogPolicy / RuntimeNativeClosePolicy /
// RuntimeUpdatePolicy / RouteExcludePolicy. Behaviour is unchanged.

internal const val FOXHOLE_RUNTIME_LOG_LEVEL: String = DEFAULT_FOXHOLE_RUNTIME_LOG_LEVEL

internal object RuntimeNativeClosePolicy {
    const val FORCE_CLOSE_TIMEOUT_MS = 700L
}

object RuntimeUpdatePolicy {
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

internal const val ANDROID_ROUTE_EXCLUDE_LIMIT = 512

internal enum class RouteExcludeCompatibility {
    SUPPORTED,
    UNSUPPORTED_ANDROID_VERSION,
    EXCEEDS_ANDROID_LIMIT,
}

internal fun routeExcludeCompatibility(
    apiLevel: Int,
    excludeRouteCount: Int,
): RouteExcludeCompatibility =
    when {
        excludeRouteCount <= 0 -> RouteExcludeCompatibility.SUPPORTED
        apiLevel < Build.VERSION_CODES.TIRAMISU -> RouteExcludeCompatibility.UNSUPPORTED_ANDROID_VERSION
        excludeRouteCount > ANDROID_ROUTE_EXCLUDE_LIMIT -> RouteExcludeCompatibility.EXCEEDS_ANDROID_LIMIT
        else -> RouteExcludeCompatibility.SUPPORTED
    }
