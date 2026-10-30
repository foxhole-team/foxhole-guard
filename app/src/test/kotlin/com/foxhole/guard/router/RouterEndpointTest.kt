package com.foxhole.guard.router

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class RouterEndpointTest {
    @Test fun onlyPrivateLiteralLanDestinationsAreAdmitted() {
        for (value in listOf("192.168.1.1", "10.66.66.13", "172.16.1.2")) assertEquals(value, routerLanAddress(value)?.hostAddress)
        for (value in listOf("127.0.0.1", "1.1.1.1", "169.254.1.2", "172.32.1.2", "router.lan", "192.168.01.1",
            "192.168.1.255", "192.168.1.0", "192.168.1.1/24", "192.168.1.1:443", "::1", "192.168.1.1\n")) assertNull(value, routerLanAddress(value))
    }

    @Test fun endpointCannotBeUnresolvedOrUseAnInvalidPort() {
        assertThrows(IllegalArgumentException::class.java) { RouterEndpoint("example.test") }
        assertThrows(IllegalArgumentException::class.java) { RouterEndpoint("192.168.1.1", 0) }
    }

    @Test fun aSelectedWifiSubnetMustActuallyContainTheRouter() {
        val local = requireNotNull(routerLanAddress("192.168.1.12"))
        assertEquals(true, routerSubnetContains(local, requireNotNull(routerLanAddress("192.168.1.1")), 24))
        assertEquals(false, routerSubnetContains(local, requireNotNull(routerLanAddress("192.168.2.1")), 24))
        assertEquals(false, routerSubnetContains(local, local, 0))
    }
}
