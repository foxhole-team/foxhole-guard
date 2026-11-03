package com.foxhole.guard.router

import org.junit.Assert.*
import org.junit.Test

class RouterManagementAccessTest {
    @Test fun cellularWithoutPairedRemoteVpnDoesNotDiscover() {
        assertEquals(RouterManagementAccess.UNAVAILABLE, routerManagementAccess(false, false, false, false))
        assertEquals(RouterManagementAccess.UNAVAILABLE, routerManagementAccess(false, false, true, false))
    }
    @Test fun anotherWifiWithoutPairedRemoteVpnDoesNotDiscover() {
        assertEquals(RouterManagementAccess.UNAVAILABLE, routerManagementAccess(true, false, false, false))
        assertEquals(RouterManagementAccess.UNAVAILABLE, routerManagementAccess(true, false, true, false))
    }
    @Test fun homeWifiRequiresDisconnectingVpn() {
        assertEquals(RouterManagementAccess.LOCAL, routerManagementAccess(true, true, false, false))
        assertEquals(RouterManagementAccess.VPN_ACTIVE, routerManagementAccess(true, true, true, false))
        assertEquals(RouterManagementAccess.VPN_ACTIVE, routerManagementAccess(true, true, true, true))
    }
    @Test fun pairedRemoteVpnWorksOnCellularAndAnotherWifi() {
        assertEquals(RouterManagementAccess.REMOTE, routerManagementAccess(false, false, true, true))
        assertEquals(RouterManagementAccess.REMOTE, routerManagementAccess(true, false, true, true))
    }
    @Test fun accessHintsDoNotExposeWifiNames() {
        val hash = routerWifiIdentityHash(ByteArray(32) { 1 }, null, "Private home")!!
        assertTrue(hash.matches(Regex("[a-f0-9]{64}")))
        assertFalse(hash.contains("Private"))
        assertNotEquals(hash, routerWifiIdentityHash(ByteArray(32) { 2 }, null, "Private home"))
    }
    @Test fun renamedWifiRetainsBssidHint() {
        val credential = ByteArray(32) { 3 }
        assertEquals(routerWifiIdentityHash(credential, "10:20:30:40:50:60", "Old name"),
            routerWifiIdentityHash(credential, "10:20:30:40:50:60", "New name"))
    }
    @Test fun redactedWifiIdentityStaysUnknown() {
        assertNull(routerWifiIdentityHash(ByteArray(32), "02:00:00:00:00:00", "<unknown ssid>"))
        assertNull(routerWifiIdentityHash(ByteArray(32), null, null))
    }
}
