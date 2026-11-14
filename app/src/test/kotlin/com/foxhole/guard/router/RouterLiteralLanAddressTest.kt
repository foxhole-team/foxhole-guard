package com.foxhole.guard.router

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.*

class RouterLiteralLanAddressTest {
    @Test fun onlyLiteralIpv4AndIpv6AreDisplayed() {
        assertEquals("192.168.1.1", routerLiteralLanAddress("192.168.1.1"))
        assertEquals("fd12:3456::1", routerLiteralLanAddress("fd12:3456::1"))
        assertEquals("::ffff:192.0.2.1", routerLiteralLanAddress("::ffff:192.0.2.1"))
    }
    @Test fun hostsUrlsPortsZonesAndInvalidOctetsNeverTriggerResolution() {
        for (value in listOf("router.local", "https://192.168.1.1", "192.168.1.1:443", "[::1]:443", "fe80::1%wlan0", "999.1.1.1", "01.2.3.4", "١.٢.٣.٤", "dead:beef"))
            assertNull(value, routerLiteralLanAddress(value))
    }
    @Test fun controlsAndLeadingWhitespaceAreRejectedBeforeAnyTrimming() {
        for (value in listOf(" 192.168.1.1", "192.168.1.1\n", "\n192.168.1.1", "192.168.1.1\u0000")) {
            assertNull(routerLiteralLanAddress(value))
            val metadata = parseRouterHardwareInfo(buildJsonObject { put("schema", 1); put("lanAddress", value) })
            assertNull(metadata?.lanAddress)
        }
    }
    @Test fun missingLanFactsRemainUnknownWhileOtherHardwareFactsAreKept() {
        val metadata = parseRouterHardwareInfo(buildJsonObject { put("schema", 1); put("model", "Fixture"); put("firmwareVersion", "OpenWRT 25.12") })
        assertEquals("Fixture", metadata?.model); assertNull(metadata?.lanAddress)
    }
}
