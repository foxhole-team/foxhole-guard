package com.foxhole.beta.vpn

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Test

class RouteExcludeCompatibilityTest {
    @Test
    fun `no route excludes are supported on all Android versions`() {
        assertEquals(
            RouteExcludeCompatibility.SUPPORTED,
            routeExcludeCompatibility(apiLevel = Build.VERSION_CODES.O, excludeRouteCount = 0),
        )
    }

    @Test
    fun `route excludes fail closed before Android 13`() {
        assertEquals(
            RouteExcludeCompatibility.UNSUPPORTED_ANDROID_VERSION,
            routeExcludeCompatibility(apiLevel = Build.VERSION_CODES.S_V2, excludeRouteCount = 1),
        )
    }

    @Test
    fun `route excludes fail closed above the Android limit`() {
        assertEquals(
            RouteExcludeCompatibility.EXCEEDS_ANDROID_LIMIT,
            routeExcludeCompatibility(apiLevel = Build.VERSION_CODES.TIRAMISU, excludeRouteCount = 513),
        )
    }

    @Test
    fun `route excludes are accepted within the Android limit on Android 13 plus`() {
        assertEquals(
            RouteExcludeCompatibility.SUPPORTED,
            routeExcludeCompatibility(apiLevel = Build.VERSION_CODES.TIRAMISU, excludeRouteCount = 512),
        )
    }
}
