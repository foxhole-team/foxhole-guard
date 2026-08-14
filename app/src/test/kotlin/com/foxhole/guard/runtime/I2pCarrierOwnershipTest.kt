package com.foxhole.guard.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class I2pCarrierOwnershipTest {
    @Test
    fun `connected publication requires the exact owned vpn endpoint and applied runtime`() {
        assertTrue(
            carrierCurrent(
                activeVpnNetworkHandle = 361L,
                currentVpnNetworkHandle = 361L,
                currentEndpointGeneration = 17L,
                appliedRuntimeFingerprint = 23,
                sessionOwned = true,
            ),
        )
    }

    @Test
    fun `persisted settings or a stale snapshot cannot stand in for a live android carrier`() {
        assertFalse(carrierCurrent(activeVpnNetworkHandle = null, currentVpnNetworkHandle = null))
        assertFalse(carrierCurrent(activeVpnNetworkHandle = 361L, currentVpnNetworkHandle = null))
        assertFalse(carrierCurrent(activeVpnNetworkHandle = null, currentVpnNetworkHandle = 361L))
        assertFalse(carrierCurrent(sessionOwned = false))
    }

    @Test
    fun `replaced endpoint or unapplied config cannot publish connected`() {
        assertFalse(carrierCurrent(currentEndpointGeneration = 18L))
        assertFalse(carrierCurrent(appliedRuntimeFingerprint = 24))
    }

    private fun carrierCurrent(
        activeVpnNetworkHandle: Long? = 361L,
        currentVpnNetworkHandle: Long? = 361L,
        currentEndpointGeneration: Long? = 17L,
        appliedRuntimeFingerprint: Int? = 23,
        sessionOwned: Boolean = true,
    ): Boolean =
        isI2pCarrierConfirmationCurrent(
            expectedEndpointGeneration = 17L,
            currentEndpointGeneration = currentEndpointGeneration,
            expectedVpnNetworkHandle = 361L,
            activeVpnNetworkHandle = activeVpnNetworkHandle,
            currentVpnNetworkHandle = currentVpnNetworkHandle,
            expectedRuntimeFingerprint = 23,
            appliedRuntimeFingerprint = appliedRuntimeFingerprint,
            sessionOwned = sessionOwned,
        )
}
