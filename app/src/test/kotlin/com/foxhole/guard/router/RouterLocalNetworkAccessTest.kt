package com.foxhole.guard.router

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterLocalNetworkAccessTest {
    @Test fun olderPlatformsDoNotRequestAnUnknownPermission() {
        for (sdk in listOf(26, 35, 36)) assertFalse(routerLocalNetworkPermissionNeeded(sdk, 37, false))
    }

    @Test fun legacyTargetsKeepTheirPlatformPermissionPolicy() {
        assertFalse(routerLocalNetworkPermissionNeeded(37, 36, false))
    }

    @Test fun currentTargetRequiresAGrantBeforeLanTraffic() {
        assertTrue(routerLocalNetworkPermissionNeeded(37, 37, false))
        assertFalse(routerLocalNetworkPermissionNeeded(37, 37, true))
    }

    @Test fun futurePlatformsRetainThePermissionGate() {
        assertTrue(routerLocalNetworkPermissionNeeded(38, 38, false))
    }
}
