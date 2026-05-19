package com.foxhole.beta.vpn

import android.os.Build

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
