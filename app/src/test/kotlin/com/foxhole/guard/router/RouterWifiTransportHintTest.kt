package com.foxhole.guard.router

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouterWifiTransportHintTest {
    @Test fun legacyAndroidNeverReadsAnUnscopedOrUnavailableWifiIdentity() {
        for (sdk in listOf(24, 25, 26, 28)) {
            assertNull(routerWifiTransportHint(sdk) { error("Network-scoped Wi-Fi identity unavailable on SDK $sdk") })
        }
    }

    @Test fun modernAndroidRetainsTheExactNetworkScopedHint() {
        for (sdk in listOf(29, 30, 37)) {
            var reads = 0
            assertEquals("authenticated-hint", routerWifiTransportHint(sdk) { reads++; "authenticated-hint" })
            assertEquals(1, reads)
        }
    }

    @Test fun missingModernIdentityDoesNotInventAHomeWifiHint() {
        assertNull(routerWifiTransportHint(29) { null })
    }
}
