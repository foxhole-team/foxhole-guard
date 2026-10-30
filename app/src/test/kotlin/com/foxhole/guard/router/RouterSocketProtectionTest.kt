package com.foxhole.guard.router

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.Socket

class RouterSocketProtectionTest {
    @Test fun onlyTheOwnedSocketIsProtectedBeforeBindingToWifi() {
        val events = mutableListOf<String>()
        val socket = Socket()
        val actual = createRouterWifiSocket({ true }, RouterSocketProtection { value ->
            assertEquals(socket, value); assertTrue(!value.isConnected); events += "protect"; true
        }, { value -> assertEquals(socket, value); events += "wifi" }, { socket })
        assertEquals(listOf("protect", "wifi"), events)
        actual.close()
    }

    @Test fun anActiveUnownedVpnFailsWithoutBindingOrFallback() {
        val socket = Socket()
        var binds = 0
        assertThrows(RouterVpnSocketUnavailable::class.java) {
            createRouterWifiSocket({ true }, null, { binds++ }, { socket })
        }
        assertTrue(socket.isClosed); assertEquals(0, binds)
    }

    @Test fun lossOrAVpnStartingDuringBindingClosesTheSocket() {
        val failed = Socket()
        assertThrows(IOException::class.java) { createRouterWifiSocket({ false }, null, { throw IOException("wifi gone") }, { failed }) }
        assertTrue(failed.isClosed)
        val raced = Socket()
        var vpn = false
        assertThrows(RouterVpnSocketUnavailable::class.java) { createRouterWifiSocket({ vpn }, null, { vpn = true }, { raced }) }
        assertTrue(raced.isClosed)
    }
}
