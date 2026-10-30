package com.foxhole.guard.router

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterWifiEventGateTest {
    private val initial = RouterWifiObservation(true, true, true, "wlan0", setOf("192.168.1.50/24"), setOf("192.168.1.0/24"))

    @Test fun repeatedCapabilitiesCannotAmplifyStatusPollsButDhcpRouteAndValidationChangesRemainImmediate() {
        val gate = RouterWifiEventGate()
        assertTrue(gate.changed(7, initial))
        repeat(100) { assertFalse(gate.changed(7, initial)) }
        assertTrue(gate.changed(7, initial.copy(addresses = setOf("192.168.1.51/24"))))
        assertTrue(gate.changed(7, initial.copy(routes = setOf("192.168.2.0/24"))))
        assertTrue(gate.changed(7, initial.copy(validated = false)))
        assertTrue(gate.changed(7, initial.copy(usable = false)))
    }

    @Test fun actualLossAndNetworkRejoinRemainObservableWithoutTrustingAnSsid() {
        val gate = RouterWifiEventGate()
        gate.changed(7, initial)
        assertTrue(gate.lost(7))
        assertFalse(gate.lost(7))
        assertTrue(gate.changed(7, initial))
        assertTrue(gate.changed(8, initial))
    }
}
